package com.consumer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * waitbound_collect：扫描 unpack 中未明文解出的钱包加密材料 → 落盘 + waitbound 表。
 * <p>
 * 与 parse_ci / news4 解耦；失败由 WaitboundConsumer 重试/DLQ。
 */
@Service
@Slf4j
public class WaitboundHandler {

    @Resource
    private EncryptedWalletArtifactCollector encryptedWalletArtifactCollector;

    public boolean handle(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return true;
        }
        String job = fields.getOrDefault("job", "");
        if (!job.isEmpty() && !"waitbound_collect".equals(job)) {
            log.warn("【waitbound】未知 job={}，跳过", job);
            return true;
        }
        String unpackPath = nullToEmpty(fields.get("unpack_path"));
        String deviceId = nullToEmpty(fields.get("device_id"));
        Integer ios18paramId = parseIntOrNull(fields.get("ios18param_id"));
        Set<String> decrypted = parseDecryptedWallets(fields.get("decrypted_wallets"));

        if (unpackPath.isEmpty()) {
            log.warn("【waitbound】unpack_path 为空 device={} id={}", deviceId, ios18paramId);
            return true;
        }
        int n = encryptedWalletArtifactCollector.collectAndSave(
                unpackPath, deviceId, ios18paramId, decrypted);
        log.info("【waitbound】完成 device={} id={} inserted={}", deviceId, ios18paramId, n);
        return true;
    }

    private static Set<String> parseDecryptedWallets(String csv) {
        Set<String> set = new HashSet<>();
        if (csv == null || csv.trim().isEmpty()) {
            return set;
        }
        for (String p : csv.split("[,;\\s]+")) {
            if (p == null) continue;
            String t = p.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
