package com.consumer.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import cn.hutool.http.HttpRequest;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.consumer.config.BalanceRpcProperties;
import com.consumer.config.BalanceRpcProperties.NowNodes;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 查链余额：NOWNodes 优先（header api-key），失败再降级官方/公共节点。对齐 18 WalletBalanceQuery。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceLiveService {

    private static final String ETH_USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
    private static final String BSC_USDC = "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d";
    private static final String TRON_USDC = "TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8";
    private static final String SOL_USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";

    private final BalanceRpcProperties rpc;
    private Semaphore slots;
    private List<String> ethRpcs = new ArrayList<>();
    private List<String> bscRpcs = new ArrayList<>();
    private List<String> btcApis = new ArrayList<>();
    private List<String> solRpcs = new ArrayList<>();
    private List<String> tronApis = new ArrayList<>();

    @PostConstruct
    public void init() {
        this.slots = new Semaphore(Math.max(1, rpc.getMaxConcurrent()));
        boolean nn = useNowNodes();
        NowNodes n = rpc.getNownodes() == null ? new NowNodes() : rpc.getNownodes();
        this.ethRpcs = mergePrimary(nn ? n.getEth() : null, split(rpc.getEthRpc()));
        this.bscRpcs = mergePrimary(nn ? n.getBsc() : null, split(rpc.getBscRpc()));
        this.btcApis = mergePrimary(nn ? n.getBtc() : null, split(rpc.getBtcApi()));
        this.solRpcs = mergePrimary(nn ? n.getSol() : null, split(rpc.getSolRpc()));
        this.tronApis = mergePrimary(nn ? n.getTron() : null, split(rpc.getTronApi()));
        if (n.isEnabled() && !nn) {
            log.warn("【balance】NOWNODES 已启用但 api-key 为空，跳过三方，仅用官方/公共节点");
        }
        log.info("【balance】节点已加载 nownodes={} eth={} bsc={} btc={} sol={} tron={} timeout={}ms concurrent={} fallback={}",
                nn, ethRpcs.size(), bscRpcs.size(), btcApis.size(), solRpcs.size(), tronApis.size(),
                rpc.getTimeoutMs(), rpc.getMaxConcurrent(), rpc.getMaxFallbackNodes());
    }

    /** native, usdt, usdc。BTC 的稳定币为 0。 */
    public String[] fetchAmounts(String chain, String address) {
        if (!rpc.isEnabled()) {
            return new String[] {"0", "0", "0"};
        }
        String mapped = norm(chain);
        String addr = address == null ? "" : address.trim();
        if (mapped == null || addr.isEmpty()) {
            return new String[] {"0", "0", "0"};
        }
        boolean acquired = false;
        try {
            acquired = slots.tryAcquire(Math.max(1000, rpc.getTimeoutMs()), TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("【balance】并发槽位耗尽 chain={} addr={}", mapped, addr);
                return new String[] {"0", "0", "0"};
            }
            switch (mapped) {
                case "eth":
                    return firstAmt(fetchEvmBatchUnlocked(ethRpcs, List.of(addr), rpc.getEthUsdt(), 6), addr);
                case "bsc":
                    return firstAmt(fetchEvmBatchUnlocked(bscRpcs, List.of(addr), rpc.getBscUsdt(), 18), addr);
                case "tron":
                    return fetchTron(addr);
                case "btc":
                    return new String[] {fetchBtc(addr), "0", "0"};
                case "sol":
                    return firstAmt(fetchSolBatchUnlocked(List.of(addr)), addr);
                default:
                    return new String[] {"0", "0", "0"};
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new String[] {"0", "0", "0"};
        } catch (Exception e) {
            log.warn("【balance】fetch fail chain={} addr={} err={}", mapped, addr, shortErr(e));
            return new String[] {"0", "0", "0"};
        } finally {
            if (acquired) {
                slots.release();
            }
        }
    }

    private boolean useNowNodes() {
        NowNodes n = rpc.getNownodes();
        return n != null && n.isEnabled() && StringUtils.isNotBlank(n.getApiKey());
    }

    private String norm(String chain) {
        String key = chain == null ? "" : chain.trim().toLowerCase(Locale.ROOT);
        switch (key) {
            case "eth":
            case "ethereum":
                return "eth";
            case "bsc":
            case "bnb":
                return "bsc";
            case "tron":
            case "trx":
                return "tron";
            case "btc":
            case "bitcoin":
                return "btc";
            case "sol":
            case "solana":
                return "sol";
            default:
                return null;
        }
    }

    public Map<String, String[]> fetchEvmBatch(String chain, List<String> addresses) {
        String mapped = norm(chain);
        List<String> rpcs = "bsc".equals(mapped) ? bscRpcs : ethRpcs;
        String usdt = "bsc".equals(mapped) ? rpc.getBscUsdt() : rpc.getEthUsdt();
        int decimals = "bsc".equals(mapped) ? 18 : 6;
        return withSlot(() -> fetchEvmBatchUnlocked(rpcs, addresses, usdt, decimals));
    }

    public Map<String, String[]> fetchSolBatch(List<String> addresses) {
        return withSlot(() -> fetchSolBatchUnlocked(addresses));
    }

    private Map<String, String[]> withSlot(java.util.function.Supplier<Map<String, String[]>> work) {
        boolean acquired = false;
        try {
            acquired = slots.tryAcquire(Math.max(1000, rpc.getTimeoutMs()), TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("【balance】并发槽位耗尽 batch");
                return zeros(List.of());
            }
            return work.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return zeros(List.of());
        } finally {
            if (acquired) {
                slots.release();
            }
        }
    }

    private Map<String, String[]> fetchEvmBatchUnlocked(List<String> rpcs, List<String> addresses,
            String usdtContract, int usdtDecimals) {
        List<String> uniq = uniqueAddrs(addresses);
        Map<String, String[]> out = zeros(uniq);
        if (uniq.isEmpty()) {
            return out;
        }
        Exception last = null;
        for (String rpcUrl : nodesToTry(rpcs)) {
            try {
                List<Map<String, Object>> calls = new ArrayList<>();
                Map<Integer, String> nativeIds = new HashMap<>();
                Map<Integer, String> usdtIds = new HashMap<>();
                Map<Integer, String> usdcIds = new HashMap<>();
                String usdcContract = usdtDecimals == 18 ? BSC_USDC : ETH_USDC;
                int id = 1;
                for (String address : uniq) {
                    String hexAddr = ensureHexAddr(address);
                    calls.add(rpcReq(id, "eth_getBalance", List.of(hexAddr, "latest")));
                    nativeIds.put(id, address);
                    id++;
                    Map<String, Object> call = new LinkedHashMap<>();
                    call.put("to", usdtContract);
                    call.put("data", "0x70a08231" + padAddress(hexAddr));
                    calls.add(rpcReq(id, "eth_call", List.of(call, "latest")));
                    usdtIds.put(id, address);
                    id++;
                    Map<String, Object> usdcCall = new LinkedHashMap<>();
                    usdcCall.put("to", usdcContract);
                    usdcCall.put("data", "0x70a08231" + padAddress(hexAddr));
                    calls.add(rpcReq(id, "eth_call", List.of(usdcCall, "latest")));
                    usdcIds.put(id, address);
                    id++;
                }
                Map<Integer, JSONObject> byId = jsonRpcBatch(rpcUrl, calls, rpc.getTimeoutMs());
                for (Map.Entry<Integer, String> e : nativeIds.entrySet()) {
                    JSONObject row = byId.get(e.getKey());
                    if (row == null || row.get("error") != null) {
                        continue;
                    }
                    String[] amt = out.computeIfAbsent(e.getValue(), k -> new String[] {"0", "0", "0"});
                    amt[0] = hexToDec(row.getString("result"), 18);
                }
                for (Map.Entry<Integer, String> e : usdtIds.entrySet()) {
                    JSONObject row = byId.get(e.getKey());
                    if (row == null || row.get("error") != null) {
                        continue;
                    }
                    String[] amt = out.computeIfAbsent(e.getValue(), k -> new String[] {"0", "0", "0"});
                    amt[1] = hexToDec(row.getString("result"), usdtDecimals);
                }
                for (Map.Entry<Integer, String> e : usdcIds.entrySet()) {
                    JSONObject row = byId.get(e.getKey());
                    if (row == null || row.get("error") != null) {
                        continue;
                    }
                    String[] amt = out.computeIfAbsent(e.getValue(), k -> new String[] {"0", "0", "0"});
                    amt[2] = hexToDec(row.getString("result"), usdtDecimals);
                }
                return out;
            } catch (Exception e) {
                last = e;
                if (isFatalAddressError(e)) {
                    return out;
                }
                log.warn("【balance】evm batch FAIL rpc={} err={}，尝试下一节点", rpcUrl, shortErr(e));
            }
        }
        if (last != null) {
            log.warn("【balance】evm batch 全部节点失败 addrs={} tried={} err={}",
                    uniq.size(), nodesToTry(rpcs), shortErr(last));
        }
        return out;
    }

    private Map<String, String[]> fetchSolBatchUnlocked(List<String> addresses) {
        List<String> uniq = uniqueAddrs(addresses);
        Map<String, String[]> out = zeros(uniq);
        if (uniq.isEmpty()) {
            return out;
        }
        Exception last = null;
        Map<String, Object> usdtFilter = new LinkedHashMap<>();
        usdtFilter.put("mint", rpc.getSolUsdt());
        Map<String, Object> usdcFilter = new LinkedHashMap<>();
        usdcFilter.put("mint", SOL_USDC);
        Map<String, Object> enc = new LinkedHashMap<>();
        enc.put("encoding", "jsonParsed");
        for (String rpcUrl : nodesToTry(solRpcs)) {
            try {
                List<Map<String, Object>> calls = new ArrayList<>();
                Map<Integer, String> nativeIds = new HashMap<>();
                Map<Integer, String> usdtIds = new HashMap<>();
                Map<Integer, String> usdcIds = new HashMap<>();
                int id = 1;
                for (String address : uniq) {
                    calls.add(rpcReq(id, "getBalance", List.of(address)));
                    nativeIds.put(id, address);
                    id++;
                    calls.add(rpcReq(id, "getTokenAccountsByOwner", List.of(address, usdtFilter, enc)));
                    usdtIds.put(id, address);
                    id++;
                    calls.add(rpcReq(id, "getTokenAccountsByOwner", List.of(address, usdcFilter, enc)));
                    usdcIds.put(id, address);
                    id++;
                }
                int timeout = Math.max(rpc.getTimeoutMs() * 2, 12000);
                Map<Integer, JSONObject> byId = jsonRpcBatch(rpcUrl, calls, timeout);
                for (Map.Entry<Integer, String> e : nativeIds.entrySet()) {
                    JSONObject row = byId.get(e.getKey());
                    if (row == null || row.get("error") != null) {
                        continue;
                    }
                    JSONObject result = row.getJSONObject("result");
                    long lamports = result == null ? 0 : result.getLongValue("value");
                    String[] amt = out.computeIfAbsent(e.getValue(), k -> new String[] {"0", "0", "0"});
                    amt[0] = trim(new BigDecimal(lamports).movePointLeft(9));
                }
                for (Map.Entry<Integer, String> e : usdtIds.entrySet()) {
                    JSONObject row = byId.get(e.getKey());
                    if (row == null || row.get("error") != null) {
                        continue;
                    }
                    String[] amt = out.computeIfAbsent(e.getValue(), k -> new String[] {"0", "0", "0"});
                    amt[1] = parseSolUsdtResult(row.getJSONObject("result"));
                }
                for (Map.Entry<Integer, String> e : usdcIds.entrySet()) {
                    JSONObject row = byId.get(e.getKey());
                    if (row == null || row.get("error") != null) {
                        continue;
                    }
                    String[] amt = out.computeIfAbsent(e.getValue(), k -> new String[] {"0", "0", "0"});
                    amt[2] = parseSolUsdtResult(row.getJSONObject("result"));
                }
                return out;
            } catch (Exception e) {
                last = e;
                if (isFatalAddressError(e)) {
                    return out;
                }
                log.warn("【balance】sol batch FAIL rpc={} err={}，尝试下一节点", rpcUrl, shortErr(e));
            }
        }
        if (last != null) {
            log.warn("【balance】sol batch 全部节点失败 addrs={} tried={} err={}",
                    uniq.size(), nodesToTry(solRpcs), shortErr(last));
        }
        return out;
    }

    private String[] fetchTron(String address) {
        Exception last = null;
        for (String api : nodesToTry(tronApis)) {
            try {
                String base = StringUtils.removeEnd(api, "/");
                if (isBlockbook(base)) {
                    return fetchTronBlockbook(base, address);
                }
                return fetchTronGrid(base, address);
            } catch (Exception e) {
                last = e;
                if (isFatalAddressError(e)) {
                    log.debug("【balance】tron skip invalid addr={} err={}", address, shortErr(e));
                    return new String[] {"0", "0", "0"};
                }
                log.debug("【balance】tron FAIL api={} err={}，尝试下一节点", api, shortErr(e));
            }
        }
        if (last != null) {
            log.warn("【balance】tron 全部节点失败 addr={} err={}", address, shortErr(last));
        }
        return new String[] {"0", "0", "0"};
    }

    private String[] fetchTronGrid(String base, String address) {
        String url = base + "/v1/accounts/" + address;
        JSONObject obj = parseJson(httpGet(url));
        if (obj == null) {
            throw new IllegalStateException("empty trongrid");
        }
        JSONArray data = obj.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            return new String[] {"0", "0", "0"};
        }
        JSONObject acc = data.getJSONObject(0);
        BigDecimal trx = BigDecimal.ZERO;
        if (acc.get("balance") != null) {
            trx = new BigDecimal(acc.getLongValue("balance")).movePointLeft(6);
        }
        BigDecimal usdt = BigDecimal.ZERO;
        BigDecimal usdc = BigDecimal.ZERO;
        JSONArray trc20 = acc.getJSONArray("trc20");
        if (trc20 != null) {
            for (int i = 0; i < trc20.size(); i++) {
                JSONObject row = trc20.getJSONObject(i);
                if (row == null) {
                    continue;
                }
                for (String k : row.keySet()) {
                    if (rpc.getTronUsdt().equalsIgnoreCase(k)) {
                        usdt = new BigDecimal(row.getString(k)).movePointLeft(6);
                    } else if (TRON_USDC.equalsIgnoreCase(k)) {
                        usdc = new BigDecimal(row.getString(k)).movePointLeft(6);
                    }
                }
            }
        }
        return new String[] {trim(trx), trim(usdt), trim(usdc)};
    }

    private String[] fetchTronBlockbook(String base, String address) {
        String url = base + "/api/v2/address/" + address + "?details=tokenBalances";
        JSONObject root = parseJson(httpGet(url));
        if (root == null) {
            throw new IllegalStateException("empty blockbook tron");
        }
        String nativeBal = "0";
        String balance = root.getString("balance");
        if (StringUtils.isNotBlank(balance)) {
            nativeBal = trim(new BigDecimal(new BigInteger(balance)).movePointLeft(6));
        }
        String usdtBal = "0";
        String usdcBal = "0";
        JSONArray tokens = root.getJSONArray("tokens");
        if (tokens != null) {
            for (int i = 0; i < tokens.size(); i++) {
                JSONObject t = tokens.getJSONObject(i);
                if (t == null) {
                    continue;
                }
                String contract = firstNonEmpty(t.getString("contract"), t.getString("token"));
                String amt = firstNonEmpty(t.getString("balance"), t.getString("value"));
                if (StringUtils.isBlank(amt)) {
                    continue;
                }
                if (rpc.getTronUsdt().equalsIgnoreCase(contract)) {
                    usdtBal = trim(new BigDecimal(new BigInteger(amt)).movePointLeft(6));
                } else if (TRON_USDC.equalsIgnoreCase(contract)) {
                    usdcBal = trim(new BigDecimal(new BigInteger(amt)).movePointLeft(6));
                }
            }
        }
        return new String[] {nativeBal, usdtBal, usdcBal};
    }

    private String fetchBtc(String address) {
        Exception last = null;
        for (String api : nodesToTry(btcApis)) {
            try {
                String base = StringUtils.removeEnd(api, "/");
                if (isBlockbook(base)) {
                    JSONObject root = parseJson(httpGet(base + "/api/v2/address/" + address));
                    if (root == null) {
                        throw new IllegalStateException("empty blockbook btc");
                    }
                    String balance = root.getString("balance");
                    if (StringUtils.isBlank(balance)) {
                        return "0";
                    }
                    return trim(new BigDecimal(new BigInteger(balance)).movePointLeft(8));
                }
                JSONObject obj = parseJson(httpGet(base + "/address/" + address));
                if (obj == null) {
                    throw new IllegalStateException("empty btc");
                }
                JSONObject chain = obj.getJSONObject("chain_stats");
                long funded = chain == null ? 0 : chain.getLongValue("funded_txo_sum");
                long spent = chain == null ? 0 : chain.getLongValue("spent_txo_sum");
                return trim(new BigDecimal(funded - spent).movePointLeft(8));
            } catch (Exception e) {
                last = e;
                if (isFatalAddressError(e)) {
                    log.debug("【balance】btc skip invalid addr={} err={}", address, shortErr(e));
                    return "0";
                }
                log.debug("【balance】btc FAIL api={} err={}，尝试下一节点", api, shortErr(e));
            }
        }
        if (last != null) {
            log.warn("【balance】btc 全部节点失败 addr={} err={}", address, shortErr(last));
        }
        return "0";
    }

    private String parseSolUsdtResult(JSONObject result) {
        JSONArray value = result == null ? null : result.getJSONArray("value");
        if (value == null || value.isEmpty()) {
            return "0";
        }
        BigInteger total = BigInteger.ZERO;
        for (int i = 0; i < value.size(); i++) {
            JSONObject acc = value.getJSONObject(i);
            if (acc == null) {
                continue;
            }
            JSONObject tokenAmount = acc.getJSONObject("account");
            if (tokenAmount == null) {
                continue;
            }
            JSONObject data = tokenAmount.getJSONObject("data");
            JSONObject parsed = data == null ? null : data.getJSONObject("parsed");
            JSONObject info = parsed == null ? null : parsed.getJSONObject("info");
            JSONObject amt = info == null ? null : info.getJSONObject("tokenAmount");
            String amount = amt == null ? null : amt.getString("amount");
            if (StringUtils.isNotBlank(amount)) {
                total = total.add(new BigInteger(amount));
            }
        }
        return trim(new BigDecimal(total).movePointLeft(6));
    }

    private JSONObject jsonRpc(String rpcUrl, String method, List<?> params, int timeoutMs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        body.put("params", params);
        int t = Math.max(timeoutMs, 3000);
        HttpRequest req = HttpRequest.post(rpcUrl)
                .timeout(t)
                .setConnectionTimeout(connectTimeout(t))
                .header("Content-Type", "application/json")
                .body(JSON.toJSONString(body))
                .charset(StandardCharsets.UTF_8);
        applyAuth(req, rpcUrl);
        return parseJson(req.execute().body());
    }

    private Map<Integer, JSONObject> jsonRpcBatch(String rpcUrl, List<Map<String, Object>> calls, int timeoutMs) {
        int t = Math.max(timeoutMs, 3000);
        HttpRequest req = HttpRequest.post(rpcUrl)
                .timeout(t)
                .setConnectionTimeout(connectTimeout(t))
                .header("Content-Type", "application/json")
                .body(JSON.toJSONString(calls))
                .charset(StandardCharsets.UTF_8);
        applyAuth(req, rpcUrl);
        String raw = req.execute().body();
        if (raw == null) {
            throw new IllegalStateException("empty batch");
        }
        String tbody = raw.trim();
        JSONArray arr;
        if (tbody.startsWith("{")) {
            JSONObject one = parseJson(tbody);
            if (one == null) {
                throw new IllegalStateException("empty batch object");
            }
            if (one.get("error") != null && one.get("result") == null) {
                throw new IllegalStateException("batch error " + one.getString("error"));
            }
            arr = new JSONArray();
            arr.add(one);
        } else if (tbody.startsWith("[")) {
            arr = JSON.parseArray(tbody);
        } else {
            throw new IllegalStateException(preview(tbody, 160));
        }
        if (arr == null || arr.isEmpty()) {
            throw new IllegalStateException("empty batch array");
        }
        Map<Integer, JSONObject> byId = new HashMap<>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject row = arr.getJSONObject(i);
            if (row != null) {
                byId.put(row.getIntValue("id"), row);
            }
        }
        return byId;
    }

    private static Map<String, Object> rpcReq(int id, String method, List<?> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        body.put("params", params);
        return body;
    }

    private List<String> nodesToTry(List<String> all) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int extra = Math.max(0, rpc.getMaxFallbackNodes());
        int n = Math.min(all.size(), 1 + extra);
        return all.subList(0, n);
    }

    private static List<String> uniqueAddrs(List<String> addresses) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (addresses != null) {
            for (String a : addresses) {
                if (a != null) {
                    String t = a.trim();
                    if (!t.isEmpty()) {
                        set.add(t);
                    }
                }
            }
        }
        return new ArrayList<>(set);
    }

    private static Map<String, String[]> zeros(List<String> addrs) {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (String a : addrs) {
            out.put(a, new String[] {"0", "0", "0"});
        }
        return out;
    }

    private static String[] firstAmt(Map<String, String[]> map, String addr) {
        if (map == null) {
            return new String[] {"0", "0", "0"};
        }
        String[] amt = map.get(addr);
        if (amt == null) {
            return new String[] {"0", "0", "0"};
        }
        if (amt.length >= 3) {
            return amt;
        }
        return new String[] {amt[0], amt.length > 1 ? amt[1] : "0", "0"};
    }

    private String httpGet(String url) {
        int t = Math.max(rpc.getTimeoutMs(), 3000);
        HttpRequest req = HttpRequest.get(url)
                .timeout(t)
                .setConnectionTimeout(connectTimeout(t))
                .header("User-Agent", "v26-consumer/1.0");
        applyAuth(req, url);
        return req.execute().body();
    }

    private void applyAuth(HttpRequest req, String url) {
        if (isNowNodesUrl(url) && useNowNodes()) {
            req.header("api-key", rpc.getNownodes().getApiKey().trim());
        }
    }

    private int connectTimeout(int timeoutMs) {
        int cap = rpc.getConnectTimeoutMs() > 0 ? rpc.getConnectTimeoutMs() : 5000;
        return Math.min(Math.max(1000, cap), timeoutMs);
    }

    private static boolean isNowNodesUrl(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).contains("nownodes.io");
    }

    private static boolean isBlockbook(String base) {
        if (base == null) {
            return false;
        }
        String u = base.toLowerCase(Locale.ROOT);
        return u.contains("blockbook") || u.contains("btcbook") || u.contains("trx-blockbook");
    }

    private static List<String> mergePrimary(String primary, List<String> fallback) {
        List<String> out = new ArrayList<>();
        if (StringUtils.isNotBlank(primary)) {
            out.add(StringUtils.removeEnd(primary.trim(), "/"));
        }
        if (fallback != null) {
            for (String u : fallback) {
                String n = StringUtils.removeEnd(u, "/");
                if (!n.isEmpty() && !out.contains(n)) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String p : csv.split(",")) {
            String s = p.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static JSONObject parseJson(String body) {
        if (body == null) {
            return null;
        }
        String t = body.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.charAt(0) == '<' || t.regionMatches(true, 0, "<!DOCTYPE", 0, 9)) {
            throw new IllegalStateException("rpc returned HTML: " + preview(t, 80));
        }
        if (t.charAt(0) != '{' && t.charAt(0) != '[') {
            throw new IllegalStateException(preview(t, 160));
        }
        return JSON.parseObject(t);
    }

    private static String firstNonEmpty(String a, String b) {
        return StringUtils.isNotBlank(a) ? a : b;
    }

    /** 地址本身非法：换节点也不会成功，直接记 0。 */
    private static boolean isFatalAddressError(Throwable e) {
        String m = errText(e).toLowerCase(Locale.ROOT);
        return m.contains("invalid bitcoin address")
                || m.contains("invalid address")
                || m.contains("checksum mismatch")
                || m.contains("base58 error")
                || m.contains("legacy address base58")
                || m.contains("unknown format")
                || m.contains("decoded address is of unknown format");
    }

    private static String errText(Throwable e) {
        if (e == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(e.toString());
        Throwable c = e.getCause();
        if (c != null && c != e) {
            sb.append(' ').append(c.toString());
        }
        return sb.toString();
    }

    private static String shortErr(Throwable e) {
        if (e == null) {
            return "";
        }
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            String name = t.getClass().getName();
            if (name.contains("HttpException") || name.contains("ResourceAccess") || name.contains("Nested")) {
                t = t.getCause();
                continue;
            }
            break;
        }
        String m = t.getMessage();
        if (m == null || m.isEmpty()) {
            m = t.toString();
        }
        return preview(m, 160);
    }

    private static String preview(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    private static String ensureHexAddr(String addr) {
        if (addr == null) {
            return "0x";
        }
        return addr.startsWith("0x") || addr.startsWith("0X") ? addr : ("0x" + addr);
    }

    private static String padAddress(String hexAddr) {
        String clean = hexAddr.startsWith("0x") || hexAddr.startsWith("0X")
                ? hexAddr.substring(2) : hexAddr;
        if (clean.length() > 40) {
            clean = clean.substring(clean.length() - 40);
        }
        StringBuilder sb = new StringBuilder(64);
        for (int i = clean.length(); i < 64; i++) {
            sb.append('0');
        }
        sb.append(clean.toLowerCase(Locale.ROOT));
        return sb.toString();
    }

    private static String hexToDec(String hex, int decimals) {
        if (hex == null || hex.isEmpty() || "0x".equalsIgnoreCase(hex)) {
            return "0";
        }
        try {
            BigInteger n = new BigInteger(hex.startsWith("0x") ? hex.substring(2) : hex, 16);
            return trim(new BigDecimal(n).movePointLeft(decimals));
        } catch (Exception e) {
            return "0";
        }
    }

    private static String trim(BigDecimal v) {
        if (v == null || v.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return v.stripTrailingZeros().setScale(Math.min(v.scale(), 8), RoundingMode.DOWN).toPlainString();
    }
}
