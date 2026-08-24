package com.ctwo.util;

import java.nio.charset.StandardCharsets;
import java.security.spec.KeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import lombok.extern.slf4j.Slf4j;

/**
 * C2 上行「AES-256-CTR」信封解密（接口文档 §9）。
 *
 * <p>线格式：
 * <pre>
 * {"v":1,"alg":"aes-256-ctr","iv":"<b64>","ct":"<b64>"}
 * </pre>
 *
 * <p>密钥派生：PBKDF2(HmacSHA256, password, salt="lab_nuih_persist_4022_v1", iterations=10000, dkLen=256bit)。
 * 默认 password = {@code 9898asd147258}，可通过 {@code lab.enc.password} / {@code lab.enc.salt} 覆盖。
 *
 * <p>对明文 JSON body，原样返回；非信封 JSON / 解析失败也原样返回，保持对旧客户端明文的兼容。
 */
@Slf4j
public final class ExfilCrypto {

    public static final String DEFAULT_PASSWORD = "9898asd147258";
    public static final String DEFAULT_SALT = "lab_nuih_persist_4022_v1";
    public static final int DEFAULT_ITERATIONS = 10_000;

    private static final String AES_CTR_NOPADDING = "AES/CTR/NoPadding";
    private static final String AES = "AES";
    private static final String PBKDF2_ALG = "PBKDF2WithHmacSHA256";

    private ExfilCrypto() {}

    /**
     * 尝试解密信封；若 body 不是信封 JSON / 解密失败，返回原字符串。
     *
     * @param body            原始请求 body（UTF-8）
     * @param encPassword     可选配置口令；空则回退默认
     * @param encSalt         可选配置 salt；空则回退默认
     * @return 解密后的 UTF-8 文本，或原 body（不是信封 / 失败时）
     */
    public static String maybeDecryptExfilBody(String body, String encPassword, String encSalt) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        // 快速判定：信封以 '{' 开头，包含 "alg":"aes-256-ctr"
        int brace = body.indexOf('{');
        if (brace < 0) {
            return body;
        }
        String trimmed = body.substring(brace).trim();
        if (trimmed.isEmpty()) {
            return body;
        }
        // 必须包含这些字段名才能判定为信封；用字符串先做 cheap filter，避免 JSON parse 浪费
        if (!trimmed.contains("\"alg\"") || !trimmed.contains("\"iv\"") || !trimmed.contains("\"ct\"")) {
            return body;
        }
        JSONObject env;
        try {
            env = JSON.parseObject(trimmed);
        } catch (Exception e) {
            return body;
        }
        if (env == null) {
            return body;
        }
        String alg = env.getString("alg");
        String ivB64 = env.getString("iv");
        String ctB64 = env.getString("ct");
        Integer v = env.getInteger("v");
        // 只支持 v=1 + aes-256-ctr；否则视为普通 JSON 直接返回
        if ((v != null && v != 1)
                || alg == null
                || !"aes-256-ctr".equalsIgnoreCase(alg)
                || ivB64 == null || ivB64.isEmpty()
                || ctB64 == null || ctB64.isEmpty()) {
            return body;
        }
        try {
            String pwd = (encPassword == null || encPassword.isEmpty()) ? DEFAULT_PASSWORD : encPassword;
            String salt = (encSalt == null || encSalt.isEmpty()) ? DEFAULT_SALT : encSalt;
            byte[] key = deriveKey(pwd, salt, DEFAULT_ITERATIONS, 32);
            byte[] iv = Base64.getDecoder().decode(ivB64);
            byte[] ct = Base64.getDecoder().decode(ctB64);
            byte[] pt = decryptCtr(ct, key, iv);
            String plain = new String(pt, StandardCharsets.UTF_8);
            log.debug("exfil AES-256-ctr decrypt OK, bytesIn={}, bytesOut={}", ct.length, pt.length);
            return plain;
        } catch (Exception e) {
            log.warn("exfil decrypt envelope FAIL, err={}", e.getMessage());
            return body;
        }
    }

    public static byte[] deriveKey(String password, String salt, int iterations, int keyLenBytes) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALG);
            KeySpec spec = new PBEKeySpec(password.toCharArray(),
                    salt.getBytes(StandardCharsets.UTF_8), iterations, keyLenBytes * 8);
            SecretKey tmp = factory.generateSecret(spec);
            return tmp.getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 key derive failed: " + e.getMessage(), e);
        }
    }

    public static byte[] decryptCtr(byte[] ciphertext, byte[] key, byte[] iv) {
        if (ciphertext == null || key == null || iv == null) {
            throw new IllegalArgumentException("ciphertext/key/iv must not be null");
        }
        try {
            Cipher cipher = Cipher.getInstance(AES_CTR_NOPADDING);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, AES),
                    new IvParameterSpec(iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("AES-256-CTR decrypt failed: " + e.getMessage(), e);
        }
    }
}
