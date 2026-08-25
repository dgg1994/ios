package com.consumer.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * 助记词入库加解密：{@code consumer.mnemonic-aes-key} 为空则明文存取，有值则 AES-256-ECB。
 */
@Slf4j
public final class MnemonicAesUtil {

    private MnemonicAesUtil() {}

    public static boolean isEnabled(String configKey) {
        return configKey != null && !configKey.trim().isEmpty();
    }

    /** 未配置或空字符串 → null（明文模式）；否则 32 字节 AES-256 密钥 */
    public static SecretKeySpec resolveKey(String configKey) {
        if (!isEnabled(configKey)) {
            return null;
        }
        byte[] key = configKey.trim().getBytes(StandardCharsets.UTF_8);
        if (key.length != 32) {
            log.warn("mnemonic-aes-key length={} (expected 32 for AES-256), padding/truncating", key.length);
            key = Arrays.copyOf(key, 32);
        }
        return new SecretKeySpec(key, "AES");
    }

    /** key 为 null 时原样返回明文；否则 Base64(AES-ECB) */
    public static String encodeForStorage(String plain, SecretKeySpec key) throws Exception {
        if (plain == null) {
            return "";
        }
        if (key == null) {
            return plain;
        }
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(enc);
    }

    /** key 为 null 时 stored 即明文；否则解密 */
    public static String decodeFromStorage(String stored, SecretKeySpec key) throws Exception {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        if (key == null) {
            return stored;
        }
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, key);
        byte[] dec = c.doFinal(Base64.getDecoder().decode(stored));
        return new String(dec, StandardCharsets.UTF_8);
    }
}
