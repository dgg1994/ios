package com.consume.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * /us 助记词地址派生线程池：与 Kafka listener 隔离，避免 BIP39/多链派生堵消费槽。
 */
@Configuration
public class WalletDeriveAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(WalletDeriveAsyncConfig.class);
    private static final AtomicLong REJECTED = new AtomicLong();

    @Value("${news4.wallet-derive.pool.core-size:4}")
    private int coreSize;

    @Value("${news4.wallet-derive.pool.max-size:12}")
    private int maxSize;

    @Value("${news4.wallet-derive.pool.queue-capacity:2000}")
    private int queueCapacity;

    @Bean(name = "walletDeriveExecutor")
    public Executor walletDeriveExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(Math.max(2, coreSize));
        exec.setMaxPoolSize(Math.max(coreSize, maxSize));
        exec.setQueueCapacity(Math.max(64, queueCapacity));
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("wallet-derive-");
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(60);
        // 队列满时 CallerRuns：派生不丢，最坏在当前线程跑完
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                long n = REJECTED.incrementAndGet();
                if (n % 50 == 1) {
                    log.warn("异常日志:[wallet-derive] 队列满，CallerRuns rejected={}, active={}, queue={}",
                            n, e.getActiveCount(), e.getQueue().size());
                }
                super.rejectedExecution(r, e);
            }
        });
        exec.initialize();
        log.info("正常日志:[wallet-derive] 派生线程池已就绪, core={}, max={}, queue={}",
                coreSize, maxSize, queueCapacity);
        return exec;
    }
}
