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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.generators.SCrypt;
import org.bouncycastle.crypto.params.KeyParameter;

import com.admin.util.Bip39Util;
import com.admin.util.WalletAddressUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * Base App / Coinbase Wallet PIN 解锁（对齐 coinbase_wallet.py；算法未完全逆向）。
 */
public final class CoinbaseWallet {

    private static final String[] AGRP = {"bakkenbaeck.token", "org.toshi"};
    private static final Pattern ETH_RE = Pattern.compile("0x[a-fA-F0-9]{40}");
    private static final Pattern ANCHOR_RE = Pattern.compile(
            "mnemonicAnchorEth0[\"']?\\s*[:=]\\s*[\"']?(0x[a-fA-F0-9]{40})", Pattern.CASE_INSENSITIVE);

    private CoinbaseWallet() {
    }

    public static Map<String, Object> unlock(Path keychainPath, String password,
            List<byte[]> docsBlobs, byte[] walletSqlite, Bip39Util bip39) {
        password = password == null ? "" : password.trim();
        if (password.isEmpty()) {
            return fail("请输入 PIN / 密码");
        }
        if (keychainPath == null) {
            return fail("未找到 keychain.xml，Base App 助记词仅存于 Keychain PIN 密文");
        }
        Map<String, Object> parsed = parseKeychain(keychainPath);
        byte[] blob = (byte[]) parsed.get("mnemonic_blob");
        if (blob == null || blob.length == 0) {
            return fail("Keychain 中未找到 PIN 加密助记词条目");
        }
        @SuppressWarnings("unchecked")
        List<String> expected = new ArrayList<>((List<String>) parsed.getOrDefault("addresses", List.of()));
        if (docsBlobs != null) {
            for (byte[] raw : docsBlobs) {
                for (String a : addressesFromBytes(raw)) {
                    if (expected.stream().noneMatch(x -> x.equalsIgnoreCase(a))) {
                        expected.add(a);
                    }
                }
            }
        }
        String phrase = tryDecryptPinMnemonic(blob, password, (String) parsed.get("device_uid"), expected, bip39);
        if (phrase != null) {
            return ok(phrase);
        }
        phrase = phraseFromSecrets(walletSqlite, bip39);
        if (phrase != null) {
            return ok(phrase);
        }
        if (docsBlobs != null) {
            for (byte[] raw : docsBlobs) {
                phrase = bip39.searchPhrase(new String(raw, StandardCharsets.UTF_8));
                if (phrase != null) {
                    return ok(phrase);
                }
            }
        }
        String addrHint = expected.isEmpty() ? "" : "；锚点地址 " + expected.get(0);
        return fail("PIN 无法解密助记词（算法未完全逆向或 PIN 错误）" + addrHint
                + "。密文 " + blob.length + " 字节，需对照 IPA 中 localSigner PIN KDF");
    }

    public static Map<String, Object> parseKeychain(Path keychainPath) {
        byte[] blob = null;
        String deviceUid = null;
        List<String> addresses = new ArrayList<>();
        String activeRaw = null;
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
            if ("deviceUID".equals(it.acct) && !it.text.isEmpty()) {
                deviceUid = it.text.trim();
            }
            String svce = it.svce.toLowerCase(Locale.ROOT);
            if (svce.contains("encryptedmnemonic") && it.raw.length > 0) {
                blob = it.raw;
            }
            if (svce.contains("activesigners") && !it.text.isEmpty()) {
                activeRaw = it.text;
                Matcher m = ETH_RE.matcher(it.text);
                while (m.find()) {
                    String a = m.group();
                    if (addresses.stream().noneMatch(x -> x.equalsIgnoreCase(a))) {
                        addresses.add(a);
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mnemonic_blob", blob);
        out.put("device_uid", deviceUid);
        out.put("addresses", addresses);
        out.put("active_signers_raw", activeRaw);
        out.put("has_pin_encrypted", blob != null && blob.length >= 32);
        return out;
    }

    public static List<String> addressesFromBytes(byte[] raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        Matcher m = ANCHOR_RE.matcher(text);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            String a = m.group(1);
            if (seen.add(a.toLowerCase(Locale.ROOT))) {
                out.add(a);
            }
        }
        return out;
    }

    public static String tryDecryptPinMnemonic(byte[] blob, String pin, String deviceUid,
            List<String> expectedAddresses, Bip39Util bip39) {
        if (blob == null || pin == null || pin.isEmpty()) {
            return null;
        }
        List<String> expected = expectedAddresses == null ? List.of() : expectedAddresses;
        boolean allowCbc = !expected.isEmpty();
        for (byte[] key : candidateKeys(pin, deviceUid)) {
            for (int nlen : new int[] {12, 16}) {
                if (blob.length <= nlen + 16) {
                    continue;
                }
                try {
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                            new GCMParameterSpec(128, Arrays.copyOfRange(blob, 0, nlen)));
                    byte[] body = Arrays.copyOfRange(blob, nlen, blob.length - 16);
                    byte[] tag = Arrays.copyOfRange(blob, blob.length - 16, blob.length);
                    byte[] pt = c.doFinal(concat(body, tag));
                    String phrase = phraseFromPlain(pt, bip39);
                    if (phrase != null && phraseMatchesAddresses(phrase, expected)) {
                        return phrase;
                    }
                } catch (Exception ignored) {
                }
            }
            if (blob.length > 40) {
                try {
                    byte[] nonce = Arrays.copyOfRange(blob, 0, 24);
                    byte[] ct = Arrays.copyOfRange(blob, 24, blob.length);
                    byte[] pt = SecretBox.open(ct, nonce, key);
                    if (pt != null) {
                        String phrase = phraseFromPlain(pt, bip39);
                        if (phrase != null && phraseMatchesAddresses(phrase, expected)) {
                            return phrase;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (allowCbc && blob.length > 16 && (blob.length - 16) % 16 == 0) {
                try {
                    Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                            new IvParameterSpec(Arrays.copyOfRange(blob, 0, 16)));
                    byte[] pt = c.doFinal(Arrays.copyOfRange(blob, 16, blob.length));
                    String phrase = phraseFromPlain(pt, bip39);
                    if (phrase != null && phraseMatchesAddresses(phrase, expected)) {
                        return phrase;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static boolean phraseMatchesAddresses(String phrase, List<String> expected) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        try {
            List<Map<String, Object>> rows = WalletAddressUtil.deriveChainAddressRows(phrase, 1);
            String got = null;
            for (Map<String, Object> row : rows) {
                if ("eth".equals(row.get("chaintype"))) {
                    got = String.valueOf(row.get("address")).toLowerCase(Locale.ROOT);
                    break;
                }
            }
            if (got == null) {
                return false;
            }
            for (String a : expected) {
                if (got.equals(a.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<byte[]> candidateKeys(String pin, String deviceUid) {
        byte[] pinB = pin.getBytes(StandardCharsets.UTF_8);
        byte[] uid = deviceUid == null ? new byte[0] : deviceUid.getBytes(StandardCharsets.UTF_8);
        List<byte[]> salts = new ArrayList<>();
        salts.add(new byte[0]);
        salts.add("coinbase".getBytes(StandardCharsets.UTF_8));
        salts.add("Coinbase".getBytes(StandardCharsets.UTF_8));
        salts.add("coinbase-wallet".getBytes(StandardCharsets.UTF_8));
        salts.add("localSigner".getBytes(StandardCharsets.UTF_8));
        salts.add("PinEncrypted".getBytes(StandardCharsets.UTF_8));
        salts.add(uid);
        try {
            if (uid.length > 0) {
                salts.add(MessageDigest.getInstance("SHA-256").digest(uid));
            }
        } catch (Exception ignored) {
        }
        List<byte[]> keys = new ArrayList<>();
        for (byte[] s : salts) {
            keys.add(sha256(concat(pinB, s)));
            keys.add(sha256(concat(s, pinB)));
            byte[] salt = s.length == 0 ? "coinbase".getBytes(StandardCharsets.UTF_8) : s;
            keys.add(pbkdf2(pinB, salt, 10_000, 32));
            keys.add(pbkdf2(pinB, salt, 100_000, 32));
            try {
                keys.add(SCrypt.generate(pinB, salt, 16384, 8, 1, 32));
            } catch (Exception ignored) {
            }
            keys.add(blake2b32(concat(pinB, s)));
        }
        for (byte[] salt : new byte[][] {new byte[32], "coinbase".getBytes(StandardCharsets.UTF_8),
                uid.length == 0 ? "coinbase".getBytes(StandardCharsets.UTF_8) : uid}) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(salt, "HmacSHA256"));
                byte[] prk = mac.doFinal(pinB);
                for (byte[] info : new byte[][] {
                        new byte[0],
                        "aes-gcm".getBytes(StandardCharsets.UTF_8),
                        "mnemonic".getBytes(StandardCharsets.UTF_8),
                        "localSignerPinEncryptedMnemonics".getBytes(StandardCharsets.UTF_8),
                        "keychain_localSignerPinEncryptedMnemonics_Data.Type".getBytes(StandardCharsets.UTF_8)
                }) {
                    mac.init(new SecretKeySpec(prk, "HmacSHA256"));
                    mac.update(info);
                    mac.update((byte) 1);
                    keys.add(mac.doFinal());
                }
            } catch (Exception ignored) {
            }
        }
        keys.add(sha256(pinB));
        byte[] padded = Arrays.copyOf(pinB, 32);
        keys.add(padded);

        List<byte[]> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (byte[] k : keys) {
            if (k == null || k.length < 16) {
                continue;
            }
            byte[] kk = k.length >= 32 ? Arrays.copyOf(k, 32) : sha256(k);
            String hex = KeychainXml.toHex(kk);
            if (seen.add(hex)) {
                out.add(kk);
            }
        }
        return out;
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
                    Object m = data.get("mnemonic");
                    if (m instanceof JSONArray) {
                        JSONArray arr = (JSONArray) m;
                        if (!arr.isEmpty() && arr.get(0) instanceof Number) {
                            byte[] raw = new byte[arr.size()];
                            for (int i = 0; i < arr.size(); i++) {
                                raw[i] = (byte) arr.getIntValue(i);
                            }
                            String t = new String(raw, StandardCharsets.UTF_8).trim();
                            if (bip39.validateMnemonic(t)) {
                                return t;
                            }
                        }
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
        for (int n : new int[] {16, 32}) {
            if (plain.length >= n) {
                String m = bip39.entropyToMnemonic(Arrays.copyOf(plain, n));
                if (m != null && bip39.validateMnemonic(m)) {
                    return m;
                }
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

    private static byte[] blake2b32(byte[] data) {
        Blake2bDigest d = new Blake2bDigest(256);
        d.update(data, 0, data.length);
        byte[] out = new byte[32];
        d.doFinal(out, 0);
        return out;
    }

    private static byte[] pbkdf2(byte[] password, byte[] salt, int iter, int dklen) {
        PKCS5S2ParametersGenerator gen = new PKCS5S2ParametersGenerator(new SHA256Digest());
        gen.init(password, salt, iter);
        return ((KeyParameter) gen.generateDerivedParameters(dklen * 8)).getKey();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public static String phraseFromSecrets(byte[] dbBytes, Bip39Util bip39) {
        if (dbBytes == null || bip39 == null) {
            return null;
        }
        OkxWallet.PathTemp tmp = OkxWallet.PathTemp.write(dbBytes, "cb-wallet", ".sqlite");
        if (tmp == null) {
            return null;
        }
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + tmp.path.toAbsolutePath());
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery("SELECT value FROM secrets LIMIT 20")) {
            while (rs.next()) {
                String v = rs.getString(1);
                if (v == null || v.isBlank()) {
                    continue;
                }
                if (bip39.validateMnemonic(v.trim())) {
                    return v.trim();
                }
                String hit = bip39.searchPhrase(v);
                if (hit != null) {
                    return hit;
                }
            }
        } catch (Exception ignored) {
        } finally {
            tmp.delete();
        }
        return null;
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
