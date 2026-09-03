package com.consume.service;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.consume.dto.C2NotifyMessage;
import com.consume.util.InstanceId;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntry;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.StreamPendingEntry;
import redis.clients.jedis.exceptions.JedisDataException;

/**
 * Redis Stream 消费循环（主通道 / 相册通道共用实现）。
 */
public class C2NotifyRedisStreamRunner {

    private static final Logger log = LoggerFactory.getLogger(C2NotifyRedisStreamRunner.class);

    private final JedisPool jedisPool;
    private final C2NotifyDispatcher dispatcher;
    private final String streamKey;
    private final String groupName;
    private final int workerThreads;
    private final int blockMs;
    private final int readCount;
    private final long claimIdleMs;
    private final int claimCount;
    private final boolean albumChannel;
    private final String channelTag;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;
    private final AtomicInteger workerSeq = new AtomicInteger(0);
    private final AtomicLong lastClaimAt = new AtomicLong(0);

    public C2NotifyRedisStreamRunner(
            JedisPool jedisPool,
            C2NotifyDispatcher dispatcher,
            String streamKey,
            String groupName,
            int workerThreads,
            int blockMs,
            int readCount,
            long claimIdleMs,
            int claimCount,
            boolean albumChannel,
            String channelTag) {
        this.jedisPool = jedisPool;
        this.dispatcher = dispatcher;
        this.streamKey = streamKey;
        this.groupName = groupName;
        this.workerThreads = workerThreads;
        this.blockMs = blockMs;
        this.readCount = readCount;
        this.claimIdleMs = claimIdleMs;
        this.claimCount = claimCount;
        this.albumChannel = albumChannel;
        this.channelTag = channelTag;
    }

    public void start() {
        ensureGroup();
        int n = Math.max(1, workerThreads);
        running.set(true);
        executor = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "c2-notify-redis-" + channelTag + "-" + workerSeq.incrementAndGet());
            t.setDaemon(false);
            return t;
        });
        for (int i = 0; i < n; i++) {
            executor.submit(this::consumeLoop);
        }
        log.info("正常日志:[c2_notify] Redis {} 监听已启动, stream={}, group={}, instance={}, threads={}, block={}ms, count={}, claimIdle={}ms",
                channelTag, streamKey, groupName, InstanceId.get(), n, blockMs, readCount, claimIdleMs);
    }

    public void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(6, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("正常日志:[c2_notify] Redis {} 监听已停止, stream={}", channelTag, streamKey);
    }

    private void ensureGroup() {
        try (Jedis jedis = jedisPool.getResource()) {
            try {
                jedis.xgroupCreate(streamKey, groupName, StreamEntryID.LAST_ENTRY, true);
                log.info("正常日志:[c2_notify] 创建消费组成功, stream={}, group={}", streamKey, groupName);
            } catch (JedisDataException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("BUSYGROUP")) {
                    log.info("正常日志:[c2_notify] 消费组已存在, stream={}, group={}", streamKey, groupName);
                } else {
                    log.info("异常日志:[c2_notify] 创建消费组异常, stream={}, group={}, err={}",
                            streamKey, groupName, msg);
                }
            }
        } catch (Exception e) {
            log.info("异常日志:[c2_notify] 连接 Redis 创建消费组失败, stream={}, err={}", streamKey, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void consumeLoop() {
        String consumer = InstanceId.get() + "-" + Thread.currentThread().getId();
        Map.Entry<String, StreamEntryID> stream =
                new AbstractMap.SimpleEntry<>(streamKey, StreamEntryID.UNRECEIVED_ENTRY);
        while (running.get()) {
            List<StreamEntry> batch = new ArrayList<>();
            try (Jedis jedis = jedisPool.getResource()) {
                List<Map.Entry<String, List<StreamEntry>>> resp =
                        jedis.xreadGroup(groupName, consumer, readCount, blockMs, false, stream);
                if (resp != null) {
                    for (Map.Entry<String, List<StreamEntry>> e : resp) {
                        if (e.getValue() == null) {
                            continue;
                        }
                        batch.addAll(e.getValue());
                    }
                }
                if (batch.isEmpty() || shouldClaimNow()) {
                    claimIdlePending(jedis, consumer, batch);
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.info("异常日志:[c2_notify] XREADGROUP 异常, stream={}, err={}", streamKey, e.getMessage());
                    sleep(1000);
                }
                continue;
            }
            for (StreamEntry entry : batch) {
                if (!running.get()) {
                    break;
                }
                handleOne(entry);
            }
        }
    }

    private boolean shouldClaimNow() {
        if (claimIdleMs <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        long prev = lastClaimAt.get();
        long interval = Math.max(3000L, claimIdleMs / 6);
        if (now - prev < interval) {
            return false;
        }
        return lastClaimAt.compareAndSet(prev, now);
    }

    private void claimIdlePending(Jedis jedis, String consumer, List<StreamEntry> batch) {
        if (claimIdleMs <= 0 || claimCount <= 0) {
            return;
        }
        try {
            List<StreamPendingEntry> pending = jedis.xpending(
                    streamKey, groupName,
                    new StreamEntryID("0-0"),
                    new StreamEntryID("9999999999999-0"),
                    claimCount, null);
            if (pending == null || pending.isEmpty()) {
                return;
            }
            List<StreamEntryID> ids = new ArrayList<>();
            for (StreamPendingEntry p : pending) {
                if (p != null && p.getID() != null && p.getIdleTime() >= claimIdleMs) {
                    ids.add(p.getID());
                }
            }
            if (ids.isEmpty()) {
                return;
            }
            List<StreamEntry> claimed = jedis.xclaim(
                    streamKey, groupName, consumer, claimIdleMs,
                    0L, 0, false,
                    ids.toArray(new StreamEntryID[0]));
            if (claimed == null || claimed.isEmpty()) {
                return;
            }
            batch.addAll(claimed);
            log.info("正常日志:[c2_notify][XCLAIM] 接管 PEL, stream={}, consumer={}, count={}",
                    streamKey, consumer, claimed.size());
        } catch (Exception ex) {
            log.info("异常日志:[c2_notify][XCLAIM] 失败, stream={}, consumer={}, err={}",
                    streamKey, consumer, ex.getMessage());
        }
    }

    private void handleOne(StreamEntry entry) {
        StreamEntryID entryId = entry.getID();
        Map<String, String> fields = entry.getFields();
        C2NotifyMessage msg = toMessage(fields);
        String notifyEntry = String.valueOf(entryId);
        boolean shouldAck = dispatcher.handle("redis", notifyEntry, msg, albumChannel);
        if (shouldAck) {
            try (Jedis jedis = jedisPool.getResource()) {
                long acked = jedis.xack(streamKey, groupName, entryId);
                log.debug("正常日志:[c2_notify][XACK] acked={}, stream={}, entry={}", acked, streamKey, notifyEntry);
            } catch (Exception ex) {
                log.info("异常日志:[c2_notify][XACK] 失败, stream={}, entry={}, err={}",
                        streamKey, notifyEntry, ex.getMessage());
            }
        }
    }

    private static C2NotifyMessage toMessage(Map<String, String> fields) {
        if (fields == null) {
            return null;
        }
        return new C2NotifyMessage(
                fields.get("id"),
                fields.get("kind"),
                fields.get("version"),
                fields.get("path"),
                fields.get("enqueued_at")
        );
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
