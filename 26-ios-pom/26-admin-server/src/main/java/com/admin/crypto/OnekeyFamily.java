package com.admin.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
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
 * OneKey / DigitalShield 便携 |VS|/|RP| 与 |LSE1| 解锁（对齐 onekey_family.py）。
 */
public final class OnekeyFamily {

    private static final int PBKDF2_ITERS_LEGACY = 5000;
    private static final int SALT_LEN = 32;
    private static final int IV_LEN = 16;
    private static final int GCM_NONCE_LEN = 12;
    private static final byte[] ENC_V2_MAGIC = "1K_ENC_V2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ENC_GCM_MAGIC = "1K_AES_GCM".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern VS_RE = Pattern.compile("\\|VS\\|([0-9a-fA-F]{64,})");
    private static final Pattern RP_HEX_RE = Pattern.compile("(E)?\\|RP\\|([0-9a-fA-F]{96,})");
    private static final Pattern LSE_RE = Pattern.compile("\\|LSE1\\|RP\\|");
    private static final String LSE_KEY_REF = "onekey_lse_secure_storage_v1";

    private OnekeyFamily() {
    }

    public static Map<String, Object> unlockDigitalShield(byte[] rawDb, String password, Bip39Util bip39) {
        if (hasLse(rawDb) && extractPortableVsRp(rawDb)[1] == null) {
            return fail("凭证为 Keychain 包裹格式，无法仅用密码解锁");
        }
        return unlockPortable(password, rawDb, bip39);
    }

    public static Map<String, Object> unlockOnekey(byte[] rawDb, String password, String lseKeyHex, Bip39Util bip39) {
        byte[][] vsRp = extractPortableVsRp(rawDb);
        if (vsRp[1] != null) {
            return unlockPortable(password, rawDb, bip39);
        }
        if (!hasLse(rawDb)) {
            return fail("未找到 OneKey 加密凭证");
        }
        if (lseKeyHex == null || lseKeyHex.isBlank()) {
            return fail("凭证为 Keychain 包裹（|LSE1|），缺少 onekey_lse_secure_storage_v1");
        }
        byte[] rpBlob = unwrapLse(rawDb, lseKeyHex);
        if (rpBlob == null) {
            return fail("LSE 解包失败（Keychain 密钥不匹配或密文损坏）");
        }
        if (vsRp[0] != null && !verifyPasswordAgainstVs(password, vsRp[0])) {
            return fail("密码错误");
        }
        byte[] plain = decryptPortableBlob(password, rpBlob);
        if (plain == null) {
            return fail("密码错误");
        }
        String phrase = mnemonicFromRpPlaintext(plain, bip39);
        if (phrase == null) {
            return fail("解密成功但未能还原助记词");
        }
        return ok(phrase);
    }

    public static Map<String, Object> unlockPortable(String password, byte[] rawDb, Bip39Util bip39) {
        byte[][] vsRp = extractPortableVsRp(rawDb);
        if (vsRp[1] == null) {
            return fail("未找到便携 |RP| 凭证");
        }
        if (vsRp[0] != null && !verifyPasswordAgainstVs(password, vsRp[0])) {
            return fail("密码错误");
        }
        byte[] plain = decryptPortableBlob(password, vsRp[1]);
        if (plain == null) {
            return fail("密码错误");
        }
        String phrase = mnemonicFromRpPlaintext(plain, bip39);
        if (phrase == null) {
            return fail("解密成功但未能还原助记词");
        }
        return ok(phrase);
    }

    public static String onekeyLseKeyHexFromKeychain(java.nio.file.Path keychainPath) {
        if (keychainPath == null) {
            return null;
        }
        for (KeychainXml.Item it : KeychainXml.parse(keychainPath)) {
            String agrp = it.agrp.toLowerCase(Locale.ROOT);
            if (!agrp.contains("so.onekey.wallet") && !agrp.contains("onekey")) {
                continue;
            }
            if (!LSE_KEY_REF.equals(it.acct)) {
                continue;
            }
            String hexKey = it.text.trim().toLowerCase(Locale.ROOT);
            if (hexKey.matches("[0-9a-f]{64}")) {
                return hexKey;
            }
            if (it.raw.length == 32) {
                return KeychainXml.toHex(it.raw);
            }
            if (it.raw.length == 64) {
                String asText = new String(it.raw, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
                if (asText.matches("[0-9a-f]{64}")) {
                    return asText;
                }
            }
        }
        return null;
    }

    public static boolean hasLse(byte[] raw) {
        return raw != null && LSE_RE.matcher(new String(raw, StandardCharsets.ISO_8859_1)).find();
    }

    public static byte[][] extractPortableVsRp(byte[] raw) {
        byte[] vs = null;
        byte[] rp = null;
        if (raw == null) {
            return new byte[][] {null, null};
        }
        String latin = new String(raw, StandardCharsets.ISO_8859_1);
        Matcher vsM = VS_RE.matcher(latin);
        if (vsM.find()) {
            vs = KeychainXml.fromHex(vsM.group(1));
        }
        Matcher rpM = RP_HEX_RE.matcher(latin);
        while (rpM.find()) {
            int start = rpM.start();
            String prefix = latin.substring(Math.max(0, start - 8), start);
            if (prefix.contains("|LSE1|") || prefix.contains("LSE1|")) {
                continue;
            }
            try {
                rp = KeychainXml.fromHex(rpM.group(2));
            } catch (Exception ignored) {
                continue;
            }
            break;
        }
        return new byte[][] {vs, rp};
    }

    public static byte[] decryptPortableBlob(String password, byte[] blob) {
        if (blob == null || blob.length == 0) {
            return null;
        }
        if (startsWith(blob, ENC_V2_MAGIC)) {
            return decryptEncV2(password, blob);
        }
        if (startsWith(blob, ENC_GCM_MAGIC)) {
            return decryptLegacyGcm(password, blob, PBKDF2_ITERS_LEGACY);
        }
        return decryptLegacyCbc(password, blob, PBKDF2_ITERS_LEGACY);
    }

    private static boolean verifyPasswordAgainstVs(String password, byte[] vsBlob) {
        byte[] plain = decryptPortableBlob(password, vsBlob);
        if (plain == null) {
            return false;
        }
        String text = new String(plain, StandardCharsets.UTF_8);
        return "Digitalshield".equals(text) || "OneKey".equals(text)
                || "DigitalShield".equals(text) || "OneKey".equals(text);
    }

    private static byte[] keyFromPasswordAndSalt(String password, byte[] salt, int iterations) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashed = md.digest(password.getBytes(StandardCharsets.UTF_8));
            PKCS5S2ParametersGenerator gen = new PKCS5S2ParametersGenerator(new SHA256Digest());
            gen.init(hashed, salt, iterations);
            return ((KeyParameter) gen.generateDerivedParameters(256)).getKey();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] decryptLegacyCbc(String password, byte[] blob, int iterations) {
        if (blob.length <= SALT_LEN + IV_LEN || (blob.length - SALT_LEN - IV_LEN) % 16 != 0) {
            return null;
        }
        byte[] salt = Arrays.copyOfRange(blob, 0, SALT_LEN);
        byte[] iv = Arrays.copyOfRange(blob, SALT_LEN, SALT_LEN + IV_LEN);
        byte[] ct = Arrays.copyOfRange(blob, SALT_LEN + IV_LEN, blob.length);
        try {
            byte[] key = keyFromPasswordAndSalt(password, salt, iterations);
            if (key == null) {
                return null;
            }
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(ct);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] decryptLegacyGcm(String password, byte[] blob, int iterations) {
        byte[] body = Arrays.copyOfRange(blob, ENC_GCM_MAGIC.length, blob.length);
        if (body.length < SALT_LEN + GCM_NONCE_LEN + 16) {
            return null;
        }
        byte[] salt = Arrays.copyOfRange(body, 0, SALT_LEN);
        byte[] nonce = Arrays.copyOfRange(body, SALT_LEN, SALT_LEN + GCM_NONCE_LEN);
        byte[] ct = Arrays.copyOfRange(body, SALT_LEN + GCM_NONCE_LEN, body.length);
        try {
            byte[] key = keyFromPasswordAndSalt(password, salt, iterations);
            if (key == null) {
                return null;
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return c.doFinal(ct);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] decryptEncV2(String password, byte[] blob) {
        if (blob.length <= ENC_V2_MAGIC.length + 1 + 1 + 1 + 4 + SALT_LEN + GCM_NONCE_LEN + 1 + 16) {
            return null;
        }
        int o = ENC_V2_MAGIC.length;
        int version = blob[o++] & 0xff;
        int cipher = blob[o++] & 0xff;
        int kdf = blob[o++] & 0xff;
        int iterations = ((blob[o] & 0xff) << 24) | ((blob[o + 1] & 0xff) << 16)
                | ((blob[o + 2] & 0xff) << 8) | (blob[o + 3] & 0xff);
        o += 4;
        byte[] salt = Arrays.copyOfRange(blob, o, o + SALT_LEN);
        o += SALT_LEN;
        byte[] nonce = Arrays.copyOfRange(blob, o, o + GCM_NONCE_LEN);
        o += GCM_NONCE_LEN;
        int dtypeLen = blob[o++] & 0xff;
        if (version != 2 || cipher != 1 || kdf != 1 || iterations <= 0 || o + dtypeLen + 16 > blob.length) {
            return null;
        }
        o += dtypeLen;
        byte[] aad = Arrays.copyOfRange(blob, 0, o);
        byte[] ctag = Arrays.copyOfRange(blob, o, blob.length);
        try {
            byte[] key = keyFromPasswordAndSalt(password, salt, iterations);
            if (key == null) {
                return null;
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            return c.doFinal(ctag);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] unwrapLse(byte[] raw, String lseKeyHex) {
        JSONObject env = extractLseEnvelope(raw);
        if (env == null) {
            return null;
        }
        JSONArray layers = env.getJSONArray("wrappingLayers");
        if (layers == null || layers.isEmpty()) {
            return null;
        }
        JSONObject layer = layers.getJSONObject(0);
        if (layer == null || !"AES-256-GCM".equals(layer.getString("alg")) || layer.getString("iv") == null) {
            return null;
        }
        String ciphertext = env.getString("ciphertext");
        String protectedHeader = env.getString("protectedHeader");
        String dataType = env.getString("dataType");
        String recordId = env.getString("recordId");
        if (ciphertext == null || protectedHeader == null || dataType == null || recordId == null) {
            return null;
        }
        try {
            byte[] key = KeychainXml.fromHex(lseKeyHex.trim());
            byte[] iv = KeychainXml.fromHex(layer.getString("iv"));
            byte[] ct = Base64.getDecoder().decode(ciphertext);
            if (key.length != 32 || iv.length != GCM_NONCE_LEN) {
                return null;
            }
            // stableStringify: sorted keys, compact
            JSONObject aadObj = new JSONObject(true);
            aadObj.put("dataType", dataType);
            aadObj.put("protectedHeader", protectedHeader);
            aadObj.put("recordId", recordId);
            // Python sorts keys: dataType, protectedHeader, recordId — but JSONObject(true) is LinkedHashMap insertion order
            // Need sorted: dataType, protectedHeader, recordId alphabetically: dataType, protectedHeader, recordId
            String aad = "{\"dataType\":" + JSON.toJSONString(dataType)
                    + ",\"protectedHeader\":" + JSON.toJSONString(protectedHeader)
                    + ",\"recordId\":" + JSON.toJSONString(recordId) + "}";
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] plain = c.doFinal(ct);
            if (startsWith(plain, "|RP|".getBytes(StandardCharsets.US_ASCII))) {
                byte[] body = Arrays.copyOfRange(plain, 4, plain.length);
                String text = new String(body, StandardCharsets.US_ASCII);
                if (text.matches("[0-9a-fA-F]+")) {
                    return KeychainXml.fromHex(text);
                }
                return body;
            }
            return plain;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject extractLseEnvelope(byte[] raw) {
        String latin = new String(raw, StandardCharsets.ISO_8859_1);
        Matcher m = LSE_RE.matcher(latin);
        if (!m.find()) {
            return null;
        }
        int start = m.end();
        if (start >= raw.length || raw[start] != '{') {
            return null;
        }
        int depth = 0;
        for (int i = start; i < Math.min(raw.length, start + 500_000); i++) {
            if (raw[i] == '{') {
                depth++;
            } else if (raw[i] == '}') {
                depth--;
                if (depth == 0) {
                    try {
                        Object o = JSON.parse(new String(raw, start, i - start + 1, StandardCharsets.UTF_8));
                        return o instanceof JSONObject ? (JSONObject) o : null;
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static String mnemonicFromRpPlaintext(byte[] plain, Bip39Util bip39) {
        try {
            String text = new String(plain, StandardCharsets.UTF_8);
            JSONObject obj = JSON.parseObject(text);
            if (obj == null) {
                return null;
            }
            String ent = obj.getString("entropyWithLangPrefixed");
            if (ent == null || ent.isBlank()) {
                ent = obj.getString("entropy");
            }
            if (ent != null && !ent.isBlank()) {
                return revealEntropyToMnemonic(ent.trim(), bip39);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String revealEntropyToMnemonic(String hexOrBytes, Bip39Util bip39) {
        byte[] raw;
        try {
            raw = KeychainXml.fromHex(hexOrBytes);
        } catch (Exception e) {
            return null;
        }
        return revealEntropyToMnemonic(raw, bip39);
    }

    public static String revealEntropyToMnemonic(byte[] raw, Bip39Util bip39) {
        if (raw == null || raw.length < 18) {
            return null;
        }
        int lang = raw[0] & 0xff;
        int elen = raw[1] & 0xff;
        if (lang != 1 || !(elen == 16 || elen == 20 || elen == 24 || elen == 28 || elen == 32)
                || raw.length < 2 + elen) {
            return null;
        }
        byte[] entropy = Arrays.copyOfRange(raw, 2, 2 + elen);
        String phrase = bip39.entropyToMnemonic(entropy);
        return bip39.validateMnemonic(phrase) ? phrase : null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || prefix == null || data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
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
