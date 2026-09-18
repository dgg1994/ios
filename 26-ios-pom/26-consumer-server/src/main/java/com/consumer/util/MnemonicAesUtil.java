package com.consumer.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-CBC，密钥=SHA256(secret)，IV 前置后整体 Base64。
 * secret 为空时明文存取。解密先试 CBC，再回退旧版 AES-256-ECB。
 */
public final class MnemonicAesUtil {

    private MnemonicAesUtil() {
    }

    public static boolean hasSecret(String secret) {
        return secret != null && !secret.trim().isEmpty();
    }

    /** 像 Base64 密文（无空格、可 decode、长度够 AES 块），而非 BIP39 明文。 */
    public static boolean looksEncrypted(String stored) {
        String raw = stored == null ? "" : stored.trim();
        if (raw.isEmpty() || raw.contains(" ") || raw.length() < 24) {
            return false;
        }
        try {
            byte[] blob = Base64.getDecoder().decode(raw);
            return blob.length >= 16 && blob.length % 16 == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static String encrypt(String plaintext, String secret) {
        String plain = plaintext == null ? "" : plaintext;
        if (!hasSecret(secret)) {
            return plain;
        }
        try {
            byte[] key = sha256(secret);
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    public static String decrypt(String stored, String secret) {
        String raw = stored == null ? "" : stored;
        if (raw.isEmpty()) {
            return "";
        }
        if (!hasSecret(secret)) {
            if (looksEncrypted(raw)) {
                throw new IllegalStateException("未配置加密密钥（MNEMONIC_AES_KEY）");
            }
            return raw;
        }
        Exception last = null;
        try {
            byte[] blob = Base64.getDecoder().decode(raw.trim());
            try {
                String plain = decryptCbcSha256(blob, secret);
                if (plain != null && !plain.trim().isEmpty()) {
                    return plain;
                }
            } catch (Exception e) {
                last = e;
            }
            try {
                String plain = decryptEcbRaw32(blob, secret);
                if (plain != null && !plain.trim().isEmpty()) {
                    return plain;
                }
            } catch (Exception e) {
                last = e;
            }
        } catch (Exception e) {
            last = e;
        }
        // 库内可能已是明文（未配密钥时写入）
        if (raw.contains(" ")) {
            return raw;
        }
        throw new IllegalStateException("decrypt failed" + (last == null ? "" : ": " + last.getMessage()));
    }

    private static String decryptCbcSha256(byte[] blob, String secret) throws Exception {
        if (blob.length < 32 || blob.length % 16 != 0) {
            throw new IllegalArgumentException("ciphertext too short");
        }
        byte[] iv = Arrays.copyOfRange(blob, 0, 16);
        byte[] ct = Arrays.copyOfRange(blob, 16, blob.length);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sha256(secret), "AES"), new IvParameterSpec(iv));
        return new String(c.doFinal(ct), StandardCharsets.UTF_8);
    }

    private static String decryptEcbRaw32(byte[] blob, String secret) throws Exception {
        if (blob.length < 16 || blob.length % 16 != 0) {
            throw new IllegalArgumentException("ciphertext too short");
        }
        byte[] key = Arrays.copyOf((secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8), 32);
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return new String(c.doFinal(blob), StandardCharsets.UTF_8);
    }

    private static byte[] sha256(String secret) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest((secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8));
    }
}
