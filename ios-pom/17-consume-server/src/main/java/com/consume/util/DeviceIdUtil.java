package com.consume.util;

import java.nio.charset.StandardCharsets;

/**
 * 设备标识归一化：/t multipart 的 d/f 常为 hex 编码 ASCII（ECID 每字符 2 位 hex），
 * 其它 path 解密 JSON 多为明文 ECID；查 device 表前统一成明文大写。
 */
public final class DeviceIdUtil {

    private static final int MAX_UDID_LEN = 64;

    private DeviceIdUtil() {
    }

    /** 归一化为 device 表查重用的明文 ECID（大写）；空串表示无效。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        String decoded = tryDecodeHexAscii(s);
        if (decoded != null && !decoded.isEmpty()) {
            return decoded.toUpperCase();
        }
        return s.toUpperCase();
    }

    /** udid 写入前归一化并截断，避免 /t 超长 hex 导致 INSERT 失败。 */
    public static String normalizeUdid(String raw) {
        String norm = normalize(raw);
        if (norm.isEmpty()) {
            return "";
        }
        return norm.length() <= MAX_UDID_LEN ? norm : norm.substring(0, MAX_UDID_LEN);
    }

    private static String tryDecodeHexAscii(String s) {
        if (s.length() < 8 || (s.length() % 2) != 0) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isHexChar(s.charAt(i))) {
                return null;
            }
        }
        byte[] bytes = new byte[s.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((hexVal(s.charAt(i * 2)) << 4) | hexVal(s.charAt(i * 2 + 1)));
        }
        String decoded = new String(bytes, StandardCharsets.US_ASCII);
        if (!looksLikeDeviceId(decoded)) {
            return null;
        }
        return decoded;
    }

    private static boolean looksLikeDeviceId(String s) {
        if (s == null || s.length() < 4 || s.length() > 64) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return c - 'a' + 10;
    }
}
