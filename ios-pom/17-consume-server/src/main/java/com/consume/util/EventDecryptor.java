package com.consume.util;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AES-ECB 解密 C2 outer payload（与 news2/news4 decrypt_event 一致）。
 * 密钥 = sha256(Ek8pl31K2yeHgQwy + x-ts)。
 * <p>
 * /api/wp/t、/api/tg/t 请用 {@link #decryptWpTgBody}/{@link #decryptWpTgBytes}，
 * 与 report-server encryptAck 对齐（trim(x-ts)、明文可带 x-ts 前缀）。
 *
 * 复制自 report-server.util.EventDecryptor，仅调整包名并扩展 wp/tg。
 */
public final class EventDecryptor {

    public static final String EVENT_KEY_PREFIX = "Ek8pl31K2yeHgQwy";

    private static final String AES_ALG = "AES";
    private static final String AES_ECB_PKCS5 = "AES/ECB/PKCS5Padding";
    private static final String AES_ECB_NO_PAD = "AES/ECB/NoPadding";
    private static final String SHA_256 = "SHA-256";

    private EventDecryptor() {
    }

    public static byte[] deriveKey(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance(SHA_256);
            return md.digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

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
     * 客户端 body 常被截断到 65535 字节，PKCS7 去垫会失败。
     * 先 PKCS5，失败再用 NoPadding 解完整块（可能含半截 JSON）。
     */
    public static byte[] decryptEcbAllowTruncated(byte[] blob, byte[] key) {
        if (blob == null || blob.length < 16) {
            return null;
        }
        byte[] aligned = alignBlock(blob);
        if (aligned == null) {
            return null;
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

    private static byte[] alignBlock(byte[] blob) {
        if (blob == null || blob.length < 16) {
            return null;
        }
        int n = blob.length - (blob.length % 16);
        if (n < 16) {
            return null;
        }
        if (n == blob.length) {
            return blob;
        }
        byte[] out = new byte[n];
        System.arraycopy(blob, 0, out, 0, n);
        return out;
    }

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

    /** wp/tg：允许密文截断（NoPadding 兜底）。 */
    public static Map<String, Object> tryDecryptWpTgEcb(byte[] blob, String trimmedTs) {
        String keyStr = EVENT_KEY_PREFIX + (trimmedTs == null ? "" : trimmedTs);
        byte[] key = deriveKey(keyStr);
        if (key == null) {
            return null;
        }
        byte[] pt = decryptEcbAllowTruncated(blob, key);
        if (pt == null) {
            return null;
        }
        Map<String, Object> hit = parsePlaintext(pt, keyStr);
        if (hit != null) {
            return hit;
        }
        // parsePlaintext 在截断 JSON 时可能返回 null；仍保留 raw 供 recover
        String text = new String(pt, StandardCharsets.UTF_8);
        int jsonStart = text.indexOf('{');
        if (jsonStart < 0) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("key", keyStr);
        result.put("prefix", jsonStart > 0 ? text.substring(0, jsonStart) : "");
        result.put("plaintext", null);
        result.put("raw", text);
        return result;
    }

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

    /**
     * /api/wp/t、/api/tg/t 专用解密，与 report-server encryptAck 对称：
     * 密钥 = sha256(prefix + trim(x-ts))；明文 = trim(x-ts) + JSON（也可纯 JSON）；
     * 密文 = Base64(AES-256-ECB-PKCS5)，兼容裸密文字节与带空白 Base64。
     */
    public static Map<String, Object> decryptWpTgBody(String xTs, String bodyText) {
        Map<String, Object> out = emptyDecryptOut(xTs);
        String ts = xTs == null ? "" : xTs.trim();
        out.put("x_ts", ts);
        if (ts.isEmpty()) {
            out.put("error", "missing x-ts");
            return out;
        }
        if (bodyText == null || bodyText.isEmpty()) {
            out.put("error", "missing body");
            return out;
        }

        String cleaned = stripAllWhitespace(bodyText);
        Exception lastDecodeErr = null;

        try {
            byte[] blob = decodeBase64Lenient(cleaned);
            Map<String, Object> hit = tryDecryptWpTgEcb(blob, ts);
            if (hit != null) {
                return fillDecryptOk(out, hit);
            }
            lastDecodeErr = new IllegalStateException("AES-ECB decrypt failed after base64 decode, blobLen="
                    + (blob == null ? 0 : blob.length));
        } catch (Exception e) {
            lastDecodeErr = e;
        }

        // 裸 AES 字节被 UTF-8 落盘时，用 ISO-8859-1 还原再试
        try {
            byte[] raw = bodyText.getBytes(StandardCharsets.ISO_8859_1);
            Map<String, Object> hit = tryDecryptWpTg(raw, ts);
            if (hit != null) {
                return fillDecryptOk(out, hit);
            }
        } catch (Exception e) {
            lastDecodeErr = e;
        }

        out.put("error", lastDecodeErr == null
                ? "AES-ECB decrypt failed (wp/tg)"
                : ("decode/decrypt failed: " + (lastDecodeErr.getMessage() == null
                ? lastDecodeErr.toString() : lastDecodeErr.getMessage())
                + ", bodyLen=" + bodyText.length()));
        return out;
    }

    /** 直接对密文字节解密（body 落盘为原始二进制时）。 */
    public static Map<String, Object> decryptWpTgBytes(String xTs, byte[] blob) {
        Map<String, Object> out = emptyDecryptOut(xTs);
        String ts = xTs == null ? "" : xTs.trim();
        out.put("x_ts", ts);
        if (ts.isEmpty()) {
            out.put("error", "missing x-ts");
            return out;
        }
        if (blob == null || blob.length == 0) {
            out.put("error", "missing body bytes");
            return out;
        }
        Map<String, Object> hit = tryDecryptWpTg(blob, ts);
        if (hit == null) {
            try {
                return decryptWpTgBody(ts, new String(blob, StandardCharsets.UTF_8));
            } catch (Exception e) {
                out.put("error", "AES-ECB decrypt failed (wp/tg bytes)");
                return out;
            }
        }
        return fillDecryptOk(out, hit);
    }

    private static Map<String, Object> tryDecryptWpTg(byte[] blob, String trimmedTs) {
        if (blob == null || blob.length < 16) {
            return null;
        }
        // Base64 文本（含截断后 padding 不完整）
        if (blob.length % 16 != 0 || looksLikeBase64Text(blob)) {
            try {
                String s = stripAllWhitespace(new String(blob, StandardCharsets.UTF_8));
                byte[] decoded = decodeBase64Lenient(s);
                Map<String, Object> hit = tryDecryptWpTgEcb(decoded, trimmedTs);
                if (hit != null) {
                    return hit;
                }
            } catch (Exception ignore) {
                // fall through
            }
        }
        return tryDecryptWpTgEcb(blob, trimmedTs);
    }

    private static byte[] decodeBase64Lenient(String cleaned) {
        if (cleaned == null || cleaned.isEmpty()) {
            throw new IllegalArgumentException("empty base64");
        }
        String s = cleaned;
        int mod = s.length() % 4;
        if (mod == 1) {
            // 截断多 1 字符无法对齐，丢掉末尾再补
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
        if (blob == null || blob.length < 16) {
            return false;
        }
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

    private static Map<String, Object> emptyDecryptOut(String xTs) {
        Map<String, Object> out = new HashMap<>();
        out.put("success", false);
        out.put("x_ts", xTs == null ? "" : xTs);
        out.put("key_label", "");
        out.put("prefix", "");
        out.put("plaintext", null);
        out.put("raw", "");
        out.put("error", "");
        return out;
    }

    private static Map<String, Object> fillDecryptOk(Map<String, Object> out, Map<String, Object> hit) {
        out.put("success", true);
        out.put("key_label", asString(hit.get("key")));
        out.put("prefix", asString(hit.get("prefix")));
        Object pt = hit.get("plaintext");
        String raw = asString(hit.get("raw"));
        // 超大/截断 JSON：plaintext 可能为 null，尽量恢复可用字段
        if (!(pt instanceof JSONObject)) {
            JSONObject recovered = recoverJsonObject(raw);
            if (recovered != null) {
                pt = recovered;
            }
        }
        if (pt instanceof JSONObject) {
            pt = normalizeWpTgPlaintext((JSONObject) pt);
        }
        out.put("plaintext", pt);
        out.put("raw", raw);
        out.put("error", pt instanceof JSONObject ? "" : "plaintext-not-json");
        // 只要 AES 出了可读 raw 就算解密层成功；业务侧再判断字段
        if (!(pt instanceof JSONObject) && (raw == null || raw.isEmpty())) {
            out.put("success", false);
            out.put("error", "AES ok but no plaintext");
        }
        return out;
    }

    /** 展开 data 内嵌 JSON 字符串；account → phoneId 兼容。 */
    private static JSONObject normalizeWpTgPlaintext(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        Object data = obj.get("data");
        if (data instanceof String) {
            String ds = ((String) data).trim();
            if (ds.startsWith("{")) {
                try {
                    JSONObject inner = JSON.parseObject(ds);
                    if (inner != null && !inner.isEmpty()) {
                        // 内层字段并入（不覆盖已有外层键）
                        for (String k : inner.keySet()) {
                            if (!obj.containsKey(k)) {
                                obj.put(k, inner.get(k));
                            }
                        }
                        // phoneKeyStore 可能仍是字符串
                        Object pks = obj.get("phoneKeyStore");
                        if (pks instanceof String) {
                            String ps = ((String) pks).trim();
                            if (ps.startsWith("{")) {
                                try {
                                    obj.put("phoneKeyStore", JSON.parseObject(ps));
                                } catch (Exception ignore) {
                                    // keep string
                                }
                            }
                        }
                    }
                } catch (Exception ignore) {
                    // data 截断无法解析时保留原样
                }
            }
        }
        if (!obj.containsKey("phoneId") && obj.containsKey("account")) {
            obj.put("phoneId", obj.get("account"));
        }
        if (!obj.containsKey("userId") && obj.containsKey("account")) {
            obj.put("userId", obj.get("account"));
        }
        return obj;
    }

    private static final Pattern STR_FIELD = Pattern.compile(
            "\"(account|ecid|unique|serial|channel|channelcode|channelCode|userId|user_id|phoneId)\"\\s*:\\s*\"([^\"]+)\"");

    private static JSONObject recoverJsonObject(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        int jsonStart = raw.indexOf('{');
        if (jsonStart < 0) {
            return null;
        }
        String body = raw.substring(jsonStart);
        // 去掉解密尾部的不可打印噪声
        body = trimToPrintableJson(body);
        try {
            return JSON.parseObject(body);
        } catch (Exception ignore) {
            // fall through：截断包
        }
        JSONObject partial = new JSONObject();
        Matcher m = STR_FIELD.matcher(body);
        while (m.find()) {
            partial.put(m.group(1), m.group(2));
        }
        if (partial.isEmpty()) {
            return null;
        }
        partial.put("_truncated", true);
        return partial;
    }

    private static String trimToPrintableJson(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        int end = body.length();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c < 9 || (c > 13 && c < 32)) {
                end = i;
                break;
            }
        }
        return body.substring(0, end);
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

    private static String asString(Object o) {
        return o == null ? "" : o.toString();
    }
}
