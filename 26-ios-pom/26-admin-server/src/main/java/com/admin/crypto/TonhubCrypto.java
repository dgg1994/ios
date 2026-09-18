package com.admin.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * Tonhub MMKV PIN 解锁 / 爆破，对齐 Python wallet_unlock tonhub_*。
 */
public final class TonhubCrypto {

    private static final Pattern SALT = Pattern.compile("ton-storage-passcode-naclA@([0-9a-f]{64})");
    private static final Pattern ENC_KEY = Pattern.compile(
            "ton-storage-passcode-enc-key-([0-9a-f]{64}).{0,4}([A-Za-z0-9+/=]{80,120})",
            Pattern.DOTALL);
    private static final Pattern WALLET_JSON = Pattern.compile(
            "\\{\"version\":2,\"selected\":\\d+,\"addresses\":\\[.*?\\]\\}");

    private TonhubCrypto() {
    }

    public static Map<String, Object> parseMmkv(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("tonhub mmkv empty");
        }
        String asLatin = new String(raw, StandardCharsets.ISO_8859_1);
        Matcher sm = SALT.matcher(asLatin);
        if (!sm.find()) {
            throw new IllegalArgumentException("tonhub salt not found");
        }
        String salt = sm.group(1);
        Matcher em = ENC_KEY.matcher(asLatin);
        if (!em.find()) {
            throw new IllegalArgumentException("tonhub enc key not found");
        }
        String b64 = em.group(2).replaceAll("[^A-Za-z0-9+/=]", "");
        byte[] encAppKey = b64decode(b64);
        Matcher wm = WALLET_JSON.matcher(asLatin);
        if (!wm.find()) {
            throw new IllegalArgumentException("tonhub wallet json not found");
        }
        JSONObject wallet = JSON.parseObject(wm.group());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("salt", salt);
        out.put("enc_app_key", encAppKey);
        out.put("wallet", wallet);
        return out;
    }

    public static String decryptPin(String passcode, String salt, byte[] encAppKey, byte[] secretKeyEnc) {
        if (passcode == null || salt == null || encAppKey == null || secretKeyEnc == null) {
            return null;
        }
        if (encAppKey.length < 24 || secretKeyEnc.length < 24) {
            return null;
        }
        byte[] key = pbkdf2Sha512(passcode.getBytes(StandardCharsets.UTF_8), salt.getBytes(StandardCharsets.UTF_8), 100000, 32);
        byte[] nonce1 = new byte[24];
        System.arraycopy(encAppKey, 0, nonce1, 0, 24);
        byte[] ct1 = new byte[encAppKey.length - 24];
        System.arraycopy(encAppKey, 24, ct1, 0, ct1.length);
        byte[] appKey = SecretBox.open(ct1, nonce1, key);
        if (appKey == null) {
            return null;
        }
        byte[] nonce2 = new byte[24];
        System.arraycopy(secretKeyEnc, 0, nonce2, 0, 24);
        byte[] ct2 = new byte[secretKeyEnc.length - 24];
        System.arraycopy(secretKeyEnc, 24, ct2, 0, ct2.length);
        byte[] plain = SecretBox.open(ct2, nonce2, appKey);
        if (plain == null) {
            return null;
        }
        return new String(plain, StandardCharsets.UTF_8);
    }

    public static boolean phraseOk(String phrase) {
        if (phrase == null) {
            return false;
        }
        String[] parts = phrase.trim().split("\\s+");
        if (parts.length != 12 && parts.length != 15 && parts.length != 18 && parts.length != 21 && parts.length != 24) {
            return false;
        }
        for (String w : parts) {
            if (w.isEmpty()) {
                return false;
            }
            for (int i = 0; i < w.length(); i++) {
                char c = w.charAt(i);
                if (c < 'a' || c > 'z') {
                    return false;
                }
            }
        }
        return true;
    }

    public static byte[] secretKeyEncFromWallet(JSONObject wallet) {
        if (wallet == null) {
            return null;
        }
        JSONArray addrs = wallet.getJSONArray("addresses");
        if (addrs == null || addrs.isEmpty()) {
            return null;
        }
        JSONObject a0 = addrs.getJSONObject(0);
        return b64decode(a0.getString("secretKeyEnc"));
    }

    public static byte[] b64decode(String s) {
        String raw = s == null ? "" : s.trim();
        int pad = (4 - (raw.length() % 4)) % 4;
        raw = raw + "====".substring(0, pad);
        return Base64.getDecoder().decode(raw);
    }

    private static byte[] pbkdf2Sha512(byte[] password, byte[] salt, int iter, int dkLen) {
        PKCS5S2ParametersGenerator gen = new PKCS5S2ParametersGenerator(new SHA512Digest());
        gen.init(password, salt, iter);
        return ((KeyParameter) gen.generateDerivedParameters(dkLen * 8)).getKey();
    }
}
