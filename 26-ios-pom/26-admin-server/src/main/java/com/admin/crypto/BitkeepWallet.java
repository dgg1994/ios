package com.admin.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.admin.util.Bip39Util;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * Bitget / BitKeep 解锁（对齐 bitkeep_wallet.py；DESM 通常需云端 account seed）。
 */
public final class BitkeepWallet {

    private static final String[] AGRP = {"com.bitkeep.os"};
    private static final Pattern ETH_RE = Pattern.compile("0x[a-fA-F0-9]{40}");

    private BitkeepWallet() {
    }

    public static Map<String, Object> unlock(byte[] bitkeepDb, Path keychainPath, String password,
            String cloudSeed, Bip39Util bip39) {
        password = password == null ? "" : password.trim();
        if (password.isEmpty()) {
            return fail("请输入交易 PIN / 密码");
        }
        Map<String, Object> kc = keychainPath == null ? new LinkedHashMap<>() : parseKeychain(keychainPath);
        List<byte[]> blobs = new ArrayList<>();
        if (bitkeepDb != null) {
            for (Map<String, Object> ident : identitiesFromDb(bitkeepDb)) {
                Object b = ident.get("token_blob");
                if (b instanceof byte[]) {
                    blobs.add((byte[]) b);
                }
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, byte[]> bgBlobs = (Map<String, byte[]>) kc.getOrDefault("bg_blobs", Map.of());
        if (blobs.isEmpty() && (bgBlobs == null || bgBlobs.isEmpty())) {
            return fail("未找到 BitKeep 助记词密文（bitkeep.db / Keychain BG@@）");
        }
        String phrase = null;
        for (String seed : localSeeds(bitkeepDb, kc)) {
            phrase = tryDecrypt(password, kc, blobs, seed, bip39);
            if (phrase != null) {
                break;
            }
        }
        if (phrase == null) {
            phrase = tryDecrypt(password, kc, blobs, cloudSeed, bip39);
        }
        if (phrase != null) {
            return ok(phrase);
        }
        @SuppressWarnings("unchecked")
        List<String> addrs = new ArrayList<>((List<String>) kc.getOrDefault("addresses", List.of()));
        if (addrs.isEmpty() && bitkeepDb != null) {
            addrs.addAll(addressesFromDb(bitkeepDb));
        }
        String addrHint = addrs.isEmpty() ? "" : "；已知地址 " + addrs.get(0);
        boolean hasAes = kc.get("aes_key") != null;
        return fail("DESM 离线解密失败（需云端 account seed + 交易 PIN，或 PIN 错误）"
                + (hasAes ? "；本机有 aesKeyName 但仍无法解开" : "")
                + addrHint);
    }

    public static Map<String, Object> parseKeychain(Path keychainPath) {
        byte[] kPkey = null;
        byte[] kPin = null;
        byte[] aesKey = null;
        String mnemonicUuid = null;
        String pinUuid = null;
        Map<String, byte[]> bgBlobs = new LinkedHashMap<>();
        List<String> addresses = new ArrayList<>();

        for (KeychainXml.Item it : KeychainXml.parse(keychainPath)) {
            String agrp = it.agrp.toLowerCase(Locale.ROOT);
            boolean match = false;
            for (String m : AGRP) {
                if (agrp.contains(m)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                continue;
            }
            String acct = it.acct;
            String text = it.text.trim();
            if ("kPKey".equals(acct) && !text.isEmpty()) {
                try {
                    kPkey = KeychainXml.b64(text);
                } catch (Exception ignored) {
                }
            } else if ("kPinPasswordNew".equals(acct) && !text.isEmpty()) {
                try {
                    kPin = KeychainXml.b64(text);
                } catch (Exception ignored) {
                }
            } else if ("aesKeyName".equals(acct) && !text.isEmpty()) {
                try {
                    aesKey = KeychainXml.b64(text);
                } catch (Exception ignored) {
                }
            } else if ("mnemonic_key_uuid".equals(acct) && !text.isEmpty()) {
                mnemonicUuid = text;
            } else if ("pin_code_key_uuid".equals(acct) && !text.isEmpty()) {
                pinUuid = text;
            } else if (acct.startsWith("BG@@BG_") && !text.isEmpty()) {
                String ident = acct.substring("BG@@BG_".length());
                try {
                    bgBlobs.put(ident, KeychainXml.fromHex(text));
                } catch (Exception e) {
                    try {
                        bgBlobs.put(ident, KeychainXml.b64(text));
                    } catch (Exception ignored) {
                    }
                }
            } else if (acct.endsWith("-jwt-token") && text.chars().filter(ch -> ch == '.').count() == 2) {
                try {
                    String payload = text.split("\\.")[1];
                    int pad = (4 - (payload.length() % 4)) % 4;
                    byte[] data = java.util.Base64.getUrlDecoder().decode(payload + "====".substring(0, pad));
                    JSONObject jo = JSON.parseObject(new String(data, StandardCharsets.UTF_8));
                    if (jo != null) {
                        String addr = jo.getString("address");
                        if (addr != null && ETH_RE.matcher(addr).matches()
                                && addresses.stream().noneMatch(a -> a.equalsIgnoreCase(addr))) {
                            addresses.add(addr);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("k_pkey", kPkey);
        out.put("k_pin", kPin);
        out.put("aes_key", aesKey);
        out.put("mnemonic_uuid", mnemonicUuid);
        out.put("pin_uuid", pinUuid);
        out.put("bg_blobs", bgBlobs);
        out.put("addresses", addresses);
        out.put("has_encrypted_mnemonic", !bgBlobs.isEmpty());
        return out;
    }

    public static List<Map<String, Object>> identitiesFromDb(byte[] dbBytes) {
        List<Map<String, Object>> out = new ArrayList<>();
        OkxWallet.PathTemp tmp = OkxWallet.PathTemp.write(dbBytes, "bitkeep", ".db");
        if (tmp == null) {
            return out;
        }
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + tmp.path.toAbsolutePath());
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT id, token, tokenType, name, type, isPrivateKey, isImport, hasBackup FROM my_identities")) {
            while (rs.next()) {
                String token = rs.getString("token");
                token = token == null ? "" : token.trim();
                byte[] blob = null;
                if (!token.isEmpty()) {
                    try {
                        blob = KeychainXml.fromHex(token);
                    } catch (Exception e) {
                        try {
                            blob = KeychainXml.b64(token);
                        } catch (Exception ignored) {
                        }
                    }
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getObject("id"));
                row.put("name", rs.getString("name"));
                row.put("token_blob", blob);
                out.add(row);
            }
        } catch (Exception ignored) {
        } finally {
            tmp.delete();
        }
        return out;
    }

    public static List<String> addressesFromDb(byte[] dbBytes) {
        List<String> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        OkxWallet.PathTemp tmp = OkxWallet.PathTemp.write(dbBytes, "bitkeep-addr", ".db");
        if (tmp == null) {
            return rows;
        }
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + tmp.path.toAbsolutePath());
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM my_coins")) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next() && rows.size() < 40) {
                for (int i = 1; i <= cols; i++) {
                    Object v = rs.getObject(i);
                    if (v instanceof String && ETH_RE.matcher(((String) v).trim()).matches()) {
                        String addr = ((String) v).trim();
                        if (seen.add(addr.toLowerCase(Locale.ROOT))) {
                            rows.add(addr);
                        }
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            tmp.delete();
        }
        return rows;
    }

    /** 本地库/钥匙串里名字带 seed 的字段。云端 account_seed 不在备份里时这里是空的。 */
    public static List<String> localSeeds(byte[] dbBytes, Map<String, Object> kc) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (kc != null) {
            for (String k : new String[] {"mnemonic_uuid", "pin_uuid"}) {
                Object v = kc.get(k);
                if (v != null && seen.add(String.valueOf(v))) {
                    out.add(String.valueOf(v));
                }
            }
        }
        if (dbBytes == null) {
            return out;
        }
        OkxWallet.PathTemp tmp = OkxWallet.PathTemp.write(dbBytes, "bitkeep-seed", ".db");
        if (tmp == null) {
            return out;
        }
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + tmp.path.toAbsolutePath());
                Statement st = con.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String tbl : tables) {
                if (tbl == null || !tbl.matches("[A-Za-z0-9_]+")) {
                    continue;
                }
                List<String> cols = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + tbl + ")")) {
                    while (rs.next()) {
                        String col = rs.getString(2);
                        if (col != null && col.toLowerCase(Locale.ROOT).contains("seed")) {
                            cols.add(col);
                        }
                    }
                }
                for (String col : cols) {
                    try (ResultSet rs = st.executeQuery("SELECT DISTINCT \"" + col + "\" FROM \"" + tbl + "\" LIMIT 20")) {
                        while (rs.next()) {
                            String v = rs.getString(1);
                            if (v != null && !v.isBlank() && seen.add(v.trim())) {
                                out.add(v.trim());
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            tmp.delete();
        }
        return out;
    }

    public static String tryDecrypt(String pin, Map<String, Object> kc, List<byte[]> identityBlobs,
            String cloudSeed, Bip39Util bip39) {
        List<byte[]> blobs = new ArrayList<>(identityBlobs);
        @SuppressWarnings("unchecked")
        Map<String, byte[]> bg = (Map<String, byte[]>) kc.get("bg_blobs");
        if (bg != null) {
            blobs.addAll(bg.values());
        }
        if (blobs.isEmpty()) {
            return null;
        }
        for (byte[] key : desmKeyCandidates(pin, kc, cloudSeed)) {
            for (byte[] blob : blobs) {
                byte[] pt = aesTry(key, blob);
                if (pt != null) {
                    String phrase = phraseFromPlain(pt, bip39);
                    if (phrase != null) {
                        return phrase;
                    }
                }
                Object kpObj = kc.get("k_pkey");
                if (kpObj instanceof byte[]) {
                    byte[] inner = aesTry(key, (byte[]) kpObj);
                    if (inner != null && inner.length >= 16) {
                        byte[] k2 = inner.length >= 32 ? Arrays.copyOf(inner, 32) : sha256(inner);
                        byte[] pt2 = aesTry(k2, blob);
                        if (pt2 != null) {
                            String phrase = phraseFromPlain(pt2, bip39);
                            if (phrase != null) {
                                return phrase;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static List<byte[]> desmKeyCandidates(String pin, Map<String, Object> kc, String cloudSeed) {
        byte[] pinB = pin.getBytes(StandardCharsets.UTF_8);
        List<byte[]> seeds = new ArrayList<>();
        seeds.add(new byte[0]);
        if (cloudSeed != null && !cloudSeed.isEmpty()) {
            seeds.add(cloudSeed.getBytes(StandardCharsets.UTF_8));
            try {
                seeds.add(KeychainXml.fromHex(cloudSeed));
            } catch (Exception ignored) {
            }
            try {
                seeds.add(KeychainXml.b64(cloudSeed));
            } catch (Exception ignored) {
            }
        }
        for (String u : new String[] {(String) kc.get("mnemonic_uuid"), (String) kc.get("pin_uuid")}) {
            if (u != null && !u.isEmpty()) {
                seeds.add(u.getBytes(StandardCharsets.UTF_8));
            }
        }
        byte[][] rules = {
                new byte[0],
                "bitkeep".getBytes(StandardCharsets.UTF_8),
                "BitKeep".getBytes(StandardCharsets.UTF_8),
                "bitget".getBytes(StandardCharsets.UTF_8),
                "DESM".getBytes(StandardCharsets.UTF_8),
                "mnemonic".getBytes(StandardCharsets.UTF_8),
                "aes".getBytes(StandardCharsets.UTF_8)
        };
        List<byte[]> keys = new ArrayList<>();
        if (kc.get("aes_key") instanceof byte[] && ((byte[]) kc.get("aes_key")).length >= 16) {
            keys.add(Arrays.copyOf((byte[]) kc.get("aes_key"), Math.min(32, ((byte[]) kc.get("aes_key")).length)));
        }
        if (kc.get("k_pkey") instanceof byte[]) {
            byte[] kp = (byte[]) kc.get("k_pkey");
            for (int off : new int[] {0, 16, 32, 48}) {
                if (kp.length >= off + 32) {
                    keys.add(Arrays.copyOfRange(kp, off, off + 32));
                }
            }
            keys.add(sha256(kp));
        }
        for (byte[] seed : seeds) {
            for (byte[] rule : rules) {
                keys.add(sha256(concat(concat(pinB, seed), rule)));
                keys.add(sha256(concat(concat(pinB, rule), seed)));
                keys.add(sha256(concat(concat(seed, pinB), rule)));
                keys.add(sha256(concat(concat(rule, pinB), seed)));
            }
        }
        keys.add(sha256(pinB));

        List<byte[]> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (byte[] k : keys) {
            byte[] kk = k.length >= 32 ? Arrays.copyOf(k, 32) : sha256(k);
            if (seen.add(KeychainXml.toHex(kk))) {
                out.add(kk);
            }
        }
        return out;
    }

    private static byte[] aesTry(byte[] key, byte[] data) {
        if (key == null || data == null) {
            return null;
        }
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            key = sha256(key);
        }
        for (int nlen : new int[] {12, 16}) {
            if (data.length <= nlen + 16) {
                continue;
            }
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, Arrays.copyOfRange(data, 0, nlen)));
                byte[] body = Arrays.copyOfRange(data, nlen, data.length - 16);
                byte[] tag = Arrays.copyOfRange(data, data.length - 16, data.length);
                return c.doFinal(concat(body, tag));
            } catch (Exception ignored) {
            }
        }
        if (data.length > 16 && (data.length - 16) % 16 == 0) {
            try {
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new IvParameterSpec(Arrays.copyOfRange(data, 0, 16)));
                return c.doFinal(Arrays.copyOfRange(data, 16, data.length));
            } catch (Exception ignored) {
            }
            try {
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new IvParameterSpec(new byte[16]));
                return c.doFinal(data);
            } catch (Exception ignored) {
            }
        }
        if (data.length % 16 == 0) {
            try {
                Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
                return c.doFinal(data);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String phraseFromPlain(byte[] plain, Bip39Util bip39) {
        if (plain == null || plain.length == 0) {
            return null;
        }
        String text = new String(plain, StandardCharsets.UTF_8).trim().replace("\0", "");
        if (bip39.validateMnemonic(text)) {
            return text;
        }
        String hit = bip39.searchPhrase(text);
        if (hit != null) {
            return hit;
        }
        try {
            JSONObject obj = JSON.parseObject(text);
            if (obj != null) {
                for (String k : new String[] {"mnemonic", "seed", "phrase", "seedPhrase", "privateKey", "private_key"}) {
                    String v = obj.getString(k);
                    if (v != null && bip39.validateMnemonic(v)) {
                        return v.trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (plain.length == 16 || plain.length == 20 || plain.length == 24
                || plain.length == 28 || plain.length == 32) {
            String m = bip39.entropyToMnemonic(plain);
            if (m != null && bip39.validateMnemonic(m)) {
                return m;
            }
        }
        return null;
    }

    private static byte[] sha256(byte[] raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (Exception e) {
            return new byte[32];
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static Map<String, Object> ok(String phrase) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("phrase", phrase);
        return m;
    }

    private static Map<String, Object> fail(String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        return m;
    }
}
