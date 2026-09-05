package com.report.config;

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

/**
 * 异步任务线程池（report-server）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    // ---------- c2-record 入库线程池 ----------
    @Value("${c2.record.async-pool.core-size:32}")
    private int c2RecordCoreSize;

    @Value("${c2.record.async-pool.max-size:128}")
    private int c2RecordMaxSize;

    @Value("${c2.record.async-pool.queue-capacity:8000}")
    private int c2RecordQueueCapacity;

    @Value("${c2.record.async-pool.keep-alive-seconds:60}")
    private int c2RecordKeepAlive;

    // ---------- Redis Stream 发布线程池 ----------
    @Value("${c2.redis-stream.async-pool.core-size:16}")
    private int streamCoreSize;

    @Value("${c2.redis-stream.async-pool.max-size:64}")
    private int streamMaxSize;

    @Value("${c2.redis-stream.async-pool.queue-capacity:5000}")
    private int streamQueueCapacity;

    @Value("${c2.redis-stream.async-pool.keep-alive-seconds:60}")
    private int streamKeepAlive;

    // ---------- Telegram 通知线程池（与 Redis Stream 隔离） ----------
    @Value("${c2.telegram.async-pool.core-size:4}")
    private int telegramCoreSize;

    @Value("${c2.telegram.async-pool.max-size:16}")
    private int telegramMaxSize;

    @Value("${c2.telegram.async-pool.queue-capacity:2000}")
    private int telegramQueueCapacity;

    @Value("${c2.telegram.async-pool.keep-alive-seconds:60}")
    private int telegramKeepAlive;

    // ---------- Kafka 发布线程池（已启用） ----------
    @Value("${c2.kafka.async-pool.core-size:8}")
    private int kafkaCoreSize;

    @Value("${c2.kafka.async-pool.max-size:32}")
    private int kafkaMaxSize;

    @Value("${c2.kafka.async-pool.queue-capacity:5000}")
    private int kafkaQueueCapacity;

    @Value("${c2.kafka.async-pool.keep-alive-seconds:60}")
    private int kafkaKeepAlive;

    // ---------- 设备绑定线程池（与 c2-record 入库隔离） ----------
    @Value("${c2.device-bind.async-pool.core-size:16}")
    private int deviceBindCoreSize;

    @Value("${c2.device-bind.async-pool.max-size:48}")
    private int deviceBindMaxSize;

    @Value("${c2.device-bind.async-pool.queue-capacity:5000}")
    private int deviceBindQueueCapacity;

    @Value("${c2.device-bind.async-pool.keep-alive-seconds:60}")
    private int deviceBindKeepAlive;

    // ====================== Bean 定义 ======================

    @Bean("c2RecordExecutor")
    public ThreadPoolTaskExecutor c2RecordExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(c2RecordCoreSize);
        exec.setMaxPoolSize(c2RecordMaxSize);
        exec.setQueueCapacity(c2RecordQueueCapacity);
        exec.setKeepAliveSeconds(c2RecordKeepAlive);
        exec.setThreadNamePrefix("c2-record-");
        exec.setRejectedExecutionHandler(new CallerRunsWithLogPolicy("c2-record"));
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        log.debug("正常日志:[pool] c2-record 已初始化, core={}, max={}, queue={}, keepAlive={}s",
                c2RecordCoreSize, c2RecordMaxSize, c2RecordQueueCapacity, c2RecordKeepAlive);
        return exec;
    }

    @Bean("deviceBindExecutor")
    public ThreadPoolTaskExecutor deviceBindExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(deviceBindCoreSize);
        exec.setMaxPoolSize(deviceBindMaxSize);
        exec.setQueueCapacity(deviceBindQueueCapacity);
        exec.setKeepAliveSeconds(deviceBindKeepAlive);
        exec.setThreadNamePrefix("c2-bind-");
        exec.setRejectedExecutionHandler(new CallerRunsWithLogPolicy("c2-bind"));
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        log.debug("正常日志:[pool] c2-bind 已初始化, core={}, max={}, queue={}, keepAlive={}s",
                deviceBindCoreSize, deviceBindMaxSize, deviceBindQueueCapacity, deviceBindKeepAlive);
        return exec;
    }

    @Bean("c2RecordStreamExecutor")
    public ThreadPoolTaskExecutor c2RecordStreamExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(streamCoreSize);
        exec.setMaxPoolSize(streamMaxSize);
        exec.setQueueCapacity(streamQueueCapacity);
        exec.setKeepAliveSeconds(streamKeepAlive);
        exec.setThreadNamePrefix("c2-stream-");
        exec.setRejectedExecutionHandler(new MetricsAbortPolicy("c2-stream"));
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        log.debug("正常日志:[pool] c2-stream 已初始化, core={}, max={}, queue={}, keepAlive={}s",
                streamCoreSize, streamMaxSize, streamQueueCapacity, streamKeepAlive);
        return exec;
    }

    /**
     * Telegram 通知线程池。
     *
     */
    @Bean("c2TelegramExecutor")
    public ThreadPoolTaskExecutor c2TelegramExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(telegramCoreSize);
        exec.setMaxPoolSize(telegramMaxSize);
        exec.setQueueCapacity(telegramQueueCapacity);
        exec.setKeepAliveSeconds(telegramKeepAlive);
        exec.setThreadNamePrefix("c2-tg-");
        exec.setRejectedExecutionHandler(new MetricsAbortPolicy("c2-tg"));
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        log.debug("正常日志:[pool] c2-tg 已初始化, core={}, max={}, queue={}, keepAlive={}s",
                telegramCoreSize, telegramMaxSize, telegramQueueCapacity, telegramKeepAlive);
        return exec;
    }

    /**
     * Kafka 发布线程池（已启用）。
     *
     */
    @Bean("kafkaPublisherExecutor")
    public ThreadPoolTaskExecutor kafkaPublisherExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(kafkaCoreSize);
        exec.setMaxPoolSize(kafkaMaxSize);
        exec.setQueueCapacity(kafkaQueueCapacity);
        exec.setKeepAliveSeconds(kafkaKeepAlive);
        exec.setThreadNamePrefix("kafka-pub-");
        exec.setRejectedExecutionHandler(new CallerRunsWithLogPolicy("kafka-pub"));
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        return exec;
    }

    // ====================== 自定义拒绝策略 ======================

    /**
     * 拒绝策略：降级为同步执行（CallerRunsPolicy）+ 告警日志。
     * 保证关键入库/消息任务不丢失，线程池满时由调用者线程（HTTP 线程）同步执行。
     */
    public static class CallerRunsWithLogPolicy implements RejectedExecutionHandler {
        private final String poolName;
        private final AtomicLong rejectCount = new AtomicLong(0);

        public CallerRunsWithLogPolicy(String poolName) {
            this.poolName = poolName;
        }

        public long getRejectCount() {
            return rejectCount.get();
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            long n = rejectCount.incrementAndGet();
            log.warn("错误日志:[pool] {} 线程池已满，降级为同步执行, totalRejected={}, active={}, queue={}, poolSize={}",
                    poolName, n, e.getActiveCount(), e.getQueue().size(), e.getPoolSize());
            try {
                r.run();
            } catch (Exception ex) {
                log.error("错误日志:[pool] {} 同步执行任务异常: {}", poolName, ex.getMessage(), ex);
            }
        }
    }

    /**
     * 已停用：原 MetricsAbortPolicy（丢弃任务），改用 CallerRunsWithLogPolicy 保证数据不丢。
     */
    @SuppressWarnings("unused")
    private static class MetricsAbortPolicy implements RejectedExecutionHandler {
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
                log.info("错误日志:[pool] {} 拒绝执行, total={}, active={}, queue={}, poolSize={}",
                        poolName, n, e.getActiveCount(), e.getQueue().size(), e.getPoolSize());
            }
        }
    }
}
