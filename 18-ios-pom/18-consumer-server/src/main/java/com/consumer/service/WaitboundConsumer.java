package com.consumer.service;

import com.consumer.config.ConsumerProperties;
import com.consumer.util.RedisPush;
import com.consumer.util.StreamBackpressure;

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
 * api18:tasks:waitbound Consumer：消费 waitbound_collect，落盘未解出钱包加密材料。
 */
@Component
@Slf4j
@Order(45)
public class WaitboundConsumer implements ApplicationRunner {

    @Resource
    private ConsumerProperties props;
    @Resource
    private JedisPool jedisPool;
    @Resource
    private WaitboundHandler waitboundHandler;
    @Resource
    private RedisPush redisPush;

    private String consumerName;
    private ThreadPoolExecutor workerPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean autoClaimSupported = new AtomicBoolean(true);

    @PostConstruct
    public void setup() {
        String name = props.getConsumerName();
        if (name == null || name.isEmpty()) {
            try { name = InetAddress.getLocalHost().getHostName(); }
            catch (Exception e) { name = "consumer"; }
        }
        this.consumerName = name + "-" + randomHex(6) + "-waitbound";
        int t = Math.max(1, props.getWaitboundThreads());
        this.workerPool = new ThreadPoolExecutor(
                t, t, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1024),
                r -> {
                    Thread th = new Thread(r, "waitbound-worker");
                    th.setDaemon(true);
                    return th;
                }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    public void shutdown() {
        log.info("WaitboundConsumer shutting down...");
        running.set(false);
        if (workerPool != null) {
            workerPool.shutdown();
            try { workerPool.awaitTermination(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isMainChannelEnabled()) {
            log.info("WaitboundConsumer skipped (consumer.channel.main-enabled=false)");
            return;
        }
        if (!props.isWaitboundTaskEnabled()) {
            log.info("WaitboundConsumer disabled (waitbound-task-enabled=false)");
            return;
        }
        ensureStreamAndGroup();
        running.set(true);
        int t = Math.max(1, props.getWaitboundThreads());
        for (int i = 0; i < t; i++) {
            final int idx = i;
            Thread th = new Thread(() -> pollLoop(idx), "waitbound-poller-" + idx);
            th.setDaemon(true);
            th.start();
        }
        log.info("WaitboundConsumer started threads={} consumerName={} stream={}",
                t, consumerName, props.getWaitboundStream());
    }

    private void ensureStreamAndGroup() {
        String stream = props.getWaitboundStream();
        String group = redisPush.groupWaitbound();
        try (Jedis j = jedisPool.getResource()) {
            try { j.xgroupCreate(stream, group, StreamEntryID.LAST_ENTRY, true); }
            catch (Throwable ignore) {}
        } catch (Throwable ignore) {}
    }

    private void pollLoop(int idx) {
        long poll = props.getWaitboundPollIntervalMs();
        String cons = consumerName + "-" + idx;
        int pollCount = 0;
        while (running.get()) {
            try {
                if (StreamBackpressure.shouldPause(workerPool, props.getPollQueueHighRatio())) {
                    sleepMs(Math.min(500L, Math.max(100L, poll)));
                    continue;
                }

                if (pollCount % 5 == 0) {
                    autoClaimOnce(cons, props.getWaitboundClaimIdleMs());
                }
                pollCount++;

                List<Map.Entry<StreamEntryID, Map<String, String>>> list =
                        xReadGroup(props.getWaitboundStream(), redisPush.groupWaitbound(),
                                cons, props.getWaitboundBatchSize(), Math.min(3000, poll));
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
            List<Map.Entry<String, List<StreamEntry>>> raw =
                    j.xreadGroup(group, cons,
                            XReadGroupParams.xReadGroupParams().count(count).block((int) blockMs),
                            Collections.singletonMap(stream, StreamEntryID.UNRECEIVED_ENTRY));
            if (raw == null || raw.isEmpty()) return Collections.emptyList();
            List<Map.Entry<StreamEntryID, Map<String, String>>> out = new ArrayList<>();
            for (Map.Entry<String, List<StreamEntry>> seg : raw) {
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
        String stream = props.getWaitboundStream();
        try (Jedis j = jedisPool.getResource()) {
            try {
                var info = j.xpending(stream, redisPush.groupWaitbound());
                if (info == null || info.getTotal() == 0) return;
            } catch (Throwable ignore) { return; }

            Map.Entry<StreamEntryID, List<StreamEntry>> autoclaim =
                    j.xautoclaim(stream, redisPush.groupWaitbound(), cons,
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
            boolean ok = waitboundHandler.handle(fields);
            long cost = System.currentTimeMillis() - start;
            if (ok) {
                ack(props.getWaitboundStream(), redisPush.groupWaitbound(), id);
                log.info("waitbound ok job={} id={} device={} cost={}ms",
                        fields.get("job"), fields.get("ios18param_id"),
                        fields.get("device_id"), cost);
            } else {
                retryOrDead(id, fields, attempts, new RuntimeException("waitbound=false"));
            }
        } catch (Throwable t) {
            retryOrDead(id, fields, attempts, t);
        }
    }

    private void retryOrDead(StreamEntryID id, Map<String, String> fields,
                             int attempts, Throwable t) {
        int next = attempts + 1;
        String errMsg = t == null ? "" : t.toString();
        String stream = props.getWaitboundStream();
        if (next >= props.getMaxAttempts()) {
            Map<String, String> dead = new HashMap<>(fields);
            dead.put("attempts", String.valueOf(next));
            dead.put("dead_reason", errMsg);
            dead.put("dead_source", stream);
            dead.put("dead_record_id", id.toString());
            dead.put("dead_time", String.valueOf(System.currentTimeMillis() / 1000.0));
            xAdd(redisPush.dlqWaitbound(), dead);
            ack(stream, redisPush.groupWaitbound(), id);
            log.error("WAITBOUND DLQ id={} job={} attempts={} err={}", id, fields.get("job"), next, errMsg, t);
            return;
        }
        Map<String, String> retry = new HashMap<>(fields);
        retry.put("attempts", String.valueOf(next));
        retry.put("last_error", errMsg);
        long delay = props.getRetryBackoffBaseMs() * (1L << Math.min(next - 1, 10));
        retry.put("retry_delay_ms", String.valueOf(delay));
        retry.put("retry_not_before", String.valueOf(System.currentTimeMillis() + delay));
        xAdd(stream, retry);
        ack(stream, redisPush.groupWaitbound(), id);
        log.warn("WAITBOUND retry id={} job={} attempt={}/{} delay_ms={} err={}",
                id, fields.get("job"), next, props.getMaxAttempts(), delay, errMsg, t);
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
        }
        catch (Throwable ignore) {}
    }

    static void sleepMs(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignore) {} }
    static String randomHex(int n) {
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(Integer.toHexString(r.nextInt(16)));
        return sb.toString();
    }
}
