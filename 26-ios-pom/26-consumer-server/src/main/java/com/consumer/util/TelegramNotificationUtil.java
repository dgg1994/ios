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

    /** 拿不到槽位时最长等待毫秒，超时抛异常由队列重试 */
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

    /** 使用默认渠道 bot token */
    public void sendTelegramMsg(String message, String groupId) {
        sendTelegramMsg(message, groupId, botToken);
    }

    /** 指定 bot token（高额隐私群用独立 token）。失败抛异常，由队列重试，26 不允许丢消息。 */
    public void sendTelegramMsg(String message, String groupId, String tokenOverride) {
        String token = (tokenOverride != null && !tokenOverride.trim().isEmpty())
                ? tokenOverride.trim() : botToken;
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("telegram bot token 未配置");
        }
        if (groupId == null || groupId.isEmpty()) {
            throw new IllegalStateException("telegram groupId 为空");
        }
        boolean acquired = false;
        long t0 = System.currentTimeMillis();
        try {
            acquired = slots().tryAcquire(Math.max(1000L, acquireTimeoutMs), TimeUnit.MILLISECONDS);
            long waitMs = System.currentTimeMillis() - t0;
            if (!acquired) {
                throw new IllegalStateException("telegram 并发槽位耗尽 groupId=" + groupId);
            }
            long tHttp = System.currentTimeMillis();
            String telegramApiUrl = String.format("https://api.telegram.org/bot%s/sendMessage", token);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("chat_id", groupId);
            requestBody.put("text", message);
            requestBody.put("parse_mode", "HTML");
            requestBody.put("disable_web_page_preview", true);
            HttpEntity<Map<String, Object>> httpEntity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(telegramApiUrl, httpEntity, String.class);
            long httpMs = System.currentTimeMillis() - tHttp;
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("【telegram】通知发送成功 groupId={} waitMs={} httpMs={} costMs={}",
                        groupId, waitMs, httpMs, System.currentTimeMillis() - t0);
                return;
            }
            throw new IllegalStateException("telegram HTTP " + response.getStatusCodeValue()
                    + " groupId=" + groupId + " httpMs=" + httpMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("telegram 等待槽位被中断 groupId=" + groupId, ie);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("telegram 发送异常 groupId=" + groupId
                    + " costMs=" + (System.currentTimeMillis() - t0) + " err=" + e, e);
        } finally {
            if (acquired) slots().release();
        }
    }
}
