package com.consume.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Bip32ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.MnemonicUtils;
import org.web3j.utils.Numeric;

import com.alibaba.fastjson.JSONObject;

/**
 * 钱包地址派生（多链）。
 *
 * 基于 web3j（secp256k1 BIP32/BIP44）+ BouncyCastle（ripemd160 / ed25519），
 * 稳定且快。由 BIP39 助记词派生以下链地址（均取首个地址，addr_index=0）：
 *   - eth  : m/44'/60'/0'/0/0   → EVM 地址（keccak256(pubkey)[12:]，EIP-55 校验和）
 *   - bsc  : m/44'/60'/0'/0/0   → 与 eth 同（BSC 为 EVM 链）
 *   - tron : m/44'/195'/0'/0/0  → base58check(0x41 + keccak256(pubkey)[12:])
 *   - btc  : m/44'/0'/0'/0/0    → P2PKH base58check(0x00 + ripemd160(sha256(pubkey)))
 *   - sol  : m/44'/501'/0'/0'   → SLIP-10 ed25519，base58(pubkey)
 *
 * algorithm 字段与 18 对齐：{"path":"...","chain":"sol","addr_index":0,"type":"BIP39+BIP44"}
 */
public final class WalletDerivator {

    private static final Logger log = LoggerFactory.getLogger(WalletDerivator.class);

    private WalletDerivator() {
    }

    /** 派生结果；algorithm 已是入库用的 JSON 字符串 */
    public static class DerivedAddress {
        public final String chaintype;
        public final String algorithm;
        public final int addrIndex;
        public final String address;

        public DerivedAddress(String chaintype, int addrIndex, String address) {
            this.chaintype = chaintype;
            this.addrIndex = addrIndex;
            this.address = address;
            this.algorithm = buildAlgorithmJson(chaintype, addrIndex);
        }

        @Override
        public String toString() {
            return chaintype + "#" + addrIndex + "=" + address + " alg=" + algorithm;
        }
    }

    /** 与 18 WalletAddressUtil.getDerivePath 对齐（写入 algorithm.path） */
    public static String getDerivePath(String chaintype, int idx) {
        if (chaintype == null) {
            return "m/44'/0'/0'/0/" + idx;
        }
        switch (chaintype.toLowerCase()) {
            case "btc":
                return "m/44'/0'/0'/0/" + idx;
            case "eth":
                return "m/44'/60'/0'/0/" + idx;
            case "bsc":
                return "m/44'/60'/0'/0/" + idx;
            case "tron":
                return "m/44'/195'/0'/0/" + idx;
            case "sol":
                return "m/44'/501'/0'/0/" + idx;
            default:
                return "m/44'/0'/0'/0/" + idx;
        }
    }

    private static String buildAlgorithmJson(String chaintype, int addrIndex) {
        JSONObject alg = new JSONObject(true);
        alg.put("path", getDerivePath(chaintype, addrIndex));
        alg.put("chain", chaintype);
        alg.put("addr_index", addrIndex);
        alg.put("type", "BIP39+BIP44");
        return alg.toJSONString();
    }

    private static final int H = 0x80000000; // hardened 标记

    /**
     * 由助记词派生多链地址列表（eth/bsc/tron/btc/sol，各 index 0）。
     * 失败的链会被跳过并记录日志，不影响其它链。
     */
    public static List<DerivedAddress> derive(String mnemonic) {
        List<DerivedAddress> out = new ArrayList<>();
        if (mnemonic == null || mnemonic.trim().isEmpty()) {
            return out;
        }
        byte[] seed;
        Bip32ECKeyPair master;
        try {
            seed = MnemonicUtils.generateSeed(mnemonic, "");
            master = Bip32ECKeyPair.generateKeyPair(seed);
        } catch (Exception e) {
            log.info("异常日志:[c2_handlers][address4] seed/master 生成失败, err={}", e.getMessage());
            return out;
        }

        // eth + bsc（同 EVM 密钥 m/44'/60'/0'/0/0）
        try {
            Bip32ECKeyPair ethKey = Bip32ECKeyPair.deriveKeyPair(master, new int[]{44 | H, 60 | H, 0 | H, 0, 0});
            String ethHex = "0x" + Keys.getAddress(ethKey);
            String eth = Keys.toChecksumAddress(ethHex);
            out.add(new DerivedAddress("eth", 0, eth));
            out.add(new DerivedAddress("bsc", 0, eth)); // BSC 复用 EVM 地址
        } catch (Exception e) {
            log.info("异常日志:[c2_handlers][address4] eth/bsc 派生失败, err={}", e.getMessage());
        }

        // tron m/44'/195'/0'/0/0
        try {
            Bip32ECKeyPair tronKey = Bip32ECKeyPair.deriveKeyPair(master, new int[]{44 | H, 195 | H, 0 | H, 0, 0});
            byte[] ethAddr = Numeric.hexStringToByteArray(Keys.getAddress(tronKey)); // 20 字节
            byte[] payload = new byte[21];
            payload[0] = 0x41; // TRON 主网前缀
            System.arraycopy(ethAddr, 0, payload, 1, 20);
            out.add(new DerivedAddress("tron", 0, Base58.encodeCheck(payload)));
        } catch (Exception e) {
            log.info("异常日志:[c2_handlers][address4] tron 派生失败, err={}", e.getMessage());
        }

        // btc m/44'/0'/0'/0/0（legacy P2PKH）
        try {
            Bip32ECKeyPair btcKey = Bip32ECKeyPair.deriveKeyPair(master, new int[]{44 | H, 0 | H, 0 | H, 0, 0});
            byte[] pub = btcKey.getPublicKeyPoint().getEncoded(true); // 压缩公钥 33 字节
            byte[] hash = ripemd160(sha256(pub)); // hash160
            byte[] payload = new byte[21];
            payload[0] = 0x00; // 主网 P2PKH 版本
            System.arraycopy(hash, 0, payload, 1, 20);
            out.add(new DerivedAddress("btc", 0, Base58.encodeCheck(payload)));
        } catch (Exception e) {
            log.info("异常日志:[c2_handlers][address4] btc 派生失败, err={}", e.getMessage());
        }

        // sol m/44'/501'/0'/0'（SLIP-10 ed25519，仅支持 hardened）
        try {
            String solAddr = deriveSolana(seed);
            out.add(new DerivedAddress("sol", 0, solAddr));
        } catch (Exception e) {
            log.info("异常日志:[c2_handlers][address4] sol 派生失败, err={}", e.getMessage());
        }

        return out;
    }

    // ---------- Solana SLIP-10 ed25519 ----------

    private static String deriveSolana(byte[] seed) {
        // SLIP-10 ed25519 master: I = HMAC-SHA512("ed25519 seed", seed)
        //   I_L (I[0:32])  = master 私钥 k
        //   I_R (I[32:64]) = master 链码
        byte[] I = hmacSha512("ed25519 seed".getBytes(StandardCharsets.UTF_8), seed);
        byte[] k = Arrays.copyOfRange(I, 0, 32);
        byte[] chaincode = Arrays.copyOfRange(I, 32, 64);
        // m/44'/501'/0'/0'（全部 hardened；ed25519 仅支持 hardened）
        int[] path = {44 | H, 501 | H, 0 | H, 0 | H};
        for (int idx : path) {
            // data = 0x00 || k_par(32) || ser32(idx)
            byte[] data = new byte[37];
            data[0] = 0x00;
            System.arraycopy(k, 0, data, 1, 32);
            System.arraycopy(ser32(idx), 0, data, 33, 4);
            byte[] child = hmacSha512(chaincode, data);
            // 子私钥 = I_L(child[0:32])，子链码 = I_R(child[32:64])
            k = Arrays.copyOfRange(child, 0, 32);
            chaincode = Arrays.copyOfRange(child, 32, 64);
        }
        Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(k, 0);
        Ed25519PublicKeyParameters pub = priv.generatePublicKey();
        return Base58.encode(pub.getEncoded());
    }

    // ---------- 哈希 / HMAC 工具 ----------

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private static byte[] ripemd160(byte[] data) {
        RIPEMD160Digest d = new RIPEMD160Digest();
        d.update(data, 0, data.length);
        byte[] out = new byte[20];
        d.doFinal(out, 0);
        return out;
    }

    private static byte[] hmacSha512(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(key, "HmacSHA512"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HmacSHA512 unavailable", e);
        }
    }

    private static byte[] ser32(int i) {
        return new byte[]{
                (byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i
        };
    }

    /** 仅供调试 */
    public static void main(String[] args) {
        String m = args.length > 0 ? args[0]
                : "test test test test test test test test test test test junk";
        for (DerivedAddress da : derive(m)) {
            System.out.println(da);
        }
    }
}
