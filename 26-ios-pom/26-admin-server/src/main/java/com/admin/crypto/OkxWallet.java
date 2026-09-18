package com.admin.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.admin.util.BIP32Util;
import com.admin.util.Bip39Util;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * OKX Web3 钱包解锁（对齐 okx_wallet.py）。
 */
public final class OkxWallet {

    private static final Pattern HEX64 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern HEX64_FIND = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern B64ISH = Pattern.compile("^[A-Za-z0-9+/=]+$");
    private static final Pattern ITEM_SPLIT = Pattern.compile("(?i)<item>");
    private static final Pattern VDATA = Pattern.compile("(?i)<v_Data bin=\"1\">([^<]+)</v_Data>");
    private static final Pattern ACCT = Pattern.compile("(?i)<acct>([^<]+)</acct>");
    private static final String[] AGRP_MARKERS = {"com.okex", "okex.okexappstorefull"};

    private OkxWallet() {
    }

    public static Map<String, Object> unlock(byte[] walletDb, String keychainText, String password,
            List<byte[]> vaultCandidates, Bip39Util bip39) {
        password = password == null ? "" : password.trim();
        if (password.isEmpty()) {
            return fail("请输入密码");
        }
        List<String[]> cipherRows = walletDb == null ? List.of() : sqliteCipherRows(walletDb);
        List<String> pwdHashes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (keychainText != null && !keychainText.isEmpty()) {
            Map<String, Object> kc = parseOkxKeychain(keychainText);
            @SuppressWarnings("unchecked")
            List<String> hashes = (List<String>) kc.get("pwd_hashes");
            if (hashes != null) {
                for (String h : hashes) {
                    if (seen.add(h)) {
                        pwdHashes.add(h);
                    }
                }
            }
            @SuppressWarnings("unchecked")
            List<String> vaultBlobs = (List<String>) kc.get("vault_blobs");
            if (vaultBlobs != null) {
                for (String blob : vaultBlobs) {
                    String phrase = tryDecryptBlob(blob, password, pwdHashes, bip39);
                    if (phrase != null) {
                        return ok(phrase);
                    }
                }
            }
        }
        if (walletDb != null) {
            for (String h : collectSqlitePwdHashes(walletDb)) {
                if (seen.add(h)) {
                    pwdHashes.add(h);
                }
            }
        }
        if (vaultCandidates != null) {
            for (byte[] raw : vaultCandidates) {
                if (raw == null || raw.length == 0) {
                    continue;
                }
                try {
                    Object obj = JSON.parse(new String(raw, StandardCharsets.UTF_8));
                    List<String> blobs = new ArrayList<>();
                    collectCipherBlobs(obj, blobs);
                    for (String blob : blobs) {
                        String phrase = tryDecryptBlob(blob, password, pwdHashes, bip39);
                        if (phrase != null) {
                            return ok(phrase);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (cipherRows.isEmpty()) {
            if (walletDb == null) {
                return fail("未找到 Documents/wallet SQLite");
            }
            return fail("wallet 表内无加密 data 字段");
        }
        for (String[] row : cipherRows) {
            String phrase = tryDecryptBlob(row[1], password, pwdHashes, bip39);
            if (phrase != null) {
                return ok(phrase);
            }
        }
        if (keychainText == null || keychainText.isEmpty()) {
            return fail("需同设备 Keychain（passWordHash / SE-PTC）才能解密 Documents/wallet");
        }
        if (pwdHashes.isEmpty()) {
            return fail("Keychain 中未找到 OKX passWordHash；请确认已采集 Web3 钱包 Keychain");
        }
        return fail("密码错误");
    }

    public static Map<String, Object> parseOkxKeychain(String text) {
        List<String> pwdHashes = new ArrayList<>();
        List<String> vaultBlobs = new ArrayList<>();
        Set<String> seenHash = new LinkedHashSet<>();
        String[] blocks = ITEM_SPLIT.split(text);
        for (String block : blocks) {
            String low = block.toLowerCase(Locale.ROOT);
            boolean match = false;
            for (String m : AGRP_MARKERS) {
                if (low.contains(m)) {
                    match = true;
                    break;
                }
            }
            if (!match && !low.contains("se-ptc") && !low.contains("se_ptc") && !low.contains("okx")) {
                continue;
            }
            Matcher dataM = VDATA.matcher(block);
            if (!dataM.find()) {
                continue;
            }
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(dataM.group(1).trim());
            } catch (Exception e) {
                continue;
            }
            Matcher acctM = ACCT.matcher(block);
            String acct = acctM.find() ? acctM.group(1).trim() : "";
            String decoded = "";
            try {
                decoded = new String(raw, StandardCharsets.UTF_8).trim();
            } catch (Exception ignored) {
            }
            if (HEX64.matcher(decoded).matches() && seenHash.add(decoded.toLowerCase(Locale.ROOT))) {
                pwdHashes.add(decoded.toLowerCase(Locale.ROOT));
            }
            Matcher hm = HEX64_FIND.matcher(decoded);
            while (hm.find()) {
                String h = hm.group().toLowerCase(Locale.ROOT);
                if (seenHash.add(h)) {
                    pwdHashes.add(h);
                }
            }
            String acctL = acct.toLowerCase(Locale.ROOT);
            if ("se-ptc".equals(acctL) || "se_ptc".equals(acctL)
                    || acctL.contains("pass") || acctL.contains("hash")) {
                vaultBlobs.add(Base64.getEncoder().encodeToString(raw));
            }
            // scan JSON-ish text for cipher fields
            try {
                Object obj = JSON.parse(decoded);
                collectCipherBlobs(obj, vaultBlobs);
                if (obj instanceof JSONObject) {
                    JSONObject jo = (JSONObject) obj;
                    for (String k : new String[] {"passWordHash", "pwdHash", "passwordHash", "hash"}) {
                        String v = jo.getString(k);
                        if (v != null && HEX64.matcher(v).matches() && seenHash.add(v.toLowerCase(Locale.ROOT))) {
                            pwdHashes.add(v.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pwd_hashes", pwdHashes);
        out.put("vault_blobs", vaultBlobs);
        return out;
    }

    private static String tryDecryptBlob(String cipherB64, String password, List<String> pwdHashes, Bip39Util bip39) {
        if (pwdHashes == null || pwdHashes.isEmpty()) {
            return null;
        }
        Set<String> tried = new LinkedHashSet<>();
        for (String stored : pwdHashes) {
            for (String[] variant : getHashVariants(password, stored)) {
                String label = variant[0];
                String hHex = variant[1].toLowerCase(Locale.ROOT);
                if (!tried.add(hHex)) {
                    continue;
                }
                if (stored != null && !stored.isEmpty() && !"stored".equals(label)
                        && !validatePass(password, stored, hHex, label)) {
                    continue;
                }
                byte[] key;
                try {
                    key = getAesPass(password, hHex);
                } catch (Exception e) {
                    continue;
                }
                byte[] plain = aesCbcDecryptB64(cipherB64, key);
                if (plain == null) {
                    continue;
                }
                String phrase = phraseFromBytes(plain, bip39);
                if (phrase != null) {
                    return phrase;
                }
            }
        }
        return null;
    }

    private static List<String[]> getHashVariants(String password, String storedHash) {
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        String sh = storedHash == null ? "" : storedHash.trim().toLowerCase(Locale.ROOT);
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        java.util.function.BiConsumer<String, String> add = (label, h) -> {
            h = h.toLowerCase(Locale.ROOT);
            if (HEX64.matcher(h).matches() && seen.add(h)) {
                out.add(new String[] {label, h});
            }
        };
        if (!sh.isEmpty()) {
            add.accept("stored", sh);
        }
        add.accept("keccak_pw", KeychainXml.toHex(BIP32Util.keccak256(pw)));
        try {
            add.accept("sha256_pw", KeychainXml.toHex(MessageDigest.getInstance("SHA-256").digest(pw)));
        } catch (Exception ignored) {
        }
        if (!sh.isEmpty()) {
            byte[] shb = sh.getBytes(StandardCharsets.UTF_8);
            add.accept("keccak_pw+hash", KeychainXml.toHex(BIP32Util.keccak256(concat(pw, shb))));
            add.accept("keccak_hash+pw", KeychainXml.toHex(BIP32Util.keccak256(concat(shb, pw))));
            try {
                add.accept("sha256_pw+hash",
                        KeychainXml.toHex(MessageDigest.getInstance("SHA-256").digest(concat(pw, shb))));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static boolean validatePass(String password, String storedHash, String hHex, String label) {
        String sh = storedHash == null ? "" : storedHash.trim().toLowerCase(Locale.ROOT);
        if (sh.isEmpty()) {
            return false;
        }
        String hh = hHex.toLowerCase(Locale.ROOT);
        if (hh.equals(sh)) {
            return true;
        }
        if (getHash(password, sh).equals(sh) && hh.equals(sh)) {
            return true;
        }
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        byte[] shb = sh.getBytes(StandardCharsets.UTF_8);
        if ("keccak_pw+hash".equals(label) && KeychainXml.toHex(BIP32Util.keccak256(concat(pw, shb))).equals(sh)) {
            return true;
        }
        if ("keccak_hash+pw".equals(label) && KeychainXml.toHex(BIP32Util.keccak256(concat(shb, pw))).equals(sh)) {
            return true;
        }
        try {
            if ("sha256_pw+hash".equals(label)
                    && KeychainXml.toHex(MessageDigest.getInstance("SHA-256").digest(concat(pw, shb))).equals(sh)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static String getHash(String password, String storedHash) {
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        String sh = storedHash == null ? "" : storedHash.trim().toLowerCase(Locale.ROOT);
        if (!sh.isEmpty()) {
            for (int n : new int[] {1, 1024, 10_000}) {
                if (KeychainXml.toHex(moreHash(n, pw)).equals(sh)) {
                    return sh;
                }
            }
            for (byte[] combo : new byte[][] {
                    concat(pw, sh.getBytes(StandardCharsets.UTF_8)),
                    concat(sh.getBytes(StandardCharsets.UTF_8), pw)
            }) {
                for (int n : new int[] {1, 1024}) {
                    if (KeychainXml.toHex(moreHash(n, combo)).equals(sh)) {
                        return sh;
                    }
                }
            }
        }
        return sh.isEmpty() ? KeychainXml.toHex(moreHash(1, pw)) : sh;
    }

    public static byte[] getAesPass(String password, String pwdHashHex) {
        String hHex = getHash(password, pwdHashHex);
        byte[] hb = moreHash(1, password.getBytes(StandardCharsets.UTF_8));
        byte[] aesPass = KeychainXml.fromHex(hHex);
        if (aesPass.length < 32) {
            aesPass = Arrays.copyOf(aesPass, 32);
        }
        System.arraycopy(hb, 0, aesPass, 0, Math.min(8, hb.length));
        System.arraycopy(hb, Math.max(0, hb.length - 8), aesPass, aesPass.length - 8, 8);
        return aesPass;
    }

    private static byte[] moreHash(int count, byte[] value) {
        byte[] v = value;
        for (int i = 0; i < count; i++) {
            v = BIP32Util.keccak256(v);
        }
        return Arrays.copyOf(v, 32);
    }

    private static byte[] aesCbcDecryptB64(String cipherB64, byte[] key) {
        try {
            byte[] raw = Base64.getDecoder().decode(cipherB64.trim());
            if (raw.length < 32) {
                return null;
            }
            byte[] iv = Arrays.copyOfRange(raw, 0, 16);
            byte[] ct = Arrays.copyOfRange(raw, 16, raw.length);
            for (boolean doUnpad : new boolean[] {true, false}) {
                try {
                    Cipher c = Cipher.getInstance(doUnpad ? "AES/CBC/PKCS5Padding" : "AES/CBC/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                    byte[] plain = c.doFinal(ct);
                    if (plain != null && plain.length > 0) {
                        return plain;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void collectCipherBlobs(Object obj, List<String> out) {
        if (obj instanceof JSONObject) {
            JSONObject jo = (JSONObject) obj;
            for (String k : jo.keySet()) {
                Object v = jo.get(k);
                String lk = k.toLowerCase(Locale.ROOT);
                if (("data".equals(lk) || "cipher".equals(lk) || "ciphertext".equals(lk) || "encrypted".equals(lk))
                        && v instanceof String) {
                    String s = ((String) v).trim();
                    if (s.length() >= 32 && B64ISH.matcher(s).matches()) {
                        out.add(s);
                    }
                }
                collectCipherBlobs(v, out);
            }
        } else if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            for (int i = 0; i < arr.size(); i++) {
                collectCipherBlobs(arr.get(i), out);
            }
        }
    }

    private static List<String[]> sqliteCipherRows(byte[] dbBytes) {
        List<String[]> rows = new ArrayList<>();
        PathTemp tmp = PathTemp.write(dbBytes, "okx-wallet", ".db");
        if (tmp == null) {
            return rows;
        }
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + tmp.path.toAbsolutePath())) {
            List<String> tables = new ArrayList<>();
            try (Statement st = con.createStatement();
                    ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String tbl : tables) {
                List<String> cols = new ArrayList<>();
                try (Statement st = con.createStatement();
                        ResultSet rs = st.executeQuery("PRAGMA table_info(\"" + tbl + "\")")) {
                    while (rs.next()) {
                        cols.add(rs.getString(2));
                    }
                } catch (Exception ignored) {
                    continue;
                }
                for (String col : cols) {
                    String cl = col.toLowerCase(Locale.ROOT);
                    if (!("data".equals(cl) || "privkey".equals(cl) || "encjwt".equals(cl)
                            || "encryptsharekey".equals(cl))) {
                        continue;
                    }
                    try (Statement st = con.createStatement();
                            ResultSet rs = st.executeQuery(
                                    "SELECT \"" + col + "\" FROM \"" + tbl + "\" WHERE \"" + col
                                            + "\" IS NOT NULL AND \"" + col + "\" != ''")) {
                        while (rs.next()) {
                            String s = String.valueOf(rs.getObject(1)).trim();
                            if (s.length() >= 32 && B64ISH.matcher(s).matches()) {
                                rows.add(new String[] {tbl + "." + col, s});
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
        return rows;
    }

    private static List<String> collectSqlitePwdHashes(byte[] dbBytes) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        PathTemp tmp = PathTemp.write(dbBytes, "okx-hash", ".db");
        if (tmp == null) {
            return out;
        }
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + tmp.path.toAbsolutePath());
                Statement st = con.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT _value FROM keyValues WHERE _key=\"time\"")) {
                while (rs.next()) {
                    String val = rs.getString(1);
                    if (val == null) {
                        continue;
                    }
                    Object parsed = null;
                    try {
                        parsed = JSON.parse(JSON.parse(val).toString());
                    } catch (Exception e) {
                        try {
                            parsed = JSON.parse(val);
                        } catch (Exception ignored) {
                        }
                    }
                    if (parsed instanceof JSONArray) {
                        JSONArray arr = (JSONArray) parsed;
                        for (int i = 0; i < arr.size(); i++) {
                            Object item = arr.get(i);
                            if (item instanceof String && HEX64.matcher((String) item).matches()) {
                                String h = ((String) item).toLowerCase(Locale.ROOT);
                                if (seen.add(h)) {
                                    out.add(h);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            try (ResultSet tables = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                List<String> names = new ArrayList<>();
                while (tables.next()) {
                    names.add(tables.getString(1));
                }
                for (String tbl : names) {
                    try (ResultSet rs = st.executeQuery("SELECT * FROM \"" + tbl + "\"")) {
                        int cols = rs.getMetaData().getColumnCount();
                        while (rs.next()) {
                            for (int i = 1; i <= cols; i++) {
                                Object val = rs.getObject(i);
                                if (val instanceof String) {
                                    Matcher m = HEX64_FIND.matcher((String) val);
                                    while (m.find()) {
                                        String h = m.group().toLowerCase(Locale.ROOT);
                                        if (seen.add(h)) {
                                            out.add(h);
                                        }
                                    }
                                }
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

    private static String phraseFromBytes(byte[] plain, Bip39Util bip39) {
        if (plain == null || plain.length == 0) {
            return null;
        }
        String text = new String(plain, StandardCharsets.UTF_8).trim().replace("\"", "");
        String phrase = bip39.searchPhrase(text.replace(',', ' '));
        if (phrase != null) {
            return phrase;
        }
        try {
            Object obj = JSON.parse(text);
            if (obj instanceof JSONObject) {
                JSONObject jo = (JSONObject) obj;
                for (String k : new String[] {"mnemonic", "seed", "phrase", "seedPhrase", "recoveryPhrase"}) {
                    String v = jo.getString(k);
                    if (v != null && bip39.validateMnemonic(v)) {
                        return v.trim();
                    }
                }
                JSONObject data = jo.getJSONObject("data");
                if (data != null) {
                    for (String k : new String[] {"mnemonic", "seed", "phrase"}) {
                        String v = data.getString(k);
                        if (v != null && bip39.validateMnemonic(v)) {
                            return v.trim();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
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

    /** temp sqlite helper */
    static final class PathTemp {
        final java.nio.file.Path path;

        PathTemp(java.nio.file.Path path) {
            this.path = path;
        }

        static PathTemp write(byte[] data, String prefix, String suffix) {
            try {
                java.nio.file.Path p = java.nio.file.Files.createTempFile(prefix, suffix);
                java.nio.file.Files.write(p, data);
                return new PathTemp(p);
            } catch (Exception e) {
                return null;
            }
        }

        void delete() {
            try {
                java.nio.file.Files.deleteIfExists(path);
            } catch (Exception ignored) {
            }
        }
    }
}
