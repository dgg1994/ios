package com.consume.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 飞机消息异步线程池：与 Kafka 消费线程隔离。
 */
@Configuration
public class TelegramAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(TelegramAsyncConfig.class);

    @Value("${telegram-msg-stream.async-pool.core-size:4}")
    private int coreSize;

    @Value("${telegram-msg-stream.async-pool.max-size:16}")
    private int maxSize;

    @Value("${telegram-msg-stream.async-pool.queue-capacity:2000}")
    private int queueCapacity;

    @Value("${telegram-msg-stream.async-pool.keep-alive-seconds:60}")
    private int keepAlive;

    @Bean("telegramMsgExecutor")
    public ThreadPoolTaskExecutor telegramMsgExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(Math.max(1, coreSize));
        exec.setMaxPoolSize(Math.max(coreSize, maxSize));
        exec.setQueueCapacity(Math.max(100, queueCapacity));
        exec.setKeepAliveSeconds(Math.max(10, keepAlive));
        exec.setThreadNamePrefix("telegram-msg-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        log.info("正常日志:[telegram] 线程池已就绪, core={}, max={}, queue={}",
                coreSize, maxSize, queueCapacity);
        return exec;
    }
}
