package com.consumer.util;

import java.util.Arrays;

/**
 * 多链钱包地址派生工具。
 * <p>
 * 输入 BIP39 助记词，按 BIP44 路径派生 5 条链的钱包地址：
 * <ul>
 *   <li>BTC:  m/44'/0'/0'/0/0   → P2PKH Base58Check 地址（1开头）</li>
 *   <li>ETH:  m/44'/60'/0'/0/0  → 0x + Keccak256(pubkey)[12:32]</li>
 *   <li>BSC:  m/44'/60'/0'/0/0  → 同 ETH 地址格式（与 ETH 共用派生路径）</li>
 *   <li>TRON: m/44'/195'/0'/0/0 → 0x41 前缀 + Keccak256(pubkey)[12:32] → Base58Check</li>
 *   <li>SOL:  m/44'/501'/0'/0/0 → Base58(Ed25519公钥)</li>
 * </ul>
 */
public class WalletAddressUtil {

    // BIP44 coin type 常量
    public static final int COIN_BTC = 0;
    public static final int COIN_ETH = 60;
    public static final int COIN_TRON = 195;
    public static final int COIN_SOL = 501;

    /**
     * 派生 ETH 地址。
     * 路径：m/44'/60'/0'/0/{idx}
     * 算法：未压缩公钥(65字节) → Keccak256 → 取后20字节 → 0x前缀
     */
    public static String deriveEth(String mnemonic, int idx) throws Exception {
        byte[] priv = BIP32Util.derivePrivateKey(mnemonic, COIN_ETH, idx);
        return ethAddressFromPriv(priv);
    }

    /**
     * 派生 BSC 地址。
     * BSC 与 ETH 使用相同的派生路径和地址格式。
     */
    public static String deriveBsc(String mnemonic, int idx) throws Exception {
        return deriveEth(mnemonic, idx);
    }

    /**
     * 派生 TRON 地址。
     * 路径：m/44'/195'/0'/0/{idx}
     * 算法：未压缩公钥 → Keccak256 → 后20字节 → 加 0x41 前缀 → Base58Check
     */
    public static String deriveTron(String mnemonic, int idx) throws Exception {
        byte[] priv = BIP32Util.derivePrivateKey(mnemonic, COIN_TRON, idx);
        return tronAddressFromPriv(priv);
    }

    /**
     * 派生 BTC 地址（P2PKH，Base58Check，version=0x00）。
     * 路径：m/44'/0'/0'/0/{idx}
     */
    public static String deriveBtc(String mnemonic, int idx) throws Exception {
        byte[] priv = BIP32Util.derivePrivateKey(mnemonic, COIN_BTC, idx);
        return btcAddressFromPriv(priv);
    }

    /**
     * 派生 SOL 地址。
     * 路径：m/44'/501'/0'/0/{idx}
     * 算法：SLIP-0010 Ed25519 派生私钥 → 公钥(32字节) → Base58
     */
    public static String deriveSol(String mnemonic, int idx) throws Exception {
        byte[] priv = Slip10Ed25519.derivePrivateKey(mnemonic, idx);
        byte[] pub = Slip10Ed25519.derivePublicKey(priv);
        return BIP32Util.base58Encode(pub);
    }

    // ==================== 从已计算 seed 派生（多链复用 seed，避免重复 PBKDF2） ====================

    /** 从 seed 派生 BTC 地址（与 deriveBtc 等价，但复用外部传入的 seed） */
    public static String deriveBtcFromSeed(byte[] seed, int idx) throws Exception {
        byte[] priv = BIP32Util.derivePrivateKeyFromSeed(seed, COIN_BTC, idx);
        return btcAddressFromPriv(priv);
    }

    /** 从 seed 派生 ETH 地址（与 deriveEth 等价，但复用外部传入的 seed） */
    public static String deriveEthFromSeed(byte[] seed, int idx) throws Exception {
        byte[] priv = BIP32Util.derivePrivateKeyFromSeed(seed, COIN_ETH, idx);
        return ethAddressFromPriv(priv);
    }

    /** 从 seed 派生 BSC 地址（与 deriveBsc 等价，但复用外部传入的 seed） */
    public static String deriveBscFromSeed(byte[] seed, int idx) throws Exception {
        return deriveEthFromSeed(seed, idx);
    }

    /** 从 seed 派生 TRON 地址（与 deriveTron 等价，但复用外部传入的 seed） */
    public static String deriveTronFromSeed(byte[] seed, int idx) throws Exception {
        byte[] priv = BIP32Util.derivePrivateKeyFromSeed(seed, COIN_TRON, idx);
        return tronAddressFromPriv(priv);
    }

    /** 从 seed 派生 SOL 地址（与 deriveSol 等价，但复用外部传入的 seed） */
    public static String deriveSolFromSeed(byte[] seed, int idx) throws Exception {
        // SOL 走 SLIP-0010 Ed25519，需要从 seed 重新派生（与 secp256k1 不同曲线）
        byte[] priv = Slip10Ed25519.derivePrivateKeyFromSeed(seed, idx);
        byte[] pub = Slip10Ed25519.derivePublicKey(priv);
        return BIP32Util.base58Encode(pub);
    }

    // ==================== 私钥 → 地址（内部复用） ====================

    private static String btcAddressFromPriv(byte[] priv) {
        byte[] pub = BIP32Util.pointCompressed(priv);
        byte[] sha = BIP32Util.sha256(pub);
        byte[] ripemd = BIP32Util.ripemd160(sha);
        return BIP32Util.base58CheckEncode((byte) 0x00, ripemd);
    }

    private static String ethAddressFromPriv(byte[] priv) {
        byte[] pubUncompressed = BIP32Util.pointUncompressed(priv);
        // 去掉 0x04 前缀，取后 64 字节
        byte[] pubXY = Arrays.copyOfRange(pubUncompressed, 1, 65);
        byte[] hash = BIP32Util.keccak256(pubXY);
        byte[] addrBytes = Arrays.copyOfRange(hash, 12, 32);
        return "0x" + bytesToHex(addrBytes);
    }

    private static String tronAddressFromPriv(byte[] priv) {
        byte[] pubUncompressed = BIP32Util.pointUncompressed(priv);
        byte[] pubXY = Arrays.copyOfRange(pubUncompressed, 1, 65);
        byte[] hash = BIP32Util.keccak256(pubXY);
        byte[] addrBytes = Arrays.copyOfRange(hash, 12, 32);
        // TRON 地址前缀 0x41
        return BIP32Util.base58CheckEncode(new byte[]{0x41}, addrBytes);
    }

    /**
     * 按 chaintype 派生地址（统一入口）。
     *
     * @param chaintype 小写链名：tron/eth/bsc/btc/sol
     * @param mnemonic  助记词明文
     * @param idx       地址索引
     * @return 钱包地址，或 null（不支持的链）
     */
    public static String deriveByChain(String chaintype, String mnemonic, int idx) throws Exception {
        switch (chaintype.toLowerCase()) {
            case "btc":  return deriveBtc(mnemonic, idx);
            case "eth":  return deriveEth(mnemonic, idx);
            case "bsc":  return deriveBsc(mnemonic, idx);
            case "tron": return deriveTron(mnemonic, idx);
            case "sol":  return deriveSol(mnemonic, idx);
            default:     return null;
        }
    }

    /**
     * 按 chaintype 从已计算 seed 派生地址（多链复用同一 seed，避免重复 PBKDF2）。
     *
     * @param chaintype 小写链名：tron/eth/bsc/btc/sol
     * @param seed      BIP39 种子（来自 BIP32Util.mnemonicToSeed）
     * @param idx       地址索引
     * @return 钱包地址，或 null（不支持的链）
     */
    public static String deriveByChainFromSeed(String chaintype, byte[] seed, int idx) throws Exception {
        switch (chaintype.toLowerCase()) {
            case "btc":  return deriveBtcFromSeed(seed, idx);
            case "eth":  return deriveEthFromSeed(seed, idx);
            case "bsc":  return deriveBscFromSeed(seed, idx);
            case "tron": return deriveTronFromSeed(seed, idx);
            case "sol":  return deriveSolFromSeed(seed, idx);
            default:     return null;
        }
    }

    /**
     * 一次性派生多条链地址（高并发优化：seed 只计算一次，多链共享）。
     * <p>原方案每条链调用 deriveByChain 各跑一次 PBKDF2(2048轮)，5 条链 = 5 次 PBKDF2；
     * 此方法只算 1 次 seed，CPU 开销降为 1/5，显著提升 news4 高并发吞吐。
     *
     * @param chains 链名数组（小写），如 {"tron","eth","bsc","btc","sol"}
     * @param mnemonic 助记词明文
     * @param idx 地址索引
     * @return 链→地址 映射；派生失败的链不会出现在结果中
     */
    public static java.util.Map<String, String> deriveAllChains(String[] chains, String mnemonic, int idx) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        if (chains == null || chains.length == 0 || mnemonic == null || mnemonic.isEmpty()) {
            return result;
        }
        // seed 只算一次，多链共享
        byte[] seed;
        try {
            seed = BIP32Util.mnemonicToSeed(mnemonic, "");
        } catch (Exception e) {
            return result;
        }
        for (String chainRaw : chains) {
            String chain = chainRaw == null ? "" : chainRaw.trim().toLowerCase();
            if (chain.isEmpty()) continue;
            try {
                String addr = deriveByChainFromSeed(chain, seed, idx);
                if (addr != null) result.put(chain, addr);
            } catch (Exception ignore) {
                // 单链失败不影响其他链
            }
        }
        return result;
    }

    /**
     * 获取 BIP44 派生路径（用于 algorithm 字段记录）。
     */
    public static String getDerivePath(String chaintype, int idx) {
        switch (chaintype.toLowerCase()) {
            case "btc":  return "m/44'/0'/0'/0/" + idx;
            case "eth":  return "m/44'/60'/0'/0/" + idx;
            case "bsc":  return "m/44'/60'/0'/0/" + idx;
            case "tron": return "m/44'/195'/0'/0/" + idx;
            case "sol":  return "m/44'/501'/0'/0/" + idx;
            default:     return "m/44'/0'/0'/0/" + idx;
        }
    }

    // ==================== 工具 ====================

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            sb.append(HEX_CHARS[v >>> 4]).append(HEX_CHARS[v & 0x0F]);
        }
        return sb.toString();
    }
}
