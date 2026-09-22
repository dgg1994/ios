package com.admin.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
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

import com.admin.util.WalletAddressUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import lombok.extern.slf4j.Slf4j;

/**
 * 纯 Java 多链转账：ETH/BSC（原生+USDT）、TRON（TRX+USDT）、BTC、SOL（原生+USDT）。
 * 对齐 Python chain_transfer。归集入口放行 eth/bsc/tron/btc/sol。
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

    private final RestTemplate http;
    private final TronGridClient tron;

    public ChainTransferService(TronGridClient tron) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(15000);
        f.setReadTimeout(45000);
        this.http = new RestTemplate(f);
        this.tron = tron;
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
                String unit = "bsc".equals(chain) ? "BNB" : "ETH";
                String need = "bsc".equals(chain) ? "约 0.001 BNB" : "约 0.0008 ETH";
                return fail(unit + " 可转余额不足（需预留 " + need + " + gas），当前余额不足以发起归集");
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
            JSONObject acc = tron.getAccounts(from);
            JSONArray data = acc == null ? null : acc.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                JSONObject ga = new JSONObject();
                ga.put("address", from);
                ga.put("visible", true);
                acc = accountFromGetAccount(tron.postWallet("/wallet/getaccount", ga));
                data = acc == null ? null : acc.getJSONArray("data");
            }
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
            body.put("owner_address", TronGridClient.toHex41(from));
            body.put("to_address", TronGridClient.toHex41(to.trim()));
            body.put("amount", sendSun);
            body.put("visible", false);
            JSONObject created = tron.postWallet("/wallet/createtransaction", body);
            if (created == null || created.getString("txID") == null) {
                return fail("创建 TRX 交易失败", created);
            }
            JSONObject signed = signTronTx(created, priv, from);
            return finishTronBroadcast(signed, "TRX", from, to.trim(), fromWei(BigInteger.valueOf(sendSun), 6));
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
            balReq.put("parameter", TronGridClient.abiAddressParam(from));
            balReq.put("visible", true);
            JSONObject balResp = tron.postWallet("/wallet/triggerconstantcontract", balReq);
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
            String energyErr = usdtFeeBlockReason(from);
            if (energyErr != null) {
                return fail(energyErr);
            }
            String parameter = TronGridClient.abiAddressParam(to.trim()) + String.format("%064x", sendRaw);
            JSONObject trig = new JSONObject();
            trig.put("owner_address", TronGridClient.toHex41(from));
            trig.put("contract_address", TronGridClient.toHex41(TRON_USDT));
            trig.put("function_selector", "transfer(address,uint256)");
            trig.put("parameter", parameter);
            trig.put("fee_limit", 30_000_000);
            trig.put("call_value", 0);
            trig.put("visible", false);
            JSONObject created = tron.postWallet("/wallet/triggersmartcontract", trig);
            if (created == null || created.getJSONObject("transaction") == null) {
                return fail("创建 USDT 交易失败", created);
            }
            JSONObject tx = created.getJSONObject("transaction");
            JSONObject signed = signTronTx(tx, priv, from);
            return finishTronBroadcast(signed, "USDT", from, to.trim(), fromWei(BigInteger.valueOf(sendRaw), 6));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    /**
     * 对齐 tronpy：签 sha256(raw_data)，recovery 必须还原到转出地址。
     * 签名写回节点返回的整笔交易。广播优先走 broadcasthex，避免丢掉 raw_data 触发节点空指针，
     * 也不让节点按 JSON 把 raw_data 重编码后再验签。
     */
    private JSONObject signTronTx(JSONObject tx, byte[] priv, String fromAddr) throws Exception {
        String rawDataHex = tx.getString("raw_data_hex");
        if (rawDataHex == null || rawDataHex.isBlank()) {
            throw new IllegalStateException("缺少 raw_data_hex");
        }
        byte[] hash = sha256(Numeric.hexStringToByteArray(rawDataHex));
        String txid = tx.getString("txID");
        if (txid != null && !txid.isBlank()
                && !txid.equalsIgnoreCase(Numeric.toHexStringNoPrefix(hash))) {
            throw new IllegalStateException("交易 txID 与 raw_data 不一致，已中止");
        }
        Credentials cred = Credentials.create(Numeric.toHexStringNoPrefix(priv));
        org.web3j.crypto.Sign.SignatureData sd =
                org.web3j.crypto.Sign.signMessage(hash, cred.getEcKeyPair(), false);
        byte[] r = pad32(sd.getR());
        byte[] s = pad32(sd.getS());
        int rec = sd.getV()[0] & 0xFF;
        if (rec >= 27) {
            rec -= 27;
        }
        java.math.BigInteger expectPub = cred.getEcKeyPair().getPublicKey();
        if (!recoversTo(hash, r, s, rec, expectPub)) {
            int flipped = rec ^ 1;
            if (!recoversTo(hash, r, s, flipped, expectPub)) {
                throw new IllegalStateException("TRON 签名与转出地址不一致，已中止广播");
            }
            rec = flipped;
        }
        byte[] out = new byte[65];
        System.arraycopy(r, 0, out, 0, 32);
        System.arraycopy(s, 0, out, 32, 32);
        out[64] = (byte) rec;
        JSONArray sigs = new JSONArray();
        sigs.add(Numeric.toHexStringNoPrefix(out));
        tx.put("signature", sigs);
        if (fromAddr != null && !fromAddr.isBlank()
                && !fromAddr.equals(WalletAddressUtil.tronFromPrivPublic(priv))) {
            throw new IllegalStateException("私钥与转出地址不一致，已中止广播");
        }
        return tx;
    }

    private Map<String, Object> finishTronBroadcast(JSONObject signed, String coin, String from, String to, String amount) {
        String txid = signed.getString("txID");
        // 只传 raw_data_hex 时，节点 packTransaction 读 raw_data 会空指针。
        // broadcasthex 直接解析 protobuf，签名仍对着原始 raw_data 字节，不会按 JSON 重编码。
        JSONObject broadcast = tron.postWallet("/wallet/broadcasthex", tronBroadcastHexBody(signed));
        if (broadcast == null || broadcast.getString("Error") != null) {
            broadcast = tron.postWallet("/wallet/broadcasttransaction", signed);
        }
        if (!tronBroadcastOk(broadcast)) {
            return fail(tronBroadcastError(broadcast), broadcast);
        }
        // 节点收下交易 ≠ 合约执行成功；TRC20 常因能量不足上链后仍失败，手续费已扣、USDT 不动
        String ret = waitTronContractRet(txid);
        if ("SUCCESS".equalsIgnoreCase(ret)) {
            return ok("tron", coin, txid, from, to, amount);
        }
        if (ret == null || ret.isBlank()) {
            return fail("交易已广播上链，但暂未查到执行结果（" + txid + "）。请先在区块浏览器核对，不要马上再次归集");
        }
        String upper = ret.toUpperCase(Locale.ROOT);
        if (upper.contains("ENERGY") || upper.contains("BANDWIDTH") || upper.contains("RESOURCE")) {
            return fail("交易已上链但执行失败（" + ret + "），" + coin + " 未转出；能量/带宽不足，手续费可能已扣除。txid=" + txid);
        }
        return fail("交易已上链但执行失败（" + ret + "），" + coin + " 未转出。txid=" + txid);
    }

    /** 能量不够且 TRX 也覆盖不了手续费时，不要广播，避免只扣 TRX、USDT 不动还提示成功。 */
    private String usdtFeeBlockReason(String from) {
        try {
            JSONObject req = new JSONObject();
            req.put("address", TronGridClient.toHex41(from));
            req.put("visible", false);
            JSONObject res = tron.postWallet("/wallet/getaccountresource", req);
            long energyLimit = res == null ? 0 : res.getLongValue("EnergyLimit");
            long energyUsed = res == null ? 0 : res.getLongValue("EnergyUsed");
            long energy = Math.max(0, energyLimit - energyUsed);
            JSONObject accReq = new JSONObject();
            accReq.put("address", TronGridClient.toHex41(from));
            accReq.put("visible", false);
            JSONObject acc = tron.postWallet("/wallet/getaccount", accReq);
            long sun = acc == null ? 0 : acc.getLongValue("balance");
            if (energy < 30000 && sun < 5_000_000L) {
                return "能量和 TRX 都不足以支付 USDT 手续费（约需能量或至少数个 TRX）。为避免只扣手续费却转不出 USDT，已中止";
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String waitTronContractRet(String txid) {
        if (txid == null || txid.isBlank()) {
            return "";
        }
        for (int i = 0; i < 10; i++) {
            try {
                // gettransactioninfobyid：失败时 receipt.result=OUT_OF_ENERGY；成功时常无 result 字段
                JSONObject infoReq = new JSONObject();
                infoReq.put("value", txid);
                JSONObject info = tron.postWallet("/wallet/gettransactioninfobyid", infoReq);
                long blockNumber = info == null ? 0 : info.getLongValue("blockNumber");
                if (info != null && !info.isEmpty()) {
                    JSONObject receipt = info.getJSONObject("receipt");
                    if (receipt != null) {
                        String result = receipt.getString("result");
                        if (result != null && !result.isBlank() && !"SUCCESS".equalsIgnoreCase(result)) {
                            return result;
                        }
                    }
                }
                JSONObject txReq = new JSONObject();
                txReq.put("value", txid);
                JSONObject tx = tron.postWallet("/wallet/gettransactionbyid", txReq);
                if (tx != null) {
                    JSONArray ret = tx.getJSONArray("ret");
                    if (ret != null && !ret.isEmpty()) {
                        String contractRet = ret.getJSONObject(0).getString("contractRet");
                        if (contractRet != null && !contractRet.isBlank()) {
                            return contractRet;
                        }
                    }
                }
                if (blockNumber > 0) {
                    // 已进块且未标失败 → 视为成功
                    return "SUCCESS";
                }
                Thread.sleep(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static boolean recoversTo(byte[] hash, byte[] r, byte[] s, int recId, java.math.BigInteger expectPub) {
        try {
            int v = recId >= 27 ? recId : recId + 27;
            org.web3j.crypto.Sign.SignatureData data =
                    new org.web3j.crypto.Sign.SignatureData((byte) v, r, s);
            java.math.BigInteger pub = org.web3j.crypto.Sign.signedMessageHashToKey(hash, data);
            return pub != null && pub.equals(expectPub);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pad32(byte[] src) {
        if (src == null) {
            return new byte[32];
        }
        if (src.length == 32) {
            return src;
        }
        byte[] out = new byte[32];
        if (src.length > 32) {
            System.arraycopy(src, src.length - 32, out, 0, 32);
            return out;
        }
        System.arraycopy(src, 0, out, 32 - src.length, src.length);
        return out;
    }

    private static JSONObject accountFromGetAccount(JSONObject got) {
        if (got == null) {
            return null;
        }
        JSONObject row = new JSONObject();
        row.put("balance", got.getLongValue("balance"));
        JSONObject wrapped = new JSONObject();
        JSONArray data = new JSONArray();
        data.add(row);
        wrapped.put("data", data);
        return wrapped;
    }

    /** 把 raw_data 与签名装成 Transaction protobuf，交给 /wallet/broadcasthex。 */
    private static JSONObject tronBroadcastHexBody(JSONObject signed) {
        JSONArray sigs = signed.getJSONArray("signature");
        String sig = sigs == null || sigs.isEmpty() ? "" : sigs.getString(0);
        String rawHex = signed.getString("raw_data_hex");
        if (rawHex == null || rawHex.isBlank() || sig == null || sig.isBlank()) {
            return new JSONObject();
        }
        byte[] raw = Numeric.hexStringToByteArray(rawHex);
        byte[] signature = Numeric.hexStringToByteArray(sig);
        byte[] lenRaw = protoVarint(raw.length);
        byte[] lenSig = protoVarint(signature.length);
        byte[] tx = new byte[1 + lenRaw.length + raw.length + 1 + lenSig.length + signature.length];
        int o = 0;
        tx[o++] = 0x0a;
        System.arraycopy(lenRaw, 0, tx, o, lenRaw.length);
        o += lenRaw.length;
        System.arraycopy(raw, 0, tx, o, raw.length);
        o += raw.length;
        tx[o++] = 0x12;
        System.arraycopy(lenSig, 0, tx, o, lenSig.length);
        o += lenSig.length;
        System.arraycopy(signature, 0, tx, o, signature.length);
        JSONObject body = new JSONObject();
        body.put("transaction", Numeric.toHexStringNoPrefix(tx));
        return body;
    }

    private static byte[] protoVarint(int value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(4);
        int v = value;
        while ((v & ~0x7f) != 0) {
            out.write((v & 0x7f) | 0x80);
            v >>>= 7;
        }
        out.write(v);
        return out.toByteArray();
    }

    private static boolean tronBroadcastOk(JSONObject broadcast) {
        if (broadcast == null || broadcast.getString("Error") != null) {
            return false;
        }
        if (!Boolean.TRUE.equals(broadcast.getBoolean("result"))) {
            return false;
        }
        String code = broadcast.getString("code");
        return code == null || code.isBlank() || "SUCCESS".equalsIgnoreCase(code);
    }

    private static String tronBroadcastError(JSONObject broadcast) {
        if (broadcast == null) {
            return "广播失败";
        }
        Object msg = broadcast.get("message");
        if (msg == null) {
            msg = broadcast.get("code");
        }
        if (msg == null) {
            return String.valueOf(broadcast);
        }
        String text = String.valueOf(msg);
        // TronGrid 有时把错误写成 hex
        if (text.matches("(?i)^[0-9a-f]+$") && text.length() >= 8 && text.length() % 2 == 0) {
            try {
                String decoded = new String(Numeric.hexStringToByteArray(text), StandardCharsets.UTF_8).trim();
                if (!decoded.isEmpty()) {
                    return decoded;
                }
            } catch (Exception ignored) {
            }
        }
        return text;
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
        m.put("error", humanizeChainError(error));
        if (response != null) {
            m.put("response", response);
        }
        return m;
    }

    /** 将节点/HTTP 原始错误收成运营可读提示，避免把 JSON、<EOL> 等直接甩到弹窗。 */
    static String humanizeChainError(String raw) {
        if (raw == null || raw.isBlank()) {
            return "链上转账失败";
        }
        String t = raw.trim()
                .replace("<EOL>", " ")
                .replace("\\/", "/")
                .replaceAll("\\s+", " ");
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("429") || lower.contains("too many requests")
                || lower.contains("rate exceeded") || lower.contains("allowed_rps")
                || lower.contains("request rate")) {
            return "链上节点请求过于频繁，请稍候几秒后再试归集";
        }
        if (lower.contains("401") || lower.contains("403") || lower.contains("api key")
                || lower.contains("apikey")) {
            return "链上节点鉴权失败，请检查 API Key 或稍后重试";
        }
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("connect timed")) {
            return "链上节点超时，请稍后重试";
        }
        if (lower.contains("insufficient energy") || lower.contains("out of energy")
                || lower.contains("bandwidth") || lower.contains("out_of_energy")) {
            return "TRX 能量/带宽不足，USDT 未转出";
        }
        if (lower.contains("validate signature") || lower.contains("not contained of permission")) {
            return "签名与转出地址不一致，交易未上链";
        }
        if (lower.contains("insufficient") && (lower.contains("balance") || lower.contains("funds"))) {
            return "余额不足以支付转账金额或手续费";
        }
        if (lower.contains("nonce too low")) {
            return "交易 nonce 冲突，请稍后重试";
        }
        // 仍像英文/JSON/HTTP 原文时，给短提示并截断细节
        boolean looksRaw = t.startsWith("{") || t.startsWith("\"") || t.contains("\"Error\"")
                || lower.startsWith("http") || lower.matches("(?s)^\\d{3}\\s+.*")
                || (t.length() > 80 && t.chars().filter(c -> c == '{' || c == '"' || c == '\\').count() > 3);
        if (looksRaw) {
            String shortMsg = t.length() > 120 ? t.substring(0, 120) + "…" : t;
            return "链上转账失败：" + shortMsg;
        }
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
