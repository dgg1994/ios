package com.report.service;

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

import com.report.util.C2PathUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * C2 业务 Redis Stream 发布器。
 * 热路径默认不做 MAXLEN 裁剪（减轻 Redis 单线程压力）；需要时用 c2.redis-stream.xadd-maxlen &gt; 0 开启，
 * 或运维侧定时 XTRIM。
 */
@Component
public class C2RecordStreamPublisher {

    private static final Logger log = LoggerFactory.getLogger(C2RecordStreamPublisher.class);

    @Value("${redis.host:127.0.0.1}")
    private String host;

    @Value("${redis.port:6379}")
    private int port;

    @Value("${redis.password:}")
    private String password;

    @Value("${redis.db:0}")
    private int db;

    @Value("${redis.pool.maxTotal:64}")
    private int maxTotal;

    @Value("${redis.timeout:3000}")
    private int timeoutMs;

    /** 与 17-consume news4.redis-prefix + news4.c2.notify-stream 对齐 */
    @Value("${news4.redis-prefix:news4:}")
    private String redisPrefix;

    @Value("${news4.c2.notify-stream:c2:new}")
    private String notifyStreamSuffix;

    @Value("${news4.c2.notify-stream-t:c2:new:t}")
    private String notifyAlbumStreamSuffix;

    /**
     * XADD 时近似裁剪上限；0=不裁剪（推荐高 QPS）。
     * 例：50000 表示约保留 5 万条。
     */
    @Value("${c2.redis-stream.xadd-maxlen:0}")
    private long xaddMaxlen;

    private JedisPool pool;

    private String streamKey(String path) {
        String suffix = C2PathUtil.isAlbumPath(path) ? notifyAlbumStreamSuffix : notifyStreamSuffix;
        return redisPrefix + suffix;
    }

    @PostConstruct
    public void init() {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(Math.max(8, maxTotal));
        cfg.setMaxIdle(Math.min(16, cfg.getMaxTotal()));
        this.pool = new JedisPool(cfg, host, port, timeoutMs,
                (password == null || password.isEmpty()) ? null : password, db);
        log.info("正常日志:c2 record stream 发布器已就绪, host={}, port={}, db={}, stream={}, streamT={}, maxTotal={}, xaddMaxlen={}",
                host, port, db, redisPrefix + notifyStreamSuffix, redisPrefix + notifyAlbumStreamSuffix,
                maxTotal, xaddMaxlen);
    }

    @PreDestroy
    public void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
            log.debug("正常日志:c2 record stream 发布器已关闭");
        }
    }

    /**
     * 入库成功后调用（异步，不阻塞 HTTP）。
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
            String key = streamKey(path);
            XAddParams params = XAddParams.xAddParams().id("*");
            if (xaddMaxlen > 0) {
                params.maxLen(xaddMaxlen).approximateTrimming();
            }
            StreamEntryID entryId = jedis.xadd(key, fields, params);
            log.debug("正常日志:c2 record stream xadd 成功, stream={}, entryId={}, id={}, kind={}, path={}",
                    key, entryId, recordId, kind, path);
        } catch (Exception e) {
            log.info("异常日志:c2 record stream xadd 失败, id={}, kind={}, err={}",
                    recordId, kind, e.getMessage());
        }
    }
}
