package com.consumer.parse;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.generators.SCrypt;
import org.bouncycastle.crypto.params.KeyParameter;

import com.alibaba.fastjson.JSONObject;
import com.consumer.util.BIP32Util;

/**
 * 对齐 Python wallet_unlock.decrypt_keystore_v3：Ethereum Keystore V3。
 */
public final class KeystoreV3 {

    private KeystoreV3() {
    }

    public static byte[] decrypt(JSONObject crypto, String password) {
        if (password == null) {
            return null;
        }
        return decrypt(crypto, password.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 对齐 18 {@code tryTrustDecrypt}：64hex 先按 32 字节解码，再试 UTF-8；
     * 128hex 先还原成 64hex 密码串。
     */
    public static byte[] decryptTrust(JSONObject crypto, String password) {
        if (crypto == null || password == null) {
            return null;
        }
        String pwd = password.trim();
        if (pwd.isEmpty()) {
            return null;
        }
        if (isHexLen(pwd, 128)) {
            try {
                String decoded = new String(hex(pwd), StandardCharsets.UTF_8).trim();
                if (isHexLen(decoded, 64)) {
                    pwd = decoded;
                }
            } catch (Exception ignored) {
            }
        }
        if (isHexLen(pwd, 64)) {
            byte[] hit = decrypt(crypto, hex(pwd));
            if (hit != null) {
                return hit;
            }
        }
        return decrypt(crypto, pwd.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] decrypt(JSONObject crypto, byte[] password) {
        if (crypto == null || password == null) {
            return null;
        }
        try {
            String kdf = crypto.getString("kdf");
            kdf = kdf == null ? "" : kdf.toLowerCase(Locale.ROOT);
            JSONObject params = crypto.getJSONObject("kdfparams");
            if (params == null) {
                return null;
            }
            byte[] salt = hex(params.getString("salt"));
            byte[] derived;
            if ("pbkdf2".equals(kdf)) {
                String prf = String.valueOf(params.getOrDefault("prf", "hmac-sha256")).replace("hmac-", "");
                int c = params.getIntValue("c");
                if (c <= 0) {
                    c = 262144;
                }
                int dklen = params.getIntValue("dklen");
                if (dklen <= 0) {
                    dklen = 32;
                }
                derived = pbkdf2(prf, password, salt, c, dklen);
            } else if ("scrypt".equals(kdf)) {
                int dklen = params.getIntValue("dklen");
                if (dklen <= 0) {
                    dklen = 32;
                }
                derived = SCrypt.generate(
                        password,
                        salt,
                        params.getIntValue("n"),
                        params.getIntValue("r"),
                        params.getIntValue("p"),
                        dklen);
            } else {
                return null;
            }
            byte[] ciphertext = hex(crypto.getString("ciphertext"));
            byte[] macInput = concat(Arrays.copyOfRange(derived, 16, 32), ciphertext);
            String macHex = toHex(BIP32Util.keccak256(macInput));
            String expect = String.valueOf(crypto.getOrDefault("mac", "")).toLowerCase(Locale.ROOT);
            if (!macHex.equals(expect)) {
                return null;
            }
            JSONObject cipherParams = crypto.getJSONObject("cipherparams");
            byte[] iv = hex(cipherParams == null ? null : cipherParams.getString("iv"));
            String cipher = String.valueOf(crypto.getOrDefault("cipher", "aes-128-ctr")).toLowerCase(Locale.ROOT);
            if (!"aes-128-ctr".equals(cipher)) {
                return null;
            }
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derived, 0, 16, "AES"), new IvParameterSpec(iv));
            return c.doFinal(ciphertext);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] pbkdf2(String prf, byte[] password, byte[] salt, int c, int dklen) {
        PKCS5S2ParametersGenerator gen;
        if ("sha512".equalsIgnoreCase(prf) || "sha-512".equalsIgnoreCase(prf)) {
            gen = new PKCS5S2ParametersGenerator(new SHA512Digest());
        } else {
            gen = new PKCS5S2ParametersGenerator(new SHA256Digest());
        }
        gen.init(password, salt, c);
        return ((KeyParameter) gen.generateDerivedParameters(dklen * 8)).getKey();
    }

    private static boolean isHexLen(String s, int len) {
        if (s == null || s.length() != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static byte[] hex(String s) {
        String raw = s == null ? "" : s.trim();
        if ((raw.length() & 1) == 1) {
            throw new IllegalArgumentException("odd hex");
        }
        byte[] out = new byte[raw.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(raw.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
