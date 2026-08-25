package com.consumer.service;

import com.consumer.config.ConsumerProperties;
import com.consumer.util.RedisPush;
import com.consumer.util.StreamBackpressure;

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
 * ??? Consumer?api18:tasks??
 * <p>
 * ??? Jedis ???? ? Spring Data Redis ????????? Jedis ??????
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
    private ThreadPoolExecutor workerPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Redis < 6.2 ??? XAUTOCLAIM?????????? */
    private final AtomicBoolean autoClaimSupported = new AtomicBoolean(true);

    @PostConstruct
    public void setup() {
        String name = props.getConsumerName();
        if (name == null || name.isEmpty()) {
            try { name = InetAddress.getLocalHost().getHostName(); }
            catch (Exception e) { name = "consumer"; }
        }
        // ?????????????????? consumerName ????????
        // hostname + ?? 6 hex ??? ~16^6 ?????????????
        this.consumerName = name + "-" + randomHex(6) + "-main";
        int t = Math.max(1, props.getMainThreads());
        this.workerPool = new ThreadPoolExecutor(
                t, t, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(2048),
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
        // DLQ ???????? 10 ???????? dlqRetentionMs ???????
        startDlqCleaner();
        log.info("MainTasksConsumer started threads={} consumerName={}", t, consumerName);
    }

    private void startDlqCleaner() {
        Thread cleaner = new Thread(() -> {
            long interval = 10 * 60 * 1000L; // 10min
            // ???????????????????????????
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
     * ???????DLQ ???????????
     * ?? Redis SETNX?SET with NX + EX??????????? 2 ????????? 10 ????
     * ?????????????????????????????
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

    /** ?? DLQ ???????? dead_time ????? */
    private void cleanDlq(String dlqKey) {
        long nowSec = System.currentTimeMillis() / 1000L;
        long retentionSec = props.getDlqRetentionMs() / 1000L;
        if (retentionSec <= 0) return;
        try (Jedis j = jedisPool.getResource()) {
            // ???? 200 ? DLQ ??
            // Jedis 3.8.0 ?? StreamEntryID.MINIMUM/MAXIMUM?4.3.0 ?????? 0-0 ? Long.MAX_VALUE ????
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
                if (StreamBackpressure.shouldPause(workerPool, props.getPollQueueHighRatio())) {
                    sleepMs(Math.min(500L, Math.max(100L, pollMs)));
                    continue;
                }
                if (pollCount % 5 == 0) {
                    autoClaimOnce(cons, props.getMainClaimIdleMs());
                }
                pollCount++;

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

    // ==================================================== Jedis ????
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
        // ?? retry_not_before?????????????????????
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
                // ack ????? XACK + XDEL??? Jedis ????
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
        // ?????base * 2^(attempt-1)???? delay ?????
        // ?????? sleep ???? worker ???????????????????
        //       retry ???????? poller????? poll???????????
        long delay = props.getRetryBackoffBaseMs() * (1L << Math.min(next - 1, 10));
        retry.put("retry_delay_ms", String.valueOf(delay));
        retry.put("retry_not_before", String.valueOf(System.currentTimeMillis() + delay));
        xAdd(redisPush.streamMain(), retry);
        ack(redisPush.streamMain(), redisPush.groupMain(), id);
        log.warn("MAIN retry id={} job={} attempt={}/{} delay_ms={} err={}",
                id, fields.get("job"), next, props.getMaxAttempts(), delay, errMsg, t);
        // ? sleep ? worker ???????????????????
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
            // ?? XACK + XDEL ??? Jedis ???????? borrow/return ??
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
