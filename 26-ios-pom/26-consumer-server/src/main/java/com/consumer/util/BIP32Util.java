package com.consumer.util;

import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.math.ec.ECPoint;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * BIP32 HD Wallet 派生工具（secp256k1 曲线）。
 * <p>
 * 流程：BIP39 助记词 → 种子（PBKDF2-HMAC-SHA512）→ 主密钥 → BIP44 路径派生 → 私钥/公钥。
 * <p>
 * 用于 BTC/ETH/BSC/TRON 地址派生。SOL 使用 {@link Slip10Ed25519}。
 */
public class BIP32Util {

    private static final X9ECParameters CURVE_PARAMS = SECNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters CURVE =
            new ECDomainParameters(CURVE_PARAMS.getCurve(), CURVE_PARAMS.getG(), CURVE_PARAMS.getN(), CURVE_PARAMS.getH());

    /** secp256k1 曲线阶 n */
    private static final BigInteger N = CURVE_PARAMS.getN();

    /** BIP39 种子派生：PBKDF2-HMAC-SHA512，迭代 2048 次，输出 64 字节种子 */
    public static byte[] mnemonicToSeed(String mnemonic, String passphrase) {
        try {
            String salt = "mnemonic" + (passphrase == null ? "" : passphrase);
            // 优先使用 JCE 标准实现 PBKDF2WithHmacSHA512（多数 JVM 有硬件加速/原生优化），
            // 比手写 HMAC 循环快 2-5 倍，且行为完全等价。
            try {
                PBEKeySpec spec = new PBEKeySpec(
                        mnemonic.toCharArray(),
                        salt.getBytes(StandardCharsets.UTF_8),
                        2048, 64 * 8);
                SecretKeyFactory kf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
                return kf.generateSecret(spec).getEncoded();
            } catch (Exception jce) {
                // 兜底：JCE 不可用时回退到纯 Java 实现
                return pbkdf2HmacSha512Manual(mnemonic.getBytes(StandardCharsets.UTF_8),
                        salt.getBytes(StandardCharsets.UTF_8), 2048, 64);
            }
        } catch (Exception e) {
            throw new RuntimeException("mnemonicToSeed fail: " + e.getMessage(), e);
        }
    }

    /** PBKDF2-HMAC-SHA512 纯 Java 兜底实现（仅当 JCE 不可用时使用） */
    private static byte[] pbkdf2HmacSha512Manual(byte[] password, byte[] salt, int iterations, int dkLen) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(password, "HmacSHA512"));

        int hLen = mac.getMacLength();
        int blocks = (dkLen + hLen - 1) / hLen;
        byte[] out = new byte[blocks * hLen];
        byte[] blockBytes = new byte[4];
        for (int i = 1; i <= blocks; i++) {
            blockBytes[0] = (byte) (i >>> 24);
            blockBytes[1] = (byte) (i >>> 16);
            blockBytes[2] = (byte) (i >>> 8);
            blockBytes[3] = (byte) (i);

            mac.reset();
            mac.update(salt);
            byte[] U = mac.doFinal(blockBytes);
            byte[] T = U.clone();
            for (int j = 1; j < iterations; j++) {
                mac.reset();
                U = mac.doFinal(U);
                for (int k = 0; k < hLen; k++) T[k] ^= U[k];
            }
            System.arraycopy(T, 0, out, (i - 1) * hLen, hLen);
        }
        byte[] result = new byte[dkLen];
        System.arraycopy(out, 0, result, 0, dkLen);
        return result;
    }

    /**
     * 从种子派生主密钥（HMAC-SHA512 key="Bitcoin seed"）。
     * 返回 [32字节主私钥, 32字节链码]。
     */
    private static byte[][] masterKey(byte[] seed) throws Exception {
        byte[] I = hmacSha512("Bitcoin seed".getBytes(StandardCharsets.UTF_8), seed);
        byte[] IL = Arrays.copyOfRange(I, 0, 32);
        byte[] IR = Arrays.copyOfRange(I, 32, 64);
        return new byte[][]{IL, IR};
    }

    /**
     * BIP32 CKD（Child Key Derivation）。
     * <p>
     * 支持硬化派生（index >= 0x80000000）和普通派生。
     *
     * @param parentPriv 32字节父私钥
     * @param chainCode  32字节父链码
     * @param index      派生索引
     * @return [32字节子私钥, 32字节子链码]
     */
    private static byte[][] ckdPriv(byte[] parentPriv, byte[] chainCode, int index) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(chainCode, "HmacSHA512"));

        byte[] data;
        if ((index & 0x80000000) != 0) {
            // 硬化派生：0x00 || parentPriv || index(4字节BE)
            data = new byte[1 + 32 + 4];
            data[0] = 0x00;
            System.arraycopy(parentPriv, 0, data, 1, 32);
            writeBE32(data, 33, index);
        } else {
            // 普通派生：parentPub || index(4字节BE)
            byte[] pub = pointCompressed(parentPriv);
            data = new byte[33 + 4];
            System.arraycopy(pub, 0, data, 0, 33);
            writeBE32(data, 33, index);
        }
        byte[] I = mac.doFinal(data);
        byte[] IL = Arrays.copyOfRange(I, 0, 32);
        byte[] IR = Arrays.copyOfRange(I, 32, 64);

        // ki = (parse256(IL) + kpar) mod n
        BigInteger ilInt = new BigInteger(1, IL);
        BigInteger kPar = new BigInteger(1, parentPriv);
        BigInteger ki = ilInt.add(kPar).mod(N);
        if (ki.equals(BigInteger.ZERO) || ilInt.compareTo(N) >= 0) {
            throw new RuntimeException("invalid derived key, try next index");
        }
        byte[] childPriv = ki.toByteArray();
        // 左填充到 32 字节
        byte[] padded = new byte[32];
        System.arraycopy(childPriv, Math.max(0, childPriv.length - 32),
                padded, Math.max(0, 32 - childPriv.length), Math.min(32, childPriv.length));
        return new byte[][]{padded, IR};
    }

    /**
     * 按 BIP44 路径 m/44'/{coin}'/0'/0/{idx} 派生私钥。
     *
     * @param mnemonic 助记词
     * @param coinType 链类型常量（BTC=0, ETH=60, TRON=195）
     * @param idx      地址索引
     * @return 32字节私钥
     */
    public static byte[] derivePrivateKey(String mnemonic, int coinType, int idx) throws Exception {
        byte[] seed = mnemonicToSeed(mnemonic, "");
        return derivePrivateKeyFromSeed(seed, coinType, idx);
    }

    /**
     * 从已计算好的 seed 按 BIP44 路径 m/44'/{coin}'/0'/0/{idx} 派生私钥。
     * <p>多链地址派生场景下，可调用一次 {@link #mnemonicToSeed} 后复用同一 seed
     * 派生 BTC/ETH/BSC/TRON 全部地址，避免每个链重复 PBKDF2(2048轮) 计算。
     *
     * @param seed     BIP39 种子（64 字节，来自 mnemonicToSeed）
     * @param coinType 链类型常量（BTC=0, ETH=60, TRON=195）
     * @param idx      地址索引
     * @return 32字节私钥
     */
    public static byte[] derivePrivateKeyFromSeed(byte[] seed, int coinType, int idx) throws Exception {
        byte[][] master = masterKey(seed);

        // m/44'
        byte[][] l1 = ckdPriv(master[0], master[1], 0x80000000 + 44);
        // m/44'/{coin}'
        byte[][] l2 = ckdPriv(l1[0], l1[1], 0x80000000 + coinType);
        // m/44'/{coin}'/0'
        byte[][] l3 = ckdPriv(l2[0], l2[1], 0x80000000 + 0);
        // m/44'/{coin}'/0'/0
        byte[][] l4 = ckdPriv(l3[0], l3[1], 0);
        // m/44'/{coin}'/0'/0/{idx}
        byte[][] l5 = ckdPriv(l4[0], l4[1], idx);
        return l5[0];
    }

    /**
     * 从私钥派生压缩公钥（33字节：0x02/0x03 前缀 + 32字节 X 坐标）。
     */
    public static byte[] pointCompressed(byte[] privKey) {
        BigInteger priv = new BigInteger(1, privKey);
        ECPoint point = CURVE.getG().multiply(priv).normalize();
        return point.getEncoded(true);
    }

    /**
     * 从私钥派生未压缩公钥（65字节：0x04 前缀 + 32字节 X + 32字节 Y）。
     */
    public static byte[] pointUncompressed(byte[] privKey) {
        BigInteger priv = new BigInteger(1, privKey);
        ECPoint point = CURVE.getG().multiply(priv).normalize();
        return point.getEncoded(false);
    }

    // ==================== 地址编码 ====================

    /**
     * Keccak-256（用于 ETH/BSC/TRON 地址派生）。
     */
    public static byte[] keccak256(byte[] input) {
        KeccakDigest digest = new KeccakDigest(256);
        digest.update(input, 0, input.length);
        byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return out;
    }

    /**
     * RIPEMD-160（用于 BTC 地址派生）。
     */
    public static byte[] ripemd160(byte[] input) {
        RIPEMD160Digest digest = new RIPEMD160Digest();
        digest.update(input, 0, input.length);
        byte[] out = new byte[20];
        digest.doFinal(out, 0);
        return out;
    }

    /**
     * SHA-256（用于 Base58Check 校验和）。
     */
    public static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Base58Check 编码：version || payload || checksum(前4字节SHA256^2) → Base58。
     */
    public static String base58CheckEncode(byte version, byte[] payload) {
        byte[] data = new byte[1 + payload.length];
        data[0] = version;
        System.arraycopy(payload, 0, data, 1, payload.length);
        byte[] checksum = sha256(sha256(data));
        byte[] full = new byte[data.length + 4];
        System.arraycopy(data, 0, full, 0, data.length);
        System.arraycopy(checksum, 0, full, data.length, 4);
        return base58Encode(full);
    }

    /**
     * Base58Check 编码（带多字节 version 前缀，如 TRON 用 0x41）。
     */
    public static String base58CheckEncode(byte[] version, byte[] payload) {
        byte[] data = new byte[version.length + payload.length];
        System.arraycopy(version, 0, data, 0, version.length);
        System.arraycopy(payload, 0, data, version.length, payload.length);
        byte[] checksum = sha256(sha256(data));
        byte[] full = new byte[data.length + 4];
        System.arraycopy(data, 0, full, 0, data.length);
        System.arraycopy(checksum, 0, full, data.length, 4);
        return base58Encode(full);
    }

    // ==================== Base58 ====================

    private static final char[] BASE58_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    public static String base58Encode(byte[] input) {
        if (input.length == 0) return "";
        // 计算前导 0
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) zeros++;
        // Base58 编码
        byte[] copy = Arrays.copyOf(input, input.length);
        char[] encoded = new char[input.length * 2];
        int outputStart = encoded.length;
        int inputStart = zeros;
        while (inputStart < copy.length) {
            encoded[--outputStart] = BASE58_ALPHABET[divmod(copy, inputStart, 256, 58)];
            if (copy[inputStart] == 0) inputStart++;
        }
        while (outputStart < encoded.length && encoded[outputStart] == BASE58_ALPHABET[0]) outputStart++;
        for (int i = 0; i < zeros; i++) encoded[--outputStart] = BASE58_ALPHABET[0];
        return new String(encoded, outputStart, encoded.length - outputStart);
    }

    private static byte divmod(byte[] number, int firstDigit, int base, int divisor) {
        int remainder = 0;
        for (int i = firstDigit; i < number.length; i++) {
            int digit = number[i] & 0xFF;
            int temp = remainder * base + digit;
            number[i] = (byte) (temp / divisor);
            remainder = temp % divisor;
        }
        return (byte) remainder;
    }

    // ==================== 工具 ====================

    private static byte[] hmacSha512(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(key, "HmacSHA512"));
        return mac.doFinal(data);
    }

    private static void writeBE32(byte[] arr, int offset, int value) {
        arr[offset] = (byte) (value >>> 24);
        arr[offset + 1] = (byte) (value >>> 16);
        arr[offset + 2] = (byte) (value >>> 8);
        arr[offset + 3] = (byte) (value);
    }

    /** 硬化索引：i' */
    public static int hard(int i) {
        return 0x80000000 + i;
    }

    /**
     * 按自定义 BIP32 路径从 seed 派生私钥。indexes 已含硬化位。
     */
    public static byte[] deriveByPathFromSeed(byte[] seed, int... indexes) throws Exception {
        byte[][] cur = masterKey(seed);
        for (int idx : indexes) {
            cur = ckdPriv(cur[0], cur[1], idx);
        }
        return cur[0];
    }
}
