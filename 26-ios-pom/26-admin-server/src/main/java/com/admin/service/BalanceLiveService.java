package com.admin.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * 对齐 Python balance_live：ETH/BSC/Polygon 等 EVM、TRON、SOL、BTC、TON 可实时刷新。
 */
@Service
public class BalanceLiveService {

    private static final Map<String, String> CHAIN_ALIAS = Map.ofEntries(
            Map.entry("eth", "eth"),
            Map.entry("ethereum", "eth"),
            Map.entry("bsc", "bsc"),
            Map.entry("bnb", "bsc"),
            Map.entry("polygon", "polygon"),
            Map.entry("matic", "polygon"),
            Map.entry("arbitrum", "arbitrum"),
            Map.entry("optimism", "optimism"),
            Map.entry("base", "base"),
            Map.entry("linea", "linea"),
            Map.entry("tron", "tron"),
            Map.entry("trx", "tron"),
            Map.entry("solana", "solana"),
            Map.entry("sol", "solana"),
            Map.entry("btc", "btc"),
            Map.entry("bitcoin", "btc"),
            Map.entry("ton", "ton"));

    private static final Map<String, String> EVM_RPC = Map.of(
            "eth", "https://ethereum.publicnode.com",
            "bsc", "https://bsc-dataseed.binance.org",
            "polygon", "https://polygon-bor.publicnode.com",
            "arbitrum", "https://arbitrum-one.publicnode.com",
            "optimism", "https://optimism.publicnode.com",
            "base", "https://base.publicnode.com",
            "linea", "https://rpc.linea.build");

    private static final Map<String, String> EVM_NATIVE = Map.of(
            "eth", "ETH",
            "bsc", "BNB",
            "polygon", "MATIC",
            "arbitrum", "ETH",
            "optimism", "ETH",
            "base", "ETH",
            "linea", "ETH");

    private static final Map<String, String[]> USDC_EVM = Map.of(
            "eth", new String[] {"0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "6"},
            "bsc", new String[] {"0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", "18"});
    private static final String TRON_USDC = "TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8";
    private static final String SOL_USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    private static final String SOL_USDT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";

    private static final Map<String, String[]> USDT_EVM = Map.of(
            "eth", new String[] {"0xdAC17F958D2ee523a2206206994597C13D831ec7", "6"},
            "bsc", new String[] {"0x55d398326f99059fF775485246999027B3197955", "18"},
            "polygon", new String[] {"0xc2132D05D31c914a87C6611C10748AEb04B58e8F", "6"},
            "arbitrum", new String[] {"0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", "6"},
            "optimism", new String[] {"0x94b008aA00579c1307B0EF2c499aD98a8ce58e58", "6"},
            "base", new String[] {"0xfde4C96c8593536E31F229EA8f37b2ADa2699bb2", "6"});

    private final RestTemplate http;
    private final TronGridClient tron;

    public BalanceLiveService(TronGridClient tron) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(4000);
        f.setReadTimeout(4000);
        this.http = new RestTemplate(f);
        this.tron = tron;
    }

    public String normChain(String chain, String address) {
        String key = chain == null ? "" : chain.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("chain:")) {
            return guessChain(address);
        }
        if (key.contains(":")) {
            key = key.split(":", 2)[0];
        }
        String mapped = CHAIN_ALIAS.get(key);
        if (mapped != null) {
            return mapped;
        }
        return guessChain(address);
    }

    public boolean supports(String chain, String address) {
        String addr = address == null ? "" : address.trim();
        if (addr.isEmpty() || "—".equals(addr)) {
            return false;
        }
        return normChain(chain, addr) != null;
    }

    public String fetchDisplay(String chain, String address) {
        String mapped = normChain(chain, address);
        String addr = address == null ? "" : address.trim();
        if (mapped == null || addr.isEmpty()) {
            return null;
        }
        try {
            if (EVM_RPC.containsKey(mapped)) {
                return fetchEvm(mapped, addr);
            }
            if ("tron".equals(mapped)) {
                return fetchTron(addr);
            }
            if ("solana".equals(mapped)) {
                return fetchSol(addr);
            }
            if ("btc".equals(mapped)) {
                return fetchBtc(addr);
            }
            if ("ton".equals(mapped)) {
                return fetchTon(addr);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    public String[] fetchAmounts(String chain, String address) {
        String mapped = normChain(chain, address);
        String addr = address == null ? "" : address.trim();
        if (mapped == null) {
            return null;
        }
        String disp = fetchDisplay(mapped, addr);
        if (disp == null) {
            // 查链失败时返回 null，调用方保留预览余额，避免被写成 0
            return null;
        }
        String[] parts = disp.split("/");
        String nativeUnit;
        switch (mapped) {
            case "tron":
                nativeUnit = "TRX";
                break;
            case "bsc":
                nativeUnit = "BNB";
                break;
            case "polygon":
                nativeUnit = "MATIC";
                break;
            case "solana":
                nativeUnit = "SOL";
                break;
            case "btc":
                nativeUnit = "BTC";
                break;
            case "ton":
                nativeUnit = "TON";
                break;
            default:
                nativeUnit = EVM_NATIVE.getOrDefault(mapped, "ETH");
        }
        return new String[] {
                firstNumber(parts[0], nativeUnit),
                parts.length > 1 ? firstNumber(parts[1], "USDT") : "0",
                parts.length > 2 ? firstNumber(parts[2], "USDC") : "0"};
    }

    private String fetchEvm(String chain, String address) {
        String rpc = EVM_RPC.get(chain);
        String nativeHex = evmCall(rpc, "eth_getBalance", List.of(address, "latest"));
        String nativeHuman = hexToDecimal(nativeHex, 18);
        String sym = EVM_NATIVE.getOrDefault(chain, "ETH");
        String[] usdt = USDT_EVM.get(chain);
        if (usdt == null || address.length() < 3) {
            return nativeHuman + " " + sym;
        }
        int decimals = Integer.parseInt(usdt[1]);
        String data = "0x70a08231" + String.format("%64s", address.substring(2).toLowerCase(Locale.ROOT)).replace(' ', '0');
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("to", usdt[0]);
        call.put("data", data);
        String usdtHex = evmCall(rpc, "eth_call", List.of(call, "latest"));
        String usdcPart = "";
        String[] usdc = USDC_EVM.get(chain);
        if (usdc != null) {
            Map<String, Object> usdcCall = new LinkedHashMap<>();
            usdcCall.put("to", usdc[0]);
            usdcCall.put("data", data);
            String usdcHex = evmCall(rpc, "eth_call", List.of(usdcCall, "latest"));
            usdcPart = " / " + hexToDecimal(usdcHex, Integer.parseInt(usdc[1])) + " USDC";
        }
        return nativeHuman + " " + sym + " / " + hexToDecimal(usdtHex, decimals) + " USDT" + usdcPart;
    }

    private String evmCall(String rpc, String method, List<?> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        body.put("params", params);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = http.exchange(rpc, HttpMethod.POST, new HttpEntity<>(JSON.toJSONString(body), headers), String.class);
        JSONObject obj = JSON.parseObject(resp.getBody());
        if (obj == null || obj.get("error") != null) {
            return null;
        }
        return obj.getString("result");
    }

    private String fetchTron(String address) {
        String usdt = triggerTrc20(address, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t");
        String usdc = triggerTrc20(address, TRON_USDC);
        String trx = tronViaGetAccount(address);
        if (trx == null || usdt == null || usdc == null) {
            String[] v1 = tronViaV1(address);
            if (v1 != null) {
                if (trx == null) {
                    trx = v1[0];
                }
                if (usdt == null) {
                    usdt = v1[1];
                }
                if (usdc == null) {
                    usdc = v1[2];
                }
            }
        }
        if (trx == null || usdt == null || usdc == null) {
            String[] scan = tronViaTronscan(address);
            if (scan != null) {
                if (trx == null) {
                    trx = scan[0];
                }
                if (usdt == null) {
                    usdt = scan[1];
                }
                if (usdc == null) {
                    usdc = scan[2];
                }
            }
        }
        if (trx == null && usdt == null && usdc == null) {
            return null;
        }
        return (trx == null ? "0" : trx) + " TRX / " + (usdt == null ? "0" : usdt) + " USDT / "
                + (usdc == null ? "0" : usdc) + " USDC";
    }

    private String triggerTrc20(String address, String contract) {
        try {
            JSONObject req = new JSONObject();
            req.put("owner_address", address);
            req.put("contract_address", contract);
            req.put("function_selector", "balanceOf(address)");
            req.put("parameter", TronGridClient.abiAddressParam(address));
            req.put("visible", true);
            JSONObject resp = tron.postWallet("/wallet/triggerconstantcontract", req);
            if (resp == null) {
                return null;
            }
            JSONObject result = resp.getJSONObject("result");
            if (result != null && Boolean.FALSE.equals(result.getBoolean("result"))) {
                return null;
            }
            JSONArray arr = resp.getJSONArray("constant_result");
            if (arr == null || arr.isEmpty()) {
                return null;
            }
            return fmt(new BigDecimal(new java.math.BigInteger(arr.getString(0), 16)).movePointLeft(6));
        } catch (Exception e) {
            return null;
        }
    }

    private String tronViaGetAccount(String address) {
        try {
            JSONObject req = new JSONObject();
            req.put("address", address);
            req.put("visible", true);
            JSONObject resp = tron.postWallet("/wallet/getaccount", req);
            if (resp == null || resp.isEmpty()) {
                return "0";
            }
            if (resp.containsKey("balance") || resp.containsKey("address")) {
                return fmt(new BigDecimal(resp.getLongValue("balance")).movePointLeft(6));
            }
            return "0";
        } catch (Exception e) {
            return null;
        }
    }

    private String[] tronViaV1(String address) {
        JSONObject obj = tron.getAccounts(address);
        if (obj == null) {
            return null;
        }
        JSONArray data = obj.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            return null;
        }
        JSONObject acc = data.getJSONObject(0);
        String trx = fmt(new BigDecimal(acc.getLongValue("balance")).movePointLeft(6));
        String usdt = "0";
        String usdc = "0";
        JSONArray trc20 = acc.getJSONArray("trc20");
        if (trc20 != null) {
            for (int i = 0; i < trc20.size(); i++) {
                JSONObject t = trc20.getJSONObject(i);
                if (t.containsKey("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t")) {
                    usdt = fmt(new BigDecimal(t.getString("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t")).movePointLeft(6));
                }
                if (t.containsKey(TRON_USDC)) {
                    usdc = fmt(new BigDecimal(t.getString(TRON_USDC)).movePointLeft(6));
                }
            }
        }
        return new String[] {trx, usdt, usdc};
    }

    private String[] tronViaTronscan(String address) {
        JSONObject parsed = getJson("https://apilist.tronscanapi.com/api/account?address=" + address);
        if (parsed == null || parsed.isEmpty()) {
            return null;
        }
        String trx = "0";
        JSONArray balances = parsed.getJSONArray("balances");
        if (balances == null) {
            balances = parsed.getJSONArray("tokenBalances");
        }
        if (balances != null) {
            for (int i = 0; i < balances.size(); i++) {
                JSONObject item = balances.getJSONObject(i);
                String abbr = String.valueOf(item.getString("tokenAbbr") == null ? item.getString("tokenName") : item.getString("tokenAbbr"))
                        .toLowerCase(Locale.ROOT);
                String tid = String.valueOf(item.getString("tokenId") == null ? "" : item.getString("tokenId"));
                if ("trx".equals(abbr) || "_".equals(tid)) {
                    trx = fmt(new BigDecimal(item.getLongValue("balance")).movePointLeft(6));
                    break;
                }
            }
        }
        String usdt = "0";
        String usdc = "0";
        JSONArray trc20 = parsed.getJSONArray("trc20token_balances");
        if (trc20 != null) {
            for (int i = 0; i < trc20.size(); i++) {
                JSONObject item = trc20.getJSONObject(i);
                String tid = String.valueOf(item.getString("tokenId") == null ? "" : item.getString("tokenId"));
                if ("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t".equals(tid)) {
                    usdt = fmt(new BigDecimal(item.getLongValue("balance")).movePointLeft(6));
                } else if (TRON_USDC.equals(tid)) {
                    usdc = fmt(new BigDecimal(item.getLongValue("balance")).movePointLeft(6));
                }
            }
        }
        return new String[] {trx, usdt, usdc};
    }

    private String fetchSol(String address) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "getBalance");
        body.put("params", List.of(address));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = http.exchange("https://api.mainnet-beta.solana.com", HttpMethod.POST,
                new HttpEntity<>(JSON.toJSONString(body), headers), String.class);
        JSONObject obj = JSON.parseObject(resp.getBody());
        if (obj == null || obj.getJSONObject("result") == null) {
            return null;
        }
        long lamports = obj.getJSONObject("result").getLongValue("value");
        return fmt(new BigDecimal(lamports).movePointLeft(9)) + " SOL / "
                + solToken(address, SOL_USDT) + " USDT / "
                + solToken(address, SOL_USDC) + " USDC";
    }

    private String solToken(String address, String mint) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("mint", mint);
        Map<String, Object> enc = new LinkedHashMap<>();
        enc.put("encoding", "jsonParsed");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "getTokenAccountsByOwner");
        body.put("params", List.of(address, filter, enc));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<String> resp = http.exchange("https://api.mainnet-beta.solana.com", HttpMethod.POST,
                    new HttpEntity<>(JSON.toJSONString(body), headers), String.class);
            JSONObject obj = JSON.parseObject(resp.getBody());
            JSONObject result = obj == null ? null : obj.getJSONObject("result");
            JSONArray value = result == null ? null : result.getJSONArray("value");
            BigDecimal total = BigDecimal.ZERO;
            if (value != null) {
                for (int i = 0; i < value.size(); i++) {
                    JSONObject item = value.getJSONObject(i);
                    JSONObject acc = item == null ? null : item.getJSONObject("account");
                    JSONObject data = acc == null ? null : acc.getJSONObject("data");
                    JSONObject parsed = data == null ? null : data.getJSONObject("parsed");
                    JSONObject info = parsed == null ? null : parsed.getJSONObject("info");
                    JSONObject amt = info == null ? null : info.getJSONObject("tokenAmount");
                    if (amt != null && amt.getString("uiAmountString") != null) {
                        total = total.add(new BigDecimal(amt.getString("uiAmountString")));
                    }
                }
            }
            return fmt(total);
        } catch (Exception e) {
            return "0";
        }
    }

    private String fetchBtc(String address) {
        JSONObject obj = getJson("https://mempool.space/api/address/" + address);
        if (obj == null) {
            return null;
        }
        JSONObject chain = obj.getJSONObject("chain_stats");
        JSONObject mem = obj.getJSONObject("mempool_stats");
        long funded = (chain == null ? 0 : chain.getLongValue("funded_txo_sum"))
                + (mem == null ? 0 : mem.getLongValue("funded_txo_sum"));
        long spent = (chain == null ? 0 : chain.getLongValue("spent_txo_sum"))
                + (mem == null ? 0 : mem.getLongValue("spent_txo_sum"));
        long sats = Math.max(0, funded - spent);
        return fmt(new BigDecimal(sats).movePointLeft(8)) + " BTC";
    }

    private String fetchTon(String address) {
        JSONObject acc = getJson("https://tonapi.io/v2/accounts/" + address);
        Long nano = null;
        if (acc != null && acc.get("balance") != null) {
            nano = acc.getLong("balance");
        }
        if (nano == null) {
            JSONObject tc = getJson("https://toncenter.com/api/v2/getAddressBalance?address=" + address);
            if (tc != null && Boolean.TRUE.equals(tc.getBoolean("ok"))) {
                try {
                    nano = Long.parseLong(String.valueOf(tc.get("result")));
                } catch (Exception ignored) {
                    nano = null;
                }
            }
        }
        if (nano == null) {
            return null;
        }
        String usdtAmt = "0";
        JSONObject jets = getJson("https://tonapi.io/v2/accounts/" + address + "/jettons");
        JSONArray balances = jets == null ? null : jets.getJSONArray("balances");
        if (balances != null) {
            for (int i = 0; i < balances.size(); i++) {
                JSONObject item = balances.getJSONObject(i);
                JSONObject jetton = item.getJSONObject("jetton");
                String sym = jetton == null ? "" : String.valueOf(jetton.getString("symbol")).toUpperCase(Locale.ROOT);
                if (!"USDT".equals(sym) && !"USD₮".equals(sym)) {
                    continue;
                }
                int dec = jetton.getIntValue("decimals");
                if (dec <= 0) {
                    dec = 6;
                }
                usdtAmt = fmt(new BigDecimal(item.getString("balance") == null ? "0" : item.getString("balance")).movePointLeft(dec));
                break;
            }
        }
        return fmt(new BigDecimal(nano).movePointLeft(9)) + " TON / " + usdtAmt + " USDT";
    }

    private JSONObject getJson(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "v26-admin/1.0");
        headers.set("Accept", "application/json");
        ResponseEntity<String> resp = http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        return JSON.parseObject(resp.getBody());
    }

    private static String guessChain(String address) {
        String a = address == null ? "" : address.trim();
        if (a.startsWith("0x") && a.length() == 42) {
            return "eth";
        }
        if (a.startsWith("T") && a.length() >= 30 && a.length() <= 36) {
            return "tron";
        }
        if ((a.startsWith("bc1") || a.startsWith("1") || a.startsWith("3")) && a.length() >= 26) {
            return "btc";
        }
        if ((a.startsWith("EQ") || a.startsWith("UQ") || a.startsWith("kQ") || a.startsWith("0Q")) && a.length() > 40) {
            return "ton";
        }
        if (a.length() >= 32 && a.indexOf(' ') < 0 && !a.startsWith("0x") && a.chars().allMatch(Character::isLetterOrDigit)) {
            return "solana";
        }
        return null;
    }

    private String hexToDecimal(String hex, int decimals) {
        if (hex == null || !hex.startsWith("0x")) {
            return "0";
        }
        try {
            BigDecimal wei = new BigDecimal(new java.math.BigInteger(hex.substring(2), 16));
            return fmt(wei.movePointLeft(decimals));
        } catch (Exception e) {
            return "0";
        }
    }

    private String firstNumber(String text, String unit) {
        if (text == null) {
            return "0";
        }
        String token = text.trim().split("\\s+")[0].replace(",", "");
        String u = unit.toLowerCase(Locale.ROOT);
        if (token.toLowerCase(Locale.ROOT).endsWith(u)) {
            token = token.substring(0, token.length() - u.length());
        }
        try {
            return fmt(new BigDecimal(token));
        } catch (Exception e) {
            return "0";
        }
    }

    private String fmt(BigDecimal d) {
        return d.stripTrailingZeros().scale() < 0
                ? d.toPlainString()
                : d.setScale(Math.min(d.scale(), 8), RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }
}
