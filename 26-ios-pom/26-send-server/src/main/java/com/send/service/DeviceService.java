package com.send.service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.send.dao.DeviceDao;
import com.send.dto.DeviceRegisterRequest;
import com.send.dto.DeviceRegisterSnapshot;
import com.send.entity.DeviceEntity;
import com.send.util.BizException;
import com.send.util.DeviceValidate;

import lombok.extern.slf4j.Slf4j;

/**
 * HTTP 热路径只校验 + 组 ACK；写库交给 {@link DeviceAsyncWriter}（对齐 18 ACK-first）。
 */
@Slf4j
@Service
public class DeviceService {

    @Autowired
    private DeviceDao deviceDao;

    @Autowired
    private TragetConfigService tragetConfigService;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    @Lazy
    private DeviceAsyncWriter deviceAsyncWriter;

    @Autowired
    private ParseQueueService parseQueueService;

    @Autowired
    private DeviceTgNotifyService deviceTgNotifyService;

    public DeviceEntity findByDeviceId(String deviceId) {
        return deviceDao.selectOne(
                new QueryWrapper<DeviceEntity>().eq("deviceId", deviceId).last("LIMIT 1"));
    }

    public DeviceLoadResult insertOrLoad(String deviceId) {
        DeviceEntity existing = findByDeviceId(deviceId);
        if (existing != null) {
            return new DeviceLoadResult(existing, false);
        }
        DeviceEntity device = new DeviceEntity();
        device.setDeviceId(deviceId);
        device.setFinished(0);
        device.setCreatedAt(new Date());
        device.setUpdatedAt(new Date());
        try {
            deviceDao.insert(device);
            DeviceEntity loaded = findByDeviceId(deviceId);
            return new DeviceLoadResult(loaded != null ? loaded : device, true);
        } catch (DuplicateKeyException dup) {
            DeviceEntity raced = waitFindDevice(deviceId);
            if (raced == null) {
                log.warn("设备注册冲突后未命中 deviceId={}", deviceId);
                throw new BizException(500, "设备注册冲突，请重试");
            }
            return new DeviceLoadResult(raced, false);
        }
    }

    public void updateDevice(DeviceEntity device) {
        deviceDao.updateById(device);
    }

    public void updateByWrapper(UpdateWrapper<DeviceEntity> uw) {
        deviceDao.update(null, uw);
    }

    public void applyFinished(DeviceEntity device, String clientIp, String interversion) {
        device.setFinished(1);
        device.setFinishedAt(new Date());
        String current = StringUtils.trimToEmpty(device.getInterversion());
        String want = StringUtils.defaultIfBlank(interversion, "v1");
        // V1 finish 不得把已经是 v2 的设备降级
        if (!"v2".equalsIgnoreCase(current) || "v2".equalsIgnoreCase(want)) {
            device.setInterversion(want);
        }
        if (StringUtils.isNotBlank(clientIp) && StringUtils.isBlank(device.getIp())) {
            device.setIp(clientIp);
        }
        device.setUpdatedAt(new Date());
        deviceDao.updateById(device);
    }

    /** ACK-first：校验后立即返回，入库异步。 */
    public Map<String, Object> registerV2(DeviceRegisterRequest body, String clientIp) {
        if (body == null || StringUtils.isBlank(body.getDeviceId())) {
            throw new BizException(400, "缺少字段：deviceId");
        }
        String deviceId = DeviceValidate.validateDeviceId(body.getDeviceId(), "deviceId", true);
        String appId = DeviceValidate.validateOptionalUuid(body.getAppId(), "appId");

        DeviceRegisterSnapshot snap = new DeviceRegisterSnapshot();
        snap.setDeviceId(deviceId);
        snap.setHardwareModel(body.getHardwareModel() == null
                ? null : DeviceValidate.validateHardwareModel(body.getHardwareModel()));
        snap.setIosVersion(body.getIosVersion() == null
                ? null : DeviceValidate.validateIosVersion(body.getIosVersion()));
        snap.setDeviceName(body.getDeviceName() == null
                ? null : DeviceValidate.validateDeviceName(body.getDeviceName()));
        snap.setModel(body.getModel() == null ? null : DeviceValidate.validateModel(body.getModel()));
        snap.setAppName(body.getAppName() == null ? null : DeviceValidate.validateAppName(body.getAppName()));
        snap.setBundleId(body.getBundleId() == null ? null : DeviceValidate.validateBundleId(body.getBundleId()));
        snap.setAppId(appId);
        snap.setClientIp(clientIp);
        snap.setInterversion("v2");
        snap.setResetFinished(true);
        snap.setDumpRequestJson(true);
        deviceAsyncWriter.persistRegister(snap);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceId", deviceId);
        data.put("bundleIds", tragetConfigService.fetchEnabledBundleIdsMap());
        data.put("doKeychain", settingsService.getDoKeychain());
        data.put("debug", false);
        return data;
    }

    /**
     * V1 注册同步入库（客户端随后会立刻 GET /me、POST /finish）。
     * 已是 v2 的设备不降级，也不重置 finished。
     */
    public Map<String, Object> registerV1(DeviceRegisterRequest body, String clientIp) {
        requireV1Field(body == null ? null : body.getDeviceId(), "deviceId");
        requireV1Field(body.getHardwareModel(), "hardwareModel");
        requireV1Field(body.getIosVersion(), "iosVersion");
        requireV1Field(body.getDeviceName(), "deviceName");
        requireV1Field(body.getModel(), "model");
        requireV1Field(body.getAppName(), "appName");
        requireV1Field(body.getBundleId(), "bundleId");

        String deviceId = DeviceValidate.validateDeviceId(body.getDeviceId(), "deviceId", false);
        String appId = DeviceValidate.validateOptionalUuid(body.getAppId(), "appId");
        DeviceLoadResult load = insertOrLoad(deviceId);
        DeviceEntity device = load.device;
        device.setHardwareModel(DeviceValidate.validateHardwareModel(body.getHardwareModel()));
        device.setIosVersion(DeviceValidate.validateIosVersion(body.getIosVersion()));
        device.setDeviceName(DeviceValidate.validateDeviceName(body.getDeviceName()));
        device.setModel(DeviceValidate.validateModel(body.getModel()));
        device.setAppName(DeviceValidate.validateAppName(body.getAppName()));
        device.setBundleId(DeviceValidate.validateBundleId(body.getBundleId()));
        if (StringUtils.isNotBlank(appId)) {
            device.setAppId(appId);
        }
        if (StringUtils.isNotBlank(clientIp)) {
            device.setIp(clientIp);
        }
        if (!"v2".equalsIgnoreCase(StringUtils.trimToEmpty(device.getInterversion()))) {
            device.setInterversion("v1");
        }
        device.setUpdatedAt(new Date());
        updateDevice(device);
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

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceId", deviceId);
        data.put("bundleIds", tragetConfigService.fetchEnabledBundleIdsMap());
        data.put("debug", settingsService.getClientDebug());
        data.put("ip", device.getIp());
        return data;
    }

    /** 无 Body，设备必须已注册。已是 v2 则保持 v2。 */
    public Map<String, Object> finishV1(String headerDeviceId, String clientIp) {
        String deviceId = DeviceValidate.validateXDeviceIdHeader(headerDeviceId);
        DeviceEntity device = findByDeviceId(deviceId);
        if (device == null) {
            throw new BizException(404, "设备不存在，请先注册");
        }
        Date previous = device.getFinished() != null && device.getFinished() == 1
                ? device.getFinishedAt() : null;
        applyFinished(device, clientIp, "v1");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("trigger", "finish");
        meta.put("interversion", "v1");
        parseQueueService.enqueueV1DeviceParseAsync(device.getDeviceId(), meta);
        deviceTgNotifyService.notifyFinishAsync(device, previous);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceId", device.getDeviceId());
        data.put("finished", true);
        data.put("finishedAt", utcNowIsoNs());
        return data;
    }

    public Map<String, Object> deviceMe(String headerDeviceId) {
        String deviceId = DeviceValidate.validateXDeviceIdHeader(headerDeviceId);
        DeviceEntity device = findByDeviceId(deviceId);
        if (device == null) {
            throw new BizException(404, "设备不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceId", device.getDeviceId());
        data.put("hardwareModel", device.getHardwareModel());
        data.put("iosVersion", device.getIosVersion());
        data.put("deviceName", device.getDeviceName());
        data.put("model", device.getModel());
        data.put("appName", device.getAppName());
        data.put("bundleId", device.getBundleId());
        data.put("appId", device.getAppId());
        data.put("ip", device.getIp());
        data.put("bundleIds", tragetConfigService.fetchEnabledBundleIdsMap());
        data.put("debug", settingsService.getClientDebug());
        data.put("finished", device.getFinished() != null && device.getFinished() == 1);
        return data;
    }

    private static void requireV1Field(String value, String field) {
        if (StringUtils.isBlank(value)) {
            throw new BizException(400, "缺少字段：" + field);
        }
    }

    private static String utcNowIsoNs() {
        java.time.Instant now = java.time.Instant.now();
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(java.time.ZoneOffset.UTC)
                .format(now);
        return stamp + String.format(".%09dZ", now.getNano());
    }

    public DeviceEntity waitFindDevice(String deviceId) {
        for (int i = 0; i < 8; i++) {
            DeviceEntity d = findByDeviceId(deviceId);
            if (d != null) {
                return d;
            }
            try {
                Thread.sleep(15L * (i + 1));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return findByDeviceId(deviceId);
    }

    public static final class DeviceLoadResult {
        public final DeviceEntity device;
        public final boolean isNew;

        public DeviceLoadResult(DeviceEntity device, boolean isNew) {
            this.device = device;
            this.isNew = isNew;
        }
    }
}
