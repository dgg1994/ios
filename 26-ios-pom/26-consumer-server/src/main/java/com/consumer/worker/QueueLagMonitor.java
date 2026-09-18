package com.consumer.worker;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.consumer.config.V26ConsumerProperties;

import lombok.RequiredArgsConstructor;

/**
 * 供 /ready 查询 Redis 队列长度，不再定时打日志。
 */
@Component
@RequiredArgsConstructor
public class QueueLagMonitor {

    private final StringRedisTemplate redis;
    private final V26ConsumerProperties props;

    public Map<String, Long> queueLens() {
        Map<String, Long> out = new LinkedHashMap<>();
        putLen(out, props.getQueue().getDeviceParse());
        putLen(out, props.getQueue().getDeviceParse() + ":inflight");
        putLen(out, props.getQueue().getDeviceParse() + ":dead");
        putLen(out, props.getQueue().getMnemonicBalance());
        putLen(out, props.getQueue().getMnemonicBalance() + ":inflight");
        putLen(out, props.getQueue().getMnemonicBalance() + ":dead");
        putLen(out, props.getQueue().getNotesMnemonic());
        putLen(out, props.getQueue().getNotesMnemonic() + ":inflight");
        putLen(out, props.getQueue().getNotesMnemonic() + ":dead");
        putLen(out, props.getQueue().getTgMessage());
        putLen(out, props.getQueue().getTgMessage() + ":inflight");
        putLen(out, props.getQueue().getTgMessage() + ":dead");
        return out;
    }

    private void putLen(Map<String, Long> out, String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        try {
            Long n = redis.opsForList().size(key);
            out.put(key, n == null ? 0L : n);
        } catch (Exception e) {
            out.put(key, -1L);
        }
    }
}
