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
import com.consumer.service.InflightLockService;
import com.consumer.service.NotesMnemonicService;
import com.consumer.service.ParseQueueService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotesMnemonicWorker {

    private static final String LOCK_KIND = "notes";

    private final StringRedisTemplate redis;
    private final V26ConsumerProperties props;
    private final NotesMnemonicService notesMnemonicService;
    private final ParseQueueService parseQueueService;
    private final InflightLockService inflightLockService;
    private final ConsumerWorkerRegistry registry;
    private RedisListWorker worker;

    @PostConstruct
    public void start() {
        worker = new RedisListWorker(redis, props.getQueue().getNotesMnemonic(), "notes-mnemonic",
                props.getConsumer(), props.getConsumer().getNotes(), this::process);
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
            throw new PoisonMessageException("notes mnemonic bad payload: " + StringUtils.left(raw, 200));
        }
        String deviceId = StringUtils.trimToEmpty(data.getString("device_id"));
        if (deviceId.isEmpty()) {
            throw new PoisonMessageException("notes mnemonic missing device_id: " + StringUtils.left(raw, 200));
        }
        if (!inflightLockService.tryLock(LOCK_KIND, deviceId)) {
            inflightLockService.markDirty(LOCK_KIND, deviceId);
            log.info("notes mnemonic coalesced device={} (inflight)", deviceId);
            return;
        }
        try {
            long t0 = System.currentTimeMillis();
            var result = notesMnemonicService.process(deviceId);
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                String reason = String.valueOf(result.get("error"));
                if ("device_not_found".equals(reason)) {
                    throw new IllegalStateException("device_not_found device=" + deviceId);
                }
                log.warn("notes mnemonic skip device={} reason={} costMs={}",
                        deviceId, reason, System.currentTimeMillis() - t0);
            } else {
                enqueueBalances(deviceId, result.get("new_ids"));
                log.info("【scan】备忘录扫词入库 done device={} phrases={} added={} costMs={}",
                        deviceId, result.get("phrase_count"), result.get("mnemonic_added"),
                        System.currentTimeMillis() - t0);
            }
        } finally {
            boolean dirty = inflightLockService.clearDirty(LOCK_KIND, deviceId);
            inflightLockService.unlock(LOCK_KIND, deviceId);
            if (dirty) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("trigger", "inflight_dirty");
                parseQueueService.enqueueNotesMnemonic(deviceId, meta);
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
