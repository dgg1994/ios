package com.ctwo.config;

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
 * 异步线程池配置。
 *
 * <p>两个独立池：
 * <ul>
 *   <li>{@code redisPushStreamPush}：Redis Stream 发布（快、IO 密集，丢任务可接受）</li>
 *   <li>{@code databaseOperateStreamPush}：DB 写入 + 入队（相对慢、IO 密集，不能丢数据）</li>
 * </ul>
 *
 * <p>拒绝策略：
 * <ul>
 *   <li>Redis 池：{@link MetricsAbortPolicy} — 队列满时丢弃（Redis 发布可重试，不影响核心数据）</li>
 *   <li>Database 池：{@link BufferThenBackpressurePolicy} — 先溢出缓冲，再退化为
 *       CallerRuns（背压让 HTTP 线程短暂执行，保证不丢数据）</li>
 * </ul>
 *
 * <p>两池隔离，避免 DB 慢拖垮 Redis 入队（或反之）。
 */
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

    @Value("${database-operate-stream.async-pool.max-size:128}")
    private int databaseStreamMaxSize;

    @Value("${database-operate-stream.async-pool.queue-capacity:20000}")
    private int databaseStreamQueueCapacity;

    @Value("${database-operate-stream.async-pool.keep-alive-seconds:120}")
    private int databaseStreamKeepAlive;

    /** 数据库池溢出缓冲容量（队列满时的额外缓冲区）。 */
    @Value("${database-operate-stream.async-pool.overflow-buffer:2000}")
    private int databaseOverflowBuffer;

    @Bean("redisPushStreamPush")
    public ThreadPoolTaskExecutor redisPushStreamExecutor() {
        return build("redis-push-stream-", redisStreamCoreSize, redisStreamMaxSize,
                redisStreamQueueCapacity, redisStreamKeepAlive,
                new MetricsCallerRunsPolicy("redis-push-stream"));
    }

    @Bean("databaseOperateStreamPush")
    public ThreadPoolTaskExecutor databaseOperateStreamExecutor() {
        return build("database-operate-stream-", databaseStreamCoreSize, databaseStreamMaxSize,
                databaseStreamQueueCapacity, databaseStreamKeepAlive,
                new BufferThenBackpressurePolicy("database-operate-stream", databaseOverflowBuffer));
    }

    /** 统一构造一个 ThreadPoolTaskExecutor，避免重复样板代码。 */
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
        log.info("[pool] {} initialized, core={}, max={}, queue={}, keepAlive={}s, handler={}",
                prefix, core, max, queue, keepAlive, handler.getClass().getSimpleName());
        return exec;
    }

    // ====================== 拒绝策略 ======================

    /**
     * 可丢弃型拒绝策略：队列满时丢弃任务并周期性告警。
     * 适用于可容忍丢失的非关键场景。
     */
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
                log.warn("[pool] {} rejected (discarded), total={}, active={}, queue={}, poolSize={}",
                        poolName, n, e.getActiveCount(), e.getQueue().size(), e.getPoolSize());
            }
        }
    }

    /**
     * 背压型拒绝策略：队列满时退化为调用线程同步执行（不丢数据），带计数和日志。
     * 适用于 Redis 消息发布等不可丢弃场景。
     */
    public static class MetricsCallerRunsPolicy implements RejectedExecutionHandler {
        private final String poolName;
        private final AtomicLong rejectCount = new AtomicLong(0);

        public MetricsCallerRunsPolicy(String poolName) {
            this.poolName = poolName;
        }

        public long getRejectCount() {
            return rejectCount.get();
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

    /**
     * 不丢数据型拒绝策略：先尝试溢出缓冲 → 缓冲满则 CallerRuns 背压。
     *
     * <p>两级保护：
     * <ol>
     *   <li>线程池队列满 → 任务进入溢出缓冲（小容量同步队列，避免阻塞调用线程）</li>
     *   <li>溢出缓冲也满 → 调用线程（HTTP 线程）直接执行任务（背压，保证不丢数据）</li>
     * </ol>
     *
     * <p>背压说明：极端高并发下 HTTP 线程会短暂执行 DB insert（~5-20ms），
     * 但数据不丢失，且线程池恢复后立即回到异步路径。
     *
     * <p><b>高并发正确性：后台 drain 线程</b> — 仅在 reject 时 drain 会导致「洪峰过去后
     * 线程池空闲，但缓冲里还有任务」的假死；本类构造时启动 daemon 定时线程每 50ms
     * 回填缓冲任务，保证缓冲里的任务最多 50ms 滞后即可被执行。
     */
    public static class BufferThenBackpressurePolicy implements RejectedExecutionHandler {
        private final String poolName;
        private final AtomicLong rejectCount = new AtomicLong(0);
        private final AtomicLong backpressureCount = new AtomicLong(0);
        private final LinkedBlockingQueue<Runnable> overflowBuffer;
        private volatile ThreadPoolExecutor boundExecutor;

        public BufferThenBackpressurePolicy(String poolName, int bufferCapacity) {
            this.poolName = poolName;
            this.overflowBuffer = new LinkedBlockingQueue<>(bufferCapacity);
            startDrainDaemon();
        }

        public long getRejectCount() {
            return rejectCount.get();
        }

        public long getBackpressureCount() {
            return backpressureCount.get();
        }

        /** 启动后台定时 drain 线程（每 50ms 一次），避免溢出缓冲任务长期滞留。 */
        private void startDrainDaemon() {
            Thread th = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(50L);
                        // 主动排空溢出缓冲：洪峰过去后线程池空闲，但缓冲里可能还有任务
                        ThreadPoolExecutor e = boundExecutor;
                        if (e != null && !e.isShutdown() && !overflowBuffer.isEmpty()) {
                            drainBufferTo(e);
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Throwable ignore) {}
                }
            }, "pool-drain-" + poolName);
            th.setDaemon(true);
            th.start();
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            long n = rejectCount.incrementAndGet();
            // 绑定 executor 引用，供 drain 守护线程使用
            boundExecutor = e;
            // 0. 先把缓冲里能回填的任务先填进去，腾出空间（drain 不阻塞）
            drainBufferTo(e);
            // 1. 尝试放入溢出缓冲
            if (overflowBuffer.offer(r)) {
                // 再尝试一次回填（此时线程池队列可能已有空位）
                drainBufferTo(e);
                if (n % 100 == 1) {
                    log.warn("[pool] {} queue full → overflow buffer, total={}, bufSize={}, active={}, poolSize={}",
                            poolName, n, overflowBuffer.size(), e.getActiveCount(), e.getPoolSize());
                }
                return;
            }
            // 2. 溢出缓冲也满 → 背压：调用线程直接执行（不丢数据）
            long bp = backpressureCount.incrementAndGet();
            log.warn("[pool] {} overflow buffer FULL → CALLER_RUNS backpressure! bp={}, total={}, active={}, queue={}, poolSize={}",
                    poolName, bp, n, e.getActiveCount(), e.getQueue().size(), e.getPoolSize());
            r.run();
        }

        /** 将溢出缓冲中的任务尽量回填到线程池。 */
        private void drainBufferTo(ThreadPoolExecutor e) {
            if (e == null || e.isShutdown()) return;
            Runnable r;
            int moved = 0;
            while ((r = overflowBuffer.poll()) != null) {
                if (!e.getQueue().offer(r)) {
                    // 线程池队列仍满，放回缓冲并退出循环
                    overflowBuffer.offer(r);
                    break;
                }
                moved++;
            }
            if (moved > 0) {
                log.debug("[pool] {} drained {} tasks from overflow buffer, remaining={}",
                        poolName, moved, overflowBuffer.size());
            }
        }
    }
}
