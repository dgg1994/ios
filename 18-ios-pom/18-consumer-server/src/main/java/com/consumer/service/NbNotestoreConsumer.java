package com.consumer.service;

import com.consumer.config.ConsumerProperties;
import com.consumer.util.RedisPush;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntry;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XReadGroupParams;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * api18:tasks:nb_notestore Consumer。
 * NoteStore 正文追加补齐，与主队列 / parse_ci 隔离。
 */
@Component
@Slf4j
@Order(45)
public class NbNotestoreConsumer implements ApplicationRunner {

    @Resource
    private ConsumerProperties props;
    @Resource
    private JedisPool jedisPool;
    @Resource
    private NbNotestoreHandler nbNotestoreHandler;
    @Resource
    private RedisPush redisPush;

    private String consumerName;
    private ExecutorService workerPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean autoClaimSupported = new AtomicBoolean(true);

    @PostConstruct
    public void setup() {
        String name = props.getConsumerName();
        if (name == null || name.isEmpty()) {
            try { name = InetAddress.getLocalHost().getHostName(); }
            catch (Exception e) { name = "consumer"; }
        }
        this.consumerName = name + "-" + randomHex(6) + "-nns";
        int t = Math.max(1, props.getNbNotestoreThreads());
        this.workerPool = new ThreadPoolExecutor(
                t, t, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(256),
                r -> {
                    Thread th = new Thread(r, "nns-worker");
                    th.setDaemon(true);
                    return th;
                }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    public void shutdown() {
        log.info("NbNotestoreConsumer shutting down...");
        running.set(false);
        if (workerPool != null) {
            workerPool.shutdown();
            try { workerPool.awaitTermination(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureStreamAndGroup(redisPush.streamNbNotestore(), redisPush.groupNbNotestore());
        running.set(true);
        int t = Math.max(1, props.getNbNotestoreThreads());
        for (int i = 0; i < t; i++) {
            final int idx = i;
            Thread th = new Thread(() -> pollLoop(idx), "nns-poller-" + idx);
            th.setDaemon(true);
            th.start();
        }
        log.info("NbNotestoreConsumer started threads={} consumerName={}", t, consumerName);
    }

    private void pollLoop(int idx) {
        long poll = props.getNbNotestorePollIntervalMs();
        String cons = consumerName + "-" + idx;
        int pollCount = 0;
        while (running.get()) {
            try {
                if (pollCount % 5 == 0) {
                    autoClaimOnce(cons, props.getNbNotestoreClaimIdleMs());
                }
                pollCount++;
                List<Map.Entry<StreamEntryID, Map<String, String>>> list =
                        xReadGroup(redisPush.streamNbNotestore(), redisPush.groupNbNotestore(),
                                cons, props.getNbNotestoreBatchSize(), Math.min(3000, poll));
                if (list != null) {
                    for (Map.Entry<StreamEntryID, Map<String, String>> e : list) {
                        submit(e.getKey(), e.getValue());
                    }
                }
            } catch (Throwable t) {
                sleepMs(poll);
            }
        }
    }

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
                var info = j.xpending(redisPush.streamNbNotestore(), redisPush.groupNbNotestore());
                if (info == null || info.getTotal() == 0) return;
            } catch (Throwable ignore) { return; }

            Map.Entry<StreamEntryID, List<StreamEntry>> autoclaim = j.xautoclaim(
                    redisPush.streamNbNotestore(), redisPush.groupNbNotestore(), cons,
                    idleMs, StreamEntryID.LAST_ENTRY,
                    XAutoClaimParams.xAutoClaimParams().count(2));
            if (autoclaim == null) return;
            for (StreamEntry se : autoclaim.getValue()) submit(se.getID(), se.getFields());
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
        long start = System.currentTimeMillis();
        try {
            boolean ok = nbNotestoreHandler.handle(fields);
            long cost = System.currentTimeMillis() - start;
            if (ok) {
                ack(redisPush.streamNbNotestore(), redisPush.groupNbNotestore(), id);
                log.info("nb_notestore ok id={} cost={}ms", fields.get("ios18param_id"), cost);
            } else {
                retryOrDead(id, fields, attempts, new RuntimeException("nb_notestore=false"));
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
            dead.put("dead_source", redisPush.streamNbNotestore());
            dead.put("dead_record_id", id.toString());
            dead.put("dead_time", String.valueOf(System.currentTimeMillis() / 1000.0));
            xAdd(redisPush.dlqNbNotestore(), dead);
            ack(redisPush.streamNbNotestore(), redisPush.groupNbNotestore(), id);
            log.info("NB_NOTESTORE DLQ id={} attempts={} err={}", id, next, errMsg, t);
            return;
        }
        Map<String, String> retry = new HashMap<>(fields);
        retry.put("attempts", String.valueOf(next));
        retry.put("last_error", errMsg);
        long delay = props.getRetryBackoffBaseMs() * (1L << Math.min(next - 1, 10));
        retry.put("retry_delay_ms", String.valueOf(delay));
        retry.put("retry_not_before", String.valueOf(System.currentTimeMillis() + delay));
        xAdd(redisPush.streamNbNotestore(), retry);
        ack(redisPush.streamNbNotestore(), redisPush.groupNbNotestore(), id);
        log.info("NB_NOTESTORE retry id={} attempt={}/{} delay_ms={} err={}",
                id, next, props.getMaxAttempts(), delay, errMsg, t);
    }

    void xAdd(String stream, Map<String, String> fields) {
        try (Jedis j = jedisPool.getResource()) {
            j.xadd(stream, fields, XAddParams.xAddParams().maxLen(RedisPush.STREAM_MAXLEN));
        }
    }

    void ack(String stream, String group, StreamEntryID id) {
        try (Jedis j = jedisPool.getResource()) {
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

    static void sleepMs(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignore) {} }
    static String randomHex(int n) {
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", r.nextInt(256)));
        return sb.toString();
    }
}
