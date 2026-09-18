package com.consumer.util;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.math.ec.ECPoint;

import com.alibaba.fastjson.JSON;

/**
 * 对齐 Python 26 address_derive：TRON/ETH/BSC BIP44，BTC 四格式，SOL Phantom 路径。
 */
public final class WalletAddressUtil {

    private static final X9ECParameters CURVE = SECNamedCurves.getByName("secp256k1");

    private WalletAddressUtil() {
    }

    public static int expectedRowCount(int addressCount) {
        int n = Math.max(0, Math.min(50, addressCount));
        if (n <= 0) {
            return 0;
        }
        return n * (4 + 4); // 4 非 BTC 链 + BTC 4 格式
    }

    public static List<Map<String, Object>> deriveChainAddressRows(String mnemonic, int addressCount) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String phrase = extractPhrase(mnemonic);
        if (phrase.isEmpty()) {
            return rows;
        }
        int n = Math.max(1, Math.min(50, addressCount));
        byte[] seed;
        try {
            seed = BIP32Util.mnemonicToSeed(phrase, "");
        } catch (Exception e) {
            return rows;
        }
        for (int i = 0; i < n; i++) {
            addRow(rows, "tron", deriveTron(seed, i), i, path("m/44'/195'/0'/0/" + i, "BIP39+BIP44", "tron"));
            addRow(rows, "eth", deriveEth(seed, i), i, path("m/44'/60'/0'/0/" + i, "BIP39+BIP44", "eth"));
            addRow(rows, "bsc", deriveEth(seed, i), i, path("m/44'/60'/0'/0/" + i, "BIP39+BIP44", "bsc"));
            addBtc(rows, seed, i, "bip84", "Native SegWit", "BIP39+BIP84", "m/84'/0'/" + i + "'/0/0");
            addBtc(rows, seed, i, "bip49", "Nested SegWit", "BIP39+BIP49", "m/49'/0'/" + i + "'/0/0");
            addBtc(rows, seed, i, "bip44", "Legacy", "BIP39+BIP44", "m/44'/0'/" + i + "'/0/0");
            addBtc(rows, seed, i, "bip86", "Taproot", "BIP39+BIP86", "m/86'/0'/" + i + "'/0/0");
            addRow(rows, "sol", deriveSol(seed, i), i, path("m/44'/501'/" + i + "'/0'", "BIP39+BIP44", "sol"));
        }
        return rows;
    }

    private static void addBtc(List<Map<String, Object>> rows, byte[] seed, int i,
            String fmt, String label, String type, String p) {
        String addr = deriveBtc(seed, i, fmt);
        if (addr == null || addr.isEmpty()) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chaintype", "btc");
        row.put("address", addr);
        row.put("addr_index", i);
        row.put("btc_format", fmt);
        row.put("btc_label", label);
        Map<String, Object> algo = new LinkedHashMap<>();
        algo.put("type", type);
        algo.put("chain", "btc");
        algo.put("path", p);
        algo.put("addr_index", i);
        algo.put("btc_format", fmt);
        algo.put("btc_label", label);
        row.put("algorithm", JSON.toJSONString(algo));
        rows.add(row);
    }

    private static void addRow(List<Map<String, Object>> rows, String chain, String addr, int i, String algo) {
        if (addr == null || addr.isEmpty()) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chaintype", chain);
        row.put("address", addr);
        row.put("addr_index", i);
        row.put("algorithm", algo);
        rows.add(row);
    }

    private static String path(String p, String type, String chain) {
        Map<String, Object> algo = new LinkedHashMap<>();
        algo.put("type", type);
        algo.put("chain", chain);
        algo.put("path", p);
        algo.put("addr_index", Integer.parseInt(p.replaceAll(".*[^0-9]([0-9]+)'?$", "$1").replaceAll("[^0-9]", "0")));
        try {
            String idx = p.contains("/") ? p.substring(p.lastIndexOf('/') + 1).replace("'", "") : "0";
            algo.put("addr_index", Integer.parseInt(idx.replaceAll("[^0-9]", "0")));
        } catch (Exception ignored) {
            algo.put("addr_index", 0);
        }
        return JSON.toJSONString(algo);
    }

    public static String deriveEth(byte[] seed, int idx) {
        try {
            byte[] priv = BIP32Util.deriveByPathFromSeed(seed,
                    BIP32Util.hard(44), BIP32Util.hard(60), BIP32Util.hard(0), 0, idx);
            return ethFromPriv(priv);
        } catch (Exception e) {
            return null;
        }
    }

    public static String deriveTron(byte[] seed, int idx) {
        try {
            byte[] priv = BIP32Util.deriveByPathFromSeed(seed,
                    BIP32Util.hard(44), BIP32Util.hard(195), BIP32Util.hard(0), 0, idx);
            return tronFromPriv(priv);
        } catch (Exception e) {
            return null;
        }
    }

    public static String deriveSol(byte[] seed, int idx) {
        try {
            byte[] priv = Slip10Ed25519.derivePhantomFromSeed(seed, idx);
            byte[] pub = Slip10Ed25519.derivePublicKey(priv);
            return BIP32Util.base58Encode(pub);
        } catch (Exception e) {
            return null;
        }
    }

    public static String deriveBtc(byte[] seed, int idx, String format) {
        try {
            String fmt = format == null ? "bip84" : format.toLowerCase();
            int purpose;
            switch (fmt) {
                case "bip49":
                    purpose = 49;
                    break;
                case "bip44":
                    purpose = 44;
                    break;
                case "bip86":
                    purpose = 86;
                    break;
                default:
                    purpose = 84;
                    fmt = "bip84";
                    break;
            }
            byte[] priv = BIP32Util.deriveByPathFromSeed(seed,
                    BIP32Util.hard(purpose), BIP32Util.hard(0), BIP32Util.hard(idx), 0, 0);
            byte[] pub = BIP32Util.pointCompressed(priv);
            byte[] hash160 = BIP32Util.ripemd160(BIP32Util.sha256(pub));
            switch (fmt) {
                case "bip44":
                    return BIP32Util.base58CheckEncode((byte) 0x00, hash160);
                case "bip49":
                    byte[] redeem = new byte[22];
                    redeem[0] = 0x00;
                    redeem[1] = 0x14;
                    System.arraycopy(hash160, 0, redeem, 2, 20);
                    byte[] scriptHash = BIP32Util.ripemd160(BIP32Util.sha256(redeem));
                    return BIP32Util.base58CheckEncode((byte) 0x05, scriptHash);
                case "bip86":
                    return taprootAddress(pub);
                default:
                    return Bech32.encodeSegwit("bc", 0, hash160);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String taprootAddress(byte[] compressedPub) throws Exception {
        byte[] xonly = Arrays.copyOfRange(compressedPub, 1, 33);
        byte[] tweak = taggedHash("TapTweak", xonly);
        ECPoint p = liftX(xonly);
        if (compressedPub[0] == 0x03) {
            p = p.negate();
        }
        BigInteger t = new BigInteger(1, tweak).mod(CURVE.getN());
        ECPoint q = p.add(CURVE.getG().multiply(t)).normalize();
        byte[] qx = bigIntTo32(q.getAffineXCoord().toBigInteger());
        return Bech32.encodeSegwit("bc", 1, qx);
    }

    private static ECPoint liftX(byte[] xonly) {
        BigInteger x = new BigInteger(1, xonly);
        ECPoint p = CURVE.getCurve().decodePoint(concat(new byte[] {0x02}, xonly)).normalize();
        if (p.getAffineXCoord().toBigInteger().equals(x)) {
            return p;
        }
        return CURVE.getCurve().decodePoint(concat(new byte[] {0x03}, xonly)).normalize();
    }

    private static byte[] taggedHash(String tag, byte[] msg) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] tagHash = md.digest(tag.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        md.reset();
        md.update(tagHash);
        md.update(tagHash);
        md.update(msg);
        return md.digest();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] bigIntTo32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        int src = Math.max(0, raw.length - 32);
        int dst = Math.max(0, 32 - raw.length);
        System.arraycopy(raw, src, out, dst, Math.min(32, raw.length));
        return out;
    }

    private static String ethFromPriv(byte[] priv) {
        byte[] pubUncompressed = BIP32Util.pointUncompressed(priv);
        byte[] pubXY = Arrays.copyOfRange(pubUncompressed, 1, 65);
        byte[] hash = BIP32Util.keccak256(pubXY);
        return "0x" + toHex(Arrays.copyOfRange(hash, 12, 32));
    }

    private static String tronFromPriv(byte[] priv) {
        byte[] pubUncompressed = BIP32Util.pointUncompressed(priv);
        byte[] pubXY = Arrays.copyOfRange(pubUncompressed, 1, 65);
        byte[] hash = BIP32Util.keccak256(pubXY);
        return BIP32Util.base58CheckEncode(new byte[] {0x41}, Arrays.copyOfRange(hash, 12, 32));
    }

    private static String extractPhrase(String mnemonic) {
        if (mnemonic == null) {
            return "";
        }
        String raw = mnemonic.trim();
        if (raw.startsWith("{") && raw.endsWith("}")) {
            try {
                com.alibaba.fastjson.JSONObject obj = JSON.parseObject(raw);
                for (String k : new String[] {"seed", "mnemonic", "phrase", "words"}) {
                    Object val = obj.get(k);
                    if (val instanceof String && !((String) val).trim().isEmpty()) {
                        return ((String) val).trim().replaceAll("\\s+", " ");
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return raw.replaceAll("\\s+", " ");
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
