package com.admin.util;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * SLIP-0010 Ed25519 HD Wallet 派生工具。
 * <p>
 * 用于 Solana（SOL）地址派生。Ed25519 只支持硬化派生（index >= 0x80000000）。
 * <p>
 * 流程：BIP39 助记词 → 种子 → 主密钥 → SLIP-0010 路径派生 → Ed25519 私钥 → 公钥 → Base58 地址。
 */
public class Slip10Ed25519 {

    /**
     * 从种子派生主密钥。
     * HMAC-SHA512(key="ed25519 seed", msg=seed) → 前32字节主私钥，后32字节链码。
     */
    private static byte[][] masterKey(byte[] seed) throws Exception {
        byte[] I = hmacSha512("ed25519 seed".getBytes(StandardCharsets.UTF_8), seed);
        return new byte[][]{
                Arrays.copyOfRange(I, 0, 32),
                Arrays.copyOfRange(I, 32, 64)
        };
    }

    /**
     * SLIP-0010 CKD（仅硬化派生）。
     * HMAC-SHA512(key=chaincode, msg=0x00||parentPriv||index(4字节BE)) → 前32子私钥，后32子链码。
     */
    private static byte[][] ckdPriv(byte[] parentPriv, byte[] chainCode, int index) throws Exception {
        if ((index & 0x80000000) == 0) {
            throw new IllegalArgumentException("Ed25519 only supports hardened derivation");
        }
        byte[] data = new byte[1 + 32 + 4];
        data[0] = 0x00;
        System.arraycopy(parentPriv, 0, data, 1, 32);
        data[33] = (byte) (index >>> 24);
        data[34] = (byte) (index >>> 16);
        data[35] = (byte) (index >>> 8);
        data[36] = (byte) (index);

        byte[] I = hmacSha512(chainCode, data);
        return new byte[][]{
                Arrays.copyOfRange(I, 0, 32),
                Arrays.copyOfRange(I, 32, 64)
        };
    }

    /**
     * 按 BIP44 路径 m/44'/501'/0'/0/{idx} 派生 Ed25519 私钥。
     * <p>
     * 注意：SLIP-0010 要求所有派生都是硬化的，但路径 m/44'/501'/0'/0/{idx} 中的
     * 最后两级（0 和 {idx}）在实际钱包中可能用硬化或非硬化。
     * Solana Phantom 钱包标准路径是 m/44'/501'/0'/0'，这里按 DB 数据格式 m/44'/501'/0'/0/0 实现，
     * 非硬化级别用硬化替代（Ed25519 限制）。
     *
     * @param mnemonic 助记词
     * @param idx      地址索引
     * @return 32字节 Ed25519 私钥
     */
    public static byte[] derivePrivateKey(String mnemonic, int idx) throws Exception {
        byte[] seed = BIP32Util.mnemonicToSeed(mnemonic, "");
        return derivePrivateKeyFromSeed(seed, idx);
    }

    /**
     * 从已计算好的 seed 按 BIP44 路径 m/44'/501'/0'/0/{idx} 派生 Ed25519 私钥。
     * <p>多链地址派生场景下可与 {@link BIP32Util#derivePrivateKeyFromSeed} 共享同一 seed，
     * 避免每个链重复 PBKDF2(2048轮) 计算。
     *
     * @param seed BIP39 种子（64 字节，来自 BIP32Util.mnemonicToSeed）
     * @param idx  地址索引
     * @return 32字节 Ed25519 私钥
     */
    public static byte[] derivePrivateKeyFromSeed(byte[] seed, int idx) throws Exception {
        return derivePhantomFromSeed(seed, idx);
    }

    /**
     * Phantom 常用路径 m/44'/501'/{idx}'/0'（对齐 Python 26 address_derive._sol_ctx）。
     */
    public static byte[] derivePhantomFromSeed(byte[] seed, int idx) throws Exception {
        byte[][] master = masterKey(seed);
        byte[][] l1 = ckdPriv(master[0], master[1], 0x80000000 + 44);
        byte[][] l2 = ckdPriv(l1[0], l1[1], 0x80000000 + 501);
        byte[][] l3 = ckdPriv(l2[0], l2[1], 0x80000000 + idx);
        byte[][] l4 = ckdPriv(l3[0], l3[1], 0x80000000 + 0);
        return l4[0];
    }

    /**
     * 从 Ed25519 私钥（32字节种子）派生公钥（32字节）。
     */
    public static byte[] derivePublicKey(byte[] privKey) {
        Ed25519PrivateKeyParameters privParams = new Ed25519PrivateKeyParameters(privKey, 0);
        Ed25519PublicKeyParameters pubParams = privParams.generatePublicKey();
        return pubParams.getEncoded();
    }

    private static byte[] hmacSha512(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(key, "HmacSHA512"));
        return mac.doFinal(data);
    }
}
