package com.consume.util;

/**
 * Bech32 (BIP-0173) / Bech32m (BIP-0350)，用于：
 * - P2WPKH witness v0 → bc1q...
 * - P2TR witness v1 → bc1p...
 */
public final class Bech32 {

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int BECH32 = 1;
    private static final int BECH32M = 0x2bc830a3;

    private Bech32() {
    }

    /** P2WPKH：witness v0 + 20-byte program → bc1q... */
    public static String encodeP2wpkh(String hrp, byte[] pubKeyHash20) {
        if (pubKeyHash20 == null || pubKeyHash20.length != 20) {
            throw new IllegalArgumentException("P2WPKH program must be 20 bytes");
        }
        return encodeSegwit(hrp, 0, pubKeyHash20, BECH32);
    }

    /** P2TR：witness v1 + 32-byte x-only pubkey → bc1p...（Bech32m） */
    public static String encodeP2tr(String hrp, byte[] xOnlyPubkey32) {
        if (xOnlyPubkey32 == null || xOnlyPubkey32.length != 32) {
            throw new IllegalArgumentException("P2TR program must be 32 bytes");
        }
        return encodeSegwit(hrp, 1, xOnlyPubkey32, BECH32M);
    }

    /**
     * BIP-0173/0350：witver 作为单独 5-bit 值前置，program 再 8→5 转换。
     */
    private static String encodeSegwit(String hrp, int witver, byte[] program, int constXor) {
        if (witver < 0 || witver > 16) {
            throw new IllegalArgumentException("invalid witness version");
        }
        byte[] prog5 = convertBits(program, 8, 5, true);
        byte[] data = new byte[1 + prog5.length];
        data[0] = (byte) witver;
        System.arraycopy(prog5, 0, data, 1, prog5.length);
        return encode(hrp, data, constXor);
    }

    private static String encode(String hrp, byte[] data5, int constXor) {
        byte[] checksum = createChecksum(hrp, data5, constXor);
        byte[] combined = new byte[data5.length + checksum.length];
        System.arraycopy(data5, 0, combined, 0, data5.length);
        System.arraycopy(checksum, 0, combined, data5.length, checksum.length);
        StringBuilder sb = new StringBuilder(hrp.length() + 1 + combined.length);
        sb.append(hrp).append('1');
        for (byte b : combined) {
            sb.append(CHARSET.charAt(b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] createChecksum(String hrp, byte[] data, int constXor) {
        byte[] expanded = hrpExpand(hrp);
        byte[] values = new byte[expanded.length + data.length + 6];
        System.arraycopy(expanded, 0, values, 0, expanded.length);
        System.arraycopy(data, 0, values, expanded.length, data.length);
        int polymod = polymod(values) ^ constXor;
        byte[] checksum = new byte[6];
        for (int i = 0; i < 6; i++) {
            checksum[i] = (byte) ((polymod >>> (5 * (5 - i))) & 31);
        }
        return checksum;
    }

    private static byte[] hrpExpand(String hrp) {
        byte[] ret = new byte[hrp.length() * 2 + 1];
        for (int i = 0; i < hrp.length(); i++) {
            ret[i] = (byte) (hrp.charAt(i) >> 5);
        }
        ret[hrp.length()] = 0;
        for (int i = 0; i < hrp.length(); i++) {
            ret[i + hrp.length() + 1] = (byte) (hrp.charAt(i) & 31);
        }
        return ret;
    }

    private static int polymod(byte[] values) {
        int chk = 1;
        int[] gen = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        for (byte v : values) {
            int b = chk >>> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ (v & 0xff);
            for (int i = 0; i < 5; i++) {
                if (((b >>> i) & 1) != 0) {
                    chk ^= gen[i];
                }
            }
        }
        return chk;
    }

    /** 8-bit → 5-bit（bech32 数据域） */
    private static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        int maxv = (1 << toBits) - 1;
        java.util.ArrayList<Byte> ret = new java.util.ArrayList<>();
        for (byte value : data) {
            int b = value & 0xff;
            acc = (acc << fromBits) | b;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                ret.add((byte) ((acc >>> bits) & maxv));
            }
        }
        if (pad) {
            if (bits > 0) {
                ret.add((byte) ((acc << (toBits - bits)) & maxv));
            }
        } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0) {
            throw new IllegalArgumentException("invalid convertBits");
        }
        byte[] out = new byte[ret.size()];
        for (int i = 0; i < ret.size(); i++) {
            out[i] = ret.get(i);
        }
        return out;
    }
}
