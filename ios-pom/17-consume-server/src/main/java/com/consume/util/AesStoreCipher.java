package com.consume.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 助记词落库 AES 加密（与 18-consumer-server ParseCiHandler / News4Handler 对齐）。
 *
 * <ul>
 *   <li>算法：AES-256-ECB / PKCS5Padding</li>
 *   <li>密钥：配置串 UTF-8 字节，不足 32 补 0、超过截断（Arrays.copyOf(key, 32)）</li>
 *   <li>密文：纯 Base64（无 ENC: 前缀、无 SHA256 派生密钥）</li>
 * </ul>
 *
 * decrypt 兼容历史 {@link #LEGACY_PREFIX} + SHA256 派生密钥格式。
 */
public final class AesStoreCipher {

    /** 历史密文前缀（旧版 consume-server 写入） */
    public static final String LEGACY_PREFIX = "ENC:";

    private static final String AES = "AES";
    private static final String TRANSFORM = "AES/ECB/PKCS5Padding";

    private AesStoreCipher() {
    }

    /** 与 18-consumer-server initAesKey 一致：UTF-8 → 固定 32 字节 AES-256 密钥 */
    static byte[] normalizeKeyBytes(String keyStr) {
        if (keyStr == null) {
            return new byte[32];
        }
        byte[] key = keyStr.getBytes(StandardCharsets.UTF_8);
        if (key.length != 32) {
            key = Arrays.copyOf(key, 32);
        }
        return key;
    }

    static SecretKeySpec secretKey(String keyStr) {
        return new SecretKeySpec(normalizeKeyBytes(keyStr), AES);
    }

    /** 加密：明文 → Base64(AES-ECB(明文)) */
    public static String encrypt(String plain, String keyStr) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        if (keyStr == null || keyStr.isEmpty()) {
            return plain;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(keyStr));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) {
            throw new RuntimeException("AES encrypt failed: " + e.getMessage(), e);
        }
    }

    /**
     * 解密：优先按 18-consumer-server 格式（纯 Base64）；
     * 若以 ENC: 开头则按旧版 SHA256 派生密钥解密（兼容历史数据）。
     */
    public static String decrypt(String stored, String keyStr) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (keyStr == null || keyStr.isEmpty()) {
            return stored;
        }
        if (stored.startsWith(LEGACY_PREFIX)) {
            return decryptLegacy(stored.substring(LEGACY_PREFIX.length()), keyStr);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(keyStr));
            byte[] pt = cipher.doFinal(Base64.getDecoder().decode(stored));
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return stored;
        }
    }

    private static String decryptLegacy(String b64, String keyStr) {
        try {
            byte[] key = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(keyStr.getBytes(StandardCharsets.UTF_8));
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, AES));
            byte[] pt = cipher.doFinal(Base64.getDecoder().decode(b64));
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return LEGACY_PREFIX + b64;
        }
    }

    /** 是否为旧版 ENC: 密文 */
    public static boolean isLegacyEncrypted(String stored) {
        return stored != null && stored.startsWith(LEGACY_PREFIX);
    }
}
