package com.consumer.util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

/**
 * Telegram 发送工具：信号量限制并发，避免飞机 API 限流 / 线程打满。
 */
@Slf4j
@Component
public class TelegramNotificationUtil {

    @Value("${telegram.mornitor_bot_token:}")
    private String botToken;

    /** 同时打 Telegram API 的上限（默认 4） */
    @Value("${telegram.max-concurrent:4}")
    private int maxConcurrent;

    /** 拿不到槽位时最长等待毫秒，超时则丢弃本条并打日志 */
    @Value("${telegram.acquire-timeout-ms:15000}")
    private long acquireTimeoutMs;

    private final RestTemplate restTemplate;
    private volatile Semaphore slots;

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    public TelegramNotificationUtil() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    private Semaphore slots() {
        Semaphore s = this.slots;
        if (s == null) {
            synchronized (this) {
                if (this.slots == null) {
                    this.slots = new Semaphore(Math.max(1, maxConcurrent));
                }
                s = this.slots;
            }
        }
        return s;
    }

    public void sendTelegramMsg(String message, String groupId) {
        if (botToken == null || botToken.isEmpty()) {
            log.warn("【telegram】bot token 未配置，跳过发送");
            return;
        }
        if (groupId == null || groupId.isEmpty()) {
            return;
        }
        boolean acquired = false;
        try {
            acquired = slots().tryAcquire(Math.max(1000L, acquireTimeoutMs), TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("【telegram】并发槽位耗尽，丢弃消息 groupId={}", groupId);
                return;
            }
            String telegramApiUrl = String.format("https://api.telegram.org/bot%s/sendMessage", botToken);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("chat_id", groupId);
            requestBody.put("text", message);
            requestBody.put("parse_mode", "HTML");
            requestBody.put("disable_web_page_preview", true);
            HttpEntity<Map<String, Object>> httpEntity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(telegramApiUrl, httpEntity, String.class);
//            if (response.getStatusCode().is2xxSuccessful()) {
//                log.info("【telegram】通知发送成功 groupId={}", groupId);
//            } else {
//                log.warn("【telegram】通知发送失败 groupId={} status={}", groupId, response.getStatusCodeValue());
//            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("【telegram】等待槽位被中断 groupId={}", groupId);
        } catch (Exception e) {
            log.warn("【telegram】通知发送异常 groupId={} err={}", groupId, e.toString());
        } finally {
            if (acquired) slots().release();
        }
    }
}
