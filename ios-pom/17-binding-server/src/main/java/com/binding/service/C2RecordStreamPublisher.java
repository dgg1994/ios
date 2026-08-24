package com.binding.service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;

import java.util.HashMap;
import java.util.Map;

/**
 * C2 业务 Redis Stream 发布器。
 */
@Component
public class C2RecordStreamPublisher {

    private static final Logger log = LoggerFactory.getLogger(C2RecordStreamPublisher.class);

    /** 与 news4 前缀对齐：NEWS4_REDIS_PREFIX=news4: */
    private static final long MAXLEN = 50_000L;

    @Value("${redis.host:127.0.0.1}")
    private String host;

    @Value("${redis.port:6379}")
    private int port;

    @Value("${redis.password:}")
    private String password;

    @Value("${redis.db:0}")
    private int db;

    @Value("${redis.pool.maxTotal:32}")
    private int maxTotal;

    @Value("${redis.timeout:3000}")
    private int timeoutMs;

    /** 与 17-consume news4.redis-prefix + news4.c2.notify-stream 对齐 */
    @Value("${news4.redis-prefix:news4:}")
    private String redisPrefix;

    @Value("${news4.c2.notify-stream:c2:new}")
    private String notifyStreamSuffix;

    private JedisPool pool;

    private String streamKey() {
        return redisPrefix + notifyStreamSuffix;
    }

    @PostConstruct
    public void init() {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(maxTotal);
        this.pool = new JedisPool(cfg, host, port, timeoutMs,
                (password == null || password.isEmpty()) ? null : password, db);
        log.info("正常日志:c2 record stream 发布器已就绪, host={}, port={}, db={}, stream={}, maxTotal={}",
                host, port, db, streamKey(), maxTotal);
    }

    @PreDestroy
    public void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
            log.info("正常日志:c2 record stream 发布器已关闭");
        }
    }

    /**
     * 入库成功后调用。
     *
     * @param recordId  c2_records.id（DB auto-increment）
     * @param kind      如 c2-a
     * @param version   17 / 18 / ""
     * @param path      如 /a
     */
    @Async("c2RecordStreamExecutor")
    public void notifyNewRecord(long recordId, String kind, String version, String path) {
        Map<String, String> fields = new HashMap<>();
        fields.put("id", String.valueOf(recordId));
        fields.put("kind", kind == null ? "" : kind);
        fields.put("version", version == null ? "17" : version);
        fields.put("path", path == null ? "" : path);
        fields.put("enqueued_at", String.valueOf(System.currentTimeMillis()));
        try (Jedis jedis = pool.getResource()) {
            // Stream key 必须带前缀：news4:c2:new
        	StreamEntryID entryId = jedis.xadd(
                    streamKey(),
                    fields,
                    XAddParams.xAddParams().id("*").maxLen(MAXLEN).approximateTrimming()
            );
            log.info("正常日志:c2 record stream xadd 成功, entryId={}, id={}, kind={}", entryId, recordId, kind);
        } catch (Exception e) {
            log.info("异常日志:c2 record stream xadd 失败, id={}, kind={}, err={}",
                    recordId, kind, e.getMessage());
        }
    }
}
