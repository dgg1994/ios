package com.report.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * AES-ECB 解密 C2 /a /event 等 outer payload（与 news2 decrypt_event 一致）。
 * 密钥 = sha256(Ek8pl31K2yeHgQwy + x-ts)。
 */
public final class EventDecryptor {

    public static final String EVENT_KEY_PREFIX = "Ek8pl31K2yeHgQwy";

    private static final String AES_ALG = "AES";
    private static final String AES_ECB_PKCS5 = "AES/ECB/PKCS5Padding";
    private static final String SHA_256 = "SHA-256";

    private EventDecryptor() {
    }

    /**
     * SHA-256 派生密钥
     */
    public static byte[] deriveKey(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance(SHA_256);
            return md.digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /**
     * AES-ECB 解密（PKCS7 去填充）
     */
    public static byte[] decryptEcb(byte[] blob, byte[] key) {
        if (blob == null || blob.length < 16 || blob.length % 16 != 0) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(AES_ECB_PKCS5);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, AES_ALG));
            return cipher.doFinal(blob);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析明文：定位首个 {，截取前缀与 JSON 体
     */
    public static Map<String, Object> parsePlaintext(byte[] pt, String keyLabel) {
        if (pt == null) {
            return null;
        }
        String text;
        try {
            text = new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }

        int jsonStart = text.indexOf("{");
        String prefix = jsonStart > 0 ? text.substring(0, jsonStart) : "";
        String body = jsonStart >= 0 ? text.substring(jsonStart) : text;

        Map<String, Object> result = new HashMap<>();
        result.put("key", keyLabel);
        result.put("prefix", prefix);

        try {
            JSONObject obj = JSON.parseObject(body);
            result.put("plaintext", obj);
            result.put("raw", text);
            return result;
        } catch (JSONException ex) {
            String first = text.isEmpty() ? "" : text.substring(0, 1);
            if ("{".equals(first) || "[".equals(first)
                    || text.contains("mnemonic") || text.contains("wallet")) {
                result.put("plaintext", null);
                result.put("raw", text);
                return result;
            }
        }
        return null;
    }

    /**
     * 尝试以 x_ts 派生密钥解密 ECB
     */
    public static Map<String, Object> tryDecryptEventEcb(byte[] blob, String xTs) {
        return tryDecryptEventEcb(blob, xTs, EVENT_KEY_PREFIX);
    }

    public static Map<String, Object> tryDecryptEventEcb(byte[] blob, String xTs, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            prefix = EVENT_KEY_PREFIX;
        }
        String keyStr = prefix + (xTs == null ? "" : xTs);
        byte[] key = deriveKey(keyStr);
        if (key == null) {
            return null;
        }
        byte[] pt = decryptEcb(blob, key);
        if (pt == null) {
            return null;
        }
        return parsePlaintext(pt, keyStr);
    }

    /**
     * 解密 C2 接口 body（base64 文本 → 明文）
     */
    public static Map<String, Object> decryptEventBody(String xTs, String b64Text) {
        return decryptEventBody(xTs, b64Text, EVENT_KEY_PREFIX);
    }

    public static Map<String, Object> decryptEventBody(String xTs, String b64Text, String prefix) {
        Map<String, Object> out = new HashMap<>();
        out.put("success", false);
        out.put("x_ts", xTs == null ? "" : xTs);
        out.put("key_label", "");
        out.put("prefix", "");
        out.put("plaintext", null);
        out.put("raw", "");
        out.put("error", "");

        if (xTs == null || xTs.isEmpty() || b64Text == null || b64Text.isEmpty()) {
            out.put("error", "missing x-ts or body");
            return out;
        }

        try {
            byte[] blob = Base64.getDecoder().decode(b64Text.trim().replace(" ", ""));
            Map<String, Object> hit = tryDecryptEventEcb(blob, xTs, prefix);
            if (hit == null) {
                out.put("error", "AES-ECB decrypt failed");
                return out;
            }
            out.put("success", true);
            out.put("key_label", asString(hit.get("key")));
            out.put("prefix", asString(hit.get("prefix")));
            out.put("plaintext", hit.get("plaintext"));
            out.put("raw", asString(hit.get("raw")));
            return out;
        } catch (Exception e) {
            out.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            return out;
        }
    }

    private static String asString(Object o) {
        return o == null ? "" : o.toString();
    }
}
