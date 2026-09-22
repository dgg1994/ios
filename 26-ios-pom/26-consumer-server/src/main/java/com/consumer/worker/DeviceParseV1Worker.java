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
 * 只消费 {@code queue:device_parse_v1}。线程、扫包池、后续备忘录/查余额队列都和 V2 分开。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceParseV1Worker {

    private static final String LOCK_KIND = "parse-v1";

    private final StringRedisTemplate redis;
    private final V26ConsumerProperties props;
    private final DeviceAutoParseService autoParseService;
    private final ParseQueueService parseQueueService;
    private final InflightLockService inflightLockService;
    private final ConsumerWorkerRegistry registry;
    private RedisListWorker worker;

    @PostConstruct
    public void start() {
        worker = new RedisListWorker(redis, props.getQueue().getDeviceParseV1(), "device-parse-v1",
                props.getConsumer(), props.getConsumer().getParseV1(), raw -> ConsumerLane.runAsV1(() -> process(raw)));
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
            throw new PoisonMessageException("device parse v1 bad payload: " + StringUtils.left(raw, 200));
        }
        String deviceId = StringUtils.trimToEmpty(data.getString("device_id"));
        if (deviceId.isEmpty()) {
            throw new PoisonMessageException("device parse v1 missing device_id: " + StringUtils.left(raw, 200));
        }
        if (!inflightLockService.tryLock(LOCK_KIND, deviceId)) {
            inflightLockService.markDirty(LOCK_KIND, deviceId);
            log.info("device parse v1 coalesced device={} (inflight)", deviceId);
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            Map<String, Object> result = autoParseService.autoParse(deviceId, "v1");
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                String reason = String.valueOf(result.get("error"));
                if ("device_not_found".equals(reason)) {
                    throw new IllegalStateException("device_not_found device=" + deviceId);
                }
                log.warn("device parse v1 skip device={} reason={}", deviceId, reason);
                return;
            }
            if ("v2".equalsIgnoreCase(String.valueOf(result.get("interversion")))) {
                Map<String, Object> moved = new LinkedHashMap<>();
                moved.put("trigger", "v1_worker_saw_v2");
                moved.put("interversion", "v2");
                parseQueueService.enqueueDeviceParse(deviceId, moved);
                log.info("device parse v1 handed v2 device back device={}", deviceId);
                return;
            }
            enqueueBalances(deviceId, result.get("new_ids"));
            if (Boolean.TRUE.equals(result.get("has_notes"))) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("trigger", "device_parse");
                meta.put("from", "auto_parse");
                meta.put("interversion", "v1");
                parseQueueService.enqueueNotesMnemonic(deviceId, meta, true);
            }
            log.info("【scan】v1 扫词入库 done device={} costMs={} scanMs={} persistMs={} mnemonic+={} notes+={}",
                    deviceId, System.currentTimeMillis() - t0,
                    result.get("scan_ms"), result.get("persist_ms"),
                    result.get("mnemonic_added"), result.get("note_added"));
        } finally {
            boolean dirty = inflightLockService.clearDirty(LOCK_KIND, deviceId);
            inflightLockService.unlock(LOCK_KIND, deviceId);
            if (dirty) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("trigger", "inflight_dirty");
                meta.put("interversion", "v1");
                parseQueueService.enqueueDeviceParseV1(deviceId, meta);
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
            parseQueueService.enqueueMnemonicBalance(mid, deviceId, true);
        }
    }
}
