package com.admin.crypto;

/**
 * NaCl crypto_secretbox_open（XSalsa20-Poly1305）。
 * 密文是 16 字节 MAC 加正文。手写 BouncyCastle 的 MAC 对不上 libsodium，正确 PIN 也会被判失败。
 * 这里用和 18 相同的实现。
 */
public final class SecretBox {

    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 24;
    public static final int MAC_BYTES = 16;

    private SecretBox() {
    }

    public static byte[] open(byte[] cipherWithMac, byte[] nonce, byte[] key) {
        if (cipherWithMac == null || nonce == null || key == null) {
            return null;
        }
        if (nonce.length != NONCE_BYTES || key.length != KEY_BYTES || cipherWithMac.length < MAC_BYTES) {
            return null;
        }
        try {
            return new com.codahale.xsalsa20poly1305.SecretBox(key)
                    .open(nonce, cipherWithMac)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
