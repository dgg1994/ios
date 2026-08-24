package com.consumer.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.consumer.dao.MemorandumDao;
import com.consumer.entity.MemorandumEntity;
import com.consumer.util.NoteStoreBodyExtractor;
import com.consumer.util.NoteStoreBodyExtractor.NoteBody;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * job=nb_notestore：解析 NoteStore.sqlite，在已有 memorandum.content 上追加正文。
 * <p>不替代 nb_memorandum 快路径；仅 UPDATE 已有行。
 */
@Service
@Slf4j
public class NbNotestoreHandler {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    @Resource
    private MemorandumDao memorandumDao;
    @Resource
    private JedisPool jedisPool;

    public boolean handle(Map<String, String> fields) throws Exception {
        String ios18IdStr = fields == null ? null : fields.get("ios18param_id");
        if (ios18IdStr == null || ios18IdStr.isEmpty()) {
            log.warn("【nb_notestore】缺少 ios18param_id，跳过");
            return true;
        }
        int ios18Id;
        try {
            ios18Id = Integer.parseInt(ios18IdStr);
        } catch (Exception e) {
            log.warn("【nb_notestore】ios18param_id 非法 id={}", ios18IdStr);
            return true;
        }

        String lockKey = "nb_notestore:lock:" + ios18Id;
        String lockToken = UUID.randomUUID().toString();
        boolean locked = false;
        try {
            try (Jedis j = jedisPool.getResource()) {
                String res = j.set(lockKey, lockToken, SetParams.setParams().nx().ex(600));
                locked = "OK".equals(res);
            } catch (Throwable t) {
                locked = true; // Redis 不可用时降级
            }
            if (!locked) {
                log.info("【nb_notestore】锁未获取，稍后重试 id={}", ios18Id);
                return false;
            }
            return doEnrich(ios18Id, fields);
        } finally {
            if (locked) {
                try (Jedis j = jedisPool.getResource()) {
                    j.eval("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                            1, lockKey, lockToken);
                } catch (Throwable ignore) {}
            }
        }
    }

    private boolean doEnrich(int ios18Id, Map<String, String> fields) throws Exception {
        String storage = nullToEmpty(fields.get("storage"));
        String filePath = nullToEmpty(fields.get("file_path"));
        String bodyB64 = nullToEmpty(fields.get("body_b64"));

        String rawBody;
        if ("file".equals(storage) && !filePath.isEmpty()) {
            Path path = Paths.get(filePath);
            if (!Files.isRegularFile(path)) {
                log.warn("【nb_notestore】文件不存在 id={} path={}", ios18Id, filePath);
                return true;
            }
            rawBody = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } else if (!bodyB64.isEmpty()) {
            rawBody = new String(Base64.getDecoder().decode(bodyB64), StandardCharsets.UTF_8);
        } else {
            log.warn("【nb_notestore】无 file_path/body_b64 id={}", ios18Id);
            return true;
        }

        JSONObject nb = JSON.parseObject(rawBody);
        if (nb == null) {
            log.warn("【nb_notestore】JSON 解析失败 id={}", ios18Id);
            return true;
        }

        List<NoteBody> notes = NoteStoreBodyExtractor.extractFromNbJson(nb);
        if (notes.isEmpty()) {
            log.info("【nb_notestore】无 NoteStore 正文 id={}", ios18Id);
            return true;
        }

        List<MemorandumEntity> rows = memorandumDao.findByC2RecordId(ios18Id);
        if (rows == null || rows.isEmpty()) {
            log.info("【nb_notestore】无已有 memorandum 可追加 id={} notes={}", ios18Id, notes.size());
            return true;
        }

        int updated = 0;
        int skipped = 0;
        for (NoteBody note : notes) {
            MemorandumEntity target = matchRow(rows, note);
            if (target == null) {
                skipped++;
                continue;
            }
            String title = target.getTitle() == null ? "" : target.getTitle();
            // 拼装待追加正文：ZDATA + NoteStore title/snippet（取更长/更完整的信息）
            String toAppend = buildAppendPayload(note);
            String merged = NoteStoreBodyExtractor.appendBody(target.getContent(), title, toAppend);
            String old = nullToEmpty(target.getContent());
            if (merged.equals(old)) {
                skipped++;
                continue;
            }
            String hash = sha256Hex((title + "\n" + merged).getBytes(StandardCharsets.UTF_8));
            try {
                int n = memorandumDao.updateContentById(target.getId(), merged, hash);
                if (n > 0) {
                    updated++;
                    target.setContent(merged);
                    target.setContentHash(hash);
                    log.info("【nb_notestore】追加正文 id={} memoId={} titleLen={} contentLen={} appendLen={}",
                            ios18Id, target.getId(), title.length(), merged.length(), toAppend.length());
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                String msg = e.toString();
                if (msg.contains("Duplicate") || msg.contains("duplicate") || msg.contains("UK_")) {
                    skipped++;
                    log.info("【nb_notestore】content_hash 冲突跳过 id={} memoId={}", ios18Id, target.getId());
                } else {
                    throw e;
                }
            }
        }
        log.info("【nb_notestore】完成 id={} notes={} rows={} updated={} skipped={}",
                ios18Id, notes.size(), rows.size(), updated, skipped);
        return true;
    }

    /**
     * 优先用 ZDATA 正文；若 NoteStore 的 title/snippet 比 body 某行更完整则并入。
     */
    private static String buildAppendPayload(NoteBody note) {
        String body = note.body == null ? "" : note.body.trim();
        String nsTitle = note.title == null ? "" : note.title.trim();
        String nsSnip = note.snippet == null ? "" : note.snippet.trim();
        StringBuilder sb = new StringBuilder();
        if (!body.isEmpty()) {
            sb.append(body);
        }
        // title/snippet 若比 body 更长（含密码等），补进待追加文本
        if (!nsTitle.isEmpty() && !NoteStoreBodyExtractor.isFragmentCovered(sb.toString(), nsTitle)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(nsTitle);
        }
        if (!nsSnip.isEmpty() && !NoteStoreBodyExtractor.isFragmentCovered(sb.toString(), nsSnip)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(nsSnip);
        }
        return sb.toString();
    }

    private static MemorandumEntity matchRow(List<MemorandumEntity> rows, NoteBody note) {
        String title = note.title == null ? "" : note.title.trim();
        String snippet = note.snippet == null ? "" : note.snippet.trim();
        String body = note.body == null ? "" : note.body.trim();

        // 1) title 精确 / 前缀
        if (!title.isEmpty()) {
            for (MemorandumEntity m : rows) {
                String mt = m.getTitle() == null ? "" : m.getTitle().trim();
                if (mt.equals(title) || mt.startsWith(title) || title.startsWith(mt)) {
                    return m;
                }
            }
        }
        // 2) snippet 命中 content
        if (!snippet.isEmpty()) {
            for (MemorandumEntity m : rows) {
                String c = m.getContent() == null ? "" : m.getContent();
                if (c.contains(snippet) || snippet.contains(c) || c.equals(snippet)) {
                    return m;
                }
            }
        }
        // 3) body 与已有 content 有重叠
        if (!body.isEmpty()) {
            for (MemorandumEntity m : rows) {
                String c = m.getContent() == null ? "" : m.getContent().trim();
                if (c.isEmpty()) continue;
                if (body.contains(c) || c.contains(body)) return m;
                for (String line : body.split("\\R")) {
                    String t = line.trim();
                    if (t.length() >= 4 && c.contains(t)) return m;
                }
            }
        }
        // 4) 仅一条备忘录时直接挂上
        if (rows.size() == 1) return rows.get(0);
        return null;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xFF;
                sb.append(HEX[v >>> 4]).append(HEX[v & 0x0F]);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
