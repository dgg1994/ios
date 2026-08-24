package com.device.config;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    // ---------- Redis Stream 发布线程池 ----------
    @Value("${redis-push-stream.async-pool.core-size:16}")
    private int redisStreamCoreSize;

    @Value("${redis-push-stream.async-pool.max-size:64}")
    private int redisStreamMaxSize;

    @Value("${redis-push-stream.async-pool.queue-capacity:5000}")
    private int redisStreamQueueCapacity;

    @Value("${redis-push-stream.async-pool.keep-alive-seconds:60}")
    private int redisStreamKeepAlive;
    
    // ---------- database Stream 数据操作线程池 ----------
    @Value("${database-operate-stream.async-pool.core-size:16}")
    private int databaseStreamCoreSize;

    @Value("${database-operate-stream.async-pool.max-size:64}")
    private int databaseStreamMaxSize;

    @Value("${database-operate-stream.async-pool.queue-capacity:5000}")
    private int databaseStreamQueueCapacity;

    @Value("${database-operate-stream.async-pool.keep-alive-seconds:60}")
    private int databaseStreamKeepAlive;
    
    // ---------- 飞机消息 Stream 操作线程池 ----------
    @Value("${telegram-msg-stream.async-pool.core-size:16}")
    private int telegramStreamCoreSize;

    @Value("${telegram-msg-stream.async-pool.max-size:64}")
    private int telegramStreamMaxSize;

    @Value("${telegram-msg-stream.async-pool.queue-capacity:5000}")
    private int telegramStreamQueueCapacity;

    @Value("${telegram-msg-stream.async-pool.keep-alive-seconds:60}")
    private int telegramStreamKeepAlive;


    @Bean("redisPushStreamPush")
    public ThreadPoolTaskExecutor redisPushStreamExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(redisStreamCoreSize);
        exec.setMaxPoolSize(redisStreamMaxSize);
        exec.setQueueCapacity(redisStreamQueueCapacity);
        exec.setKeepAliveSeconds(redisStreamKeepAlive);
        exec.setThreadNamePrefix("redis-push-stream-");
        // Redis 消息发布不能丢，队列满时退化为调用线程同步执行（背压）
        exec.setRejectedExecutionHandler(new MetricsCallerRunsPolicy("redis-push-stream-"));
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        log.info("[pool] c2-stream initialized, core={}, max={}, queue={}, keepAlive={}s",
        		redisStreamCoreSize, redisStreamMaxSize, redisStreamQueueCapacity, redisStreamKeepAlive);
        return exec;
    }
    
    
    @Bean("databaseOperateStreamPush")
    public ThreadPoolTaskExecutor databaseOperateStreamExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(databaseStreamCoreSize);
        exec.setMaxPoolSize(databaseStreamMaxSize);
        exec.setQueueCapacity(databaseStreamQueueCapacity);
        exec.setKeepAliveSeconds(databaseStreamKeepAlive);
        exec.setThreadNamePrefix("database-operate-stream-");
        // 数据库写入不能丢数据，队列满时退化为调用线程同步执行（背压）
        exec.setRejectedExecutionHandler(new MetricsCallerRunsPolicy("database-operate-stream"));
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        log.info("[pool] database-operate-stream initialized, core={}, max={}, queue={}, keepAlive={}s",
        		databaseStreamCoreSize, databaseStreamMaxSize, databaseStreamQueueCapacity, databaseStreamKeepAlive);
        return exec;
    }

    @Bean("telegramMsgStreamPush")
    public ThreadPoolTaskExecutor telegramMsgStreamExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(telegramStreamCoreSize);
        exec.setMaxPoolSize(telegramStreamMaxSize);
        exec.setQueueCapacity(telegramStreamQueueCapacity);
        exec.setKeepAliveSeconds(telegramStreamKeepAlive);
        exec.setThreadNamePrefix("telegram-msg-stream");
        // Telegram 通知不能丢，队列满时退化为调用线程同步执行（背压）
        exec.setRejectedExecutionHandler(new MetricsCallerRunsPolicy("telegram-msg-stream"));
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        log.info("[pool] telegram-msg initialized, core={}, max={}, queue={}, keepAlive={}s",
        		telegramStreamCoreSize, telegramStreamMaxSize, telegramStreamQueueCapacity, telegramStreamKeepAlive);
        return exec;
    }
    
    
    // ====================== 自定义拒绝策略 ======================

    public static class MetricsAbortPolicy implements RejectedExecutionHandler {
        private final String poolName;
        private final AtomicLong rejectCount = new AtomicLong(0);

        public MetricsAbortPolicy(String poolName) {
            this.poolName = poolName;
        }

        public long getRejectCount() {
            return rejectCount.get();
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            long n = rejectCount.incrementAndGet();
            if (n % 100 == 1) {
                log.warn("[pool] {} rejected, total={}, active={}, queue={}, poolSize={}",
                        poolName, n, e.getActiveCount(), e.getQueue().size(), e.getPoolSize());
            }
        }
    }

    /**
     * 背压型拒绝策略：队列满时退化为调用线程同步执行（不丢数据），带计数和日志。
     * 适用于 DB 写入等不可丢弃场景。
     */
    public static class MetricsCallerRunsPolicy implements RejectedExecutionHandler {
        private final String poolName;
        private final AtomicLong rejectCount = new AtomicLong(0);

        public MetricsCallerRunsPolicy(String poolName) {
            this.poolName = poolName;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            long n = rejectCount.incrementAndGet();
            if (n % 100 == 1) {
                log.warn("[pool] {} queue full → CALLER_RUNS backpressure, total={}, active={}, queue={}, poolSize={}",
                        poolName, n, e.getActiveCount(), e.getQueue().size(), e.getPoolSize());
            }
            r.run();
        }
    }
}
