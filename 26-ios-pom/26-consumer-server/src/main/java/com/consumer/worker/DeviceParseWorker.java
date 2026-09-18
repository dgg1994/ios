package com.consumer.worker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.consumer.config.V26ConsumerProperties;
import com.consumer.service.DeviceAutoParseService;
import com.consumer.service.InflightLockService;
import com.consumer.service.ParseQueueService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 消费 queue:device_parse。处理成功才从 inflight 删除，不允许快 ACK 丢消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceParseWorker {

    private static final String LOCK_KIND = "parse";

    private final StringRedisTemplate redis;
    private final V26ConsumerProperties props;
    private final DeviceAutoParseService autoParseService;
    private final ParseQueueService parseQueueService;
    private final InflightLockService inflightLockService;
    private final ConsumerWorkerRegistry registry;
    private RedisListWorker worker;

    @PostConstruct
    public void start() {
        worker = new RedisListWorker(redis, props.getQueue().getDeviceParse(), "device-parse",
                props.getConsumer(), props.getConsumer().getParse(), this::process);
        registry.register(worker);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        if (worker != null) {
            worker.stop();
        }
    }

    private void process(String raw) {
        JSONObject data;
        try {
            data = JSON.parseObject(raw);
        } catch (Exception e) {
            throw new PoisonMessageException("device parse bad payload: " + StringUtils.left(raw, 200));
        }
        String deviceId = StringUtils.trimToEmpty(data.getString("device_id"));
        if (deviceId.isEmpty()) {
            throw new PoisonMessageException("device parse missing device_id: " + StringUtils.left(raw, 200));
        }
        if (!inflightLockService.tryLock(LOCK_KIND, deviceId)) {
            inflightLockService.markDirty(LOCK_KIND, deviceId);
            log.info("device parse coalesced device={} (inflight)", deviceId);
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            Map<String, Object> result = autoParseService.autoParseV2(deviceId);
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                String reason = String.valueOf(result.get("error"));
                if ("device_not_found".equals(reason)) {
                    throw new IllegalStateException("device_not_found device=" + deviceId);
                }
                log.warn("device parse skip device={} reason={}", deviceId, reason);
                return;
            }
            enqueueBalances(deviceId, result.get("new_ids"));
            if (Boolean.TRUE.equals(result.get("has_notes"))) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("trigger", "device_parse");
                meta.put("from", "auto_parse_v2");
                meta.put("interversion", "v2");
                parseQueueService.enqueueNotesMnemonic(deviceId, meta);
            }
            log.info("【scan】扫词入库 done device={} costMs={} scanMs={} persistMs={} mnemonic+={} notes+={}",
                    deviceId, System.currentTimeMillis() - t0,
                    result.get("scan_ms"), result.get("persist_ms"),
                    result.get("mnemonic_added"), result.get("note_added"));
        } finally {
            boolean dirty = inflightLockService.clearDirty(LOCK_KIND, deviceId);
            inflightLockService.unlock(LOCK_KIND, deviceId);
            if (dirty) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("trigger", "inflight_dirty");
                parseQueueService.enqueueDeviceParse(deviceId, meta);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void enqueueBalances(String deviceId, Object newIdsRaw) {
        if (!props.getConsumer().isDeferBalance() || !(newIdsRaw instanceof List)) {
            return;
        }
        for (Object id : (List<Object>) newIdsRaw) {
            long mid;
            try {
                mid = Long.parseLong(String.valueOf(id));
            } catch (Exception e) {
                continue;
            }
            parseQueueService.enqueueMnemonicBalance(mid, deviceId);
        }
    }
}
