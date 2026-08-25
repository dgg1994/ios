package com.ctwo.util;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ctwo.config.QueueProperties;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class RedisPush {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private QueueProperties queue;

    @PostConstruct
    public void logStreams() {
        log.info("[RedisPush] streams ready prefix={} main={} parseCi={} photo={}",
                queue.getPrefix(), queue.getStreamMain(), queue.getStreamParseCi(), queue.getStreamPhoto());
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

    /** 默认推到主队列 */
    @Async("redisPushStreamPush")
    public void notifyNewRecord(Object obj) {
        notifyNewRecord(obj, streamMain());
    }

    /** 指定 streamKey 推送 */
    @Async("redisPushStreamPush")
    public void notifyNewRecord(Object obj, String streamKey) {
        if (obj == null) {
            log.info("RedisPush: obj is null, 跳过处理");
            return;
        }
        Map<String, Object> fields = toMap(obj);
        if (fields == null || fields.isEmpty()) {
            log.info("RedisPush: fields is empty, 跳过处理, obj={}", obj);
            return;
        }
        try {
            RecordId recordId = redisTemplate.opsForStream()
                    .add(MapRecord.create(streamKey, fields));
            // 近似裁剪到 20 万：仅积压过深时砍最旧，正常吞吐不丢在途消息
            try {
                redisTemplate.opsForStream().trim(streamKey, 200_000);
            } catch (Exception trimEx) {
                log.debug("Redis Stream trim skip key={} err={}", streamKey, trimEx.toString());
            }

            log.info("Redis Stream 发送成功, key={}, recordId={}, fields={}",
                    streamKey, recordId, fields);

        } catch (Exception e) {
            log.info("Redis Stream 发送失败, key={}, fields={}, error={}",
                    streamKey, fields, e.getMessage(), e);
        }
    }

    /**
     * 将对象转为 Map<String, Object>
     * 支持 Map 类型和普通对象
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap(Object obj) {
        if (obj == null) {
            return new HashMap<>();
        }

        Map<String, Object> result = new HashMap<>();

        try {
            if (obj instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) obj;
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    if (entry.getValue() != null) {
                        result.put(entry.getKey(), entry.getValue().toString());
                    }
                }
            } else {
                JSONObject jsonObj = (JSONObject) JSON.toJSON(obj);
                if (jsonObj != null) {
                    for (String key : jsonObj.keySet()) {
                        Object value = jsonObj.get(key);
                        if (value != null) {
                            result.put(key, value.toString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("RedisPush 对象转 Map 失败, obj={}, error={}",
                    obj, e.getMessage(), e);
        }

        return result;
    }
}
