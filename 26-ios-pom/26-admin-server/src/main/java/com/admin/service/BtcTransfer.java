package com.admin.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import com.admin.util.BIP32Util;
import com.admin.util.Bech32;
import com.admin.util.WalletAddressUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * BTC 归集。对齐 Python transfer_btc：拉对应格式地址的 UTXO，扣矿工费后全部转出。
 * 支持 bip84 / bip49 / bip44。bip86（Taproot）签名未接。
 */
final class BtcTransfer {

    private static final String[] APIS = {
            "https://mempool.space/api",
            "https://blockstream.info/api"
    };
    private static final X9ECParameters CURVE_P = SECNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters DOMAIN = new ECDomainParameters(
            CURVE_P.getCurve(), CURVE_P.getG(), CURVE_P.getN(), CURVE_P.getH());

    private BtcTransfer() {
    }

    static Map<String, Object> transfer(RestTemplate http, byte[] priv, String toAddress,
            String amountOrNull, String btcFormat) {
        String fmt = btcFormat == null || btcFormat.isBlank() ? "bip84" : btcFormat.trim().toLowerCase(Locale.ROOT);
        if ("bip86".equals(fmt)) {
            return fail("暂不支持 Taproot（bip86）归集");
        }
        if (!"bip84".equals(fmt) && !"bip49".equals(fmt) && !"bip44".equals(fmt)) {
            fmt = "bip84";
        }
        String to = toAddress == null ? "" : toAddress.trim();
        if (to.isEmpty()) {
            return fail("转入地址无效");
        }
        try {
            String from = WalletAddressUtil.btcAddressFromPriv(priv, fmt);
            if (from == null || from.isEmpty()) {
                return fail("无法从私钥得到 BTC 地址");
            }
            byte[] outScript = outputScript(to);
            if (outScript == null) {
                return fail("转入地址无效");
            }
            List<Utxo> utxos = fetchUtxos(http, from);
            long sat = 0;
            for (Utxo u : utxos) {
                sat += u.value;
            }
            if (sat <= 1000) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("satoshi", sat);
                extra.put("from", from);
                extra.put("btc_format", fmt);
                return fail("BTC 余额过低，无法覆盖矿工费", extra);
            }
            int feeRate = feeRate(http);
            boolean specified = amountOrNull != null && !amountOrNull.isBlank();
            long sendSat;
            long changeSat = 0;
            byte[] changeScript = null;
            if (!specified) {
                int vsize = vsize(fmt, utxos.size(), 1);
                long fee = Math.max(vsize * (long) feeRate, 1000L);
                sendSat = sat - fee;
                if (sendSat <= 546) {
                    return fail("BTC 余额过低，无法覆盖矿工费");
                }
            } else {
                sendSat = new BigDecimal(amountOrNull.trim()).movePointRight(8)
                        .setScale(0, RoundingMode.DOWN).longValueExact();
                if (sendSat <= 0) {
                    return fail("转账金额无效");
                }
                int vsize = vsize(fmt, utxos.size(), 2);
                long fee = Math.max(vsize * (long) feeRate, 1000L);
                long change = sat - sendSat - fee;
                if (change < 0 || sendSat >= sat) {
                    return fail("BTC 余额不足支付金额+手续费");
                }
                if (change > 546) {
                    changeScript = outputScript(from);
                    changeSat = change;
                } else {
                    sendSat = sat - fee;
                }
            }
            byte[] raw = buildAndSign(priv, fmt, utxos, outScript, sendSat, changeScript, changeSat);
            String txid = broadcast(http, raw);
            String human = new BigDecimal(sendSat).movePointLeft(8).stripTrailingZeros().toPlainString();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("chain", "btc");
            m.put("coin", "BTC");
            m.put("hash", txid);
            m.put("outaddress", from);
            m.put("inaddress", to);
            m.put("amount", human);
            return m;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "BTC 转账失败" : e.getMessage();
            return fail(msg.length() > 500 ? msg.substring(0, 500) : msg);
        }
    }

    private static byte[] buildAndSign(byte[] priv, String fmt, List<Utxo> utxos,
            byte[] outScript, long sendSat, byte[] changeScript, long changeSat) throws Exception {
        byte[] pub = BIP32Util.pointCompressed(priv);
        byte[] hash160 = BIP32Util.ripemd160(BIP32Util.sha256(pub));
        List<byte[]> outputs = new ArrayList<>();
        outputs.add(output(sendSat, outScript));
        if (changeScript != null && changeSat > 546) {
            outputs.add(output(changeSat, changeScript));
        }
        boolean segwit = !"bip44".equals(fmt);
        byte[][] sigs = new byte[utxos.size()][];
        for (int i = 0; i < utxos.size(); i++) {
            byte[] preimage = segwit
                    ? bip143Preimage(utxos, i, hash160, outputs)
                    : legacyPreimage(utxos, i, hash160, outputs);
            sigs[i] = derSign(priv, hash256(preimage));
        }
        Buf tx = new Buf();
        tx.u32(2);
        if (segwit) {
            tx.u8(0);
            tx.u8(1);
        }
        tx.varInt(utxos.size());
        for (int i = 0; i < utxos.size(); i++) {
            Utxo u = utxos.get(i);
            tx.bytes(reverse(hexToBytes(u.txid)));
            tx.u32(u.vout);
            if ("bip44".equals(fmt)) {
                byte[] scriptSig = concat(push(sigs[i]), push(pub));
                tx.varInt(scriptSig.length);
                tx.bytes(scriptSig);
            } else if ("bip49".equals(fmt)) {
                byte[] redeem = concat(new byte[] {0x00, 0x14}, hash160);
                tx.varInt(redeem.length + 1);
                tx.u8(redeem.length);
                tx.bytes(redeem);
            } else {
                tx.u8(0);
            }
            tx.u32(0xffffffff);
        }
        tx.varInt(outputs.size());
        for (byte[] o : outputs) {
            tx.bytes(o);
        }
        if (segwit) {
            for (int i = 0; i < utxos.size(); i++) {
                tx.u8(2);
                tx.bytes(push(sigs[i]));
                tx.bytes(push(pub));
            }
        }
        tx.u32(0);
        return tx.toByteArray();
    }

    private static byte[] bip143Preimage(List<Utxo> utxos, int index, byte[] hash160, List<byte[]> outputs) {
        Buf prev = new Buf();
        Buf seq = new Buf();
        for (Utxo u : utxos) {
            prev.bytes(reverse(hexToBytes(u.txid)));
            prev.u32(u.vout);
            seq.u32(0xffffffff);
        }
        Buf outs = new Buf();
        for (byte[] o : outputs) {
            outs.bytes(o);
        }
        byte[] scriptCode = concat(new byte[] {0x19, 0x76, (byte) 0xa9, 0x14}, hash160, new byte[] {(byte) 0x88, (byte) 0xac});
        Buf pre = new Buf();
        pre.u32(2);
        pre.bytes(hash256(prev.toByteArray()));
        pre.bytes(hash256(seq.toByteArray()));
        Utxo u = utxos.get(index);
        pre.bytes(reverse(hexToBytes(u.txid)));
        pre.u32(u.vout);
        pre.bytes(scriptCode);
        pre.u64(u.value);
        pre.u32(0xffffffff);
        pre.bytes(hash256(outs.toByteArray()));
        pre.u32(0);
        pre.u32(1);
        return pre.toByteArray();
    }

    private static byte[] legacyPreimage(List<Utxo> utxos, int index, byte[] hash160, List<byte[]> outputs) {
        byte[] scriptCode = concat(new byte[] {0x76, (byte) 0xa9, 0x14}, hash160, new byte[] {(byte) 0x88, (byte) 0xac});
        Buf pre = new Buf();
        pre.u32(1);
        pre.varInt(utxos.size());
        for (int i = 0; i < utxos.size(); i++) {
            Utxo u = utxos.get(i);
            pre.bytes(reverse(hexToBytes(u.txid)));
            pre.u32(u.vout);
            if (i == index) {
                pre.varInt(scriptCode.length);
                pre.bytes(scriptCode);
            } else {
                pre.u8(0);
            }
            pre.u32(0xffffffff);
        }
        pre.varInt(outputs.size());
        for (byte[] o : outputs) {
            pre.bytes(o);
        }
        pre.u32(0);
        pre.u32(1);
        return pre.toByteArray();
    }

    private static byte[] output(long value, byte[] script) {
        Buf b = new Buf();
        b.u64(value);
        b.varInt(script.length);
        b.bytes(script);
        return b.toByteArray();
    }

    private static byte[] derSign(byte[] priv, byte[] hash) throws Exception {
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, new ECPrivateKeyParameters(new BigInteger(1, priv), DOMAIN));
        BigInteger[] rs = signer.generateSignature(hash);
        BigInteger r = rs[0];
        BigInteger s = rs[1];
        BigInteger half = DOMAIN.getN().shiftRight(1);
        if (s.compareTo(half) > 0) {
            s = DOMAIN.getN().subtract(s);
        }
        byte[] der = new DERSequence(new org.bouncycastle.asn1.ASN1Encodable[] {
                new ASN1Integer(r), new ASN1Integer(s)
        }).getEncoded();
        return concat(der, new byte[] {0x01});
    }

    private static int vsize(String fmt, int inputs, int outputs) {
        if ("bip44".equals(fmt)) {
            return 10 + inputs * 148 + outputs * 34;
        }
        if ("bip49".equals(fmt)) {
            return (int) Math.ceil(10.5 + inputs * 91.0 + outputs * 31.0);
        }
        return (int) Math.ceil(10.5 + inputs * 68.0 + outputs * 31.0);
    }

    private static List<Utxo> fetchUtxos(RestTemplate http, String address) {
        Exception last = null;
        for (String api : APIS) {
            try {
                String raw = http.getForObject(api + "/address/" + address + "/utxo", String.class);
                JSONArray arr = JSON.parseArray(raw);
                List<Utxo> out = new ArrayList<>();
                if (arr == null) {
                    return out;
                }
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    Utxo u = new Utxo();
                    u.txid = o.getString("txid");
                    u.vout = o.getIntValue("vout");
                    u.value = o.getLongValue("value");
                    if (u.txid != null && u.txid.length() == 64 && u.value > 0) {
                        out.add(u);
                    }
                }
                return out;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException(last == null ? "读取 UTXO 失败" : last.getMessage());
    }

    private static int feeRate(RestTemplate http) {
        try {
            String raw = http.getForObject(APIS[0] + "/v1/fees/recommended", String.class);
            JSONObject o = JSON.parseObject(raw);
            int rate = o == null ? 0 : o.getIntValue("halfHourFee");
            if (rate <= 0 && o != null) {
                rate = o.getIntValue("hourFee");
            }
            return rate > 0 ? rate : 8;
        } catch (Exception e) {
            return 8;
        }
    }

    private static String broadcast(RestTemplate http, byte[] raw) {
        String hex = toHex(raw);
        Exception last = null;
        for (String api : APIS) {
            try {
                HttpHeaders h = new HttpHeaders();
                h.setContentType(MediaType.TEXT_PLAIN);
                String body = http.postForObject(api + "/tx", new HttpEntity<>(hex, h), String.class);
                String txid = body == null ? "" : body.trim().replace("\"", "");
                if (txid.length() == 64) {
                    return txid;
                }
                throw new IllegalStateException(txid.isEmpty() ? "广播无交易号" : txid);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException(last == null ? "广播失败" : last.getMessage());
    }

    private static byte[] outputScript(String address) {
        String a = address.trim();
        if (a.toLowerCase(Locale.ROOT).startsWith("bc1")) {
            Bech32.Segwit sw = Bech32.decodeSegwit(a);
            if (sw == null || !"bc".equals(sw.hrp)) {
                return null;
            }
            if (sw.version == 0 && sw.program.length == 20) {
                return concat(new byte[] {0x00, 0x14}, sw.program);
            }
            if (sw.version == 1 && sw.program.length == 32) {
                return concat(new byte[] {0x51, 0x20}, sw.program);
            }
            return null;
        }
        try {
            byte[] decoded = BIP32Util.base58Decode(a);
            if (decoded.length != 25) {
                return null;
            }
            byte[] payload = Arrays.copyOf(decoded, 21);
            byte[] check = BIP32Util.sha256(BIP32Util.sha256(payload));
            if (check[0] != decoded[21] || check[1] != decoded[22] || check[2] != decoded[23] || check[3] != decoded[24]) {
                return null;
            }
            byte[] hash = Arrays.copyOfRange(payload, 1, 21);
            if (payload[0] == 0x00) {
                return concat(new byte[] {0x76, (byte) 0xa9, 0x14}, hash, new byte[] {(byte) 0x88, (byte) 0xac});
            }
            if (payload[0] == 0x05) {
                return concat(new byte[] {(byte) 0xa9, 0x14}, hash, new byte[] {(byte) 0x87});
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static byte[] push(byte[] data) {
        if (data.length < 0x4c) {
            return concat(new byte[] {(byte) data.length}, data);
        }
        return concat(new byte[] {0x4c, (byte) data.length}, data);
    }

    private static byte[] hash256(byte[] data) {
        return BIP32Util.sha256(BIP32Util.sha256(data));
    }

    private static byte[] reverse(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[in.length - 1 - i];
        }
        return out;
    }

    private static byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    private static Map<String, Object> fail(String error) {
        return fail(error, null);
    }

    private static Map<String, Object> fail(String error, Object response) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error == null || error.isBlank() ? "BTC 转账失败" : error);
        if (response != null) {
            m.put("response", response);
        }
        return m;
    }

    private static final class Utxo {
        String txid;
        int vout;
        long value;
    }

    private static final class Buf {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        void u8(int v) {
            out.write(v & 0xff);
        }

        void u32(int v) {
            out.write(v & 0xff);
            out.write((v >> 8) & 0xff);
            out.write((v >> 16) & 0xff);
            out.write((v >> 24) & 0xff);
        }

        void u64(long v) {
            for (int i = 0; i < 8; i++) {
                out.write((int) (v >> (8 * i)) & 0xff);
            }
        }

        void varInt(int v) {
            if (v < 0xfd) {
                u8(v);
            } else {
                u8(0xfd);
                out.write(v & 0xff);
                out.write((v >> 8) & 0xff);
            }
        }

        void bytes(byte[] b) {
            out.write(b, 0, b.length);
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
