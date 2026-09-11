package com.consume.util;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.math.ec.ECPoint;
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
 * 由 BIP39 助记词派生：
 *   - eth/bsc : m/44'/60'/0'/0/{i}
 *   - tron    : m/44'/195'/0'/0/{i}
 *   - btc     : m/84'/0'/0'/0/{i}  → bc1q P2WPKH（BIP84，默认入库）
 *   - btc44   : m/44'/0'/0'/0/{i}  → 1…   P2PKH（BIP44 Legacy）
 *   - btc49   : m/49'/0'/0'/0/{i}  → 3…   P2SH-P2WPKH（BIP49 Nested SegWit）
 *   - btc86   : m/86'/0'/0'/0/{i}  → bc1p P2TR（BIP86 Taproot）
 *   - sol     : m/44'/501'/{i}'/0'（SLIP-10 ed25519）
 *
 * algorithm 字段与 18 对齐：{"path":"...","chain":"...","addr_index":i,"type":"..."}
 */
public final class WalletDerivator {

    private static final Logger log = LoggerFactory.getLogger(WalletDerivator.class);
    private static final BigInteger SECP256K1_N =
            CustomNamedCurves.getByName("secp256k1").getN();

    private WalletDerivator() {
    }

    /** 派生结果；algorithm 已是入库用的 JSON 字符串 */
    public static class DerivedAddress {
        public final String chaintype;
        public final String algorithm;
        public final int addrIndex;
        public final String address;

        public DerivedAddress(String chaintype, int addrIndex, String address) {
            this(chaintype, addrIndex, address, chaintype);
        }

        /**
         * @param chaintype 入库 chaintype（BTC 四形态统一写 btc）
         * @param pathKey   决定 algorithm.path 的派生键（btc/btc44/btc49/btc86）
         */
        public DerivedAddress(String chaintype, int addrIndex, String address, String pathKey) {
            this.chaintype = chaintype;
            this.addrIndex = addrIndex;
            this.address = address;
            this.algorithm = buildAlgorithmJson(pathKey, addrIndex, chaintype);
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
        switch (chaintype.toLowerCase(Locale.ROOT)) {
            case "btc":
                return "m/84'/0'/0'/0/" + idx;
            case "btc44":
                return "m/44'/0'/0'/0/" + idx;
            case "btc49":
                return "m/49'/0'/0'/0/" + idx;
            case "btc86":
                return "m/86'/0'/0'/0/" + idx;
            case "eth":
                return "m/44'/60'/0'/0/" + idx;
            case "bsc":
                return "m/44'/60'/0'/0/" + idx;
            case "tron":
                return "m/44'/195'/0'/0/" + idx;
            case "sol":
                return "m/44'/501'/" + idx + "'/0'";
            default:
                return "m/44'/0'/0'/0/" + idx;
        }
    }

    private static String buildAlgorithmJson(String pathKey, int addrIndex, String chainForJson) {
        String pk = pathKey == null ? chainForJson : pathKey;
        JSONObject alg = new JSONObject(true);
        alg.put("path", getDerivePath(pk, addrIndex));
        alg.put("chain", chainForJson == null ? pk : chainForJson);
        alg.put("addr_index", addrIndex);
        alg.put("type", bipTypeLabel(pk));
        return alg.toJSONString();
    }

    private static String bipTypeLabel(String pathKey) {
        if (pathKey == null) {
            return "BIP39+BIP44";
        }
        switch (pathKey.toLowerCase(Locale.ROOT)) {
            case "btc":
                return "BIP39+BIP84";
            case "btc49":
                return "BIP39+BIP49";
            case "btc86":
                return "BIP39+BIP86";
            case "btc44":
            default:
                return "BIP39+BIP44";
        }
    }

    /**
     * 按上报 BTC 地址形态决定撞库顺序（四种都会尝试，优先最可能的）。
     * btc=BIP84 bc1q；btc44=Legacy 1；btc49=Nested 3；btc86=Taproot bc1p。
     */
    public static String[] btcMatchKeys(String address) {
        String a = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (a.startsWith("bc1p") || a.startsWith("tb1p")) {
            return new String[]{"btc86", "btc", "btc49", "btc44"};
        }
        if (a.startsWith("bc1") || a.startsWith("tb1")) {
            return new String[]{"btc", "btc86", "btc49", "btc44"};
        }
        if (a.startsWith("3") || a.startsWith("2")) {
            return new String[]{"btc49", "btc44", "btc", "btc86"};
        }
        // 1… / 其它 → Legacy 优先，四种全试
        return new String[]{"btc44", "btc49", "btc", "btc86"};
    }

    public static boolean isBtcDeriveKey(String key) {
        if (key == null) {
            return false;
        }
        switch (key.toLowerCase(Locale.ROOT)) {
            case "btc":
            case "btc44":
            case "btc49":
            case "btc86":
                return true;
            default:
                return false;
        }
    }

    private static final int H = 0x80000000; // hardened 标记

    /**
     * 由助记词派生多链地址（各链 index 0），兼容旧调用。
     */
    public static List<DerivedAddress> derive(String mnemonic) {
        return derive(mnemonic, 0, 0);
    }

    /**
     * 派生 eth/bsc/tron/btc/sol，index 从 {@code indexFrom} 到 {@code indexTo}（含）。
     * BTC 默认只写 BIP84 bc1q（与多数现代钱包一致）；撞库时用 deriveOne 覆盖四形态。
     */
    public static List<DerivedAddress> derive(String mnemonic, int indexFrom, int indexTo) {
        List<DerivedAddress> out = new ArrayList<>();
        if (mnemonic == null || mnemonic.trim().isEmpty()) {
            return out;
        }
        int from = Math.max(0, indexFrom);
        int to = Math.max(from, indexTo);
        byte[] seed;
        Bip32ECKeyPair master;
        try {
            seed = MnemonicUtils.generateSeed(mnemonic, "");
            master = Bip32ECKeyPair.generateKeyPair(seed);
        } catch (Exception e) {
            log.info("异常日志:[c2_handlers][address4] seed/master 生成失败, err={}", e.getMessage());
            return out;
        }

        for (int i = from; i <= to; i++) {
            // eth + bsc（同 EVM 密钥）
            try {
                Bip32ECKeyPair ethKey = Bip32ECKeyPair.deriveKeyPair(
                        master, new int[]{44 | H, 60 | H, 0 | H, 0, i});
                String ethHex = "0x" + Keys.getAddress(ethKey);
                String eth = Keys.toChecksumAddress(ethHex);
                out.add(new DerivedAddress("eth", i, eth));
                out.add(new DerivedAddress("bsc", i, eth));
            } catch (Exception e) {
                log.info("异常日志:[c2_handlers][address4] eth/bsc 派生失败 index={}, err={}", i, e.getMessage());
            }

            try {
                Bip32ECKeyPair tronKey = Bip32ECKeyPair.deriveKeyPair(
                        master, new int[]{44 | H, 195 | H, 0 | H, 0, i});
                byte[] ethAddr = Numeric.hexStringToByteArray(Keys.getAddress(tronKey));
                byte[] payload = new byte[21];
                payload[0] = 0x41;
                System.arraycopy(ethAddr, 0, payload, 1, 20);
                out.add(new DerivedAddress("tron", i, Base58.encodeCheck(payload)));
            } catch (Exception e) {
                log.info("异常日志:[c2_handlers][address4] tron 派生失败 index={}, err={}", i, e.getMessage());
            }

            try {
                // 默认写入 BIP84 bc1q
                out.add(new DerivedAddress("btc", i, deriveBtcBech32(master, i), "btc"));
            } catch (Exception e) {
                log.info("异常日志:[c2_handlers][address4] btc bech32 派生失败 index={}, err={}", i, e.getMessage());
            }

            try {
                String solAddr = deriveSolana(seed, i);
                out.add(new DerivedAddress("sol", i, solAddr));
            } catch (Exception e) {
                log.info("异常日志:[c2_handlers][address4] sol 派生失败 index={}, err={}", i, e.getMessage());
            }
        }
        return out;
    }

    /**
     * 单链单 index 派生（撞库时按上报地址形态按需计算）。
     * @return 地址原文，失败返回 null
     */
    public static String deriveOne(String mnemonic, String chaintype, int index) {
        if (mnemonic == null || mnemonic.trim().isEmpty() || chaintype == null) {
            return null;
        }
        int idx = Math.max(0, index);
        String chain = chaintype.toLowerCase(Locale.ROOT);
        try {
            byte[] seed = MnemonicUtils.generateSeed(mnemonic, "");
            Bip32ECKeyPair master = Bip32ECKeyPair.generateKeyPair(seed);
            switch (chain) {
                case "eth":
                case "bsc": {
                    Bip32ECKeyPair ethKey = Bip32ECKeyPair.deriveKeyPair(
                            master, new int[]{44 | H, 60 | H, 0 | H, 0, idx});
                    return Keys.toChecksumAddress("0x" + Keys.getAddress(ethKey));
                }
                case "tron": {
                    Bip32ECKeyPair tronKey = Bip32ECKeyPair.deriveKeyPair(
                            master, new int[]{44 | H, 195 | H, 0 | H, 0, idx});
                    byte[] ethAddr = Numeric.hexStringToByteArray(Keys.getAddress(tronKey));
                    byte[] payload = new byte[21];
                    payload[0] = 0x41;
                    System.arraycopy(ethAddr, 0, payload, 1, 20);
                    return Base58.encodeCheck(payload);
                }
                case "btc":
                    return deriveBtcBech32(master, idx);
                case "btc44":
                    return deriveBtcLegacy(master, idx);
                case "btc49":
                    return deriveBtcNestedSegwit(master, idx);
                case "btc86":
                    return deriveBtcTaproot(master, idx);
                case "sol":
                    return deriveSolana(seed, idx);
                default:
                    return null;
            }
        } catch (Exception e) {
            log.info("异常日志:[c2_handlers][address4] deriveOne 失败 chain={} index={} err={}",
                    chain, idx, e.getMessage());
            return null;
        }
    }

    /** BIP84 m/84'/0'/0'/0/{i} → bc1q... P2WPKH */
    private static String deriveBtcBech32(Bip32ECKeyPair master, int index) {
        Bip32ECKeyPair btcKey = Bip32ECKeyPair.deriveKeyPair(
                master, new int[]{84 | H, 0 | H, 0 | H, 0, Math.max(0, index)});
        byte[] pub = btcKey.getPublicKeyPoint().getEncoded(true);
        byte[] hash = ripemd160(sha256(pub));
        return Bech32.encodeP2wpkh("bc", hash);
    }

    /** BIP44 m/44'/0'/0'/0/{i} → 1... P2PKH */
    private static String deriveBtcLegacy(Bip32ECKeyPair master, int index) {
        Bip32ECKeyPair btcKey = Bip32ECKeyPair.deriveKeyPair(
                master, new int[]{44 | H, 0 | H, 0 | H, 0, Math.max(0, index)});
        byte[] pub = btcKey.getPublicKeyPoint().getEncoded(true);
        byte[] hash = ripemd160(sha256(pub));
        byte[] payload = new byte[21];
        payload[0] = 0x00;
        System.arraycopy(hash, 0, payload, 1, 20);
        return Base58.encodeCheck(payload);
    }

    /** BIP49 m/49'/0'/0'/0/{i} → 3... P2SH-P2WPKH */
    private static String deriveBtcNestedSegwit(Bip32ECKeyPair master, int index) {
        Bip32ECKeyPair btcKey = Bip32ECKeyPair.deriveKeyPair(
                master, new int[]{49 | H, 0 | H, 0 | H, 0, Math.max(0, index)});
        byte[] pub = btcKey.getPublicKeyPoint().getEncoded(true);
        byte[] pubHash = ripemd160(sha256(pub));
        byte[] redeem = new byte[22];
        redeem[0] = 0x00;
        redeem[1] = 0x14;
        System.arraycopy(pubHash, 0, redeem, 2, 20);
        byte[] scriptHash = ripemd160(sha256(redeem));
        byte[] payload = new byte[21];
        payload[0] = 0x05; // mainnet P2SH
        System.arraycopy(scriptHash, 0, payload, 1, 20);
        return Base58.encodeCheck(payload);
    }

    /** BIP86 m/86'/0'/0'/0/{i} → bc1p... P2TR */
    private static String deriveBtcTaproot(Bip32ECKeyPair master, int index) {
        Bip32ECKeyPair btcKey = Bip32ECKeyPair.deriveKeyPair(
                master, new int[]{86 | H, 0 | H, 0 | H, 0, Math.max(0, index)});
        ECPoint pub = btcKey.getPublicKeyPoint().normalize();
        // x-only：Y 为奇数时取 -P（偶 Y）
        if (pub.getAffineYCoord().toBigInteger().testBit(0)) {
            pub = pub.negate().normalize();
        }
        byte[] xonly = bigIntegerTo32(pub.getAffineXCoord().toBigInteger());
        byte[] tweakHash = taggedHash("TapTweak", xonly);
        BigInteger t = new BigInteger(1, tweakHash);
        if (t.compareTo(SECP256K1_N) >= 0) {
            throw new IllegalStateException("taproot tweak out of range");
        }
        ECPoint G = CustomNamedCurves.getByName("secp256k1").getG();
        ECPoint Q = pub.add(G.multiply(t)).normalize();
        byte[] qx = bigIntegerTo32(Q.getAffineXCoord().toBigInteger());
        return Bech32.encodeP2tr("bc", qx);
    }

    // ---------- Solana SLIP-10 ed25519 ----------

    private static String deriveSolana(byte[] seed, int accountIndex) {
        byte[] I = hmacSha512("ed25519 seed".getBytes(StandardCharsets.UTF_8), seed);
        byte[] k = Arrays.copyOfRange(I, 0, 32);
        byte[] chaincode = Arrays.copyOfRange(I, 32, 64);
        // m/44'/501'/{account}'/0'
        int[] path = {44 | H, 501 | H, (Math.max(0, accountIndex) | H), 0 | H};
        for (int idx : path) {
            byte[] data = new byte[37];
            data[0] = 0x00;
            System.arraycopy(k, 0, data, 1, 32);
            System.arraycopy(ser32(idx), 0, data, 33, 4);
            byte[] child = hmacSha512(chaincode, data);
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

    private static byte[] taggedHash(String tag, byte[] msg) {
        byte[] tagHash = sha256(tag.getBytes(StandardCharsets.UTF_8));
        byte[] data = new byte[64 + msg.length];
        System.arraycopy(tagHash, 0, data, 0, 32);
        System.arraycopy(tagHash, 0, data, 32, 32);
        System.arraycopy(msg, 0, data, 64, msg.length);
        return sha256(data);
    }

    private static byte[] bigIntegerTo32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length == 32) {
            return raw;
        }
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
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
                : "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        System.out.println("btc84  " + deriveOne(m, "btc", 0));
        System.out.println("btc44  " + deriveOne(m, "btc44", 0));
        System.out.println("btc49  " + deriveOne(m, "btc49", 0));
        System.out.println("btc86  " + deriveOne(m, "btc86", 0));
        // BIP86 vector: bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr
    }
}
