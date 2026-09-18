package com.consumer.worker;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.consumer.config.V26ConsumerProperties;
import com.consumer.util.TelegramNotificationUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramMessageWorker {

    private final StringRedisTemplate redis;
    private final V26ConsumerProperties props;
    private final TelegramNotificationUtil telegramNotificationUtil;
    private final ConsumerWorkerRegistry registry;
    private RedisListWorker worker;

    @PostConstruct
    public void start() {
        worker = new RedisListWorker(redis, props.getQueue().getTgMessage(), "tg-message",
                props.getConsumer(), props.getConsumer().getTelegram(), this::process);
        registry.register(worker);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        if (worker != null) {
            worker.stop();
        }
    }

    private void process(String raw) {
        JSONObject data;
        try {
            data = JSON.parseObject(raw);
        } catch (Exception e) {
            throw new PoisonMessageException("tg bad payload: " + StringUtils.left(raw, 200));
        }
        String token = StringUtils.defaultString(data.getString("robot_id"));
        String chat = StringUtils.defaultString(data.getString("group_id"));
        String text = StringUtils.defaultString(data.getString("text"));
        if (StringUtils.isAnyBlank(token, chat, text)) {
            throw new PoisonMessageException("tg missing token/group/text: " + StringUtils.left(raw, 200));
        }
        JSONObject meta = data.getJSONObject("meta");
        String deviceId = meta == null ? "" : StringUtils.defaultString(meta.getString("device_id"));
        String template = meta == null ? "" : StringUtils.defaultString(meta.getString("template"));
        long mnemonicId = meta == null ? 0L : meta.getLongValue("mnemonic_id");
        long t0 = System.currentTimeMillis();
        try {
            telegramNotificationUtil.sendTelegramMsg(text, chat, token);
            log.info("【telegram】发飞机 done group={} device={} mnemonic_id={} template={} chars={} costMs={}",
                    chat, deviceId, mnemonicId, template, text.length(), System.currentTimeMillis() - t0);
        } catch (RuntimeException e) {
            log.warn("【telegram】发飞机 fail group={} device={} mnemonic_id={} template={} costMs={} err={}",
                    chat, deviceId, mnemonicId, template, System.currentTimeMillis() - t0, e.getMessage());
            throw e;
        }
    }
}
