package com.consumer.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Consumer 异步配置。
 * 主队列 / parse_ci 用独立 Consumer 线程池；此处提供飞机 / 余额旁路池。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Value("${telegram-msg-stream.async-pool.core-size:4}")
    private int telegramCoreSize;

    @Value("${telegram-msg-stream.async-pool.max-size:16}")
    private int telegramMaxSize;

    @Value("${telegram-msg-stream.async-pool.queue-capacity:2000}")
    private int telegramQueueCapacity;

    @Value("${telegram-msg-stream.async-pool.keep-alive-seconds:60}")
    private int telegramKeepAlive;

    @Value("${news4.listen-pool.core-size:4}")
    private int listenCoreSize;

    @Value("${news4.listen-pool.max-size:8}")
    private int listenMaxSize;

    @Value("${news4.listen-pool.queue-capacity:5000}")
    private int listenQueueCapacity;

    @Value("${news4.balance-pool.core-size:4}")
    private int balanceCoreSize;

    @Value("${news4.balance-pool.max-size:12}")
    private int balanceMaxSize;

    @Value("${news4.balance-pool.queue-capacity:5000}")
    private int balanceQueueCapacity;

    @Bean("telegramMsgExecutor")
    public ThreadPoolTaskExecutor telegramMsgExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(Math.max(1, telegramCoreSize));
        exec.setMaxPoolSize(Math.max(telegramCoreSize, telegramMaxSize));
        exec.setQueueCapacity(Math.max(100, telegramQueueCapacity));
        exec.setKeepAliveSeconds(Math.max(10, telegramKeepAlive));
        exec.setThreadNamePrefix("telegram-msg-");
        // 队列满时 CallerRuns：飞机尽量不丢；短任务，反压风险低
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        log.info("[pool] telegram-msg initialized, core={}, max={}, queue={}, keepAlive={}s",
                telegramCoreSize, telegramMaxSize, telegramQueueCapacity, telegramKeepAlive);
        return exec;
    }

    /**
     * 仅负责 /listen/addaddress，与余额查询隔离，避免慢 RPC 堵监听上报。
     * 拒绝策略 DiscardOldest：宁可丢最旧监听，也不反压 n4-worker。
     */
    @Bean("news4ListenExecutor")
    public ThreadPoolTaskExecutor news4ListenExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(Math.max(1, listenCoreSize));
        exec.setMaxPoolSize(Math.max(listenCoreSize, listenMaxSize));
        exec.setQueueCapacity(Math.max(100, listenQueueCapacity));
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("n4-listen-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        exec.initialize();
        log.info("[pool] news4-listen initialized, core={}, max={}, queue={}",
                listenCoreSize, listenMaxSize, listenQueueCapacity);
        return exec;
    }

    /**
     * 仅负责查余额 + 回写 + 余额飞机，与 addaddress / n4-worker 隔离。
     * 拒绝策略 DiscardOldest：高峰丢最旧余额任务，不反压消费线程。
     */
    @Bean("news4BalanceExecutor")
    public ThreadPoolTaskExecutor news4BalanceExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(Math.max(1, balanceCoreSize));
        exec.setMaxPoolSize(Math.max(balanceCoreSize, balanceMaxSize));
        exec.setQueueCapacity(Math.max(100, balanceQueueCapacity));
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("n4-balance-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        exec.initialize();
        log.info("[pool] news4-balance initialized, core={}, max={}, queue={}",
                balanceCoreSize, balanceMaxSize, balanceQueueCapacity);
        return exec;
    }
}
