package com.consume.util;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Base58 / Base58Check 编码（Bitcoin 字母表）。
 * 用于 BTC / TRON 地址编码。无外部依赖。
 */
public final class Base58 {

    private Base58() {
    }

    private static final String ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final char[] CHARS = ALPHABET.toCharArray();

    /** 标准 Base58 编码（无校验和） */
    public static String encode(byte[] input) {
        if (input == null || input.length == 0) {
            return "";
        }
        // 计算前导 0 的个数
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) {
            zeros++;
        }
        // 转 base58：每次除以 58 得到一位余数，余数按"低位在前"收集
        byte[] copy = Arrays.copyOf(input, input.length);
        char[] digits = new char[copy.length * 2];
        int digitLen = 0;
        for (int start = zeros; start < copy.length; ) {
            int rem = 0;
            for (int i = start; i < copy.length; i++) {
                int acc = (rem << 8) | (copy[i] & 0xff);
                copy[i] = (byte) (acc / 58);
                rem = acc % 58;
                if (copy[i] == 0 && i == start) {
                    start++;
                }
            }
            digits[digitLen++] = CHARS[rem];
        }
        // 余数是低位在前，输出需高位在前 → 反转；前导 0 → '1'
        char[] result = new char[digitLen + zeros];
        Arrays.fill(result, 0, zeros, '1');
        for (int i = 0; i < digitLen; i++) {
            result[zeros + i] = digits[digitLen - 1 - i];
        }
        return new String(result);
    }

    /** Base58Check：data + 4 字节 double-SHA256 校验和后做 Base58 */
    public static String encodeCheck(byte[] data) {
        if (data == null) {
            return "";
        }
        byte[] checksum = doubleSha256(data);
        byte[] payload = new byte[data.length + 4];
        System.arraycopy(data, 0, payload, 0, data.length);
        System.arraycopy(checksum, 0, payload, data.length, 4);
        return encode(payload);
    }

    private static byte[] doubleSha256(byte[] data) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return Arrays.copyOf(sha.digest(sha.digest(data)), 4);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
