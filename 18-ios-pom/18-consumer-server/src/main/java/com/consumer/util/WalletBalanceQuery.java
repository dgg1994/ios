package com.consumer.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import cn.hutool.http.HttpRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 各链原生币 / USDT 余额查询。
 * <p>优先 NOWNodes（new-nodes）三方，失败再降级公共/官方节点；多节点回退 + 并发限流。
 * <p>支持：tron / eth / bsc / btc / sol。
 */
@Slf4j
@Component
public class WalletBalanceQuery {

    public static final String USDT_ETH = "0xdAC17F958D2ee523a2206206994597C13D831ec7";
    public static final String USDT_BSC = "0x55d398326f99059fF775485246999027B3197955";
    public static final String USDT_TRON = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
    /** Solana USDT (SPL) mint */
    public static final String USDT_SOL = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";

    /** 逗号分隔，按顺序尝试（勿用需 API Key 的 rpc.ankr.com）——作为 NOWNodes 之后的降级节点 */
    @Value("${monitor.balance.eth-rpc:https://ethereum.publicnode.com,https://1rpc.io/eth,https://cloudflare-eth.com,https://eth.llamarpc.com}")
    private String ethRpc;
    @Value("${monitor.balance.bsc-rpc:https://bsc-dataseed.binance.org,https://bsc-dataseed1.binance.org,https://bsc.publicnode.com,https://bsc-dataseed2.binance.org}")
    private String bscRpc;
    @Value("${monitor.balance.tron-api:https://api.trongrid.io}")
    private String tronApi;
    @Value("${monitor.balance.btc-api:https://blockstream.info/api,https://mempool.space/api}")
    private String btcApi;
    @Value("${monitor.balance.sol-rpc:https://api.mainnet-beta.solana.com,https://1rpc.io/sol,https://solana.drpc.org,https://solana-rpc.publicnode.com}")
    private String solRpc;
    @Value("${monitor.balance.timeout-ms:8000}")
    private int timeoutMs;
    @Value("${monitor.balance.pool-size:8}")
    private int poolSize;
    @Value("${monitor.balance.max-concurrent:6}")
    private int maxConcurrent;

    /** NOWNodes：优先；api-key 为空则跳过三方，只用降级节点 */
    @Value("${monitor.balance.nownodes.enabled:true}")
    private boolean nowNodesEnabled;
    @Value("${monitor.balance.nownodes.api-key:}")
    private String nowNodesApiKey;
    @Value("${monitor.balance.nownodes.eth:https://eth.nownodes.io}")
    private String nowNodesEth;
    @Value("${monitor.balance.nownodes.bsc:https://bsc.nownodes.io}")
    private String nowNodesBsc;
    @Value("${monitor.balance.nownodes.sol:https://sol.nownodes.io}")
    private String nowNodesSol;
    /** Blockbook：GET /api/v2/address/{addr} */
    @Value("${monitor.balance.nownodes.btc:https://btcbook.nownodes.io}")
    private String nowNodesBtc;
    /** Tron Blockbook：GET /api/v2/address/{addr}?details=tokenBalances */
    @Value("${monitor.balance.nownodes.tron:https://trx-blockbook.nownodes.io}")
    private String nowNodesTron;

    private ExecutorService pool;
    private Semaphore slots;
    private List<String> ethRpcs;
    private List<String> bscRpcs;
    private List<String> btcApis;
    private List<String> solRpcs;
    private List<String> tronApis;

    @PostConstruct
    public void init() {
        int size = Math.max(2, poolSize);
        this.pool = new ThreadPoolExecutor(
                size, size, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                r -> {
                    Thread t = new Thread(r, "bal-query");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.slots = new Semaphore(Math.max(1, maxConcurrent));
        boolean nn = useNowNodes();
        this.ethRpcs = mergePrimary(nn ? nowNodesEth : null, splitUrls(ethRpc));
        this.bscRpcs = mergePrimary(nn ? nowNodesBsc : null, splitUrls(bscRpc));
        this.btcApis = mergePrimary(nn ? nowNodesBtc : null, splitUrls(btcApi));
        this.solRpcs = mergePrimary(nn ? nowNodesSol : null, splitUrls(solRpc));
        this.tronApis = mergePrimary(nn ? nowNodesTron : null, splitUrls(tronApi));
        if (nowNodesEnabled && !nn) {
            log.warn("【balance】NOWNODES 已启用但 api-key 为空，跳过三方，仅用官方/公共节点");
        }
        log.info("【balance】节点已加载 nownodes={} eth={} bsc={} btc={} sol={} tron={}",
                nn, ethRpcs.size(), bscRpcs.size(), btcApis.size(), solRpcs.size(), tronApis.size());
    }

    private boolean useNowNodes() {
        return nowNodesEnabled && nowNodesApiKey != null && !nowNodesApiKey.trim().isEmpty();
    }

    /** 三方 URL 置前，再跟降级列表（去重） */
    private static List<String> mergePrimary(String primary, List<String> fallback) {
        List<String> out = new ArrayList<>();
        if (primary != null) {
            String p = primary.trim().replaceAll("/$", "");
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        if (fallback != null) {
            for (String u : fallback) {
                if (u == null || u.isEmpty()) {
                    continue;
                }
                String n = u.replaceAll("/$", "");
                if (!out.contains(n)) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    private boolean isNowNodesUrl(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).contains("nownodes.io");
    }

    private void applyAuth(HttpRequest req, String url) {
        if (isNowNodesUrl(url) && useNowNodes()) {
            req.header("api-key", nowNodesApiKey.trim());
        }
    }

    @PreDestroy
    public void destroy() {
        if (pool != null) pool.shutdownNow();
    }

    public Map<String, ChainBalance> queryAll(Map<String, String> chainAddress) {
        if (chainAddress == null || chainAddress.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, CompletableFuture<ChainBalance>> futures = new HashMap<>();
        for (Map.Entry<String, String> e : chainAddress.entrySet()) {
            String chain = normalizeChain(e.getKey());
            String addr = e.getValue();
            if (chain.isEmpty() || addr == null || addr.isEmpty()) continue;
            futures.put(chain, CompletableFuture.supplyAsync(() -> queryOne(chain, addr), pool));
        }
        Map<String, ChainBalance> out = new HashMap<>();
        for (Map.Entry<String, CompletableFuture<ChainBalance>> e : futures.entrySet()) {
            try {
                ChainBalance bal = e.getValue().get(Math.max(timeoutMs, 1000) * 3L + 2000L, TimeUnit.MILLISECONDS);
                out.put(e.getKey(), bal != null ? bal : ChainBalance.zero(e.getKey(), chainAddress.get(e.getKey())));
            } catch (Exception ex) {
                log.warn("【balance】查询超时/失败 chain={} err={}", e.getKey(), shortErr(ex));
                out.put(e.getKey(), ChainBalance.zero(e.getKey(), chainAddress.get(e.getKey())));
            }
        }
        return out;
    }

    public ChainBalance queryOne(String chain, String address) {
        boolean acquired = false;
        try {
            acquired = slots.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("【balance】并发槽位耗尽 chain={}", chain);
                return ChainBalance.zero(chain, address);
            }
            switch (normalizeChain(chain)) {
                case "eth":
                    return queryEvm(address, ethRpcs, USDT_ETH, 18, 6, "eth");
                case "bsc":
                    return queryEvm(address, bscRpcs, USDT_BSC, 18, 18, "bnb");
                case "tron":
                    return queryTron(address);
                case "btc":
                    return queryBtc(address);
                case "sol":
                    return querySol(address);
                default:
                    return ChainBalance.zero(chain, address);
            }
        } catch (Exception e) {
            log.warn("【balance】queryOne FAIL chain={} addr={} err={}", chain, address, shortErr(e));
            return ChainBalance.zero(chain, address);
        } finally {
            if (acquired) slots.release();
        }
    }

    private ChainBalance queryEvm(String address, List<String> rpcs, String usdtContract,
                                  int nativeDecimals, int usdtDecimals, String nativeSymbol) {
        ChainBalance bal = new ChainBalance();
        bal.setChain(nativeSymbol.equals("bnb") ? "bsc" : "eth");
        bal.setAddress(address);
        bal.setNativeSymbol(nativeSymbol);
        bal.setNativeBal("0");
        bal.setUsdtBal("0");
        if (rpcs == null || rpcs.isEmpty()) return bal;

        JSONArray balParams = JSON.parseArray("[\"" + ensureHexAddr(address) + "\",\"latest\"]");
        String data = "0x70a08231" + padAddress(ensureHexAddr(address));
        JSONArray usdtParams = new JSONArray();
        JSONObject call = new JSONObject();
        call.put("to", usdtContract);
        call.put("data", data);
        usdtParams.add(call);
        usdtParams.add("latest");

        Exception lastNative = null;
        for (String rpc : rpcs) {
            try {
                String hexBal = ethCall(rpc, "eth_getBalance", balParams);
                bal.setNativeBal(formatUnits(hexToBigInt(hexBal), nativeDecimals));
                lastNative = null;
                // 同一节点继续查 USDT；失败则换节点只补 USDT
                try {
                    String hexUsdt = ethCall(rpc, "eth_call", usdtParams);
                    bal.setUsdtBal(formatUnits(hexToBigInt(hexUsdt), usdtDecimals));
                } catch (Exception ue) {
                    log.warn("【balance】evm usdt FAIL rpc={} err={}", rpc, shortErr(ue));
                    for (String rpc2 : rpcs) {
                        if (rpc2.equals(rpc)) continue;
                        try {
                            String hexUsdt = ethCall(rpc2, "eth_call", usdtParams);
                            bal.setUsdtBal(formatUnits(hexToBigInt(hexUsdt), usdtDecimals));
                            break;
                        } catch (Exception ignore) {}
                    }
                }
                return bal;
            } catch (Exception e) {
                lastNative = e;
                log.warn("【balance】evm native FAIL rpc={} err={}，尝试下一节点", rpc, shortErr(e));
            }
        }
        if (lastNative != null) {
            log.warn("【balance】evm 全部节点失败 chain={} err={}", bal.getChain(), shortErr(lastNative));
        }
        return bal;
    }

    private ChainBalance queryTron(String address) {
        ChainBalance bal = new ChainBalance();
        bal.setChain("tron");
        bal.setAddress(address);
        bal.setNativeSymbol("trx");
        bal.setNativeBal("0");
        bal.setUsdtBal("0");
        List<String> apis = tronApis == null || tronApis.isEmpty()
                ? Collections.singletonList("https://api.trongrid.io") : tronApis;
        Exception last = null;
        for (String api : apis) {
            try {
                String base = api.replaceAll("/$", "");
                if (isBlockbook(base)) {
                    fillFromBlockbookTron(bal, base, address);
                } else {
                    fillFromTronGrid(bal, base, address);
                }
                return bal;
            } catch (Exception e) {
                last = e;
                log.warn("【balance】tron FAIL api={} err={}，尝试下一节点", api, shortErr(e));
            }
        }
        if (last != null) {
            log.warn("【balance】tron 全部节点失败 addr={} err={}", address, shortErr(last));
        }
        return bal;
    }

    private void fillFromTronGrid(ChainBalance bal, String base, String address) {
        String url = base + "/v1/accounts/" + address;
        String body = httpGet(url);
        JSONObject root = parseJsonObject(body);
        JSONArray data = root == null ? null : root.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            return;
        }
        JSONObject acc = data.getJSONObject(0);
        long sun = acc.getLongValue("balance");
        bal.setNativeBal(formatUnits(BigInteger.valueOf(sun), 6));
        JSONArray trc20 = acc.getJSONArray("trc20");
        if (trc20 == null) {
            return;
        }
        for (int i = 0; i < trc20.size(); i++) {
            JSONObject token = trc20.getJSONObject(i);
            if (token == null) {
                continue;
            }
            if (token.containsKey(USDT_TRON)) {
                bal.setUsdtBal(formatUnits(new BigInteger(token.getString(USDT_TRON)), 6));
                return;
            }
            for (String k : token.keySet()) {
                if (USDT_TRON.equalsIgnoreCase(k)) {
                    bal.setUsdtBal(formatUnits(new BigInteger(token.getString(k)), 6));
                    return;
                }
            }
        }
    }

    private void fillFromBlockbookTron(ChainBalance bal, String base, String address) {
        String url = base + "/api/v2/address/" + address + "?details=tokenBalances";
        String body = httpGet(url);
        JSONObject root = parseJsonObject(body);
        if (root == null) {
            throw new IllegalStateException("empty blockbook tron resp");
        }
        String balance = root.getString("balance");
        if (balance != null && !balance.isEmpty()) {
            bal.setNativeBal(formatUnits(new BigInteger(balance), 6));
        }
        JSONArray tokens = root.getJSONArray("tokens");
        if (tokens == null) {
            return;
        }
        for (int i = 0; i < tokens.size(); i++) {
            JSONObject t = tokens.getJSONObject(i);
            if (t == null) {
                continue;
            }
            String contract = firstNonEmpty(t.getString("contract"), t.getString("token"));
            if (!USDT_TRON.equalsIgnoreCase(contract)) {
                continue;
            }
            String amt = firstNonEmpty(t.getString("balance"), t.getString("value"));
            if (amt != null && !amt.isEmpty()) {
                bal.setUsdtBal(formatUnits(new BigInteger(amt), 6));
            }
            return;
        }
    }

    private ChainBalance queryBtc(String address) {
        ChainBalance bal = new ChainBalance();
        bal.setChain("btc");
        bal.setAddress(address);
        bal.setNativeSymbol("btc");
        bal.setNativeBal("0");
        bal.setUsdtBal("0");
        List<String> apis = btcApis == null || btcApis.isEmpty()
                ? Collections.singletonList("https://blockstream.info/api") : btcApis;
        Exception last = null;
        for (String api : apis) {
            try {
                String base = api.replaceAll("/$", "");
                if (isBlockbook(base)) {
                    String url = base + "/api/v2/address/" + address;
                    String body = httpGet(url);
                    JSONObject root = parseJsonObject(body);
                    if (root == null) {
                        throw new IllegalStateException("empty blockbook btc resp");
                    }
                    String balance = root.getString("balance");
                    if (balance != null && !balance.isEmpty()) {
                        bal.setNativeBal(formatUnits(new BigInteger(balance), 8));
                    }
                } else {
                    String url = base + "/address/" + address;
                    String body = httpGet(url);
                    JSONObject root = parseJsonObject(body);
                    if (root != null) {
                        JSONObject chain = root.getJSONObject("chain_stats");
                        if (chain != null) {
                            long funded = chain.getLongValue("funded_txo_sum");
                            long spent = chain.getLongValue("spent_txo_sum");
                            bal.setNativeBal(formatUnits(BigInteger.valueOf(Math.max(0, funded - spent)), 8));
                        }
                    }
                }
                return bal;
            } catch (Exception e) {
                last = e;
                log.warn("【balance】btc FAIL api={} err={}，尝试下一节点", api, shortErr(e));
            }
        }
        if (last != null) {
            log.warn("【balance】btc 全部节点失败 addr={} err={}", address, shortErr(last));
        }
        return bal;
    }

    private static boolean isBlockbook(String base) {
        if (base == null) {
            return false;
        }
        String u = base.toLowerCase(Locale.ROOT);
        return u.contains("blockbook") || u.contains("btcbook") || u.contains("trx-blockbook");
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        return b;
    }

    private ChainBalance querySol(String address) {
        ChainBalance bal = new ChainBalance();
        bal.setChain("sol");
        bal.setAddress(address);
        bal.setNativeSymbol("sol");
        bal.setNativeBal("0");
        bal.setUsdtBal("0");
        List<String> rpcs = solRpcs == null || solRpcs.isEmpty()
                ? Collections.singletonList("https://api.mainnet-beta.solana.com") : solRpcs;

        // 1) native SOL
        Exception lastNative = null;
        boolean nativeOk = false;
        for (String rpc : rpcs) {
            try {
                JSONArray balParams = new JSONArray();
                balParams.add(address);
                JSONObject balResp = solRpc(rpc, "getBalance", balParams, timeoutMs);
                JSONObject balResult = balResp == null ? null : balResp.getJSONObject("result");
                if (balResult != null) {
                    long lamports = balResult.getLongValue("value");
                    bal.setNativeBal(formatUnits(BigInteger.valueOf(Math.max(0, lamports)), 9));
                }
                nativeOk = true;
                break;
            } catch (Exception e) {
                lastNative = e;
                log.warn("【balance】sol native FAIL rpc={} err={}，尝试下一节点", rpc, shortErr(e));
            }
        }
        if (!nativeOk && lastNative != null) {
            log.warn("【balance】sol native 全部节点失败 addr={} err={}", address, shortErr(lastNative));
        }

        // 2) SPL USDT：getTokenAccountsByOwner 较慢，单独多节点重试，超时放宽
        int usdtTimeout = Math.max(timeoutMs * 2, 15000);
        Exception lastUsdt = null;
        boolean usdtOk = false;
        for (String rpc : rpcs) {
            try {
                bal.setUsdtBal(querySolUsdt(rpc, address, usdtTimeout));
                usdtOk = true;
                break;
            } catch (Exception ue) {
                lastUsdt = ue;
                log.debug("【balance】sol usdt FAIL rpc={} err={}，尝试下一节点", rpc, shortErr(ue));
            }
        }
        if (!usdtOk && lastUsdt != null) {
            log.warn("【balance】sol usdt 全部节点失败 addr={} err={}", address, shortErr(lastUsdt));
        }
        return bal;
    }

    private String querySolUsdt(String rpc, String address, int timeout) {
        JSONArray tokenParams = new JSONArray();
        tokenParams.add(address);
        JSONObject filter = new JSONObject();
        filter.put("mint", USDT_SOL);
        tokenParams.add(filter);
        JSONObject enc = new JSONObject();
        enc.put("encoding", "jsonParsed");
        tokenParams.add(enc);
        JSONObject tokenResp = solRpc(rpc, "getTokenAccountsByOwner", tokenParams, timeout);
        JSONObject tokenResult = tokenResp == null ? null : tokenResp.getJSONObject("result");
        JSONArray value = tokenResult == null ? null : tokenResult.getJSONArray("value");
        if (value == null || value.isEmpty()) {
            return "0";
        }
        BigInteger total = BigInteger.ZERO;
        for (int i = 0; i < value.size(); i++) {
            JSONObject acc = value.getJSONObject(i);
            if (acc == null) continue;
            JSONObject account = acc.getJSONObject("account");
            if (account == null) continue;
            JSONObject data = account.getJSONObject("data");
            if (data == null) continue;
            JSONObject parsed = data.getJSONObject("parsed");
            if (parsed == null) continue;
            JSONObject info = parsed.getJSONObject("info");
            if (info == null) continue;
            JSONObject tokenAmount = info.getJSONObject("tokenAmount");
            if (tokenAmount == null) continue;
            String amount = tokenAmount.getString("amount");
            if (amount != null && !amount.isEmpty()) {
                total = total.add(new BigInteger(amount));
            }
        }
        return formatUnits(total, 6);
    }

    private JSONObject solRpc(String rpc, String method, JSONArray params) {
        return solRpc(rpc, method, params, timeoutMs);
    }

    private JSONObject solRpc(String rpc, String method, JSONArray params, int timeout) {
        JSONObject req = new JSONObject();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", method);
        req.put("params", params);
        int t = Math.max(timeout, 3000);
        HttpRequest http = HttpRequest.post(rpc)
                .timeout(t)
                .setConnectionTimeout(Math.min(5000, t))
                .header("Content-Type", "application/json")
                .body(req.toJSONString())
                .charset(StandardCharsets.UTF_8);
        applyAuth(http, rpc);
        String body = http.execute().body();
        JSONObject resp = parseJsonObject(body);
        if (resp == null) throw new IllegalStateException("empty/non-json sol rpc resp");
        if (resp.get("error") != null) {
            throw new IllegalStateException(String.valueOf(resp.get("error")));
        }
        return resp;
    }

    private String ethCall(String rpc, String method, JSONArray params) {
        JSONObject req = new JSONObject();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", method);
        req.put("params", params);
        HttpRequest http = HttpRequest.post(rpc)
                .timeout(timeoutMs)
                .setConnectionTimeout(Math.min(5000, timeoutMs))
                .header("Content-Type", "application/json")
                .body(req.toJSONString())
                .charset(StandardCharsets.UTF_8);
        applyAuth(http, rpc);
        String body = http.execute().body();
        JSONObject resp = parseJsonObject(body);
        if (resp == null) throw new IllegalStateException("empty/non-json rpc resp");
        if (resp.get("error") != null) {
            Object err = resp.get("error");
            throw new IllegalStateException(String.valueOf(err));
        }
        return resp.getString("result");
    }

    private String httpGet(String url) {
        HttpRequest http = HttpRequest.get(url)
                .timeout(timeoutMs)
                .setConnectionTimeout(Math.min(5000, timeoutMs));
        applyAuth(http, url);
        return http.execute().body();
    }

    /** 拒绝 HTML 错误页，避免 fastjson 抛大段 DOCTYPE */
    private static JSONObject parseJsonObject(String body) {
        if (body == null) return null;
        String t = body.trim();
        if (t.isEmpty()) return null;
        if (t.charAt(0) == '<' || t.regionMatches(true, 0, "<!DOCTYPE", 0, 9)) {
            throw new IllegalStateException("rpc returned HTML: " + preview(t, 80));
        }
        return JSON.parseObject(t);
    }

    private static List<String> splitUrls(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) return out;
        for (String p : csv.split(",")) {
            String u = p.trim();
            if (!u.isEmpty()) out.add(u);
        }
        return out;
    }

    private static String shortErr(Throwable e) {
        if (e == null) return "";
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.toString();
        return preview(m, 160);
    }

    private static String preview(String s, int max) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    private static String normalizeChain(String chain) {
        if (chain == null) return "";
        String c = chain.trim().toLowerCase(Locale.ROOT);
        if ("trx".equals(c) || "tron".equals(c)) return "tron";
        if ("ethereum".equals(c)) return "eth";
        if ("binance".equals(c) || "bnb".equals(c)) return "bsc";
        if ("bitcoin".equals(c)) return "btc";
        if ("solana".equals(c)) return "sol";
        return c;
    }

    private static String ensureHexAddr(String addr) {
        if (addr == null) return "0x";
        return addr.startsWith("0x") || addr.startsWith("0X") ? addr : ("0x" + addr);
    }

    private static String padAddress(String hexAddr) {
        String clean = hexAddr.startsWith("0x") || hexAddr.startsWith("0X")
                ? hexAddr.substring(2) : hexAddr;
        if (clean.length() > 40) clean = clean.substring(clean.length() - 40);
        StringBuilder sb = new StringBuilder(64);
        for (int i = clean.length(); i < 64; i++) sb.append('0');
        sb.append(clean.toLowerCase(Locale.ROOT));
        return sb.toString();
    }

    private static BigInteger hexToBigInt(String hex) {
        if (hex == null || hex.isEmpty() || "0x".equals(hex) || "0X".equals(hex)) {
            return BigInteger.ZERO;
        }
        String h = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (h.isEmpty()) return BigInteger.ZERO;
        return new BigInteger(h, 16);
    }

    private static String formatUnits(BigInteger raw, int decimals) {
        if (raw == null) return "0";
        BigDecimal d = new BigDecimal(raw).movePointLeft(decimals);
        d = d.setScale(8, RoundingMode.DOWN).stripTrailingZeros();
        String s = d.toPlainString();
        if (s == null || s.isEmpty() || "0E-8".equalsIgnoreCase(s) || s.startsWith("0E")) {
            return "0";
        }
        return s;
    }

    @Data
    public static class ChainBalance {
        private String chain;
        private String address;
        private String nativeSymbol;
        private String nativeBal;
        private String usdtBal;

        public static ChainBalance zero(String chain, String address) {
            ChainBalance b = new ChainBalance();
            b.setChain(normalizeChain(chain));
            b.setAddress(address);
            switch (b.getChain()) {
                case "bsc": b.setNativeSymbol("bnb"); break;
                case "eth": b.setNativeSymbol("eth"); break;
                case "btc": b.setNativeSymbol("btc"); break;
                case "tron": b.setNativeSymbol("trx"); break;
                case "sol": b.setNativeSymbol("sol"); break;
                default: b.setNativeSymbol(b.getChain());
            }
            b.setNativeBal("0");
            b.setUsdtBal("0");
            return b;
        }
    }
}
