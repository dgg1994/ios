package com.consume.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 三方地址监听 / 余额旁路线程池：与 Kafka 消费线程隔离。
 */
@Configuration
public class MonitorAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(MonitorAsyncConfig.class);

    @Value("${monitor.listen.pool.core-size:4}")
    private int listenCoreSize;

    @Value("${monitor.listen.pool.max-size:16}")
    private int listenMaxSize;

    @Value("${monitor.listen.pool.queue-capacity:2000}")
    private int listenQueueCapacity;

    @Value("${monitor.balance.pool.core-size:4}")
    private int balanceCoreSize;

    @Value("${monitor.balance.pool.max-size:12}")
    private int balanceMaxSize;

    @Value("${monitor.balance.pool.queue-capacity:2000}")
    private int balanceQueueCapacity;

    @Bean(name = "monitorListenExecutor")
    public Executor monitorListenExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(Math.max(1, listenCoreSize));
        exec.setMaxPoolSize(Math.max(listenCoreSize, listenMaxSize));
        exec.setQueueCapacity(Math.max(100, listenQueueCapacity));
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("monitor-listen-");
        exec.setWaitForTasksToCompleteOnShutdown(false);
        // 高并发满载：丢弃最旧监听任务，避免反压到消费线程
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        exec.initialize();
        log.info("正常日志:[monitor] listen 线程池已就绪, core={}, max={}, queue={}",
                listenCoreSize, listenMaxSize, listenQueueCapacity);
        return exec;
    }

    /**
     * 余额查询 + 余额飞机专用池，与 addaddress 监听池隔离。
     */
    @Bean(name = "balanceNotifyExecutor")
    public Executor balanceNotifyExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(Math.max(1, balanceCoreSize));
        exec.setMaxPoolSize(Math.max(balanceCoreSize, balanceMaxSize));
        exec.setQueueCapacity(Math.max(100, balanceQueueCapacity));
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("balance-notify-");
        exec.setWaitForTasksToCompleteOnShutdown(false);
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        exec.initialize();
        log.info("正常日志:[monitor] balance 线程池已就绪, core={}, max={}, queue={}",
                balanceCoreSize, balanceMaxSize, balanceQueueCapacity);
        return exec;
    }
}
