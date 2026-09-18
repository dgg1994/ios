package com.consumer.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Bech32 / Bech32m（BIP173 / BIP350），用于 BTC Native SegWit / Taproot。
 */
public final class Bech32 {

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int BECH32 = 1;
    private static final int BECH32M = 0x2bc830a3;

    private Bech32() {
    }

    public static String encodeSegwit(String hrp, int witver, byte[] witprog) {
        int spec = witver == 0 ? BECH32 : BECH32M;
        List<Integer> data = new ArrayList<>();
        data.add(witver);
        for (int v : convertBits(witprog, 8, 5, true)) {
            data.add(v);
        }
        return encode(hrp, data, spec);
    }

    private static String encode(String hrp, List<Integer> data, int spec) {
        List<Integer> values = new ArrayList<>(data);
        values.addAll(createChecksum(hrp, data, spec));
        StringBuilder sb = new StringBuilder(hrp).append('1');
        for (int d : values) {
            sb.append(CHARSET.charAt(d));
        }
        return sb.toString();
    }

    private static List<Integer> createChecksum(String hrp, List<Integer> data, int spec) {
        List<Integer> values = hrpExpand(hrp);
        values.addAll(data);
        for (int i = 0; i < 6; i++) {
            values.add(0);
        }
        int polymod = polymod(values) ^ spec;
        List<Integer> checksum = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            checksum.add((polymod >> 5 * (5 - i)) & 31);
        }
        return checksum;
    }

    private static List<Integer> hrpExpand(String hrp) {
        List<Integer> ret = new ArrayList<>();
        for (int i = 0; i < hrp.length(); i++) {
            ret.add(hrp.charAt(i) >> 5);
        }
        ret.add(0);
        for (int i = 0; i < hrp.length(); i++) {
            ret.add(hrp.charAt(i) & 31);
        }
        return ret;
    }

    private static int polymod(List<Integer> values) {
        // BIP173 generator; last coeff is 0x2a1462b3 (0x2e is a common copy typo and fails mempool checksum).
        int[] gen = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        int chk = 1;
        for (int v : values) {
            int b = chk >>> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ v;
            for (int i = 0; i < 5; i++) {
                if (((b >> i) & 1) == 1) {
                    chk ^= gen[i];
                }
            }
        }
        return chk;
    }

    private static int[] convertBits(byte[] data, int from, int to, boolean pad) {
        int acc = 0;
        int bits = 0;
        int maxv = (1 << to) - 1;
        List<Integer> ret = new ArrayList<>();
        for (byte b : data) {
            acc = (acc << from) | (b & 0xff);
            bits += from;
            while (bits >= to) {
                bits -= to;
                ret.add((acc >> bits) & maxv);
            }
        }
        if (pad && bits > 0) {
            ret.add((acc << (to - bits)) & maxv);
        }
        int[] out = new int[ret.size()];
        for (int i = 0; i < ret.size(); i++) {
            out[i] = ret.get(i);
        }
        return out;
    }
}
