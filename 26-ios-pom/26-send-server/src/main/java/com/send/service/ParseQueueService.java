package com.send.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.send.config.V26SendProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 解析队列入队：HTTP 热路径只提交异步任务，避免 Redis RTT 拖住 Tomcat 线程。
 */
@Slf4j
@Service
public class ParseQueueService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private V26SendProperties props;

    /** finish 调用：异步入队（池满 CallerRuns，不丢）。 */
    @Async("redisEnqueuePush")
    public void enqueueDeviceParseAsync(String deviceId, Map<String, Object> meta) {
        enqueueDeviceParse(deviceId, meta);
    }

    @Async("redisEnqueuePush")
    public void enqueueV2DeviceParseAsync(String deviceId, Map<String, Object> meta) {
        enqueueV2DeviceParse(deviceId, meta);
    }

    public boolean enqueueDeviceParse(String deviceId, Map<String, Object> meta) {
        String did = StringUtils.trimToEmpty(deviceId);
        if (did.isEmpty()) {
            log.warn("device parse enqueue skipped: empty device_id");
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", did);
        payload.put("meta", meta != null ? meta : new LinkedHashMap<>());
        try {
            stringRedisTemplate.opsForList().rightPush(
                    props.getQueue().getDeviceParse(),
                    JSON.toJSONString(payload));
            Object ver = meta != null ? meta.get("interversion") : null;
            log.info("device parse enqueued device={} interversion={}", did,
                    ver != null ? ver : "v1");
            return true;
        } catch (Exception e) {
            log.warn("device parse enqueue failed device={}: {}", did, e.toString());
            return false;
        }
    }

    public boolean enqueueV2DeviceParse(String deviceId, Map<String, Object> meta) {
        Map<String, Object> m = meta != null ? new LinkedHashMap<>(meta) : new LinkedHashMap<>();
        m.put("interversion", "v2");
        if (!m.containsKey("trigger")) {
            m.put("trigger", "v2_parse");
        }
        return enqueueDeviceParse(deviceId, m);
    }
}
