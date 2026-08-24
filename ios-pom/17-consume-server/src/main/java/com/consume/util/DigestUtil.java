package com.consume.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 摘要工具：sha256（用于 content_hash / result_hash / file_sha256）。
 */
public final class DigestUtil {

    private DigestUtil() {
    }

    public static String sha256Hex(String input) {
        if (input == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return toHex(d);
        } catch (Exception e) {
            return "";
        }
    }

    public static String sha256Hex(byte[] input) {
        if (input == null || input.length == 0) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(input));
        } catch (Exception e) {
            return "";
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
