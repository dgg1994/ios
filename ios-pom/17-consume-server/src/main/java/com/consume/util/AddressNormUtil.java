package com.consume.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 上报/派生地址归一化与粗链类型推断（不区分钱包）。
 */
public final class AddressNormUtil {

    private static final Pattern EVM = Pattern.compile("^0x[a-fA-F0-9]{40}$");
    private static final Pattern TRON = Pattern.compile("^T[1-9A-HJ-NP-Za-km-z]{33}$");
    /** TronLink / uj 常见：21 字节 TRON 地址的 hex（0x41 + 20字节），易被误判成 Sol */
    private static final Pattern TRON_HEX = Pattern.compile("^41[a-fA-F0-9]{40}$");
    private static final Pattern HEX40 = Pattern.compile("^[a-fA-F0-9]{40}$");
    private static final Pattern BTC_LEGACY = Pattern.compile("^[13][a-km-zA-HJ-NP-Z1-9]{25,34}$");
    private static final Pattern BTC_BECH32 = Pattern.compile("^(bc1|tb1)[a-z0-9]{20,80}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TON_EQ = Pattern.compile("^(EQ|UQ)[A-Za-z0-9_-]{46}$");
    private static final Pattern TON_RAW = Pattern.compile("^0:[a-fA-F0-9]{64}$");
    private static final Pattern SOL = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");

    private AddressNormUtil() {
    }

    /**
     * 规范化地址用于撞库：
     * - EVM / bech32 / ton-raw 小写
     * - Tron hex(41…) → Base58Check T…
     * - 其余保持原文（Sol base58 大小写敏感）
     */
    public static String normalize(String address) {
        if (address == null) {
            return "";
        }
        String a = address.trim();
        if (a.isEmpty()) {
            return "";
        }
        String tronFromHex = tronHexToBase58(a);
        if (tronFromHex != null) {
            return tronFromHex;
        }
        if (EVM.matcher(a).matches()) {
            return a.toLowerCase(Locale.ROOT);
        }
        if (BTC_BECH32.matcher(a).matches()) {
            return a.toLowerCase(Locale.ROOT);
        }
        if (TON_RAW.matcher(a).matches()) {
            return a.toLowerCase(Locale.ROOT);
        }
        return a;
    }

    /**
     * 规范化展示/入库用的地址原文（tron hex → T 地址）。
     */
    public static String canonicalize(String address) {
        if (address == null) {
            return "";
        }
        String a = address.trim();
        String tronFromHex = tronHexToBase58(a);
        return tronFromHex != null ? tronFromHex : a;
    }

    /**
     * @return eth/bsc/tron/btc/sol/ton/unknown（EVM 统一记 eth，匹配时 bsc 同址）
     */
    public static String guessChain(String address) {
        if (address == null || address.isEmpty()) {
            return "unknown";
        }
        String a = address.trim();
        if (EVM.matcher(a).matches()) {
            return "eth";
        }
        if (TRON.matcher(a).matches() || TRON_HEX.matcher(a).matches()) {
            return "tron";
        }
        if (BTC_LEGACY.matcher(a).matches() || BTC_BECH32.matcher(a).matches()) {
            return "btc";
        }
        if (TON_EQ.matcher(a).matches() || TON_RAW.matcher(a).matches()) {
            return "ton";
        }
        // 纯 hex（无 0x）绝不是 Sol；40 位可能是缺 0x 的 EVM
        if (HEX40.matcher(a).matches()) {
            return "eth";
        }
        if (isMostlyHex(a) && a.length() >= 40 && a.length() <= 42) {
            return "unknown";
        }
        // Solana：排除已判为 BTC legacy / 以 T/1/3 开头的
        if (SOL.matcher(a).matches() && !a.startsWith("T") && !a.startsWith("1") && !a.startsWith("3")) {
            return "sol";
        }
        if (SOL.matcher(a).matches()) {
            return "sol";
        }
        return "unknown";
    }

    /**
     * 从 derivationPath / mnemonicPath / hdPath 推断链（优先于地址形态猜测）。
     */
    public static String chainFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String p = path.trim();
        if (p.contains("/195'")) {
            return "tron";
        }
        if (p.contains("/501'")) {
            return "sol";
        }
        if (p.contains("/60'")) {
            return "eth";
        }
        if (p.contains("/84'") || p.contains("/86'") || p.contains("/49'")
                || p.contains("/44'/0'") || p.contains("/44'/0/")) {
            return "btc";
        }
        return "";
    }

    public static boolean looksLikeAddress(String s) {
        if (s == null) {
            return false;
        }
        String a = s.trim();
        if (a.length() < 26 || a.length() > 128) {
            return false;
        }
        if (TRON_HEX.matcher(a).matches() || EVM.matcher(a).matches() || TRON.matcher(a).matches()) {
            return true;
        }
        return !"unknown".equals(guessChain(a));
    }

    /** 41 + 40 hex → Base58Check T 地址；非 tron hex 返回 null */
    public static String tronHexToBase58(String address) {
        if (address == null) {
            return null;
        }
        String a = address.trim();
        if (!TRON_HEX.matcher(a).matches()) {
            return null;
        }
        try {
            byte[] raw = hexToBytes(a);
            if (raw.length != 21 || (raw[0] & 0xff) != 0x41) {
                return null;
            }
            return Base58.encodeCheck(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isMostlyHex(String a) {
        if (a == null || a.isEmpty()) {
            return false;
        }
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static byte[] hexToBytes(String hex) {
        int n = hex.length();
        byte[] out = new byte[n / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
