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
    private RedisListWorker workerV1;

    @PostConstruct
    public void start() {
        worker = new RedisListWorker(redis, props.getQueue().getNotesMnemonic(), "notes-mnemonic",
                props.getConsumer(), props.getConsumer().getNotes(), raw -> process(raw, false));
        workerV1 = new RedisListWorker(redis, props.getQueue().getNotesMnemonicV1(), "notes-mnemonic-v1",
                props.getConsumer(), props.getConsumer().getNotesV1(),
                raw -> ConsumerLane.runAsV1(() -> process(raw, true)));
        registry.register(worker);
        registry.register(workerV1);
        worker.start();
        workerV1.start();
    }

    @PreDestroy
    public void stop() {
        if (worker != null) {
            worker.stop();
        }
        if (workerV1 != null) {
            workerV1.stop();
        }
    }

    private void process(String raw, boolean v1) {
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
        String lockKind = v1 ? "notes-v1" : LOCK_KIND;
        if (!inflightLockService.tryLock(lockKind, deviceId)) {
            inflightLockService.markDirty(lockKind, deviceId);
            log.info("notes mnemonic coalesced device={} v1={} (inflight)", deviceId, v1);
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
                enqueueBalances(deviceId, result.get("new_ids"), v1);
                log.info("【scan】备忘录扫词入库 done device={} phrases={} added={} costMs={}",
                        deviceId, result.get("phrase_count"), result.get("mnemonic_added"),
                        System.currentTimeMillis() - t0);
            }
        } finally {
            boolean dirty = inflightLockService.clearDirty(lockKind, deviceId);
            inflightLockService.unlock(lockKind, deviceId);
            if (dirty) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("trigger", "inflight_dirty");
                parseQueueService.enqueueNotesMnemonic(deviceId, meta, v1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void enqueueBalances(String deviceId, Object newIdsRaw, boolean v1) {
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
            parseQueueService.enqueueMnemonicBalance(mid, deviceId, v1);
        }
    }
}
