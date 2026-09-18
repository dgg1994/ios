package com.admin.crypto;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;

import com.admin.util.Bip39Util;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * Coin98 MMKV 解密与助记词提取（对齐 coin98_mmkv.py + wallet_unlock._unlock_coin98）。
 */
public final class Coin98Mmkv {

    private static final String COIN98_AGRP = "4jlum47s98.coin98.crypto.finance.insights";
    private static final Pattern ITEM_SPLIT = Pattern.compile("(?i)<item>");
    private static final Pattern ACCT = Pattern.compile("(?i)<acct>([^<]+)</acct>");
    private static final Pattern SVCE = Pattern.compile("(?i)<svce>([^<]+)</svce>");
    private static final Pattern VDATA = Pattern.compile("(?i)<v_Data bin=\"1\">([^<]+)</v_Data>");
    private static final Pattern MNEMONIC_RE =
            Pattern.compile("\\b([a-z]{3,8}(?:\\s+[a-z]{3,8}){11,23})\\b");

    private Coin98Mmkv() {
    }

    public static Map<String, Object> parseKeychain(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("auth_mmkv_key", null);
        out.put("wallet_encryption_key", null);
        out.put("secure_backup", new ArrayList<JSONObject>());
        if (text == null) {
            return out;
        }
        for (String block : ITEM_SPLIT.split(text)) {
            if (!block.toLowerCase(Locale.ROOT).contains(COIN98_AGRP)) {
                continue;
            }
            Matcher dataM = VDATA.matcher(block);
            if (!dataM.find()) {
                continue;
            }
            Matcher acctM = ACCT.matcher(block);
            Matcher svceM = SVCE.matcher(block);
            String acct = acctM.find() ? acctM.group(1).trim() : "";
            String svce = svceM.find() ? svceM.group(1).trim() : "";
            byte[] raw;
            try {
                raw = java.util.Base64.getDecoder().decode(dataM.group(1).trim());
            } catch (Exception e) {
                continue;
            }
            String decoded;
            try {
                decoded = new String(raw, StandardCharsets.UTF_8);
            } catch (Exception e) {
                decoded = KeychainXml.toHex(raw);
            }
            if ("coin98_auth_mmkv_key".equals(acct) || "auth_mmkv_encryption_key".equals(svce)) {
                out.put("auth_mmkv_key", decoded.replace("\0", "").trim());
            } else if ("coin98_secure_storage".equals(acct) || "wallet_encryption_key".equals(svce)) {
                String hexKey = decoded.trim().replace(" ", "");
                if (hexKey.length() == 64 && hexKey.matches("[0-9a-fA-F]+")) {
                    out.put("wallet_encryption_key", KeychainXml.fromHex(hexKey));
                }
            } else if ("WALLET_SECURE_BACKUP".equals(acct) && "rn-secure-storage".equals(svce)) {
                try {
                    Object items = JSON.parse(decoded);
                    if (items instanceof JSONArray) {
                        List<JSONObject> list = new ArrayList<>();
                        JSONArray arr = (JSONArray) items;
                        for (int i = 0; i < arr.size(); i++) {
                            if (arr.get(i) instanceof JSONObject) {
                                list.add(arr.getJSONObject(i));
                            }
                        }
                        out.put("secure_backup", list);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    public static Map<String, Object> unlock(byte[] enc, byte[] crc, byte[] plainMmkv, String password,
            String keychainText, Bip39Util bip39,
            java.util.function.BiFunction<JSONObject, String, byte[]> decryptMetamaskVault) {
        Map<String, Object> kc = parseKeychain(keychainText);
        String authKey = (String) kc.get("auth_mmkv_key");
        byte[] walletKey32 = kc.get("wallet_encryption_key") instanceof byte[]
                ? (byte[]) kc.get("wallet_encryption_key") : null;

        Map<String, byte[]> mmkvMap = null;
        if (plainMmkv != null && plainMmkv.length > 0) {
            mmkvMap = parseMmkvMap(plainMmkv);
        }
        if (enc != null && crc != null && enc.length > 0 && crc.length > 0) {
            List<Object> candidates = new ArrayList<>();
            if (authKey != null) {
                candidates.add(authKey);
            }
            if (walletKey32 != null) {
                candidates.add(walletKey32);
            }
            candidates.add(password);
            try {
                candidates.add(MessageDigest.getInstance("SHA-256")
                        .digest(password.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception ignored) {
            }
            Object[] opened = decryptWithCandidates(enc, crc, candidates);
            if (opened == null) {
                if (authKey == null) {
                    return fail("MMKV 已加密，需同设备 Keychain（coin98_auth_mmkv_key）");
                }
                return fail("MMKV 解密失败（Keychain 密钥与 Documents 可能不匹配）");
            }
            mmkvMap = parseMmkvMap((byte[]) opened[0]);
        }
        if (mmkvMap == null || mmkvMap.isEmpty()) {
            return fail("未找到 mmkv.default / mmkv.default.enc");
        }
        String phrase = extractPhraseFromMmkvMap(mmkvMap, password, walletKey32, bip39, decryptMetamaskVault);
        if (phrase != null) {
            return ok(phrase);
        }
        @SuppressWarnings("unchecked")
        List<JSONObject> backup = (List<JSONObject>) kc.get("secure_backup");
        if (backup != null) {
            for (JSONObject item : backup) {
                String m = item.getString("mnemonic");
                if (m != null && m.trim().split("\\s+").length >= 12) {
                    return ok(m.trim());
                }
            }
        }
        return fail("密码错误");
    }

    public static byte[] mmkvIvFromCrc(byte[] crcBytes) {
        if (crcBytes == null || crcBytes.length < 28) {
            return null;
        }
        return Arrays.copyOfRange(crcBytes, 12, 28);
    }

    public static byte[] normalizeAesKey(Object key, boolean aes256) {
        byte[] kb;
        if (key instanceof String) {
            kb = ((String) key).getBytes(StandardCharsets.UTF_8);
        } else if (key instanceof byte[]) {
            kb = (byte[]) key;
        } else {
            return new byte[aes256 ? 32 : 16];
        }
        int need = aes256 ? 32 : 16;
        if (kb.length > need) {
            return Arrays.copyOf(kb, need);
        }
        return Arrays.copyOf(kb, need);
    }

    public static byte[] decryptMmkvEnc(byte[] enc, byte[] crc, Object key, boolean aes256) throws Exception {
        byte[] iv = mmkvIvFromCrc(crc);
        if (iv == null) {
            throw new IllegalArgumentException("CRC 文件过短，无法读取 IV");
        }
        if (enc.length < 5) {
            throw new IllegalArgumentException("MMKV enc 文件过短");
        }
        byte[] aesKey = normalizeAesKey(key, aes256);
        Cipher c = Cipher.getInstance("AES/CFB/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
        byte[] body = c.doFinal(Arrays.copyOfRange(enc, 4, enc.length));
        byte[] out = new byte[4 + body.length];
        System.arraycopy(enc, 0, out, 0, 4);
        System.arraycopy(body, 0, out, 4, body.length);
        return out;
    }

    public static Object[] decryptWithCandidates(byte[] enc, byte[] crc, List<Object> keys) {
        Object[] best = null;
        int bestQ = -1;
        for (Object key : keys) {
            if (key == null) {
                continue;
            }
            for (boolean aes256 : new boolean[] {false, true}) {
                try {
                    byte[] plain = decryptMmkvEnc(enc, crc, key, aes256);
                    int quality = mmkvMapQuality(parseMmkvMap(plain));
                    if (quality > bestQ) {
                        bestQ = quality;
                        best = new Object[] {plain, key};
                    }
                    if (quality >= 10) {
                        return best;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return bestQ > 0 ? best : null;
    }

    public static Map<String, byte[]> parseMmkvMap(byte[] raw) {
        Map<String, byte[]> decoded = new LinkedHashMap<>();
        if (raw == null || raw.length < 5) {
            return decoded;
        }
        ByteArrayInputStream f = new ByteArrayInputStream(raw);
        f.skip(4);
        decodeUnsignedVarint(f);
        while (f.available() > 1) {
            long keyLen = decodeUnsignedVarint(f);
            if (keyLen <= 0 || keyLen > 4096) {
                break;
            }
            byte[] keyBytes = readExact(f, (int) keyLen);
            if (keyBytes == null) {
                break;
            }
            long valLen = decodeUnsignedVarint(f);
            if (valLen < 0) {
                break;
            }
            if (valLen == 0) {
                continue;
            }
            byte[] val = readExact(f, (int) valLen);
            if (val == null) {
                break;
            }
            try {
                decoded.put(new String(keyBytes, StandardCharsets.UTF_8), val);
            } catch (Exception ignored) {
            }
        }
        return decoded;
    }

    public static String decodeMmkvString(byte[] value) {
        if (value == null || value.length == 0) {
            return null;
        }
        ByteArrayInputStream buf = new ByteArrayInputStream(value);
        long ln = decodeUnsignedVarint(buf);
        if (ln <= 0) {
            try {
                return new String(value, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }
        try {
            return new String(buf.readNBytes((int) ln), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static int mmkvMapQuality(Map<String, byte[]> m) {
        if (m == null || m.isEmpty()) {
            return 0;
        }
        int score = m.size();
        String joined = String.join(" ", m.keySet()).toLowerCase(Locale.ROOT);
        for (String token : new String[] {"has_wallet", "persist:", "wallet", "superwallet", "mnemonic"}) {
            if (joined.contains(token)) {
                score += 10;
            }
        }
        return score;
    }

    private static long decodeUnsignedVarint(ByteArrayInputStream buf) {
        int shift = 0;
        long result = 0;
        while (true) {
            int b = buf.read();
            if (b < 0) {
                return -1;
            }
            result |= (long) (b & 0x7f) << shift;
            shift += 7;
            if ((b & 0x80) == 0) {
                return result & 0xffffffffL;
            }
        }
    }

    private static byte[] readExact(ByteArrayInputStream in, int n) {
        if (n < 0) {
            return null;
        }
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(out, off, n - off);
            if (r < 0) {
                return null;
            }
            off += r;
        }
        return out;
    }

    private static String extractPhraseFromMmkvMap(Map<String, byte[]> mmkvMap, String password,
            byte[] walletKey32, Bip39Util bip39,
            java.util.function.BiFunction<JSONObject, String, byte[]> decryptMetamaskVault) {
        for (Map.Entry<String, byte[]> e : mmkvMap.entrySet()) {
            String text = decodeMmkvString(e.getValue());
            if (text == null) {
                text = new String(e.getValue(), StandardCharsets.UTF_8);
            }
            if (password != null) {
                byte[] blob = text.getBytes(StandardCharsets.UTF_8);
                String got = tryDecryptWalletBlob(blob, password, walletKey32, bip39, decryptMetamaskVault);
                if (got != null) {
                    return got;
                }
            }
            String phrase = scanBytesForPhrase(text.getBytes(StandardCharsets.UTF_8), bip39);
            if (phrase != null) {
                return phrase;
            }
            if (text.toLowerCase(Locale.ROOT).contains("mnemonic")
                    || text.toLowerCase(Locale.ROOT).contains("seedphrase")) {
                try {
                    Object obj = JSON.parse(text);
                    phrase = phraseFromJsonObj(obj);
                    if (phrase != null) {
                        return phrase;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        // join values
        StringBuilder sb = new StringBuilder();
        for (byte[] v : mmkvMap.values()) {
            sb.append(new String(v, StandardCharsets.UTF_8)).append('\0');
        }
        return scanBytesForPhrase(sb.toString().getBytes(StandardCharsets.UTF_8), bip39);
    }

    private static String tryDecryptWalletBlob(byte[] blob, String password, byte[] walletKey32,
            Bip39Util bip39, java.util.function.BiFunction<JSONObject, String, byte[]> decryptMetamaskVault) {
        String text = new String(blob, StandardCharsets.UTF_8).trim();
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                Object obj = JSON.parse(text);
                if (obj instanceof JSONObject) {
                    JSONObject jo = (JSONObject) obj;
                    if (jo.get("cipher") != null || jo.get("data") != null || jo.get("crypto") != null) {
                        JSONObject target = jo.getJSONObject("crypto") != null ? jo.getJSONObject("crypto") : jo;
                        if (decryptMetamaskVault != null) {
                            byte[] plain = decryptMetamaskVault.apply(target, password);
                            if (plain != null) {
                                String phrase = scanBytesForPhrase(plain, bip39);
                                if (phrase != null) {
                                    return phrase;
                                }
                            }
                        }
                    }
                    String phrase = phraseFromJsonObj(obj);
                    if (phrase != null) {
                        return phrase;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (walletKey32 != null && walletKey32.length == 32) {
            String hit = tryAesGcmOffsets(blob, walletKey32, bip39);
            if (hit != null) {
                return hit;
            }
        }
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        int[] iters = {10_000, 5_000, 100_000, 600_000, 256_000, 64_000};
        byte[][] salts = {
                "coin98".getBytes(StandardCharsets.UTF_8),
                "coin98.wallet".getBytes(StandardCharsets.UTF_8),
                "coin98_wallet".getBytes(StandardCharsets.UTF_8),
                Arrays.copyOf(pw, Math.min(16, pw.length)),
                new byte[0]
        };
        for (int it : iters) {
            for (byte[] salt : salts) {
                byte[] key = pbkdf2Sha256(pw, salt, it, 32);
                String hit = tryAesGcmOffsets(blob, key, bip39);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static String tryAesGcmOffsets(byte[] blob, byte[] key, Bip39Util bip39) {
        for (int off : new int[] {0, 12, 16}) {
            if (blob.length <= off + 28) {
                continue;
            }
            byte[] iv = Arrays.copyOfRange(blob, off, off + 12);
            byte[] rest = Arrays.copyOfRange(blob, off + 12, blob.length);
            if (rest.length < 17) {
                continue;
            }
            byte[] tag = Arrays.copyOfRange(rest, rest.length - 16, rest.length);
            byte[] body = Arrays.copyOfRange(rest, 0, rest.length - 16);
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
                byte[] plain = c.doFinal(concat(body, tag));
                String phrase = scanBytesForPhrase(plain, bip39);
                if (phrase != null) {
                    return phrase;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static byte[] pbkdf2Sha256(byte[] password, byte[] salt, int iter, int dklen) {
        PKCS5S2ParametersGenerator gen = new PKCS5S2ParametersGenerator(new SHA256Digest());
        gen.init(password, salt, iter);
        return ((KeyParameter) gen.generateDerivedParameters(dklen * 8)).getKey();
    }

    private static String scanBytesForPhrase(byte[] raw, Bip39Util bip39) {
        if (raw == null) {
            return null;
        }
        if (raw.length == 16 || raw.length == 20 || raw.length == 24 || raw.length == 28 || raw.length == 32) {
            String fromEnt = bip39.entropyToMnemonic(raw);
            if (fromEnt != null && bip39.validateMnemonic(fromEnt)) {
                return fromEnt;
            }
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        String hit = bip39.searchPhrase(text.replace(',', ' '));
        if (hit != null) {
            return hit;
        }
        Matcher m = MNEMONIC_RE.matcher(text);
        if (m.find()) {
            String cand = m.group(1);
            if (cand.split("\\s+").length >= 12 && bip39.validateMnemonic(cand)) {
                return cand;
            }
        }
        try {
            return phraseFromJsonObj(JSON.parse(text));
        } catch (Exception e) {
            return null;
        }
    }

    private static String phraseFromJsonObj(Object obj) {
        if (obj instanceof JSONObject) {
            JSONObject jo = (JSONObject) obj;
            for (String k : new String[] {"mnemonic", "seedPhrase", "seedphrase", "phrase", "recoveryPhrase"}) {
                String v = jo.getString(k);
                if (v != null && v.trim().split("\\s+").length >= 12) {
                    return v.trim();
                }
            }
            for (String k : jo.keySet()) {
                String p = phraseFromJsonObj(jo.get(k));
                if (p != null) {
                    return p;
                }
            }
        } else if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            for (int i = 0; i < arr.size(); i++) {
                String p = phraseFromJsonObj(arr.get(i));
                if (p != null) {
                    return p;
                }
            }
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
}
