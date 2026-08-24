package com.consumer.service;

import com.consumer.config.ConsumerProperties;
import com.consumer.util.RedisPush;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntry;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.params.SetParams;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主队列 Consumer（api18:tasks）。
 * <p>
 * 直接用 Jedis 池化连接 — Spring Data Redis 新旧版本差异大，但 Jedis 命令集稳定。
 */
@Component
@Slf4j
public class MainTasksConsumer implements ApplicationRunner {

    @Resource
    private ConsumerProperties props;
    @Resource
    private JedisPool jedisPool;
    @Resource
    private CaptureDispatchService dispatch;
    @Resource
    private RedisPush redisPush;

    private String consumerName;
    private ExecutorService workerPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Redis < 6.2 不支持 XAUTOCLAIM，首次失败后自动禁用 */
    private final AtomicBoolean autoClaimSupported = new AtomicBoolean(true);

    @PostConstruct
    public void setup() {
        String name = props.getConsumerName();
        if (name == null || name.isEmpty()) {
            try { name = InetAddress.getLocalHost().getHostName(); }
            catch (Exception e) { name = "consumer"; }
        }
        // 多实例部署时：即使同一台主机也需保证 consumerName 在消费组内唯一，
        // hostname + 随机 6 hex 可容纳 ~16^6 组合，单机多副本不会冲突。
        this.consumerName = name + "-" + randomHex(6) + "-main";
        int t = Math.max(1, props.getMainThreads());
        this.workerPool = new ThreadPoolExecutor(
                t, t, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1000),
                r -> {
                    Thread th = new Thread(r, "main-worker");
                    th.setDaemon(true);
                    return th;
                }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    public void shutdown() {
        log.info("MainTasksConsumer shutting down...");
        running.set(false);
        if (workerPool != null) {
            workerPool.shutdown();
            try { workerPool.awaitTermination(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureStreamAndGroup(redisPush.streamMain(), redisPush.groupMain());
        running.set(true);
        int t = Math.max(1, props.getMainThreads());
        for (int i = 0; i < t; i++) {
            final int idx = i;
            Thread th = new Thread(() -> pollLoop(idx), "main-poller-" + idx);
            th.setDaemon(true);
            th.start();
        }
        // DLQ 定期清理线程（每 10 分钟扫描一次，按 dlqRetentionMs 过滤过期死信）
        startDlqCleaner();
        log.info("MainTasksConsumer started threads={} consumerName={}", t, consumerName);
    }

    private void startDlqCleaner() {
        Thread cleaner = new Thread(() -> {
            long interval = 10 * 60 * 1000L; // 10min
            // 首次立即启动（等待短时间错开，避免与应用启动争抢资源）
            sleepMs(3000L);
            while (running.get()) {
                try {
                    if (tryAcquireDlqLock()) {
                        cleanDlq(redisPush.dlqMain());
                        cleanDlq(redisPush.dlqParseCi());
                        cleanDlq(redisPush.dlqNews4());
                    }
                    sleepMs(interval);
                } catch (Exception ignore) {}
            }
        }, "dlq-cleaner");
        cleaner.setDaemon(true);
        cleaner.start();
    }

    /**
     * 多实例部署时：DLQ 清理只需要一个实例跑。
     * 使用 Redis SETNX（SET with NX + EX）作为分布式锁，锁持有 2 分钟（短于执行间隔 10 分钟），
     * 即使实例崩溃不释放锁，下一轮锁也会自然过期，不会永久阻塞。
     */
    @SuppressWarnings("deprecation")
	private boolean tryAcquireDlqLock() {
        final String lockKey = "api18:dlq_cleaner_lock";
        final int ttlSec = 120; // 2 min
        try (Jedis j = jedisPool.getResource()) {
            String result = j.set(lockKey, consumerName, SetParams.setParams().nx().ex(ttlSec));
            boolean ok = "OK".equalsIgnoreCase(result);
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 删除 DLQ 中超期的消息（按 dead_time 字段判断） */
    private void cleanDlq(String dlqKey) {
        long nowSec = System.currentTimeMillis() / 1000L;
        long retentionSec = props.getDlqRetentionMs() / 1000L;
        if (retentionSec <= 0) return;
        try (Jedis j = jedisPool.getResource()) {
            // 最多扫描 200 条 DLQ 消息
            // Jedis 3.8.0 没有 StreamEntryID.MINIMUM/MAXIMUM（4.3.0 才加入），用 0-0 和 Long.MAX_VALUE 等效替代
            List<StreamEntry> entries = j.xrange(dlqKey,
                    new StreamEntryID(0, 0), new StreamEntryID(Long.MAX_VALUE, Long.MAX_VALUE), 200);
            if (entries == null || entries.isEmpty()) return;
            int removed = 0;
            for (StreamEntry se : entries) {
                Map<String, String> f = se.getFields();
                String tsStr = f.get("dead_time");
                if (tsStr == null) continue;
                try {
                    double ts = Double.parseDouble(tsStr);
                    if (nowSec - ts > retentionSec) {
                        j.xdel(dlqKey, se.getID());
                        removed++;
                    }
                } catch (Exception ignore) {}
            }
            if (removed > 0) {
                log.info("DLQ cleaned stream={} removed={}", dlqKey, removed);
            }
        } catch (Throwable t) {
            log.warn("DLQ clean FAIL stream={} err={}", dlqKey, t.toString());
        }
    }

    private void pollLoop(int idx) {
        long pollMs = props.getPollIntervalMs();
        String cons = consumerName + "-" + idx;
        int pollCount = 0;
        while (running.get()) {
            try {
                // autoClaim 降频：每 5 轮 poll 才检查一次 pending，减少空闲时的 Redis 调用
                if (pollCount % 5 == 0) {
                    autoClaimOnce(cons, props.getMainClaimIdleMs());
                }
                pollCount++;

                // XREADGROUP 新消息（> 最后消费位置）
                List<Map.Entry<StreamEntryID, Map<String, String>>> list =
                        xReadGroup(redisPush.streamMain(), redisPush.groupMain(), cons,
                                props.getBatchSize(), Math.min(2000, pollMs));
                if (list != null) {
                    for (Map.Entry<StreamEntryID, Map<String, String>> e : list) {
                        submit(e.getKey(), e.getValue());
                    }
                }
            } catch (Throwable t) {
                sleepMs(pollMs);
            }
        }
    }

    // ==================================================== Jedis 命令封装
    List<Map.Entry<StreamEntryID, Map<String, String>>> xReadGroup(
            String stream, String group, String cons, int count, long blockMs) {
        try (Jedis j = jedisPool.getResource()) {
            List<java.util.Map.Entry<String, List<StreamEntry>>> raw =
                    j.xreadGroup(group, cons,
                            XReadGroupParams.xReadGroupParams().count(count).block((int) blockMs),
                            Collections.singletonMap(stream, StreamEntryID.UNRECEIVED_ENTRY));
            if (raw == null || raw.isEmpty()) return Collections.emptyList();
            List<Map.Entry<StreamEntryID, Map<String, String>>> out = new ArrayList<>();
            for (java.util.Map.Entry<String, List<StreamEntry>> seg : raw) {
                for (StreamEntry se : seg.getValue()) {
                    out.add(new AbstractMap.SimpleEntry<>(se.getID(), se.getFields()));
                }
            }
            return out;
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    void autoClaimOnce(String cons, long idleMs) {
        if (!autoClaimSupported.get()) return;
        try (Jedis j = jedisPool.getResource()) {
            try {
                var pendingInfo = j.xpending(redisPush.streamMain(), redisPush.groupMain());
                if (pendingInfo == null || pendingInfo.getTotal() == 0) return;
            } catch (Throwable ignore) { return; }

            Map.Entry<StreamEntryID, List<StreamEntry>> autoclaim = j.xautoclaim(redisPush.streamMain(), redisPush.groupMain(), cons,
                    idleMs, StreamEntryID.LAST_ENTRY,
                    XAutoClaimParams.xAutoClaimParams().count(Math.max(4, props.getBatchSize() / 2)));
            if (autoclaim == null) return;
            for (StreamEntry se : autoclaim.getValue()) {
                submit(se.getID(), se.getFields());
            }
        } catch (Throwable t) {
            if (t.toString().contains("unknown command")) {
                autoClaimSupported.set(false);
            }
        }
    }

    private void submit(StreamEntryID id, Map<String, String> fields) {
        try { workerPool.submit(() -> processOne(id, fields)); }
        catch (RejectedExecutionException e) { processOne(id, fields); }
    }

    private void processOne(StreamEntryID id, Map<String, String> fields) {
        String attemptsStr = fields.getOrDefault("attempts", "0");
        int attempts = 0;
        try { attempts = Integer.parseInt(attemptsStr); } catch (Exception ignore) {}
        // 检查 retry_not_before，尊重指数退避：重试消息未到退避时间则等待
        String rnbStr = fields.get("retry_not_before");
        if (rnbStr != null) {
            try {
                long notBefore = Long.parseLong(rnbStr);
                long delay = notBefore - System.currentTimeMillis();
                if (delay > 0 && delay <= 30000) {
                    Thread.sleep(delay);
                }
            } catch (Exception ignore) {}
        }
        try {
            boolean ok = dispatch.handleMain(fields);
            if (ok) {
                // ack 方法已合并 XACK + XDEL，单次 Jedis 连接完成
                ack(redisPush.streamMain(), redisPush.groupMain(), id);
            } else {
                retryOrDead(id, fields, attempts, new RuntimeException("dispatch=false"));
            }
        } catch (Throwable t) {
            retryOrDead(id, fields, attempts, t);
        }
    }

    private void retryOrDead(StreamEntryID id, Map<String, String> fields,
                             int attempts, Throwable t) {
        int next = attempts + 1;
        String errMsg = t == null ? "" : t.toString();
        if (next >= props.getMaxAttempts()) {
            Map<String, String> dead = new HashMap<>(fields);
            dead.put("attempts", String.valueOf(next));
            dead.put("dead_reason", errMsg);
            dead.put("dead_source", redisPush.streamMain());
            dead.put("dead_record_id", id.toString());
            dead.put("dead_time", String.valueOf(System.currentTimeMillis() / 1000.0));
            xAdd(redisPush.dlqMain(), dead);
            ack(redisPush.streamMain(), redisPush.groupMain(), id);
            log.error("MAIN DLQ id={} job={} attempts={} err={}", id, fields.get("job"), next, errMsg, t);
            return;
        }
        Map<String, String> retry = new HashMap<>(fields);
        retry.put("attempts", String.valueOf(next));
        retry.put("last_error", errMsg);
        // 指数退避：base * 2^(attempt-1)，把预期 delay 写入消息。
        // 说明：此处不 sleep 阻塞当前 worker 线程（占着线程不做事会极大降低吞吐）；
        //       retry 消息入队后由其他 poller（或下一轮 poll）消费，自然形成延迟。
        long delay = props.getRetryBackoffBaseMs() * (1L << Math.min(next - 1, 10));
        retry.put("retry_delay_ms", String.valueOf(delay));
        retry.put("retry_not_before", String.valueOf(System.currentTimeMillis() + delay));
        xAdd(redisPush.streamMain(), retry);
        ack(redisPush.streamMain(), redisPush.groupMain(), id);
        log.warn("MAIN retry id={} job={} attempt={}/{} delay_ms={} err={}",
                id, fields.get("job"), next, props.getMaxAttempts(), delay, errMsg, t);
        // 不 sleep — worker 线程立即空出处理后续任务，吞吐提升明显
    }

    // ==================================================== Jedis helper
    void xAdd(String stream, Map<String, String> fields) {
        try (Jedis j = jedisPool.getResource()) {
            j.xadd(stream, fields,
                    XAddParams.xAddParams().maxLen(RedisPush.STREAM_MAXLEN));
        }
    }

    void ack(String stream, String group, StreamEntryID id) {
        try (Jedis j = jedisPool.getResource()) {
            // 合并 XACK + XDEL 为单次 Jedis 连接，减少连接池 borrow/return 开销
            j.xack(stream, group, id);
            j.xdel(stream, id);
        } catch (Throwable ignore) {}
    }

    private void ensureStreamAndGroup(String stream, String group) {
        try (Jedis j = jedisPool.getResource()) {
            try { j.xgroupCreate(stream, group, StreamEntryID.LAST_ENTRY, true); }
            catch (Throwable ignore) {}
        } catch (Throwable ignore) {}
    }

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static void sleepMs(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignore) {} }
    static String randomHex(int n) {
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", r.nextInt(256)));
        return sb.toString();
    }
}
