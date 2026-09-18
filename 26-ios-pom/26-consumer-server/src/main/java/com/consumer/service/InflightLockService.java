package com.consumer.service;

import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.consumer.config.V26ConsumerProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 同一 device / mnemonic 并发消费锁：进行中标记 dirty，结束后再入队一次，避免重复扫盘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InflightLockService {

    private final StringRedisTemplate redis;
    private final V26ConsumerProperties props;

    public boolean tryLock(String kind, String id) {
        String key = lockKey(kind, id);
        if (key == null) {
            return true;
        }
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, "1",
                    Math.max(30, props.getConsumer().getInflightTtlSec()), TimeUnit.SECONDS);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("inflight lock fail kind={} id={}: {}", kind, id, e.toString());
            return true;
        }
    }

    public void markDirty(String kind, String id) {
        String key = dirtyKey(kind, id);
        if (key == null) {
            return;
        }
        try {
            redis.opsForValue().set(key, "1",
                    Math.max(30, props.getConsumer().getInflightTtlSec()), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("inflight dirty fail kind={} id={}: {}", kind, id, e.toString());
        }
    }

    public boolean clearDirty(String kind, String id) {
        String key = dirtyKey(kind, id);
        if (key == null) {
            return false;
        }
        try {
            Boolean deleted = redis.delete(key);
            return Boolean.TRUE.equals(deleted);
        } catch (Exception e) {
            return false;
        }
    }

    public void unlock(String kind, String id) {
        String key = lockKey(kind, id);
        if (key == null) {
            return;
        }
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("inflight unlock fail kind={} id={}: {}", kind, id, e.toString());
        }
    }

    private static String lockKey(String kind, String id) {
        String k = StringUtils.trimToEmpty(kind);
        String i = StringUtils.trimToEmpty(id).toLowerCase();
        if (k.isEmpty() || i.isEmpty()) {
            return null;
        }
        return "v26:inflight:" + k + ":" + i;
    }

    private static String dirtyKey(String kind, String id) {
        String k = StringUtils.trimToEmpty(kind);
        String i = StringUtils.trimToEmpty(id).toLowerCase();
        if (k.isEmpty() || i.isEmpty()) {
            return null;
        }
        return "v26:dirty:" + k + ":" + i;
    }
}
