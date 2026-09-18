package com.admin.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import com.admin.util.BIP32Util;
import com.admin.util.Slip10Ed25519;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * SOL 原生币与 SPL-USDT 转账。对齐 Python transfer_sol_native / transfer_sol_usdt。
 * 归集入口仍按 Python 不开放 SOL，这里只给转账层用。
 */
final class SolTransfer {

    private static final String[] RPCS = {
            "https://api.mainnet-beta.solana.com",
            "https://solana-rpc.publicnode.com",
            "https://solana.drpc.org"
    };
    private static final String USDT_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";
    private static final String TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
    private static final String ATA_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL";
    private static final long FEE = 5_000L;
    private static final long RESERVE = 2_000_000L;
    private static final long ATA_RENT = 2_039_280L;

    private SolTransfer() {
    }

    static Map<String, Object> transferNative(RestTemplate http, byte[] priv, String toAddress, String amountOrNull) {
        String to = toAddress == null ? "" : toAddress.trim();
        if (!looksLike(to)) {
            return fail("转入地址无效");
        }
        try {
            byte[] fromPk = Slip10Ed25519.derivePublicKey(priv);
            String from = BIP32Util.base58Encode(fromPk);
            String rpc = pickRpc(http);
            long lamports = balance(http, rpc, from);
            long send;
            if (amountOrNull == null || amountOrNull.isBlank()) {
                send = lamports - FEE - RESERVE;
            } else {
                send = new BigDecimal(amountOrNull.trim()).movePointRight(9)
                        .setScale(0, RoundingMode.DOWN).longValueExact();
            }
            if (send <= 0) {
                return fail("可转余额不足（需预留手续费）");
            }
            if (send + FEE > lamports) {
                return fail("余额不足以支付金额+手续费");
            }
            byte[] toPk = BIP32Util.base58Decode(to);
            byte[] system = new byte[32];
            byte[] data = new byte[12];
            writeU32(data, 0, 2);
            writeU64(data, 4, send);
            Tx tx = new Tx();
            tx.add(fromPk, true, true);
            tx.add(toPk, false, true);
            tx.add(system, false, false);
            tx.ix(system, new byte[][] {fromPk, toPk}, data);
            String sig = sendTx(http, rpc, priv, tx);
            return ok("SOL", sig, from, to, new BigDecimal(send).movePointLeft(9).stripTrailingZeros().toPlainString());
        } catch (Exception e) {
            return fail(shortMsg(e));
        }
    }

    static Map<String, Object> transferUsdt(RestTemplate http, byte[] priv, String toAddress, String amountOrNull) {
        String to = toAddress == null ? "" : toAddress.trim();
        if (!looksLike(to)) {
            return fail("转入地址无效");
        }
        try {
            byte[] fromPk = Slip10Ed25519.derivePublicKey(priv);
            String from = BIP32Util.base58Encode(fromPk);
            byte[] toPk = BIP32Util.base58Decode(to);
            byte[] mint = BIP32Util.base58Decode(USDT_MINT);
            byte[] tokenProg = BIP32Util.base58Decode(TOKEN_PROGRAM);
            byte[] ataProg = BIP32Util.base58Decode(ATA_PROGRAM);
            byte[] system = new byte[32];
            String rpc = pickRpc(http);
            TokenAcc src = largestToken(http, rpc, from);
            if (src == null || src.amount <= 0) {
                return fail("USDT 余额为 0");
            }
            long send = amountOrNull == null || amountOrNull.isBlank()
                    ? src.amount
                    : new BigDecimal(amountOrNull.trim()).movePointRight(6).setScale(0, RoundingMode.DOWN).longValueExact();
            if (send <= 0) {
                return fail("USDT 转账金额无效");
            }
            if (send > src.amount) {
                return fail("USDT 余额不足");
            }
            byte[] destAta = findAta(toPk, mint, tokenProg, ataProg);
            boolean destMissing = accountMissing(http, rpc, BIP32Util.base58Encode(destAta));
            long sol = balance(http, rpc, from);
            long need = FEE + (destMissing ? ATA_RENT : 0);
            if (sol < need) {
                return fail("SOL 不足以支付 USDT 转账手续费/租金");
            }
            Tx tx = new Tx();
            tx.add(fromPk, true, true);
            tx.add(src.pubkey, false, true);
            tx.add(destAta, false, true);
            tx.add(mint, false, false);
            tx.add(tokenProg, false, false);
            if (destMissing) {
                tx.add(system, false, false);
                tx.add(ataProg, false, false);
                tx.add(toPk, false, false);
                tx.ix(ataProg, new byte[][] {fromPk, destAta, toPk, mint, system, tokenProg}, new byte[0]);
            }
            byte[] ix = new byte[10];
            ix[0] = 12;
            writeU64(ix, 1, send);
            ix[9] = 6;
            tx.ix(tokenProg, new byte[][] {src.pubkey, mint, destAta, fromPk}, ix);
            String sig = sendTx(http, rpc, priv, tx);
            return ok("USDT", sig, from, to, new BigDecimal(send).movePointLeft(6).stripTrailingZeros().toPlainString());
        } catch (Exception e) {
            return fail(shortMsg(e));
        }
    }

    private static byte[] findAta(byte[] owner, byte[] mint, byte[] tokenProg, byte[] ataProg) throws Exception {
        return findPda(Arrays.asList(owner, tokenProg, mint), ataProg);
    }

    private static byte[] findPda(List<byte[]> seeds, byte[] program) throws Exception {
        for (int bump = 255; bump >= 0; bump--) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] s : seeds) {
                md.update(s);
            }
            md.update((byte) bump);
            md.update(program);
            md.update("ProgramDerivedAddress".getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest();
            if (!onCurve(hash)) {
                return hash;
            }
        }
        throw new IllegalStateException("无法派生 ATA");
    }

    private static boolean onCurve(byte[] point) {
        try {
            new Ed25519PublicKeyParameters(point, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sendTx(RestTemplate http, String rpc, byte[] priv, Tx tx) throws Exception {
        byte[] blockhash = BIP32Util.base58Decode(latestBlockhash(http, rpc));
        byte[] message = tx.message(blockhash);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, new Ed25519PrivateKeyParameters(priv, 0));
        signer.update(message, 0, message.length);
        byte[] sig = signer.generateSignature();
        byte[] raw = new byte[1 + 64 + message.length];
        raw[0] = 1;
        System.arraycopy(sig, 0, raw, 1, 64);
        System.arraycopy(message, 0, raw, 65, message.length);
        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("encoding", "base64");
        opt.put("skipPreflight", false);
        opt.put("preflightCommitment", "confirmed");
        Object result = rpc(http, rpc, "sendTransaction", List.of(Base64.getEncoder().encodeToString(raw), opt));
        String out = result == null ? "" : String.valueOf(result).trim();
        if (out.isEmpty() || "null".equals(out)) {
            throw new IllegalStateException("sendTransaction 无签名");
        }
        return out;
    }

    private static String pickRpc(RestTemplate http) {
        Exception last = null;
        for (String rpc : RPCS) {
            try {
                rpc(http, rpc, "getLatestBlockhash", List.of(Map.of("commitment", "confirmed")));
                return rpc;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException(last == null ? "SOL RPC 不可用" : last.getMessage());
    }

    private static long balance(RestTemplate http, String rpc, String address) {
        Object result = rpc(http, rpc, "getBalance", List.of(address));
        if (result instanceof JSONObject) {
            return ((JSONObject) result).getLongValue("value");
        }
        return 0;
    }

    private static String latestBlockhash(RestTemplate http, String rpc) {
        Object result = rpc(http, rpc, "getLatestBlockhash", List.of(Map.of("commitment", "confirmed")));
        if (!(result instanceof JSONObject)) {
            throw new IllegalStateException("getLatestBlockhash 异常");
        }
        JSONObject value = ((JSONObject) result).getJSONObject("value");
        String bh = value == null ? "" : value.getString("blockhash");
        if (bh == null || bh.isBlank()) {
            throw new IllegalStateException("无 recent blockhash");
        }
        return bh;
    }

    private static boolean accountMissing(RestTemplate http, String rpc, String address) {
        Object result = rpc(http, rpc, "getAccountInfo", List.of(address, Map.of("encoding", "base64")));
        if (!(result instanceof JSONObject)) {
            return true;
        }
        return ((JSONObject) result).get("value") == null;
    }

    private static TokenAcc largestToken(RestTemplate http, String rpc, String owner) {
        Object result = rpc(http, rpc, "getTokenAccountsByOwner", List.of(
                owner,
                Map.of("mint", USDT_MINT),
                Map.of("encoding", "jsonParsed")));
        JSONArray rows = result instanceof JSONObject ? ((JSONObject) result).getJSONArray("value") : null;
        TokenAcc best = null;
        if (rows == null) {
            return null;
        }
        for (int i = 0; i < rows.size(); i++) {
            JSONObject item = rows.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String pubkey = item.getString("pubkey");
            JSONObject info = item.getJSONObject("account");
            JSONObject data = info == null ? null : info.getJSONObject("data");
            JSONObject parsed = data == null ? null : data.getJSONObject("parsed");
            JSONObject inf = parsed == null ? null : parsed.getJSONObject("info");
            JSONObject amt = inf == null ? null : inf.getJSONObject("tokenAmount");
            String raw = amt == null ? null : amt.getString("amount");
            if (pubkey == null || raw == null) {
                continue;
            }
            long v = new BigInteger(raw).longValue();
            if (best == null || v > best.amount) {
                best = new TokenAcc();
                best.pubkey = BIP32Util.base58Decode(pubkey);
                best.amount = v;
            }
        }
        return best;
    }

    private static Object rpc(RestTemplate http, String rpc, String method, List<?> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        body.put("params", params);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String raw = http.postForObject(rpc, new HttpEntity<>(JSON.toJSONString(body), h), String.class);
        JSONObject obj = JSON.parseObject(raw);
        if (obj == null) {
            throw new IllegalStateException(method + " 无响应");
        }
        if (obj.get("error") != null) {
            throw new IllegalStateException(String.valueOf(obj.get("error")));
        }
        Object result = obj.get("result");
        if (result instanceof JSONObject) {
            return result;
        }
        if (result instanceof String) {
            return result;
        }
        return result == null ? null : JSON.parse(JSON.toJSONString(result));
    }

    private static boolean looksLike(String addr) {
        if (addr.length() < 32 || addr.length() > 44) {
            return false;
        }
        for (int i = 0; i < addr.length(); i++) {
            char c = addr.charAt(i);
            if ("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    private static void writeU32(byte[] buf, int off, int v) {
        buf[off] = (byte) v;
        buf[off + 1] = (byte) (v >> 8);
        buf[off + 2] = (byte) (v >> 16);
        buf[off + 3] = (byte) (v >> 24);
    }

    private static void writeU64(byte[] buf, int off, long v) {
        for (int i = 0; i < 8; i++) {
            buf[off + i] = (byte) (v >> (8 * i));
        }
    }

    private static String shortMsg(Exception e) {
        String msg = e.getMessage() == null ? "SOL 转账失败" : e.getMessage();
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }

    private static Map<String, Object> ok(String coin, String hash, String from, String to, String amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("chain", "sol");
        m.put("coin", coin);
        m.put("hash", hash);
        m.put("outaddress", from);
        m.put("inaddress", to);
        m.put("amount", amount);
        return m;
    }

    private static Map<String, Object> fail(String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error == null || error.isBlank() ? "SOL 转账失败" : error);
        return m;
    }

    private static final class TokenAcc {
        byte[] pubkey;
        long amount;
    }

    private static final class Acc {
        byte[] pk;
        boolean signer;
        boolean writable;
    }

    private static final class Ix {
        byte[] program;
        byte[][] accounts;
        byte[] data;
    }

    private static final class Tx {
        private final List<Acc> accs = new ArrayList<>();
        private final List<Ix> ixs = new ArrayList<>();

        void add(byte[] pk, boolean signer, boolean writable) {
            for (Acc a : accs) {
                if (Arrays.equals(a.pk, pk)) {
                    a.signer |= signer;
                    a.writable |= writable;
                    return;
                }
            }
            Acc a = new Acc();
            a.pk = pk;
            a.signer = signer;
            a.writable = writable;
            accs.add(a);
        }

        void ix(byte[] program, byte[][] accounts, byte[] data) {
            Ix ix = new Ix();
            ix.program = program;
            ix.accounts = accounts;
            ix.data = data;
            ixs.add(ix);
            add(program, false, false);
            for (byte[] a : accounts) {
                add(a, false, false);
            }
        }

        byte[] message(byte[] blockhash) {
            List<Acc> ordered = new ArrayList<>();
            for (Acc a : accs) {
                if (a.signer && a.writable) {
                    ordered.add(a);
                }
            }
            for (Acc a : accs) {
                if (a.signer && !a.writable) {
                    ordered.add(a);
                }
            }
            for (Acc a : accs) {
                if (!a.signer && a.writable) {
                    ordered.add(a);
                }
            }
            for (Acc a : accs) {
                if (!a.signer && !a.writable) {
                    ordered.add(a);
                }
            }
            int signers = 0;
            int roSigned = 0;
            int roUnsigned = 0;
            for (Acc a : ordered) {
                if (a.signer) {
                    signers++;
                    if (!a.writable) {
                        roSigned++;
                    }
                } else if (!a.writable) {
                    roUnsigned++;
                }
            }
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            o.write(signers);
            o.write(roSigned);
            o.write(roUnsigned);
            writeCompact(o, ordered.size());
            for (Acc a : ordered) {
                o.write(a.pk, 0, 32);
            }
            o.write(blockhash, 0, 32);
            writeCompact(o, ixs.size());
            for (Ix ix : ixs) {
                o.write(indexOf(ordered, ix.program));
                writeCompact(o, ix.accounts.length);
                for (byte[] a : ix.accounts) {
                    o.write(indexOf(ordered, a));
                }
                writeCompact(o, ix.data.length);
                if (ix.data.length > 0) {
                    o.write(ix.data, 0, ix.data.length);
                }
            }
            return o.toByteArray();
        }

        private static int indexOf(List<Acc> ordered, byte[] pk) {
            for (int i = 0; i < ordered.size(); i++) {
                if (Arrays.equals(ordered.get(i).pk, pk)) {
                    return i;
                }
            }
            throw new IllegalStateException("账户未入交易");
        }

        private static void writeCompact(java.io.ByteArrayOutputStream o, int v) {
            if (v < 0x80) {
                o.write(v);
            } else if (v < 0x4000) {
                o.write((v & 0x7f) | 0x80);
                o.write(v >> 7);
            } else {
                o.write((v & 0x7f) | 0x80);
                o.write(((v >> 7) & 0x7f) | 0x80);
                o.write(v >> 14);
            }
        }
    }
}
