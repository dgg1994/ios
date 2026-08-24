package com.consumer.util;

import org.springframework.stereotype.Component;

import com.consumer.config.QueueProperties;

import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.XAddParams;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis Stream 发布（Jedis 实现，稳定跨版本）。
 * Consumer 仅需同步发消息，无需 @Async。
 * <p>Stream 名称见 {@link QueueProperties}（queue.*）。
 */
@Component
@Slf4j
public class RedisPush {

    public static final long STREAM_MAXLEN = 50000L;

    @Resource
    private JedisPool jedisPool;

    @Resource
    private QueueProperties queue;

    @SuppressWarnings("deprecation")
	@PostConstruct
    public void ping() {
        try (Jedis j = jedisPool.getResource()) {
            j.ping();
        } catch (Throwable t) {
            log.warn("[RedisPush] ping fail (may be expected if Redis not up yet): {}", t.toString());
        }
        log.info("[RedisPush] streams ready prefix={} main={} parseCi={} photo={} news4={}",
                queue.getPrefix(), queue.getStreamMain(), queue.getStreamParseCi(),
                queue.getStreamPhoto(), queue.getStreamNews4());
    }

    public String streamMain() {
        return queue.getStreamMain();
    }

    public String streamParseCi() {
        return queue.getStreamParseCi();
    }

    public String streamPhoto() {
        return queue.getStreamPhoto();
    }

    public String streamNews4() {
        return queue.getStreamNews4();
    }

    public String groupMain() {
        return queue.getGroupMain();
    }

    public String groupParseCi() {
        return queue.getGroupParseCi();
    }

    public String groupNews4() {
        return queue.getGroupNews4();
    }

    public String groupPhoto() {
        return queue.getGroupPhoto();
    }

    public String dlqMain() {
        return queue.getDlqMain();
    }

    public String dlqParseCi() {
        return queue.getDlqParseCi();
    }

    public String dlqNews4() {
        return queue.getDlqNews4();
    }

    public String dlqPhoto() {
        return queue.getDlqPhoto();
    }

    /** 同步推送 Stream 消息 */
    @SuppressWarnings("unused")
	public void notifySync(Object obj, String streamKey) {
        if (obj == null) {
            log.warn("RedisPush: obj null, skip");
            return;
        }
        Map<String, String> fields = toStrMap(obj);
        if (fields.isEmpty()) {
            log.warn("RedisPush: fields empty obj={}", obj);
            return;
        }
        try (Jedis j = jedisPool.getResource()) {
            Object rid = j.xadd(streamKey, fields,
                    XAddParams.xAddParams().maxLen(STREAM_MAXLEN));
        } catch (Exception e) {
            log.error("Redis Stream send FAIL key={} err={}", streamKey, e.toString());
        }
    }

    /** 批量同步推送 Stream 消息（pipeline 单连接多 XADD，减少网络往返） */
    @SuppressWarnings("deprecation")
	public void notifyBatch(List<Map<String, String>> jobs, String streamKey) {
        if (jobs == null || jobs.isEmpty()) return;
        try (Jedis j = jedisPool.getResource()) {
            redis.clients.jedis.Pipeline pipe = j.pipelined();
            for (Map<String, String> fields : jobs) {
                if (fields == null || fields.isEmpty()) continue;
                // 防御：Jedis XADD 不允许 null value，过滤掉 null 值避免整批失败
                Map<String, String> clean = new HashMap<>(fields.size());
                for (Map.Entry<String, String> e : fields.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        clean.put(e.getKey(), e.getValue());
                    }
                }
                if (clean.isEmpty()) continue;
                pipe.xadd(streamKey, clean,
                        XAddParams.xAddParams().maxLen(STREAM_MAXLEN));
            }
            pipe.sync();
        } catch (Exception e) {
            log.error("Redis Stream batch send FAIL key={} err={}", streamKey, e.toString());
        }
    }

    /** 对象 → Map<String, String>（支持 Map / JSONObject / POJO） */
    @SuppressWarnings("unchecked")
    public Map<String, String> toStrMap(Object obj) {
        Map<String, String> r = new HashMap<>();
        if (obj == null) return r;
        if (obj instanceof Map) {
            Map<String, ?> m = (Map<String, ?>) obj;
            for (Map.Entry<String, ?> e : m.entrySet()) {
                if (e.getValue() != null) r.put(e.getKey(), e.getValue().toString());
            }
        } else {
            com.alibaba.fastjson.JSONObject j = (com.alibaba.fastjson.JSONObject) com.alibaba.fastjson.JSON.toJSON(obj);
            if (j != null) {
                for (Map.Entry<String, Object> e : j.entrySet()) {
                    if (e.getValue() != null) r.put(e.getKey(), e.getValue().toString());
                }
            }
        }
        return r;
    }
}
