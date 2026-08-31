package com.device.service.impl;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.device.dao.ChannelDao;
import com.device.entity.ChannelEntity;
import com.device.entity.DeviceEntity;
import com.device.util.MessageFormatUtils;
import com.device.util.TelegramNotificationUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * Device 服务的飞机消息异步发送封装。
 * <p>必须通过外部 Bean 调用本类方法，{@link Async} 才会经 AOP 代理生效；
 * 类内 this 调用会跳过 Spring AOP，造成同步执行、阻塞 DB 线程池。
 */
@Service
@Slf4j
public class DeviceTelegramService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private TelegramNotificationUtil telegramNotificationUtil;

    @Autowired
    private ChannelDao channelDao;

    /**
     * /a 首次绑定（bindPhase/devicestatus 由未绑定→已绑定）或 /beacon 补绑成功时 → 异步通知。
     */
    @Async("telegramMsgStreamPush")
    public void notifyBindingByDeviceIdAsync(DeviceEntity deviceEntity) {
        try {
            if (deviceEntity == null || deviceEntity.getChannelCode() == null
                    || deviceEntity.getChannelCode().isEmpty()) return;
            ChannelEntity channelEntity = channelDao.findCode(deviceEntity.getChannelCode());
            if (channelEntity == null || channelEntity.getTelegramGroupid() == null
                    || channelEntity.getTelegramGroupid().isEmpty()) return;
            String datetime = LocalDateTime.now(ZoneId.systemDefault()).format(DTF);
            String html = MessageFormatUtils.saveDeviceBindTelegram(channelEntity.getTelegramGroupid(),
                    deviceEntity.getChannelCode(), channelEntity.getName(), channelEntity.getUrl(),
                    deviceEntity.getModel(), deviceEntity.getIp(), datetime);
            telegramNotificationUtil.sendTelegramBindingMsg(html, channelEntity.getTelegramGroupid());
        } catch (Exception e) {
            log.warn("telegram notifyBindingByDeviceIdAsync failed, deviceId={}, err={}",
                    deviceEntity == null ? null : deviceEntity.getDeviceId(), e.getMessage());
        }
    }
}
