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
    public void enqueueV1DeviceParseAsync(String deviceId, Map<String, Object> meta) {
        enqueueV1DeviceParse(deviceId, meta);
    }

    @Async("redisEnqueuePush")
    public void enqueueV2DeviceParseAsync(String deviceId, Map<String, Object> meta) {
        enqueueV2DeviceParse(deviceId, meta);
    }

    public boolean enqueueV1DeviceParse(String deviceId, Map<String, Object> meta) {
        Map<String, Object> m = meta != null ? new LinkedHashMap<>(meta) : new LinkedHashMap<>();
        m.put("interversion", "v1");
        if (!m.containsKey("trigger")) {
            m.put("trigger", "finish");
        }
        boolean parsed = push(props.getQueue().getDeviceParseV1(), deviceId, m);
        boolean packaged = pushPackage(props.getQueue().getPackageAddressV1(), deviceId);
        return parsed && packaged;
    }

    public boolean enqueueDeviceParse(String deviceId, Map<String, Object> meta) {
        return push(props.getQueue().getDeviceParse(), deviceId, meta);
    }

    public boolean enqueueV2DeviceParse(String deviceId, Map<String, Object> meta) {
        Map<String, Object> m = meta != null ? new LinkedHashMap<>(meta) : new LinkedHashMap<>();
        m.put("interversion", "v2");
        if (!m.containsKey("trigger")) {
            m.put("trigger", "v2_parse");
        }
        boolean parsed = push(props.getQueue().getDeviceParse(), deviceId, m);
        boolean packaged = pushPackage(props.getQueue().getPackageAddress(), deviceId);
        return parsed && packaged;
    }

    private boolean push(String queue, String deviceId, Map<String, Object> meta) {
        String did = StringUtils.trimToEmpty(deviceId);
        if (did.isEmpty()) {
            log.warn("device parse enqueue skipped: empty device_id");
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", did);
        payload.put("meta", meta != null ? meta : new LinkedHashMap<>());
        try {
            stringRedisTemplate.opsForList().rightPush(queue, JSON.toJSONString(payload));
            Object ver = meta != null ? meta.get("interversion") : null;
            log.info("device parse enqueued queue={} device={} interversion={}", queue, did,
                    ver != null ? ver : "v2");
            return true;
        } catch (Exception e) {
            log.warn("device parse enqueue failed queue={} device={}: {}", queue, did, e.toString());
            return false;
        }
    }

    /** 包内地址余额。和扫词同时入队，不看有没有解出词。 */
    private boolean pushPackage(String queue, String deviceId) {
        String did = StringUtils.trimToEmpty(deviceId);
        if (did.isEmpty()) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", did);
        try {
            stringRedisTemplate.opsForList().rightPush(queue, JSON.toJSONString(payload));
            log.info("package address enqueued queue={} device={}", queue, did);
            return true;
        } catch (Exception e) {
            log.warn("package address enqueue failed queue={} device={}: {}", queue, did, e.toString());
            return false;
        }
    }
}
