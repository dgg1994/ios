package com.admin.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;

import com.admin.util.Bip39Util;

/**
 * Global Wallet / TokenPocket：F4SeCyr 密码解密，再用同一密钥打开 WCDB/SQLCipher。
 */
public final class GlobalWallet {

    private static final String[] AGRP_MARKERS = {"com.global.wallet.ios", "global.wallet"};

    /** off, saltLen, ivLen, iters[], mode */
    private static final Object[][] F4_FAST = {
            {0, 16, 16, new int[] {600_000, 100_000, 10_000}, "cbc"},
            {0, 16, 12, new int[] {100_000, 600_000, 10_000}, "gcm"},
            {0, 32, 12, new int[] {100_000, 10_000}, "gcm"},
            {0, 16, 16, new int[] {100_000, 10_000}, "gcm"},
    };

    private GlobalWallet() {
    }

    public static Map<String, Object> unlock(List<byte[]> f4Blobs, List<Path> sqliteFiles, Path keychainPath,
            String password, Bip39Util bip39) {
        password = password == null ? "" : password.trim();
        if (password.isEmpty()) {
            return fail("请输入密码");
        }
        Map<String, Object> kc = parseKeychain(keychainPath);
        @SuppressWarnings("unchecked")
        List<byte[]> kcBlobs = (List<byte[]>) kc.getOrDefault("blobs", List.of());
        List<byte[]> blobs = new ArrayList<>();
        if (f4Blobs != null) {
            blobs.addAll(f4Blobs);
        }
        for (byte[] b : kcBlobs) {
            if (blobs.stream().noneMatch(x -> Arrays.equals(x, b))) {
                blobs.add(b);
            }
        }
        boolean hasSqlite = sqliteFiles != null && !sqliteFiles.isEmpty();
        if (blobs.isEmpty() && !hasSqlite) {
            return fail("未找到 F4SeCyr 或 db/*.sqlite3");
        }

        List<byte[]> salts = new ArrayList<>();
        if (kc.get("udid") instanceof String) {
            byte[] ud = ((String) kc.get("udid")).getBytes(StandardCharsets.UTF_8);
            salts.add(ud);
            salts.add(new String(ud, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            salts.add(new String(ud, StandardCharsets.UTF_8).replace("-", "").getBytes(StandardCharsets.UTF_8));
        }
        salts.add("com.global.wallet.ios".getBytes(StandardCharsets.UTF_8));
        salts.add("f4secyr".getBytes(StandardCharsets.UTF_8));

        boolean f4Ok = false;
        List<byte[]> f4Plains = new ArrayList<>();
        for (byte[] blob : blobs) {
            byte[] plain = decryptF4secyr(blob, password, salts);
            if (plain == null) {
                continue;
            }
            f4Ok = true;
            f4Plains.add(plain);
            String phrase = phraseFromPlain(plain, bip39);
            if (phrase != null) {
                return ok(phrase);
            }
        }

        List<Object> materials = new ArrayList<>();
        for (byte[] plain : f4Plains) {
            materials.add(Arrays.copyOf(plain, Math.min(32, plain.length)));
            if (plain.length >= 64) {
                materials.add(Arrays.copyOfRange(plain, 32, 64));
                materials.add(Arrays.copyOf(plain, 64));
            }
            materials.add(plain);
            materials.add(sha256(Arrays.copyOf(plain, Math.min(32, plain.length))));
        }
        materials.add(password);
        materials.add(sha256(password.getBytes(StandardCharsets.UTF_8)));
        materials.add(KeychainXml.toHex(sha256(password.getBytes(StandardCharsets.UTF_8))));
        if (kc.get("udid") instanceof String) {
            byte[] ud = ((String) kc.get("udid")).getBytes(StandardCharsets.UTF_8);
            materials.add(sha256(concat(password.getBytes(StandardCharsets.UTF_8), ud)));
        }

        if (hasSqlite) {
            for (Path db : sqliteFiles) {
                String phrase = WcdbSqlCipher.tryPhrase(db, materials, bip39);
                if (phrase != null) {
                    return ok(phrase);
                }
            }
        }

        if (f4Ok) {
            boolean hasKcF4 = !kcBlobs.isEmpty();
            if (!hasKcF4) {
                return fail("密码已解开 F4SeCyr，但无法从 WCDB 取出助记词；请补充 Keychain 中 account=f4secyr 完整条目后重试");
            }
            return fail("密码已解开 F4SeCyr，但 WCDB 密钥不匹配；请确认 Keychain 与 Documents 来自同一设备备份");
        }
        if (hasSqlite && blobs.isEmpty()) {
            return fail("仅有 WCDB/SQLCipher 库，当前密钥打不开（密码错误或与备份不是同一设备）");
        }
        return fail("密码错误");
    }

    public static Map<String, Object> parseKeychain(Path keychainPath) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("udid", null);
        out.put("blobs", new ArrayList<byte[]>());
        out.put("texts", new ArrayList<String>());
        if (keychainPath == null) {
            return out;
        }
        List<byte[]> blobs = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (KeychainXml.Item item : KeychainXml.parse(keychainPath)) {
            String hay = (item.agrp + " " + item.acct + " " + item.svce).toLowerCase(Locale.ROOT);
            boolean match = false;
            for (String m : AGRP_MARKERS) {
                if (hay.contains(m)) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                continue;
            }
            if ("global.udid".equalsIgnoreCase(item.acct) || "global.udid".equalsIgnoreCase(item.svce)) {
                if (!item.text.isEmpty()) {
                    out.put("udid", item.text.trim());
                }
            }
            if (hay.contains("firebase") || hay.contains("google.iid") || "_pfo".equals(item.acct)) {
                continue;
            }
            String lowAcct = item.acct.toLowerCase(Locale.ROOT);
            if (lowAcct.contains("f4secyr") || hay.contains("f4sec")) {
                if (item.raw.length > 0) {
                    blobs.add(item.raw);
                }
            } else if (item.raw.length >= 32 && item.raw.length <= 2048 && item.raw.length != 65) {
                if (!(item.raw.length >= 6 && item.raw[0] == 'b' && item.raw[1] == 'p')
                        && !containsFir(item.raw)) {
                    blobs.add(item.raw);
                }
            }
            if (!item.text.isEmpty() && !item.text.toLowerCase(Locale.ROOT).contains("firebase")) {
                texts.add(item.text.trim());
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        List<byte[]> uniq = new ArrayList<>();
        for (byte[] b : blobs) {
            String h = KeychainXml.toHex(b);
            if (seen.add(h)) {
                uniq.add(b);
            }
        }
        out.put("blobs", uniq);
        out.put("texts", texts);
        return out;
    }

    public static byte[] decryptF4secyr(byte[] raw, String password, List<byte[]> extraSalts) {
        if (raw == null || raw.length < 33) {
            return null;
        }
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        for (Object[] layout : F4_FAST) {
            int off = (Integer) layout[0];
            int saltLen = (Integer) layout[1];
            int ivLen = (Integer) layout[2];
            int[] iters = (int[]) layout[3];
            String mode = (String) layout[4];
            int end = off + saltLen + ivLen;
            if (end + 17 > raw.length) {
                continue;
            }
            byte[] salt = Arrays.copyOfRange(raw, off, off + saltLen);
            byte[] iv = Arrays.copyOfRange(raw, off + saltLen, end);
            byte[] ct = Arrays.copyOfRange(raw, end, raw.length);
            for (int it : iters) {
                byte[] key = pbkdf2(pw, salt, it, 32);
                if ("gcm".equals(mode)) {
                    byte[] pt = aesGcm(key, Arrays.copyOf(iv, Math.min(12, iv.length)), ct);
                    if (pt != null) {
                        return pt;
                    }
                    if (iv.length > 12) {
                        pt = aesGcm(key, iv, ct);
                        if (pt != null) {
                            return pt;
                        }
                    }
                }
                if ("cbc".equals(mode) && ivLen >= 16 && ct.length % 16 == 0) {
                    byte[] pt = aesCbc(key, Arrays.copyOf(iv, 16), ct);
                    if (pt != null) {
                        return pt;
                    }
                }
            }
        }
        int limit = extraSalts == null ? 0 : Math.min(6, extraSalts.size());
        for (int i = 0; i < limit; i++) {
            byte[] slt = extraSalts.get(i);
            if (slt == null || slt.length == 0) {
                continue;
            }
            for (int it : new int[] {10_000, 1, 100_000}) {
                byte[] key = pbkdf2(pw, slt, it, 32);
                if (raw.length >= 32 && (raw.length - 16) % 16 == 0) {
                    byte[] pt = aesCbc(key, Arrays.copyOfRange(raw, 0, 16),
                            Arrays.copyOfRange(raw, 16, raw.length));
                    if (pt != null && (phraseLooksOk(pt) || pt.length == 32 || pt.length == 64
                            || pt.length == 48 || pt.length == 80)) {
                        return pt;
                    }
                }
                if (raw.length >= 16 + 12 + 17) {
                    byte[] iv = Arrays.copyOfRange(raw, 16, 28);
                    byte[] ct = Arrays.copyOfRange(raw, 28, raw.length);
                    byte[] pt = aesGcm(key, iv, ct);
                    if (pt != null) {
                        return pt;
                    }
                }
            }
        }
        return null;
    }

    private static boolean phraseLooksOk(byte[] pt) {
        String t = new String(pt, StandardCharsets.UTF_8).trim();
        return t.split("\\s+").length >= 12;
    }

    private static String phraseFromPlain(byte[] plain, Bip39Util bip39) {
        if (plain == null) {
            return null;
        }
        if (plain.length == 16 || plain.length == 20 || plain.length == 24
                || plain.length == 28 || plain.length == 32) {
            String fromEnt = bip39.entropyToMnemonic(plain);
            if (fromEnt != null && bip39.validateMnemonic(fromEnt)) {
                return fromEnt;
            }
        }
        String text = new String(plain, StandardCharsets.UTF_8).replace("\0", "").trim();
        if (bip39.validateMnemonic(text)) {
            return text;
        }
        return bip39.searchPhrase(text);
    }

    private static byte[] aesGcm(byte[] key, byte[] nonce, byte[] ctWithTag) {
        if (ctWithTag.length <= 16) {
            return null;
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return c.doFinal(ctWithTag);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] aesCbc(byte[] key, byte[] iv, byte[] ct) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(ct);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] pbkdf2(byte[] password, byte[] salt, int iter, int dklen) {
        PKCS5S2ParametersGenerator gen = new PKCS5S2ParametersGenerator(new SHA256Digest());
        gen.init(password, salt, iter);
        return ((KeyParameter) gen.generateDerivedParameters(dklen * 8)).getKey();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static boolean containsFir(byte[] raw) {
        byte[] needle = "FIR".getBytes(StandardCharsets.US_ASCII);
        int lim = Math.min(raw.length - 3, 40);
        for (int i = 0; i < lim; i++) {
            if (raw[i] == needle[0] && raw[i + 1] == needle[1] && raw[i + 2] == needle[2]) {
                return true;
            }
        }
        return false;
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
