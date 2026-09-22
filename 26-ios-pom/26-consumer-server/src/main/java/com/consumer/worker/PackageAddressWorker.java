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
import com.consumer.service.PackageAddressService;
import com.consumer.service.ParseQueueService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 扫包内缓存地址和余额。和扫词、派生不是同一条队列。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PackageAddressWorker {

    private static final String LOCK_KIND = "pkgaddr";

    private final StringRedisTemplate redis;
    private final V26ConsumerProperties props;
    private final PackageAddressService packageAddressService;
    private final ParseQueueService parseQueueService;
    private final InflightLockService inflightLockService;
    private final ConsumerWorkerRegistry registry;
    private RedisListWorker worker;
    private RedisListWorker workerV1;

    @PostConstruct
    public void start() {
        worker = new RedisListWorker(redis, props.getQueue().getPackageAddress(), "package-address",
                props.getConsumer(), props.getConsumer().getPackageAddress(), this::process);
        workerV1 = new RedisListWorker(redis, props.getQueue().getPackageAddressV1(), "package-address-v1",
                props.getConsumer(), props.getConsumer().getPackageAddressV1(),
                raw -> ConsumerLane.runAsV1(() -> process(raw)));
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

    private void process(String raw) {
        JSONObject data;
        try {
            data = JSON.parseObject(raw);
        } catch (Exception e) {
            throw new PoisonMessageException("package address bad payload: " + StringUtils.left(raw, 200));
        }
        String deviceId = StringUtils.trimToEmpty(data.getString("device_id"));
        if (deviceId.isEmpty()) {
            throw new PoisonMessageException("package address missing device_id: " + StringUtils.left(raw, 200));
        }
        String lockKind = ConsumerLane.isV1() ? "pkgaddr-v1" : LOCK_KIND;
        if (!inflightLockService.tryLock(lockKind, deviceId)) {
            inflightLockService.markDirty(lockKind, deviceId);
            log.info("package address coalesced device={} v1={}", deviceId, ConsumerLane.isV1());
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            packageAddressService.scanQueued(deviceId);
            log.info("【pkgaddr】包内地址余额 done device={} v1={} costMs={}",
                    deviceId, ConsumerLane.isV1(), System.currentTimeMillis() - t0);
        } finally {
            boolean dirty = inflightLockService.clearDirty(lockKind, deviceId);
            inflightLockService.unlock(lockKind, deviceId);
            if (dirty) {
                parseQueueService.enqueuePackageAddress(deviceId, ConsumerLane.isV1());
            }
        }
    }
}
