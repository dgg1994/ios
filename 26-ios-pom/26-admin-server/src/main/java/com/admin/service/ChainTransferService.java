package com.admin.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

import com.admin.util.BIP32Util;
import com.admin.util.WalletAddressUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import lombok.extern.slf4j.Slf4j;

/**
 * 纯 Java 多链转账：ETH/BSC（原生+USDT）、TRON（TRX+USDT）、BTC、SOL（原生+USDT）。
 * 对齐 Python chain_transfer。归集入口仍只放行 eth/bsc/tron/btc。
 */
@Slf4j
@Service
public class ChainTransferService {

    private static final BigDecimal RESERVE_ETH = new BigDecimal("0.0008");
    private static final BigDecimal RESERVE_BSC = new BigDecimal("0.001");
    private static final BigDecimal RESERVE_TRON = new BigDecimal("5");

    private static final String ETH_USDT = "0xdAC17F958D2ee523a2206206994597C13D831ec7";
    private static final String BSC_USDT = "0x55d398326f99059fF775485246999027B3197955";
    private static final String TRON_USDT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
    private static final String TRON_API = "https://api.trongrid.io";

    private final RestTemplate http;

    public ChainTransferService() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(15000);
        f.setReadTimeout(45000);
        this.http = new RestTemplate(f);
    }

    public Map<String, Object> transfer(String chain, String privateKeyHex, String toAddress,
            String coin, String amountOrNull) {
        return transfer(chain, privateKeyHex, toAddress, coin, amountOrNull, "bip84");
    }

    public Map<String, Object> transfer(String chain, String privateKeyHex, String toAddress,
            String coin, String amountOrNull, String btcFormat) {
        String c = normChain(chain);
        String coinKey = coin == null ? "native" : coin.trim().toLowerCase(Locale.ROOT);
        if ("eth".equals(c) || "bsc".equals(c)) {
            if ("usdt".equals(coinKey) || "token".equals(coinKey)) {
                return transferEvmUsdt(c, privateKeyHex, toAddress, amountOrNull);
            }
            return transferEvmNative(c, privateKeyHex, toAddress, amountOrNull);
        }
        if ("tron".equals(c)) {
            if ("usdt".equals(coinKey) || "token".equals(coinKey)) {
                return transferTronUsdt(privateKeyHex, toAddress, amountOrNull);
            }
            return transferTronNative(privateKeyHex, toAddress, amountOrNull);
        }
        if ("btc".equals(c)) {
            byte[] priv = parsePriv(privateKeyHex);
            if (priv == null) {
                return fail("私钥格式无效");
            }
            return BtcTransfer.transfer(http, priv, toAddress, amountOrNull, btcFormat);
        }
        if ("sol".equals(c)) {
            byte[] priv = parsePriv(privateKeyHex);
            if (priv == null) {
                return fail("私钥格式无效");
            }
            if ("usdt".equals(coinKey) || "token".equals(coinKey)) {
                return SolTransfer.transferUsdt(http, priv, toAddress, amountOrNull);
            }
            return SolTransfer.transferNative(http, priv, toAddress, amountOrNull);
        }
        return fail("暂不支持链上转账：" + chain + "（当前已支持 eth/bsc/tron/btc/sol）");
    }

    public String addressFromPriv(String chain, String privateKeyHex) {
        return addressFromPriv(chain, privateKeyHex, "bip84");
    }

    public String addressFromPriv(String chain, String privateKeyHex, String btcFormat) {
        String c = normChain(chain);
        byte[] priv = parsePriv(privateKeyHex);
        if (priv == null) {
            return "";
        }
        if ("eth".equals(c) || "bsc".equals(c)) {
            return Credentials.create(Numeric.toHexStringNoPrefix(priv)).getAddress();
        }
        if ("tron".equals(c)) {
            return WalletAddressUtil.tronFromPrivPublic(priv);
        }
        if ("btc".equals(c)) {
            String addr = WalletAddressUtil.btcAddressFromPriv(priv, btcFormat);
            return addr == null ? "" : addr;
        }
        if ("sol".equals(c)) {
            return com.admin.util.BIP32Util.base58Encode(com.admin.util.Slip10Ed25519.derivePublicKey(priv));
        }
        return "";
    }

    private Map<String, Object> transferEvmNative(String chain, String privateKeyHex, String to, String amount) {
        try {
            Credentials cred = Credentials.create(normPrivHex(privateKeyHex));
            String from = cred.getAddress();
            String toAddr = toChecksum(to);
            String rpc = rpcOf(chain);
            BigInteger balance = hexToBig(rpcCall(rpc, "eth_getBalance", JSON.parseArray("[\"" + from + "\",\"latest\"]")));
            BigInteger gasPrice = hexToBig(rpcCall(rpc, "eth_gasPrice", new JSONArray())).max(BigInteger.ONE);
            BigInteger gasLimit = BigInteger.valueOf(21000);
            BigInteger fee = gasPrice.multiply(gasLimit);
            BigInteger reserve = toWei(reserveNative(chain));
            BigInteger send;
            if (amount == null || amount.isBlank()) {
                send = balance.subtract(fee).subtract(reserve);
            } else {
                send = toWei(new BigDecimal(amount.trim()));
            }
            if (send.compareTo(BigInteger.ZERO) <= 0) {
                return fail("可转余额不足（需预留 gas）");
            }
            if (send.add(fee).compareTo(balance) > 0) {
                return fail("余额不足以支付金额+gas");
            }
            BigInteger nonce = hexToBig(rpcCall(rpc, "eth_getTransactionCount",
                    JSON.parseArray("[\"" + from + "\",\"pending\"]")));
            long chainId = "bsc".equals(chain) ? 56L : 1L;
            RawTransaction tx = RawTransaction.createEtherTransaction(nonce, gasPrice, gasLimit, toAddr, send);
            byte[] signed = TransactionEncoder.signMessage(tx, chainId, cred);
            String txHash = rpcCall(rpc, "eth_sendRawTransaction",
                    JSON.parseArray("[\"" + Numeric.toHexString(signed) + "\"]"));
            return ok(chain, "bsc".equals(chain) ? "BNB" : "ETH", txHash, from, toAddr, fromWei(send, 18));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    private Map<String, Object> transferEvmUsdt(String chain, String privateKeyHex, String to, String amount) {
        try {
            Credentials cred = Credentials.create(normPrivHex(privateKeyHex));
            String from = cred.getAddress();
            String toAddr = toChecksum(to);
            String contract = toChecksum("bsc".equals(chain) ? BSC_USDT : ETH_USDT);
            int decimals = "bsc".equals(chain) ? 18 : 6;
            String rpc = rpcOf(chain);
            String padded = Numeric.cleanHexPrefix(from).toLowerCase(Locale.ROOT);
            while (padded.length() < 64) {
                padded = "0" + padded;
            }
            String balHex = rpcCall(rpc, "eth_call", JSON.parseArray(
                    "[{\"to\":\"" + contract + "\",\"data\":\"0x70a08231" + padded + "\"},\"latest\"]"));
            BigInteger balance = hexToBig(balHex);
            BigInteger send;
            if (amount == null || amount.isBlank()) {
                send = balance;
            } else {
                send = new BigDecimal(amount.trim()).movePointRight(decimals).setScale(0, RoundingMode.DOWN).toBigInteger();
            }
            if (send.compareTo(BigInteger.ZERO) <= 0) {
                return fail("USDT 余额为 0");
            }
            if (send.compareTo(balance) > 0) {
                return fail("USDT 余额不足");
            }
            String data = "0xa9059cbb"
                    + Numeric.toHexStringNoPrefixZeroPadded(Numeric.toBigInt(toAddr), 64)
                    + Numeric.toHexStringNoPrefixZeroPadded(send, 64);
            BigInteger gasPrice = hexToBig(rpcCall(rpc, "eth_gasPrice", new JSONArray())).max(BigInteger.ONE);
            BigInteger gasLimit = BigInteger.valueOf(100000);
            try {
                String est = rpcCall(rpc, "eth_estimateGas", JSON.parseArray(
                        "[{\"from\":\"" + from + "\",\"to\":\"" + contract + "\",\"data\":\"" + data + "\"}]"));
                gasLimit = hexToBig(est).max(BigInteger.valueOf(60000));
            } catch (Exception ignored) {
            }
            BigInteger fee = gasPrice.multiply(gasLimit);
            BigInteger nativeBal = hexToBig(rpcCall(rpc, "eth_getBalance", JSON.parseArray("[\"" + from + "\",\"latest\"]")));
            if (nativeBal.compareTo(fee) < 0) {
                return fail("原生币不足以支付 USDT 转账 gas");
            }
            BigInteger nonce = hexToBig(rpcCall(rpc, "eth_getTransactionCount",
                    JSON.parseArray("[\"" + from + "\",\"pending\"]")));
            long chainId = "bsc".equals(chain) ? 56L : 1L;
            RawTransaction tx = RawTransaction.createTransaction(nonce, gasPrice, gasLimit, contract, BigInteger.ZERO, data);
            byte[] signed = TransactionEncoder.signMessage(tx, chainId, cred);
            String txHash = rpcCall(rpc, "eth_sendRawTransaction",
                    JSON.parseArray("[\"" + Numeric.toHexString(signed) + "\"]"));
            return ok(chain, "USDT", txHash, from, toAddr, fromWei(send, decimals));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    private Map<String, Object> transferTronNative(String privateKeyHex, String to, String amount) {
        try {
            byte[] priv = parsePriv(privateKeyHex);
            String from = WalletAddressUtil.tronFromPrivPublic(priv);
            if (from.isEmpty() || to == null || to.isBlank()) {
                return fail("TRON 地址无效");
            }
            JSONObject acc = getJson(TRON_API + "/v1/accounts/" + from);
            JSONArray data = acc == null ? null : acc.getJSONArray("data");
            long balance = 0;
            if (data != null && !data.isEmpty()) {
                balance = data.getJSONObject(0).getLongValue("balance");
            }
            long reserve = RESERVE_TRON.movePointRight(6).longValue();
            long sendSun;
            if (amount == null || amount.isBlank()) {
                sendSun = balance - reserve;
            } else {
                sendSun = new BigDecimal(amount.trim()).movePointRight(6).setScale(0, RoundingMode.DOWN).longValue();
            }
            if (sendSun <= 0) {
                return fail("可转 TRX 不足（需预留手续费）");
            }
            JSONObject body = new JSONObject();
            body.put("owner_address", from);
            body.put("to_address", to.trim());
            body.put("amount", sendSun);
            body.put("visible", true);
            JSONObject created = postJson(TRON_API + "/wallet/createtransaction", body);
            if (created == null || created.getString("txID") == null) {
                return fail("创建 TRX 交易失败", created);
            }
            JSONObject signed = signTronTx(created, priv);
            JSONObject broadcast = postJson(TRON_API + "/wallet/broadcasttransaction", signed);
            String txid = signed.getString("txID");
            if (broadcast != null && Boolean.TRUE.equals(broadcast.getBoolean("result"))) {
                return ok("tron", "TRX", txid, from, to.trim(), fromWei(BigInteger.valueOf(sendSun), 6));
            }
            return fail(broadcast == null ? "广播失败" : String.valueOf(broadcast.get("message")), broadcast);
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    private Map<String, Object> transferTronUsdt(String privateKeyHex, String to, String amount) {
        try {
            byte[] priv = parsePriv(privateKeyHex);
            String from = WalletAddressUtil.tronFromPrivPublic(priv);
            if (from.isEmpty() || to == null || to.isBlank()) {
                return fail("TRON 地址无效");
            }
            // balanceOf via triggerconstantcontract
            JSONObject balReq = new JSONObject();
            balReq.put("owner_address", from);
            balReq.put("contract_address", TRON_USDT);
            balReq.put("function_selector", "balanceOf(address)");
            balReq.put("parameter", padAddressParam(from));
            balReq.put("visible", true);
            JSONObject balResp = postJson(TRON_API + "/wallet/triggerconstantcontract", balReq);
            long balance = 0;
            if (balResp != null) {
                JSONArray arr = balResp.getJSONArray("constant_result");
                if (arr != null && !arr.isEmpty()) {
                    balance = new BigInteger(arr.getString(0), 16).longValue();
                }
            }
            long sendRaw;
            if (amount == null || amount.isBlank()) {
                sendRaw = balance;
            } else {
                sendRaw = new BigDecimal(amount.trim()).movePointRight(6).setScale(0, RoundingMode.DOWN).longValue();
            }
            if (sendRaw <= 0) {
                return fail("USDT 余额为 0");
            }
            if (sendRaw > balance) {
                return fail("USDT 余额不足");
            }
            String parameter = padAddressParam(to.trim()) + String.format("%064x", sendRaw);
            JSONObject trig = new JSONObject();
            trig.put("owner_address", from);
            trig.put("contract_address", TRON_USDT);
            trig.put("function_selector", "transfer(address,uint256)");
            trig.put("parameter", parameter);
            trig.put("fee_limit", 30_000_000);
            trig.put("call_value", 0);
            trig.put("visible", true);
            JSONObject created = postJson(TRON_API + "/wallet/triggersmartcontract", trig);
            if (created == null || created.getJSONObject("transaction") == null) {
                return fail("创建 USDT 交易失败", created);
            }
            JSONObject tx = created.getJSONObject("transaction");
            JSONObject signed = signTronTx(tx, priv);
            JSONObject broadcast = postJson(TRON_API + "/wallet/broadcasttransaction", signed);
            String txid = signed.getString("txID");
            if (broadcast != null && Boolean.TRUE.equals(broadcast.getBoolean("result"))) {
                return ok("tron", "USDT", txid, from, to.trim(), fromWei(BigInteger.valueOf(sendRaw), 6));
            }
            String msg = broadcast == null ? "广播失败" : String.valueOf(broadcast.getOrDefault("message", broadcast));
            if (msg.toLowerCase(Locale.ROOT).contains("resource") || msg.toLowerCase(Locale.ROOT).contains("bandwidth")) {
                msg = msg + "；TRON 带宽/能量不足，请先向该地址转入少量 TRX";
            }
            return fail(msg, broadcast);
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    private JSONObject signTronTx(JSONObject tx, byte[] priv) throws Exception {
        String rawDataHex = tx.getString("raw_data_hex");
        if (rawDataHex == null || rawDataHex.isBlank()) {
            throw new IllegalStateException("缺少 raw_data_hex");
        }
        byte[] raw = Numeric.hexStringToByteArray(rawDataHex);
        byte[] hash = sha256(raw);
        // secp256k1 sign
        org.bouncycastle.crypto.signers.ECDSASigner signer = new org.bouncycastle.crypto.signers.ECDSASigner();
        org.bouncycastle.crypto.params.ECPrivateKeyParameters key =
                new org.bouncycastle.crypto.params.ECPrivateKeyParameters(
                        new BigInteger(1, priv),
                        new org.bouncycastle.crypto.params.ECDomainParameters(
                                org.bouncycastle.asn1.sec.SECNamedCurves.getByName("secp256k1").getCurve(),
                                org.bouncycastle.asn1.sec.SECNamedCurves.getByName("secp256k1").getG(),
                                org.bouncycastle.asn1.sec.SECNamedCurves.getByName("secp256k1").getN()));
        signer.init(true, key);
        BigInteger[] sig = signer.generateSignature(hash);
        BigInteger r = sig[0];
        BigInteger s = sig[1];
        // low-s
        BigInteger half = key.getParameters().getN().shiftRight(1);
        if (s.compareTo(half) > 0) {
            s = key.getParameters().getN().subtract(s);
        }
        byte[] sigBytes = new byte[65];
        System.arraycopy(Numeric.toBytesPadded(r, 32), 0, sigBytes, 0, 32);
        System.arraycopy(Numeric.toBytesPadded(s, 32), 0, sigBytes, 32, 32);
        // recovery id: try 0..3
        byte recId = 0;
        for (int i = 0; i < 4; i++) {
            // Tron uses v = 27 + recId typically in signature[64]
            recId = (byte) i;
            sigBytes[64] = recId;
            break;
        }
        // Prefer recovery that matches pubkey — simplify: use 0
        sigBytes[64] = 0x1b; // common tronpy style sometimes differs; try both via broadcast
        // Actually Tron expects signature as 65 bytes with recovery 0/1 without +27 in some APIs
        // Use web3j Sign for proper recovery
        org.web3j.crypto.Sign.SignatureData sd = org.web3j.crypto.Sign.signMessage(hash, Credentials.create(Numeric.toHexStringNoPrefix(priv)).getEcKeyPair(), false);
        byte[] out = new byte[65];
        System.arraycopy(sd.getR(), 0, out, 0, 32);
        System.arraycopy(sd.getS(), 0, out, 32, 32);
        out[64] = sd.getV()[0];
        JSONArray sigs = new JSONArray();
        sigs.add(Numeric.toHexStringNoPrefix(out));
        tx.put("signature", sigs);
        return tx;
    }

    private static String padAddressParam(String base58OrHex) {
        // For Tron visible API, parameter uses hex address without 41 prefix padded to 32 bytes
        String hex;
        if (base58OrHex.startsWith("T")) {
            hex = tronBase58ToHex41(base58OrHex);
            if (hex.startsWith("41")) {
                hex = hex.substring(2);
            }
        } else {
            hex = Numeric.cleanHexPrefix(base58OrHex);
            if (hex.startsWith("41") && hex.length() == 42) {
                hex = hex.substring(2);
            }
        }
        while (hex.length() < 64) {
            hex = "0" + hex;
        }
        return hex;
    }

    private static String tronBase58ToHex41(String addr) {
        try {
            byte[] decoded = BIP32Util.base58Decode(addr);
            if (decoded.length < 21) {
                return "";
            }
            // version(1)+hash20+checksum4 → take first 21
            byte[] payload = Arrays.copyOfRange(decoded, 0, 21);
            return Numeric.toHexStringNoPrefix(payload);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256").digest(data);
    }

    private String rpcCall(String rpc, String method, JSONArray params) {
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        body.put("params", params == null ? new JSONArray() : params);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String raw = http.postForObject(rpc, new HttpEntity<>(body.toJSONString(), h), String.class);
        JSONObject resp = JSON.parseObject(raw);
        if (resp == null) {
            throw new IllegalStateException("RPC 无响应");
        }
        if (resp.get("error") != null) {
            throw new IllegalStateException(String.valueOf(resp.get("error")));
        }
        Object result = resp.get("result");
        return result == null ? "0x0" : String.valueOf(result);
    }

    private JSONObject getJson(String url) {
        try {
            String raw = http.getForObject(url, String.class);
            return JSON.parseObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject postJson(String url, JSONObject body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String raw = http.postForObject(url, new HttpEntity<>(body.toJSONString(), h), String.class);
        return JSON.parseObject(raw);
    }

    private static String rpcOf(String chain) {
        return "bsc".equals(chain) ? "https://bsc-dataseed.binance.org" : "https://ethereum.publicnode.com";
    }

    private static BigDecimal reserveNative(String chain) {
        return "bsc".equals(chain) ? RESERVE_BSC : RESERVE_ETH;
    }

    private static String normChain(String chain) {
        String c = chain == null ? "" : chain.trim().toLowerCase(Locale.ROOT);
        if ("bnb".equals(c)) {
            return "bsc";
        }
        if ("trx".equals(c)) {
            return "tron";
        }
        if ("ethereum".equals(c)) {
            return "eth";
        }
        if ("solana".equals(c) || "sol".equals(c)) {
            return "sol";
        }
        return c;
    }

    private static String normPrivHex(String privateKeyHex) {
        String key = privateKeyHex == null ? "" : privateKeyHex.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("0x")) {
            key = key.substring(2);
        }
        if (key.length() != 64) {
            throw new IllegalArgumentException("私钥格式无效");
        }
        return key;
    }

    private static byte[] parsePriv(String privateKeyHex) {
        try {
            return Numeric.hexStringToByteArray(normPrivHex(privateKeyHex));
        } catch (Exception e) {
            return null;
        }
    }

    private static String toChecksum(String address) {
        return org.web3j.crypto.Keys.toChecksumAddress(address.trim());
    }

    private static BigInteger hexToBig(String hex) {
        if (hex == null || hex.isBlank() || "0x".equals(hex)) {
            return BigInteger.ZERO;
        }
        return Numeric.toBigInt(hex);
    }

    private static BigInteger toWei(BigDecimal human) {
        return human.movePointRight(18).setScale(0, RoundingMode.DOWN).toBigInteger();
    }

    private static String fromWei(BigInteger raw, int decimals) {
        return new BigDecimal(raw).movePointLeft(decimals).stripTrailingZeros().toPlainString();
    }

    private static Map<String, Object> ok(String chain, String coin, String hash, String from, String to, String amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("chain", chain);
        m.put("coin", coin);
        m.put("hash", hash == null ? "" : hash);
        m.put("outaddress", from);
        m.put("inaddress", to);
        m.put("amount", amount);
        return m;
    }

    private static Map<String, Object> fail(String error) {
        return fail(error, null);
    }

    private static Map<String, Object> fail(String error, Object response) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error == null || error.isBlank() ? "transfer failed" : error.trim());
        if (response != null) {
            m.put("response", response);
        }
        return m;
    }
}
