package com.consume.service;

import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.consume.dao.C2RecordDao;
import com.consume.dao.ChannelDao;
import com.consume.dao.DeviceDao;
import com.consume.entity.ChannelEntity;
import com.consume.entity.DeviceEntity;
import com.consume.util.DeviceIdUtil;

/**
 * 设备解析/绑定 + c2_records 关联。
 *
 * <ul>
 *   <li>/a /u /event：按 d/f（deviceid）查/建，入库时写入 lhu</li>
 *   <li>/t：先 d/f → 再 lhu → 再 u/s；永不新建；未命中则 ACK 丢弃（不重试等 /a）</li>
 * </ul>
 */
@Component
public class DeviceResolver {

    private static final Logger log = LoggerFactory.getLogger(DeviceResolver.class);

    @Autowired
    private DeviceDao deviceDao;

    @Autowired
    private ChannelDao channelDao;

    @Autowired
    private C2RecordDao c2RecordDao;

    @Autowired
    private DeviceWriteService deviceWriteService;

    private final ConcurrentHashMap<String, CacheEntry<DeviceEntity>> deviceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<ChannelEntity>> channelCache = new ConcurrentHashMap<>();
    private static final long DEVICE_CACHE_TTL_MS = 30_000L;
    private static final long CHANNEL_CACHE_TTL_MS = 60_000L;
    private static final int LOOKUP_RETRY = 2;
    private static final long LOOKUP_RETRY_DELAY_MS = 50L;

    public Long resolveAndLink(C2HandlerContext ctx, String dValue, String fValue,
                               String uValue, String model, String iosVersion) {
        return resolveAndLink(ctx, dValue, fValue, uValue, model, iosVersion, null, null);
    }

    public Long resolveAndLink(C2HandlerContext ctx, String dValue, String fValue,
                               String uValue, String model, String iosVersion, String serial) {
        return resolveAndLink(ctx, dValue, fValue, uValue, model, iosVersion, serial, null);
    }

    /**
     * @param lhu 同机会话键；/a /u /event 写入 device.lhu，/t 作二次匹配
     */
    public Long resolveAndLink(C2HandlerContext ctx, String dValue, String fValue,
                               String uValue, String model, String iosVersion,
                               String serial, String lhu) {
        long recordId = ctx.getRecordId();
        String normD = DeviceIdUtil.normalize(dValue);
        String normF = DeviceIdUtil.normalize(fValue);
        String idLike = !normD.isEmpty() ? normD : normF;
        String udid = DeviceIdUtil.normalizeUdid(uValue);
        // /t 的 s 常为 hex-ASCII，需与 d/f 一样归一化
        String normSerial = serial == null || serial.trim().isEmpty()
                ? "" : DeviceIdUtil.normalize(serial);
        String normLhu = normalizeLhu(lhu);
        boolean isT = "/t".equals(ctx.getPath());

        if (idLike.isEmpty() && udid.isEmpty() && normSerial.isEmpty() && normLhu.isEmpty()) {
            log.info("正常日志:[c2_handlers] 无 d/f/u/s/lhu，跳过设备关联, id={}, path={}", recordId, ctx.getPath());
            return null;
        }

        if (isT) {
            return resolveForT(ctx, dValue, fValue, normD, normF, idLike, udid, normSerial, normLhu,
                    model, iosVersion, recordId);
        }

        // /a /u /event 等：d/f（及 u/s/ecid）查重 → 命中则补 lhu；未命中则建机并写 lhu
        DeviceEntity existing = findByDfThenAltWithRetry(normD, normF, dValue, fValue, udid, normSerial);
        if (existing != null && existing.getId() != null) {
            fillModelVersionIfBlank(existing, model, iosVersion);
            fillLhuIfBlank(existing, normLhu);
            return applyFound(ctx, existing, idLike.isEmpty() ? existing.getDeviceId() : idLike, recordId);
        }

        if (idLike.isEmpty()) {
            ctx.setDeviceId(udid.isEmpty() ? normSerial : udid);
            log.info("正常日志:[c2_handlers] 仅有 u/s/lhu 且未命中已有设备，跳过新建, id={}, path={}, udid={}, serial={}, lhu={}",
                    recordId, ctx.getPath(), udid, normSerial, normLhu);
            return null;
        }

        String domain = ctx.getDomain();
        ChannelEntity channel = null;
        if (domain != null && !domain.isEmpty()) {
            try {
                channel = findChannelCached(domain);
            } catch (Exception e) {
                log.info("异常日志:[c2_handlers] 查询渠道失败, domain={}, err={}", domain, e.getMessage());
            }
        }
        if (channel == null) {
            ctx.setDeviceId(idLike);
            log.info("正常日志:[c2_handlers] 未找到设备且无渠道，跳过新建, id={}, path={}, domain={}, deviceid={}",
                    recordId, ctx.getPath(), domain, idLike);
            return null;
        }

        log.info("正常日志:[c2_handlers] 准备新建设备, path={}, recordId={}, deviceid={}, lhu={}, domain={}",
                ctx.getPath(), recordId, idLike, normLhu, domain);

        DeviceEntity device = new DeviceEntity();
        device.setChannelCode(nullToEmpty(channel.getChannelcode()));
        device.setIp(ctx.getClientIp());
        device.setDomain(domain);
        device.setAddtime(System.currentTimeMillis() / 1000.0);
        device.setDeviceId(idLike);
        device.setEcid(idLike);
        if (!udid.isEmpty()) {
            device.setUdid(udid);
            device.setDeviceUuid(udid);
        }
        if (!normSerial.isEmpty()) {
            device.setSerial(normSerial);
        }
        if (!normLhu.isEmpty()) {
            device.setLhu(normLhu);
        }
        device.setModel(model);
        device.setIosVersion(iosVersion);
        device.setDevicestatus(1);
        device.setOnlinestatus(1);
        device.setBindPhase(1);
        device.setIpstatus(0);
        device.setLastEventAt(System.currentTimeMillis() / 1000.0);
        device.setC2Series(0);

        DeviceEntity saved;
        try {
            saved = deviceWriteService.insertOrFind(device, idLike, normD, normF,
                    dValue == null ? "" : dValue.trim(), fValue == null ? "" : fValue.trim(),
                    udid, normSerial);
        } catch (Exception e) {
            log.info("错误日志:[c2_handlers] 新建设备失败, deviceid={}, err={}", idLike, e.getMessage());
            ctx.setDeviceId(idLike);
            return null;
        }
        if (saved == null || saved.getId() == null) {
            ctx.setDeviceId(idLike);
            throw new BodyNotReadyException("device not ready (concurrent create), deviceid=" + idLike);
        }
        fillLhuIfBlank(saved, normLhu);
        putDeviceCache(idLike, saved);
        if (saved.getDeviceId() != null) {
            putDeviceCache(saved.getDeviceId(), saved);
        }
        log.info("正常日志:[c2_handlers] 新建设备/并发命中, deviceid={}, rowId={}, path={}, recordId={}, lhu={}, channelcode={}",
                saved.getDeviceId() == null ? idLike : saved.getDeviceId(),
                saved.getId(), ctx.getPath(), recordId, saved.getLhu(), saved.getChannelCode());
        return applyFound(ctx, saved, idLike, recordId);
    }

    /**
     * /t：① d/f → deviceid；② lhu → device.lhu；③ u/s 兜底；不新建。
     * multipart 的 d/f 常与 /a 不一致，主要靠 lhu；u/s 在 /a 已建机但 lhu 尚未回写时有用。
     */
    private Long resolveForT(C2HandlerContext ctx, String dValue, String fValue,
                             String normD, String normF, String idLike,
                             String udid, String normSerial, String normLhu,
                             String model, String iosVersion, long recordId) {
        DeviceEntity existing = findByDfWithRetry(normD, normF, dValue, fValue);
        String matchBy = "d/f";
        if (existing == null || existing.getId() == null) {
            if (!normLhu.isEmpty()) {
                existing = findByLhuWithRetry(normLhu);
                matchBy = "lhu";
            }
        }
        if (existing == null || existing.getId() == null) {
            existing = findByAlternateIds(udid, normSerial, null, null);
            if (existing != null && existing.getId() != null) {
                matchBy = "u/s";
            }
        }
        if (existing != null && existing.getId() != null) {
            log.debug("正常日志:[c2_handlers] /t 命中已有设备, matchBy={}, recordId={}, rowId={}, deviceid={}, "
                            + "rawD={}, rawF={}, lhu={}, udid={}, serial={}",
                    matchBy, recordId, existing.getId(), existing.getDeviceId(),
                    dValue, fValue, normLhu, udid, normSerial);
            fillModelVersionIfBlank(existing, model, iosVersion);
            // 若靠 u/s 命中且设备尚无 lhu，补上便于后续 /t
            fillLhuIfBlank(existing, normLhu);
            return applyFound(ctx, existing, existing.getDeviceId(), recordId);
        }

        ctx.setDeviceId(idLike.isEmpty() ? (normLhu.isEmpty() ? udid : normLhu) : idLike);
        // 未命中直接丢弃：避免同分区被重试堵住；依赖后续同设备新 /t（/a 已建机后）再落 album
        log.info("正常日志:[c2_handlers] /t 未命中（d/f、lhu、u/s 皆无），ACK 丢弃不重试, recordId={}, "
                        + "rawD={}, rawF={}, normD={}, normF={}, lhu={}, udid={}, serial={}",
                recordId, dValue, fValue, normD, normF, normLhu, udid, normSerial);
        return null;
    }

    private Long applyFound(C2HandlerContext ctx, DeviceEntity existing, String idLikeFallback, long recordId) {
        String deviceid = existing.getDeviceId();
        if (deviceid == null || deviceid.isEmpty()) {
            deviceid = idLikeFallback;
        }
        ctx.setDeviceRowId(existing.getId());
        ctx.setDeviceId(deviceid);
        applyChannelcode(ctx, existing.getChannelCode());
        link(recordId, existing.getId().longValue());
        log.info("正常日志:[c2_handlers] 设备已关联, recordId={}, path={}, rowId={}, deviceid={}, lhu={}, channelcode={}",
                recordId, ctx.getPath(), existing.getId(), deviceid, existing.getLhu(), ctx.getChannelcode());
        return existing.getId().longValue();
    }

    private DeviceEntity findByDfWithRetry(String normD, String normF, String rawD, String rawF) {
        DeviceEntity hit = findByDf(normD, normF, rawD, rawF);
        if (hit != null) {
            return hit;
        }
        for (int i = 1; i <= LOOKUP_RETRY; i++) {
            sleepBrief();
            hit = findByDf(normD, normF, rawD, rawF);
            if (hit != null) {
                log.info("正常日志:[c2_handlers] d/f 重试命中, attempt={}, deviceid={}", i, hit.getDeviceId());
                return hit;
            }
        }
        return null;
    }

    private DeviceEntity findByDf(String normD, String normF, String rawD, String rawF) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        addCandidate(keys, normD);
        addCandidate(keys, normF);
        addCandidate(keys, rawD);
        addCandidate(keys, rawF);
        for (String key : keys) {
            DeviceEntity device = findDeviceCached(key);
            if (device != null && device.getId() != null) {
                return device;
            }
        }
        return null;
    }

    private DeviceEntity findByDfThenAltWithRetry(String normD, String normF, String rawD, String rawF,
                                                  String udid, String serial) {
        DeviceEntity hit = findByDfThenAlt(normD, normF, rawD, rawF, udid, serial);
        if (hit != null) {
            return hit;
        }
        for (int i = 1; i <= LOOKUP_RETRY; i++) {
            sleepBrief();
            hit = findByDfThenAlt(normD, normF, rawD, rawF, udid, serial);
            if (hit != null) {
                log.info("正常日志:[c2_handlers] 设备重试命中, attempt={}, deviceid={}", i, hit.getDeviceId());
                return hit;
            }
        }
        return null;
    }

    private DeviceEntity findByDfThenAlt(String normD, String normF, String rawD, String rawF,
                                         String udid, String serial) {
        DeviceEntity byDf = findByDf(normD, normF, rawD, rawF);
        if (byDf != null) {
            return byDf;
        }
        return findByAlternateIds(udid, serial, normD, normF);
    }

    private DeviceEntity findByLhuWithRetry(String lhu) {
        DeviceEntity hit = findByLhu(lhu);
        if (hit != null) {
            return hit;
        }
        for (int i = 1; i <= LOOKUP_RETRY; i++) {
            sleepBrief();
            hit = findByLhu(lhu);
            if (hit != null) {
                log.info("正常日志:[c2_handlers] lhu 重试命中, attempt={}, lhu={}, rowId={}",
                        i, lhu, hit.getId());
                return hit;
            }
        }
        return null;
    }

    private DeviceEntity findByLhu(String lhu) {
        if (lhu == null || lhu.isEmpty()) {
            return null;
        }
        try {
            DeviceEntity e = deviceDao.findByLhu(lhu);
            if (e != null && e.getId() != null) {
                return e;
            }
        } catch (Exception ex) {
            log.info("异常日志:[c2_handlers] 按 lhu 查 device 失败（是否未执行 ALTER 加列？）, lhu={}, err={}",
                    lhu, ex.getMessage());
        }
        return null;
    }

    private DeviceEntity findByAlternateIds(String udid, String serial, String normD, String normF) {
        if (udid != null && !udid.isEmpty()) {
            DeviceEntity e = deviceDao.findByUdid(udid);
            if (e != null && e.getId() != null) {
                return e;
            }
            e = deviceDao.findByDeviceUuid(udid);
            if (e != null && e.getId() != null) {
                return e;
            }
        }
        if (serial != null && !serial.isEmpty()) {
            DeviceEntity e = deviceDao.findBySerial(serial);
            if (e != null && e.getId() != null) {
                return e;
            }
        }
        for (String ecid : new String[]{normD, normF}) {
            if (ecid == null || ecid.isEmpty()) {
                continue;
            }
            DeviceEntity e = deviceDao.findByEcid(ecid);
            if (e != null && e.getId() != null) {
                return e;
            }
        }
        return null;
    }

    private void fillModelVersionIfBlank(DeviceEntity existing, String model, String iosVersion) {
        if (existing == null || existing.getId() == null) {
            return;
        }
        boolean needModel = (existing.getModel() == null || existing.getModel().isEmpty())
                && model != null && !model.isEmpty();
        boolean needVer = (existing.getIosVersion() == null || existing.getIosVersion().isEmpty())
                && iosVersion != null && !iosVersion.isEmpty();
        if (!needModel && !needVer) {
            return;
        }
        try {
            DeviceEntity patch = new DeviceEntity();
            patch.setId(existing.getId());
            if (needModel) {
                patch.setModel(model);
                existing.setModel(model);
            }
            if (needVer) {
                patch.setIosVersion(iosVersion);
                existing.setIosVersion(iosVersion);
            }
            deviceDao.updateById(patch);
            log.info("正常日志:[c2_handlers] 补全设备 model/ios_version, rowId={}, model={}, iosVersion={}",
                    existing.getId(), existing.getModel(), existing.getIosVersion());
            invalidateDeviceCache(existing);
            putDeviceCacheKeys(existing);
        } catch (Exception e) {
            log.info("异常日志:[c2_handlers] 补全设备版本失败, rowId={}, err={}",
                    existing.getId(), e.getMessage());
        }
    }

    /**
     * /a /u /event 写入/刷新 lhu。
     * 设备重置后 ECID 不变、lhu 会变：必须覆盖旧值，否则 /t 用新 lhu 永远粘不上。
     */
    private void fillLhuIfBlank(DeviceEntity existing, String lhu) {
        if (existing == null || existing.getId() == null || lhu == null || lhu.isEmpty()) {
            return;
        }
        String cur = existing.getLhu();
        if (cur != null && !cur.isEmpty() && cur.equalsIgnoreCase(lhu)) {
            return;
        }
        try {
            DeviceEntity patch = new DeviceEntity();
            patch.setId(existing.getId());
            patch.setLhu(lhu);
            deviceDao.updateById(patch);
            existing.setLhu(lhu);
            if (cur == null || cur.isEmpty()) {
                log.info("正常日志:[c2_handlers] 补全设备 lhu, rowId={}, lhu={}", existing.getId(), lhu);
            } else {
                log.info("正常日志:[c2_handlers] 设备重置/会话切换，更新 lhu, rowId={}, old={}, new={}",
                        existing.getId(), cur, lhu);
            }
            invalidateDeviceCache(existing);
            putDeviceCacheKeys(existing);
        } catch (Exception e) {
            log.info("异常日志:[c2_handlers] 更新设备 lhu 失败（是否未执行 ALTER 加列？）, rowId={}, err={}",
                    existing.getId(), e.getMessage());
        }
    }

    private void sleepBrief() {
        try {
            Thread.sleep(LOOKUP_RETRY_DELAY_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String normalizeLhu(String lhu) {
        if (lhu == null) {
            return "";
        }
        return lhu.trim();
    }

    private static void addCandidate(LinkedHashSet<String> keys, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        keys.add(value);
        String norm = DeviceIdUtil.normalize(value);
        if (!norm.isEmpty()) {
            keys.add(norm);
        }
    }

    private DeviceEntity findDeviceCached(String deviceId) {
        CacheEntry<DeviceEntity> hit = deviceCache.get(deviceId);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.atMs < DEVICE_CACHE_TTL_MS) {
            return hit.value;
        }
        DeviceEntity device = deviceDao.findDeviceId(deviceId);
        if (device != null) {
            putDeviceCache(deviceId, device);
            if (device.getDeviceId() != null && !device.getDeviceId().isEmpty()) {
                putDeviceCache(device.getDeviceId(), device);
            }
        }
        return device;
    }

    private void putDeviceCache(String deviceId, DeviceEntity device) {
        if (deviceId == null || deviceId.isEmpty() || device == null) {
            return;
        }
        deviceCache.put(deviceId, new CacheEntry<>(device, System.currentTimeMillis()));
        if (deviceCache.size() > 20_000) {
            deviceCache.clear();
        }
    }

    /** 写入/刷新实体后按已知键回填缓存，避免 30s TTL 内读到旧 lhu/model */
    private void putDeviceCacheKeys(DeviceEntity device) {
        if (device == null) {
            return;
        }
        if (device.getDeviceId() != null && !device.getDeviceId().isEmpty()) {
            putDeviceCache(device.getDeviceId(), device);
        }
        if (device.getEcid() != null && !device.getEcid().isEmpty()) {
            putDeviceCache(device.getEcid(), device);
        }
    }

    private void invalidateDeviceCache(DeviceEntity device) {
        if (device == null) {
            return;
        }
        if (device.getDeviceId() != null && !device.getDeviceId().isEmpty()) {
            deviceCache.remove(device.getDeviceId());
        }
        if (device.getEcid() != null && !device.getEcid().isEmpty()) {
            deviceCache.remove(device.getEcid());
        }
    }

    private ChannelEntity findChannelCached(String domain) {
        CacheEntry<ChannelEntity> hit = channelCache.get(domain);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.atMs < CHANNEL_CACHE_TTL_MS) {
            return hit.value;
        }
        ChannelEntity channel = channelDao.findByDomain(domain);
        channelCache.put(domain, new CacheEntry<>(channel, now));
        if (channelCache.size() > 4096) {
            channelCache.clear();
        }
        return channel;
    }

    private static final class CacheEntry<T> {
        final T value;
        final long atMs;

        CacheEntry(T value, long atMs) {
            this.value = value;
            this.atMs = atMs;
        }
    }

    private void applyChannelcode(C2HandlerContext ctx, String channelcode) {
        if (channelcode != null && !channelcode.isEmpty()) {
            ctx.setChannelcode(channelcode);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public void link(long recordId, long deviceId) {
        try {
            int n = c2RecordDao.linkDevice(recordId, deviceId);
            log.debug("正常日志:[c2_handlers] 关联 c2_records.device_id, recordId={}, deviceId={}, affected={}",
                    recordId, deviceId, n);
        } catch (Exception e) {
            log.info("异常日志:[c2_handlers] 关联 c2_records.device_id 失败, recordId={}, deviceId={}, err={}",
                    recordId, deviceId, e.getMessage());
        }
    }
}
