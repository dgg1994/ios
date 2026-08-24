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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.consume.dto.C2NotifyMessage;
import com.consume.util.InstanceId;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntry;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.StreamPendingEntry;
import redis.clients.jedis.exceptions.JedisDataException;

/**
 * C2 通知 Redis Stream 消费者（c2_notify_worker）。
 *
 * 消费 news4:c2:new（XREADGROUP，消费组 news4-c2-notify），
 * 解析后交给 {@link C2NotifyDispatcher} 执行业务。
 *
 * 多实例：同一 group、不同 consumer name（hostname-pid-thread）。
 * 宕机后 PEL 中的消息由其他实例 XCLAIM（idle 超过阈值）接管。
 *
 * 消费组创建时 id="$"：只消费建组之后的新消息，不重放历史。
 * XREADGROUP 用 ">"：只读从未投递给本组的新消息。
 */
@Component
@ConditionalOnProperty(name = "news4.c2.notify-transport", havingValue = "redis")
public class C2NotifyRedisConsumer {

    private static final Logger log = LoggerFactory.getLogger(C2NotifyRedisConsumer.class);

    @Autowired
    private JedisPool jedisPool;

    @Autowired
    private C2NotifyDispatcher dispatcher;

    @Value("${news4.c2.notify-transport:redis}")
    private String transport;

    @Value("${news4.c2.notify-listen.enabled:true}")
    private boolean enabled;

    @Value("${news4.redis-prefix:news4:}")
    private String redisPrefix;

    @Value("${news4.c2.notify-stream:c2:new}")
    private String notifyStreamSuffix;

    @Value("${news4.c2.notify-group:news4-c2-notify}")
    private String groupName;

    @Value("${news4.c2.notify-worker-threads:8}")
    private int workerThreads;

    @Value("${news4.c2.notify-read-block-ms:5000}")
    private int blockMs;

    @Value("${news4.c2.notify-read-count:10}")
    private int readCount;

    /** PEL 认领：消息 idle 超过该毫秒数才 XCLAIM；0 表示关闭认领 */
    @Value("${news4.c2.notify-claim-idle-ms:180000}")
    private long claimIdleMs;

    @Value("${news4.c2.notify-claim-count:20}")
    private int claimCount;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;
    private final AtomicInteger workerSeq = new AtomicInteger(0);
    private final AtomicLong lastClaimAt = new AtomicLong(0);

    private String streamKey() {
        return redisPrefix + notifyStreamSuffix;
    }

    @PostConstruct
    public void start() {
        // 通道开关：仅当 notify-transport=redis 时启动 Redis 监听
        if (!enabled) {
            log.info("正常日志:[c2_notify] Redis 监听未启用 (news4.c2.notify-listen.enabled=false)");
            return;
        }
        if (!"redis".equalsIgnoreCase(transport)) {
            log.info("正常日志:[c2_notify] 通道为 {}，不启动 Redis 监听（仅 redis 时启动）", transport);
            return;
        }
        ensureGroup();
        int n = Math.max(1, workerThreads);
        running.set(true);
        executor = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "c2-notify-redis-" + workerSeq.incrementAndGet());
            t.setDaemon(false);
            return t;
        });
        for (int i = 0; i < n; i++) {
            executor.submit(this::consumeLoop);
        }
        log.info("正常日志:[c2_notify] Redis 监听已启动, stream={}, group={}, instance={}, threads={}, block={}ms, count={}, claimIdle={}ms",
                streamKey(), groupName, InstanceId.get(), n, blockMs, readCount, claimIdleMs);
    }

    private void ensureGroup() {
        String key = streamKey();
        try (Jedis jedis = jedisPool.getResource()) {
            try {
                // id="$" → 只消费建组之后的新消息；makeStream=true 允许 stream 不存在时自动建
                jedis.xgroupCreate(key, groupName, StreamEntryID.LAST_ENTRY, true);
                log.info("正常日志:[c2_notify] 创建消费组成功, stream={}, group={}", key, groupName);
            } catch (JedisDataException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("BUSYGROUP")) {
                    log.info("正常日志:[c2_notify] 消费组已存在, stream={}, group={}", key, groupName);
                } else {
                    log.info("异常日志:[c2_notify] 创建消费组异常, stream={}, group={}, err={}",
                            key, groupName, msg);
                }
            }
        } catch (Exception e) {
            log.info("异常日志:[c2_notify] 连接 Redis 创建消费组失败, err={}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
	private void consumeLoop() {
        String consumer = InstanceId.get() + "-" + Thread.currentThread().getId();
        Map.Entry<String, StreamEntryID> stream =
                new AbstractMap.SimpleEntry<>(streamKey(), StreamEntryID.UNRECEIVED_ENTRY);
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
                // 有新消息时优先处理；空闲或每隔一段时间才扫 PEL，避免 8 线程每轮都 XPENDING
                if (batch.isEmpty() || shouldClaimNow()) {
                    claimIdlePending(jedis, consumer, batch);
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.info("异常日志:[c2_notify] XREADGROUP 异常, err={}", e.getMessage());
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

    /**
     * 认领其他实例/线程 idle 过久的 PEL 消息，避免宕机后消息卡死。
     * 认领结果加入 batch，随后走与新消息相同的业务 + XACK。
     */
    private void claimIdlePending(Jedis jedis, String consumer, List<StreamEntry> batch) {
        if (claimIdleMs <= 0 || claimCount <= 0) {
            return;
        }
        try {
            List<StreamPendingEntry> pending = jedis.xpending(
                    streamKey(), groupName,
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
                    streamKey(), groupName, consumer, claimIdleMs,
                    0L, 0, false,
                    ids.toArray(new StreamEntryID[0]));
            if (claimed == null || claimed.isEmpty()) {
                return;
            }
            batch.addAll(claimed);
            log.info("正常日志:[c2_notify][XCLAIM] 接管 PEL, consumer={}, count={}", consumer, claimed.size());
        } catch (Exception ex) {
            log.info("异常日志:[c2_notify][XCLAIM] 失败, consumer={}, err={}", consumer, ex.getMessage());
        }
    }

    private void handleOne(StreamEntry entry) {
        StreamEntryID entryId = entry.getID();
        Map<String, String> fields = entry.getFields();
        C2NotifyMessage msg = toMessage(fields);
        String notifyEntry = String.valueOf(entryId);
        boolean shouldAck = dispatcher.handle("redis", notifyEntry, msg);
        if (shouldAck) {
            try (Jedis jedis = jedisPool.getResource()) {
                long acked = jedis.xack(streamKey(), groupName, entryId);
                log.debug("正常日志:[c2_notify][XACK] acked={}, entry={}", acked, notifyEntry);
            } catch (Exception ex) {
                log.info("异常日志:[c2_notify][XACK] 失败, entry={}, err={}", notifyEntry, ex.getMessage());
            }
        }
        // shouldAck=false → 不 ACK，等待 PEL 超时后 XCLAIM 重新投递
    }

    private C2NotifyMessage toMessage(Map<String, String> fields) {
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

    @PreDestroy
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
        log.info("正常日志:[c2_notify] Redis 监听已停止");
    }
}
