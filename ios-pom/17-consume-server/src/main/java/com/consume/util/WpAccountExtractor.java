package com.consume.util;

import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Locale;

import org.bouncycastle.math.ec.rfc7748.X25519;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSONObject;

/**
 * 将 /api/wp/t 解密后的原始 payload 提取为下游使用的扁平账号 JSON（对齐示例.json）。
 * <p>
 * 丢弃 phoneKeyStore.preKeys 等大字段；identity / signedPreKey 做 hex→Base64；
 * clientStaticPublicKey 由 X25519 私钥推导。
 */
public final class WpAccountExtractor {

    private static final Logger log = LoggerFactory.getLogger(WpAccountExtractor.class);

    /** 常见国际区号，按长度降序，便于最长前缀匹配。 */
    private static final String[] CALLING_CODES = {
            "998", "997", "996", "995", "994", "993", "992", "977", "976", "975",
            "974", "973", "972", "971", "970", "968", "967", "966", "965", "964",
            "963", "962", "961", "960", "886", "880", "856", "855", "853", "852",
            "850", "692", "691", "690", "689", "688", "687", "686", "685", "683",
            "682", "681", "680", "679", "678", "677", "676", "675", "674", "673",
            "672", "670", "599", "598", "597", "595", "593", "592", "591", "590",
            "509", "508", "507", "506", "505", "504", "503", "502", "501", "500",
            "423", "421", "420", "389", "387", "386", "385", "383", "382", "381",
            "380", "378", "377", "376", "375", "374", "373", "372", "371", "370",
            "359", "358", "357", "356", "355", "354", "353", "352", "351", "350",
            "299", "298", "297", "291", "290", "269", "268", "267", "266", "265",
            "264", "263", "262", "261", "260", "258", "257", "256", "255", "254",
            "253", "252", "251", "250", "249", "248", "246", "245", "244", "243",
            "242", "241", "240", "239", "238", "237", "236", "235", "234", "233",
            "232", "231", "230", "229", "228", "227", "226", "225", "224", "223",
            "222", "221", "220", "218", "216", "213", "212", "211", "98", "95",
            "94", "93", "92", "91", "90", "86", "84", "82", "81", "66", "65",
            "64", "63", "62", "61", "60", "58", "57", "56", "55", "54", "53",
            "52", "51", "49", "48", "47", "46", "45", "44", "43", "41", "40",
            "39", "36", "34", "33", "32", "31", "30", "27", "20", "7", "1"
    };

    static {
        Arrays.sort(CALLING_CODES, Comparator.comparingInt(String::length).reversed());
    }

    private WpAccountExtractor() {
    }

    /**
     * @param plaintext 已 normalize（data 内层已并入顶层）的解密 JSON
     * @return 扁平账号 JSON；缺关键密钥时仍尽量输出已有字段
     */
    public static JSONObject extract(JSONObject plaintext) {
        JSONObject out = new JSONObject(true);
        if (plaintext == null || plaintext.isEmpty()) {
            return out;
        }

        JSONObject payload = resolvePayload(plaintext);
        JSONObject deviceConfig = asObject(payload.get("deviceConfig"));
        if (deviceConfig == null) {
            deviceConfig = asObject(plaintext.get("deviceConfig"));
        }
        JSONObject keyStore = asObject(payload.get("phoneKeyStore"));
        if (keyStore == null) {
            keyStore = asObject(plaintext.get("phoneKeyStore"));
        }
        JSONObject identity = keyStore == null ? null : asObject(keyStore.get("identity"));
        JSONObject signedPreKey = keyStore == null ? null : asObject(keyStore.get("signedPreKey"));

        String phone = firstNonEmpty(
                str(payload, "userId"),
                str(payload, "account"),
                str(plaintext, "userId"),
                str(plaintext, "account"),
                str(plaintext, "phoneId"));
        phone = digitsOnly(phone);

        String[] ccIn = splitCallingCode(phone);
        String cc = ccIn[0];
        String national = ccIn[1];

        String locale = firstNonEmpty(str(payload, "locale"), str(plaintext, "locale"));
        String[] countryLang = parseLocale(locale);

        // sim_operator 常见为 MCC+MNC 拼接（5/6 位数字，如 72433）；无有效值则 mcc/mnc 置空
        String simOperator = firstNonEmpty(
                str(payload, "sim_operator"),
                str(plaintext, "sim_operator"),
                str(deviceConfig, "sim_operator"));
        String[] mccMnc = parseSimOperator(simOperator);

        out.put("cc", cc);
        out.put("in", national);
        out.put("jid", phone);
        out.put("mcc", mccMnc[0]);
        out.put("mnc", mccMnc[1]);
        out.put("phone", phone);
        out.put("device", firstNonEmpty(str(deviceConfig, "device"), str(deviceConfig, "model")));
        out.put("source", "wp");
        out.put("country", countryLang[0]);
        out.put("language", countryLang[1]);
        out.put("osVersion", str(deviceConfig, "sdk_release"));
        out.put("phoneUUID", firstNonEmpty(
                str(payload, "phoneId"),
                str(plaintext, "phoneId")));
        out.put("deviceUUID", "");
        out.put("manufacturer", firstNonEmpty(str(deviceConfig, "brand"), "Apple"));

        long registrationId = 0L;
        long signPreKeyId = 0L;
        if (identity != null) {
            registrationId = longVal(identity, "registration_id");
        }
        if (signedPreKey != null) {
            signPreKeyId = longVal(signedPreKey, "prekey_id");
            if (signPreKeyId <= 0) {
                signPreKeyId = registrationId;
            }
        }
        out.put("signPreKeyID", signPreKeyId);
        out.put("osBuildNumber", str(deviceConfig, "display"));
        out.put("registrationID", registrationId);
        out.put("roProductBoard", str(deviceConfig, "board"));
        out.put("roProductDevice", firstNonEmpty(str(deviceConfig, "model"), str(deviceConfig, "device")));
        out.put("whatsappVersion", "");

        if (identity != null) {
            putB64FromHex(out, "identityPublicKey", str(identity, "hexPublic"));
            putB64FromHex(out, "identityPrivateKey", str(identity, "hexPrivate"));
        } else {
            out.put("identityPublicKey", "");
            out.put("identityPrivateKey", "");
        }

        if (signedPreKey != null) {
            fillSignedPreKey(out, str(signedPreKey, "hexKey"));
        } else {
            out.put("signPreKeyPublicKey", "");
            out.put("signPreKeySignature", "");
            out.put("signPreKeyPrivateKey", "");
        }

        String staticPrivB64 = firstNonEmpty(
                str(payload, "clientStaticKeypairBase64"),
                str(plaintext, "clientStaticKeypairBase64"));
        out.put("clientStaticPublicKey", deriveX25519PublicBase64(staticPrivB64));
        out.put("clientStaticPrivateKey", staticPrivB64);

        return out;
    }

    /** 提取结果序列化为紧凑 JSON 字符串。 */
    public static String extractJson(JSONObject plaintext) {
        return extract(plaintext).toJSONString();
    }

    /**
     * data 可能是内嵌对象/字符串；优先用已展开字段，否则解析 data。
     */
    private static JSONObject resolvePayload(JSONObject plaintext) {
        if (plaintext.containsKey("phoneKeyStore")
                || plaintext.containsKey("clientStaticKeypairBase64")
                || plaintext.containsKey("deviceConfig")) {
            return plaintext;
        }
        Object data = plaintext.get("data");
        JSONObject inner = asObject(data);
        return inner != null ? inner : plaintext;
    }

    private static void fillSignedPreKey(JSONObject out, String hexKey) {
        out.put("signPreKeyPublicKey", "");
        out.put("signPreKeySignature", "");
        out.put("signPreKeyPrivateKey", "");
        byte[] blob = hexToBytes(hexKey);
        if (blob == null || blob.length < 10) {
            return;
        }
        try {
            int pos = 0;
            // field1 varint = id
            if (blob[pos] != 0x08) {
                return;
            }
            pos++;
            while (pos < blob.length && (blob[pos] & 0x80) != 0) {
                pos++;
            }
            if (pos >= blob.length) {
                return;
            }
            pos++; // last id byte

            byte[] pub = readLenDelimited(blob, pos, 0x12);
            if (pub == null) {
                return;
            }
            pos = skipLenDelimited(blob, pos, 0x12);
            byte[] priv = readLenDelimited(blob, pos, 0x1a);
            if (priv == null) {
                return;
            }
            pos = skipLenDelimited(blob, pos, 0x1a);
            byte[] sig = readLenDelimited(blob, pos, 0x22);
            out.put("signPreKeyPublicKey", Base64.getEncoder().encodeToString(pub));
            out.put("signPreKeySignature",
                    sig == null ? "" : Base64.getEncoder().encodeToString(sig));
            out.put("signPreKeyPrivateKey", Base64.getEncoder().encodeToString(priv));
        } catch (Exception e) {
            log.debug("signedPreKey hexKey 解析失败: {}", e.getMessage());
        }
    }

    private static byte[] readLenDelimited(byte[] blob, int pos, int tag) {
        if (pos >= blob.length || (blob[pos] & 0xff) != tag) {
            return null;
        }
        int len = blob[pos + 1] & 0xff;
        int start = pos + 2;
        if (start + len > blob.length) {
            return null;
        }
        return Arrays.copyOfRange(blob, start, start + len);
    }

    private static int skipLenDelimited(byte[] blob, int pos, int tag) {
        if (pos >= blob.length || (blob[pos] & 0xff) != tag) {
            return pos;
        }
        int len = blob[pos + 1] & 0xff;
        return pos + 2 + len;
    }

    static String deriveX25519PublicBase64(String privateKeyB64) {
        if (privateKeyB64 == null || privateKeyB64.isEmpty()) {
            return "";
        }
        try {
            byte[] priv = Base64.getDecoder().decode(privateKeyB64.trim());
            if (priv.length != X25519.SCALAR_SIZE) {
                // 若是 priv||pub 拼接，取前 32 字节
                if (priv.length >= X25519.SCALAR_SIZE) {
                    priv = Arrays.copyOf(priv, X25519.SCALAR_SIZE);
                } else {
                    return "";
                }
            }
            byte[] pub = new byte[X25519.POINT_SIZE];
            X25519.scalarMultBase(priv, 0, pub, 0);
            return Base64.getEncoder().encodeToString(pub);
        } catch (Exception e) {
            log.debug("X25519 公钥推导失败: {}", e.getMessage());
            return "";
        }
    }

    private static void putB64FromHex(JSONObject out, String key, String hex) {
        byte[] bytes = hexToBytes(hex);
        out.put(key, bytes == null ? "" : Base64.getEncoder().encodeToString(bytes));
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) {
            return null;
        }
        String s = hex.trim();
        if (s.isEmpty() || (s.length() & 1) != 0) {
            return null;
        }
        try {
            byte[] out = new byte[s.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 Android 风格 sim_operator：MCC(3) + MNC(2或3)。
     * 非 5/6 位纯数字时返回空，不猜运营商。
     *
     * @return [mcc, mnc]
     */
    private static String[] parseSimOperator(String simOperator) {
        String digits = digitsOnly(simOperator);
        if (digits.length() == 5 || digits.length() == 6) {
            return new String[]{digits.substring(0, 3), digits.substring(3)};
        }
        return new String[]{"", ""};
    }

    /** @return [cc, national] */
    private static String[] splitCallingCode(String phone) {
        if (phone == null || phone.isEmpty()) {
            return new String[]{"", ""};
        }
        for (String code : CALLING_CODES) {
            if (phone.startsWith(code) && phone.length() > code.length()) {
                return new String[]{code, phone.substring(code.length())};
            }
        }
        return new String[]{"", phone};
    }

    /** @return [country, language] */
    private static String[] parseLocale(String locale) {
        if (locale == null || locale.trim().isEmpty()) {
            return new String[]{"", ""};
        }
        String s = locale.trim().replace('-', '_');
        int idx = s.indexOf('_');
        if (idx > 0 && idx < s.length() - 1) {
            String lang = s.substring(0, idx).toLowerCase(Locale.ROOT);
            String country = s.substring(idx + 1).toUpperCase(Locale.ROOT);
            // 兼容 en_US / US_en 两种
            if (lang.length() == 2 && country.length() == 2) {
                return new String[]{country, lang};
            }
            if (lang.length() == 2 && country.length() > 2) {
                return new String[]{"", lang};
            }
        }
        if (s.length() == 2) {
            return new String[]{"", s.toLowerCase(Locale.ROOT)};
        }
        return new String[]{"", ""};
    }

    private static JSONObject asObject(Object value) {
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        if (value instanceof String) {
            String s = ((String) value).trim();
            if (s.startsWith("{")) {
                try {
                    return JSONObject.parseObject(s);
                } catch (Exception ignore) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String str(JSONObject o, String key) {
        if (o == null || key == null) {
            return "";
        }
        Object v = o.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static long longVal(JSONObject o, String key) {
        if (o == null) {
            return 0L;
        }
        Object v = o.get(key);
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String digitsOnly(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
