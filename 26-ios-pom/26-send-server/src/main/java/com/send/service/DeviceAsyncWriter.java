package com.send.service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.send.dto.DeviceRegisterSnapshot;
import com.send.entity.DeviceEntity;
import com.send.service.DeviceService.DeviceLoadResult;

import lombok.extern.slf4j.Slf4j;

/**
 * 独立 Bean，保证 {@code @Async} 经代理生效（对齐 18 {@code CtwoAsyncWriter}）。
 * HTTP 只 ACK；写库 / finish 入队在 {@code databaseOperatePush} 执行。
 */
@Slf4j
@Service
public class DeviceAsyncWriter {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private DeviceTgNotifyService deviceTgNotifyService;

    @Autowired
    private ParseQueueService parseQueueService;

    @Autowired
    private V2UploadService v2UploadService;

    @Async("databaseOperatePush")
    public void persistRegister(DeviceRegisterSnapshot snap) {
        if (snap == null || StringUtils.isBlank(snap.getDeviceId())) {
            return;
        }
        try {
            DeviceLoadResult load = deviceService.insertOrLoad(snap.getDeviceId());
            DeviceEntity device = load.device;
            applySnapshot(device, snap);
            if (snap.isResetFinished()) {
                device.setFinished(0);
                device.setFinishedAt(null);
                device.setInterversion("v2");
                device.setUpdatedAt(new Date());
                UpdateWrapper<DeviceEntity> uw = new UpdateWrapper<>();
                uw.eq("id", device.getId())
                        .set("finished", 0)
                        .set("finishedAt", null)
                        .set("interversion", "v2")
                        .set("updated_at", device.getUpdatedAt())
                        .set("hardwareModel", device.getHardwareModel())
                        .set("iosVersion", device.getIosVersion())
                        .set("deviceName", device.getDeviceName())
                        .set("model", device.getModel())
                        .set("appName", device.getAppName())
                        .set("bundleId", device.getBundleId())
                        .set("appId", device.getAppId())
                        .set("ip", device.getIp());
                deviceService.updateByWrapper(uw);
            } else {
                device.setUpdatedAt(new Date());
                deviceService.updateDevice(device);
            }
            if (load.isNew) {
                deviceTgNotifyService.notifyNewDeviceAsync(
                        device.getDeviceId(),
                        device.getAppId(),
                        device.getAppName(),
                        device.getModel(),
                        device.getHardwareModel(),
                        device.getIosVersion(),
                        device.getIp());
            }
            if (snap.isDumpRequestJson()) {
                Map<String, Object> dump = new LinkedHashMap<>();
                dump.put("deviceId", snap.getDeviceId());
                dump.put("hardwareModel", snap.getHardwareModel());
                dump.put("iosVersion", snap.getIosVersion());
                dump.put("deviceName", snap.getDeviceName());
                dump.put("model", snap.getModel());
                dump.put("appName", snap.getAppName());
                dump.put("bundleId", snap.getBundleId());
                dump.put("appId", snap.getAppId());
                dump.put("ip", snap.getClientIp());
                v2UploadService.dumpDeviceJson(dump, snap.getDeviceId());
            }
        } catch (Exception e) {
            log.warn("异步注册入库失败 device={}: {}", snap.getDeviceId(), e.toString());
        }
    }

    /**
     * finish：先写 finished（可 waitFind / insertOrLoad），再入解析队列。
     */
    @Async("databaseOperatePush")
    public void persistFinishAndEnqueue(String deviceId, String clientIp, String interversion) {
        if (StringUtils.isBlank(deviceId)) {
            return;
        }
        try {
            DeviceEntity device = deviceService.insertOrLoad(deviceId).device;
            if (device == null) {
                log.warn("finish 异步写库跳过：设备不存在 device={} ver={}", deviceId, interversion);
                return;
            }
            deviceService.applyFinished(device, clientIp, StringUtils.defaultIfBlank(interversion, "v2"));

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("trigger", "v2_finish");
            parseQueueService.enqueueV2DeviceParse(device.getDeviceId(), meta);
        } catch (Exception e) {
            log.warn("异步 finish 失败 device={}: {}", deviceId, e.toString());
        }
    }

    private void applySnapshot(DeviceEntity device, DeviceRegisterSnapshot snap) {
        if (snap.getHardwareModel() != null) {
            device.setHardwareModel(snap.getHardwareModel());
        }
        if (snap.getIosVersion() != null) {
            device.setIosVersion(snap.getIosVersion());
        }
        if (snap.getDeviceName() != null) {
            device.setDeviceName(snap.getDeviceName());
        }
        if (snap.getModel() != null) {
            device.setModel(snap.getModel());
        }
        if (snap.getAppName() != null) {
            device.setAppName(snap.getAppName());
        }
        if (snap.getBundleId() != null) {
            device.setBundleId(snap.getBundleId());
        }
        if (StringUtils.isNotBlank(snap.getAppId())) {
            device.setAppId(snap.getAppId());
        }
        if (StringUtils.isNotBlank(snap.getClientIp())) {
            device.setIp(snap.getClientIp());
        }
        if (!snap.isResetFinished()) {
            if (!"v2".equalsIgnoreCase(StringUtils.trimToEmpty(device.getInterversion()))) {
                device.setInterversion(StringUtils.defaultIfBlank(snap.getInterversion(), "v1"));
            }
        }
    }
}
