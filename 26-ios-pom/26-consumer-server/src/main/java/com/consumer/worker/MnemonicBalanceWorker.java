package com.consumer.worker;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.consumer.config.V26ConsumerProperties;
import com.consumer.service.InflightLockService;
import com.consumer.service.MnemonicStoreService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MnemonicBalanceWorker {

    private static final String LOCK_KIND = "balance";

    private final StringRedisTemplate redis;
    private final V26ConsumerProperties props;
    private final MnemonicStoreService mnemonicStoreService;
    private final InflightLockService inflightLockService;
    private final ConsumerWorkerRegistry registry;
    private RedisListWorker worker;

    @PostConstruct
    public void start() {
        worker = new RedisListWorker(redis, props.getQueue().getMnemonicBalance(), "mnemonic-balance",
                props.getConsumer(), props.getConsumer().getBalance(), this::process);
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
            throw new PoisonMessageException("mnemonic balance bad payload: " + StringUtils.left(raw, 200));
        }
        long mnemonicId = 0;
        try {
            mnemonicId = data.getLongValue("mnemonic_id");
        } catch (Exception ignored) {
        }
        if (mnemonicId <= 0) {
            throw new PoisonMessageException("mnemonic balance bad id: " + StringUtils.left(raw, 200));
        }
        String lockId = String.valueOf(mnemonicId);
        if (!inflightLockService.tryLock(LOCK_KIND, lockId)) {
            log.info("mnemonic balance skip duplicate in-flight id={}", mnemonicId);
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            var result = mnemonicStoreService.runBalanceNotify(mnemonicId);
            long costMs = System.currentTimeMillis() - t0;
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                String reason = String.valueOf(result.get("error"));
                if ("not_found".equals(reason) || "mnemonic_not_found".equals(reason)) {
                    throw new IllegalStateException("mnemonic not found id=" + mnemonicId);
                }
                log.warn("【balance】派生查余额 skip id={} reason={} costMs={}", mnemonicId, reason, costMs);
            } else {
                log.info("【balance】派生查余额 done id={} device={} addr={} deriveMs={} queryMs={} upsertMs={} tgMs={} costMs={} tg={}",
                        mnemonicId, result.get("device_id"), result.get("address_count"),
                        result.get("derive_ms"), result.get("query_ms"), result.get("upsert_ms"),
                        result.get("tg_ms"), costMs, result.get("tg_queued"));
            }
        } finally {
            inflightLockService.unlock(LOCK_KIND, lockId);
        }
    }
}
