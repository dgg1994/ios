package com.admin.crypto;

import org.bouncycastle.crypto.engines.XSalsa20Engine;
import org.bouncycastle.crypto.macs.Poly1305;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

/**
 * NaCl crypto_secretbox_open_easy（XSalsa20-Poly1305），对齐 Python libsodium。
 * 密文格式：16 字节 MAC + 密文。
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
            byte[] subKey = new byte[32];
            XSalsa20Engine hsalsa = new XSalsa20Engine();
            hsalsa.init(true, new ParametersWithIV(new KeyParameter(key), nonce));
            // first block produces subkey material via HSalsa20 path — use poly1305 first
            byte[] block0 = new byte[64];
            hsalsa.processBytes(block0, 0, 64, block0, 0);
            System.arraycopy(block0, 0, subKey, 0, 32);

            Poly1305 poly = new Poly1305();
            poly.init(new KeyParameter(subKey));
            int ctLen = cipherWithMac.length - MAC_BYTES;
            poly.update(cipherWithMac, MAC_BYTES, ctLen);
            byte[] mac = new byte[MAC_BYTES];
            poly.doFinal(mac, 0);
            for (int i = 0; i < MAC_BYTES; i++) {
                if (mac[i] != cipherWithMac[i]) {
                    return null;
                }
            }

            byte[] out = new byte[ctLen];
            XSalsa20Engine xsalsa = new XSalsa20Engine();
            xsalsa.init(false, new ParametersWithIV(new KeyParameter(key), nonce));
            // skip first block (used for Poly1305 key)
            byte[] skip = new byte[64];
            xsalsa.processBytes(skip, 0, 64, skip, 0);
            xsalsa.processBytes(cipherWithMac, MAC_BYTES, ctLen, out, 0);
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
