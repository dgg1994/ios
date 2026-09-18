package com.consumer.worker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.springframework.data.redis.connection.RedisListCommands.Direction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.consumer.config.V26ConsumerProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis List 可靠消费：Redis 6.2+ 用 BLMOVE，更低版本用 LPOP/BLPOP 进 inflight，处理成功才 LREM。
 * <p>26 不允许丢消息（与 18 照片高峰丢图不同）。进程崩溃后启动会把 inflight 捞回主队列。
 * 可重试失败回主队列；超过次数或毒消息进 dead。
 */
@Slf4j
public class RedisListWorker {

    private static final DefaultRedisScript<String> TAKE_LUA = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> RETRY_LUA = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> DEAD_LUA = new DefaultRedisScript<>();

    static {
        TAKE_LUA.setScriptText(
                "local v = redis.call('LPOP', KEYS[1])\n"
                        + "if not v then return false end\n"
                        + "redis.call('RPUSH', KEYS[2], v)\n"
                        + "return v");
        TAKE_LUA.setResultType(String.class);
        RETRY_LUA.setScriptText(
                "local n = redis.call('LREM', KEYS[1], 1, ARGV[1])\n"
                        + "if n > 0 then redis.call('RPUSH', KEYS[2], ARGV[1]) end\n"
                        + "return n");
        RETRY_LUA.setResultType(Long.class);
        DEAD_LUA.setScriptText(
                "local n = redis.call('LREM', KEYS[1], 1, ARGV[1])\n"
                        + "if n > 0 then redis.call('RPUSH', KEYS[2], ARGV[1]) end\n"
                        + "return n");
        DEAD_LUA.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final String queueKey;
    private final String inflightKey;
    private final String deadKey;
    private final String name;
    private final int processThreads;
    private final int pollers;
    private final int queueCapacity;
    private final int popBatch;
    private final int blpopTimeoutSec;
    private final int maxRetries;
    private final Consumer<String> processor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, AtomicInteger> retryCounts = new ConcurrentHashMap<>();

    private final AtomicLong popped = new AtomicLong();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong requeued = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();
    private final AtomicLong lastCostMs = new AtomicLong();
    private static final Object BLMOVE_LOCK = new Object();
    /** null=未探测；Redis 6.2+ 才有 BLMOVE */
    private static Boolean blmoveSupported;

    private ThreadPoolExecutor workPool;
    private ThreadPoolExecutor pollPool;

    public RedisListWorker(StringRedisTemplate redis, String queueKey, String name,
            V26ConsumerProperties.Consumer cfg, V26ConsumerProperties.Worker worker,
            java.util.function.Consumer<String> processor) {
        this.redis = redis;
        this.queueKey = queueKey;
        this.inflightKey = queueKey + ":inflight";
        this.deadKey = queueKey + ":dead";
        this.name = name;
        V26ConsumerProperties.Worker w = worker != null ? worker : new V26ConsumerProperties.Worker();
        this.processThreads = Math.max(1, w.getThreads());
        this.pollers = Math.max(1, w.getPollers());
        this.queueCapacity = Math.max(this.processThreads, w.getQueueCapacity());
        this.popBatch = Math.max(1, w.getPopBatch());
        this.blpopTimeoutSec = Math.max(1, cfg.getBlpopTimeoutSec());
        this.maxRetries = Math.max(1, cfg.getMaxRetries());
        this.processor = processor;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        int reclaimed = reclaimInflight();
        boolean blmove = probeBlmove();
        workPool = new ThreadPoolExecutor(
                processThreads,
                processThreads,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                namedFactory(name + "-work-"),
                (r, e) -> {
                    throw new java.util.concurrent.RejectedExecutionException(name + " work queue full");
                });
        workPool.allowCoreThreadTimeOut(false);
        pollPool = new ThreadPoolExecutor(
                pollers,
                pollers,
                0L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                namedFactory(name + "-poll-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        for (int i = 0; i < pollers; i++) {
            pollPool.submit(this::pollLoop);
        }
        log.info("{} worker started process={} pollers={} memQueue={} batch={} blpop={}s move={} redis={} inflight={} dead={} reclaimed={}",
                name, processThreads, pollers, queueCapacity, popBatch, blpopTimeoutSec,
                blmove ? "BLMOVE" : "LPOP",
                queueKey, inflightKey, deadKey, reclaimed);
    }

    public void stop() {
        running.set(false);
        shutdown(pollPool, 5, false);
        shutdown(workPool, 60, true);
        log.info("{} worker stopped processed={} failed={} requeued={} dead={}",
                name, processed.get(), failed.get(), requeued.get(), deadLettered.get());
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("queue", queueKey);
        m.put("inflight", inflightKey);
        m.put("dead", deadKey);
        m.put("popped", popped.get());
        m.put("processed", processed.get());
        m.put("failed", failed.get());
        m.put("requeued", requeued.get());
        m.put("deadLettered", deadLettered.get());
        m.put("lastCostMs", lastCostMs.get());
        m.put("processThreads", processThreads);
        m.put("inflightLen", llen(inflightKey));
        m.put("deadLen", llen(deadKey));
        if (workPool != null) {
            m.put("active", workPool.getActiveCount());
            m.put("memQueue", workPool.getQueue().size());
            m.put("memQueueCap", queueCapacity);
        }
        return m;
    }

    public String getQueueKey() {
        return queueKey;
    }

    public String getInflightKey() {
        return inflightKey;
    }

    public String getDeadKey() {
        return deadKey;
    }

    public String getName() {
        return name;
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                if (!hasWorkSlot()) {
                    Thread.sleep(30L);
                    continue;
                }
                List<String> batch = popBatch();
                if (batch.isEmpty()) {
                    continue;
                }
                for (String raw : batch) {
                    if (!running.get()) {
                        return;
                    }
                    enqueueWork(raw);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                log.warn("{} poll error: {}", name, e.toString());
                sleepQuiet(500L);
            }
        }
    }

    private boolean hasWorkSlot() {
        return workPool != null && workPool.getQueue().remainingCapacity() > 0;
    }

    private void enqueueWork(String raw) {
        while (running.get()) {
            if (submit(raw)) {
                return;
            }
            sleepQuiet(30L);
        }
    }

    private boolean submit(String raw) {
        try {
            workPool.execute(() -> runOne(raw));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void runOne(String raw) {
        long t0 = System.nanoTime();
        try {
            processor.accept(raw);
            ack(raw);
            retryCounts.remove(raw);
            processed.incrementAndGet();
        } catch (PoisonMessageException e) {
            failed.incrementAndGet();
            log.warn("{} poison, move to dead: {}", name, e.getMessage());
            deadLetter(raw);
        } catch (Exception e) {
            failed.incrementAndGet();
            int n = retryCounts.computeIfAbsent(raw, k -> new AtomicInteger()).incrementAndGet();
            if (n >= maxRetries) {
                log.warn("{} retries exhausted ({}/{}), move to dead: {}", name, n, maxRetries, e.toString());
                deadLetter(raw);
            } else {
                log.warn("{} process error, retry {}/{}: {}", name, n, maxRetries, e.toString());
                retry(raw);
            }
        } finally {
            lastCostMs.set((System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private List<String> popBatch() {
        int room = workPool.getQueue().remainingCapacity();
        if (room <= 0) {
            return Collections.emptyList();
        }
        int want = Math.min(popBatch, room);
        List<String> out = new ArrayList<>(want);
        for (int i = 0; i < want; i++) {
            boolean block = out.isEmpty();
            String one = takeOne(block);
            if (one == null) {
                break;
            }
            out.add(one);
            popped.incrementAndGet();
        }
        return out;
    }

    private String takeOne(boolean block) {
        if (probeBlmove()) {
            try {
                if (block) {
                    return redis.opsForList().move(queueKey, Direction.LEFT, inflightKey, Direction.RIGHT,
                            blpopTimeoutSec, TimeUnit.SECONDS);
                }
                return redis.opsForList().move(queueKey, Direction.LEFT, inflightKey, Direction.RIGHT);
            } catch (Exception e) {
                if (markBlmoveUnavailable(e)) {
                    log.info("Redis 不支持 BLMOVE（需要 6.2+），改用 LPOP/BLPOP 消费，消息仍可靠不丢");
                } else {
                    log.warn("{} BLMOVE 失败，本次改用 LPOP: {}", name, e.toString());
                }
            }
        }
        String v = redis.execute(TAKE_LUA, Arrays.asList(queueKey, inflightKey));
        if (v != null) {
            return v;
        }
        if (!block) {
            return null;
        }
        String one = redis.opsForList().leftPop(queueKey, Duration.ofSeconds(blpopTimeoutSec));
        if (one == null) {
            return null;
        }
        try {
            redis.opsForList().rightPush(inflightKey, one);
        } catch (Exception e) {
            log.error("{} inflight push FAILED after pop, restoring to queue: {}", name, e.toString());
            try {
                redis.opsForList().leftPush(queueKey, one);
            } catch (Exception e2) {
                log.error("{} restore FAILED, message at risk: {}", name, e2.toString());
            }
            return null;
        }
        return one;
    }

    private boolean probeBlmove() {
        Boolean cached = blmoveSupported;
        if (cached != null) {
            return cached;
        }
        synchronized (BLMOVE_LOCK) {
            if (blmoveSupported != null) {
                return blmoveSupported;
            }
            try {
                redis.opsForList().move("v26:blmove:probe", Direction.LEFT, "v26:blmove:probe:dst",
                        Direction.RIGHT, 1, TimeUnit.MILLISECONDS);
                blmoveSupported = true;
                return true;
            } catch (Exception e) {
                if (isUnknownBlmove(e)) {
                    blmoveSupported = false;
                    log.info("Redis 不支持 BLMOVE（需要 6.2+），改用 LPOP/BLPOP 消费，消息仍可靠不丢");
                    return false;
                }
                return false;
            }
        }
    }

    private static boolean markBlmoveUnavailable(Throwable e) {
        if (!isUnknownBlmove(e)) {
            return false;
        }
        synchronized (BLMOVE_LOCK) {
            if (Boolean.FALSE.equals(blmoveSupported)) {
                return false;
            }
            blmoveSupported = false;
            return true;
        }
    }

    private static boolean isUnknownBlmove(Throwable e) {
        String m = e == null ? "" : e.toString();
        String u = m.toUpperCase();
        return u.contains("UNKNOWN COMMAND") && u.contains("BLMOVE");
    }

    private int reclaimInflight() {
        int n = 0;
        try {
            while (n < 100_000) {
                String v = redis.opsForList().rightPopAndLeftPush(inflightKey, queueKey);
                if (v == null) {
                    break;
                }
                n++;
            }
            if (n > 0) {
                log.info("{} reclaimed {} inflight message(s) back to {}", name, n, queueKey);
            }
        } catch (Exception e) {
            log.warn("{} reclaim inflight failed: {}", name, e.toString());
        }
        return n;
    }

    private void ack(String raw) {
        try {
            Long n = redis.opsForList().remove(inflightKey, 1, raw);
            if (n == null || n <= 0) {
                log.warn("{} ack miss (already reclaimed?) queue={}", name, queueKey);
            }
        } catch (Exception e) {
            log.error("{} ack LREM FAILED: {}", name, e.toString());
        }
    }

    private void retry(String raw) {
        try {
            Long n = redis.execute(RETRY_LUA, Arrays.asList(inflightKey, queueKey), raw);
            if (n != null && n > 0) {
                requeued.incrementAndGet();
            }
        } catch (Exception e) {
            log.error("{} retry FAILED: {}", name, e.toString());
        }
    }

    private void deadLetter(String raw) {
        try {
            Long n = redis.execute(DEAD_LUA, Arrays.asList(inflightKey, deadKey), raw);
            retryCounts.remove(raw);
            if (n != null && n > 0) {
                deadLettered.incrementAndGet();
            }
        } catch (Exception e) {
            log.error("{} dead-letter FAILED: {}", name, e.toString());
        }
    }

    private long llen(String key) {
        try {
            Long n = redis.opsForList().size(key);
            return n == null ? 0L : n;
        } catch (Exception e) {
            return -1L;
        }
    }

    private static void shutdown(ThreadPoolExecutor pool, int waitSec, boolean gracefulFirst) {
        if (pool == null) {
            return;
        }
        if (gracefulFirst) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(waitSec, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                    pool.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            return;
        }
        pool.shutdownNow();
        try {
            pool.awaitTermination(waitSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
