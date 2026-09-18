package com.send.config;

import java.util.concurrent.LinkedBlockingQueue;
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
 * 对齐 18-ctwo {@code AsyncConfig}：Redis 入队池 + DB/通知池隔离，队列满时背压不丢关键任务。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Value("${redis-enqueue.async-pool.core-size:8}")
    private int redisCore;

    @Value("${redis-enqueue.async-pool.max-size:32}")
    private int redisMax;

    @Value("${redis-enqueue.async-pool.queue-capacity:5000}")
    private int redisQueue;

    @Value("${redis-enqueue.async-pool.keep-alive-seconds:60}")
    private int redisKeepAlive;

    @Value("${database-operate.async-pool.core-size:8}")
    private int dbCore;

    @Value("${database-operate.async-pool.max-size:64}")
    private int dbMax;

    @Value("${database-operate.async-pool.queue-capacity:10000}")
    private int dbQueue;

    @Value("${database-operate.async-pool.keep-alive-seconds:120}")
    private int dbKeepAlive;

    @Value("${database-operate.async-pool.overflow-buffer:2000}")
    private int dbOverflow;

    /** finish / TG 等 Redis、HTTP 旁路任务 */
    @Bean("redisEnqueuePush")
    public ThreadPoolTaskExecutor redisEnqueueExecutor() {
        return build("redis-enqueue-", redisCore, redisMax, redisQueue, redisKeepAlive,
                new MetricsCallerRunsPolicy("redis-enqueue"));
    }

    /** 设备侧写库后异步通知等（不能丢） */
    @Bean("databaseOperatePush")
    public ThreadPoolTaskExecutor databaseOperateExecutor() {
        return build("database-operate-", dbCore, dbMax, dbQueue, dbKeepAlive,
                new BufferThenBackpressurePolicy("database-operate", dbOverflow));
    }

    private ThreadPoolTaskExecutor build(String prefix, int core, int max, int queue,
            int keepAlive, RejectedExecutionHandler handler) {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(core);
        exec.setMaxPoolSize(max);
        exec.setQueueCapacity(queue);
        exec.setKeepAliveSeconds(keepAlive);
        exec.setThreadNamePrefix(prefix);
        exec.setRejectedExecutionHandler(handler);
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        log.info("[pool] {} core={} max={} queue={} keepAlive={}s handler={}",
                prefix, core, max, queue, keepAlive, handler.getClass().getSimpleName());
        return exec;
    }

    public static class MetricsCallerRunsPolicy implements RejectedExecutionHandler {
        private final String poolName;
        private final AtomicLong rejectCount = new AtomicLong();

        public MetricsCallerRunsPolicy(String poolName) {
            this.poolName = poolName;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            long n = rejectCount.incrementAndGet();
            if (n % 100 == 1) {
                log.warn("[pool] {} queue full → CALLER_RUNS total={} active={} queue={}",
                        poolName, n, e.getActiveCount(), e.getQueue().size());
            }
            r.run();
        }
    }

    public static class BufferThenBackpressurePolicy implements RejectedExecutionHandler {
        private final String poolName;
        private final AtomicLong rejectCount = new AtomicLong();
        private final AtomicLong backpressureCount = new AtomicLong();
        private final LinkedBlockingQueue<Runnable> overflowBuffer;
        private volatile ThreadPoolExecutor boundExecutor;

        public BufferThenBackpressurePolicy(String poolName, int bufferCapacity) {
            this.poolName = poolName;
            this.overflowBuffer = new LinkedBlockingQueue<>(Math.max(64, bufferCapacity));
            startDrainDaemon();
        }

        private void startDrainDaemon() {
            Thread th = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(50L);
                        ThreadPoolExecutor e = boundExecutor;
                        if (e != null && !e.isShutdown() && !overflowBuffer.isEmpty()) {
                            drainBufferTo(e);
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Throwable ignore) {
                        // keep draining
                    }
                }
            }, "pool-drain-" + poolName);
            th.setDaemon(true);
            th.start();
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            long n = rejectCount.incrementAndGet();
            boundExecutor = e;
            drainBufferTo(e);
            if (overflowBuffer.offer(r)) {
                drainBufferTo(e);
                if (n % 100 == 1) {
                    log.warn("[pool] {} → overflow buffer total={} bufSize={} active={}",
                            poolName, n, overflowBuffer.size(), e.getActiveCount());
                }
                return;
            }
            long bp = backpressureCount.incrementAndGet();
            log.warn("[pool] {} overflow FULL → CALLER_RUNS bp={} total={} active={}",
                    poolName, bp, n, e.getActiveCount());
            r.run();
        }

        private void drainBufferTo(ThreadPoolExecutor e) {
            if (e == null || e.isShutdown()) {
                return;
            }
            Runnable r;
            while ((r = overflowBuffer.poll()) != null) {
                if (!e.getQueue().offer(r)) {
                    overflowBuffer.offer(r);
                    break;
                }
            }
        }
    }
}
