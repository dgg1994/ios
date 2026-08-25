package com.device.util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.device.config.QueueProperties;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

@Component
public class RedisPush {

    private static final Logger log = LoggerFactory.getLogger(RedisPush.class);

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private QueueProperties queue;

    @PostConstruct
    public void logStreams() {
        log.info("[RedisPush] stream ready prefix={} main={}", queue.getPrefix(), queue.getStreamMain());
    }

    @Async("redisPushStreamPush")
    public void notifyNewRecord(Object obj) {
        if (obj == null) {
            log.warn("notifyNewRecord: obj is null");
            return;
        }

        Map<String, Object> fields = toMap(obj);
        if (fields == null || fields.isEmpty()) {
            log.warn("notifyNewRecord: fields is empty");
            return;
        }

        String streamKey = queue.getStreamMain();
        try {
            RecordId recordId = redisTemplate.opsForStream()
                    .add(MapRecord.create(streamKey, fields));
            try {
                redisTemplate.opsForStream().trim(streamKey, 200_000);
            } catch (Exception trimEx) {
                log.debug("Redis Stream trim skip key={} err={}", streamKey, trimEx.toString());
            }
            if (log.isDebugEnabled()) {
                log.debug("Redis Stream 发送成功, key={}, id={}, fields={}",
                    streamKey, recordId, fields);
            }

        } catch (Exception e) {
            log.error("Redis Stream 发送失败, key={}, error={}",
                streamKey, e.getMessage(), e);
        }
    }

    /**
     * 递归将对象转为 Map<String, String>
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap(Object obj) {
        Map<String, Object> result = new HashMap<>();

        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getValue() != null) {
                    result.put(entry.getKey(), entry.getValue().toString());
                }
            }
        } else {
            try {
                JSONObject jsonObj = (JSONObject) JSON.toJSON(obj);
                for (String key : jsonObj.keySet()) {
                    Object value = jsonObj.get(key);
                    if (value != null) {
                        result.put(key, value.toString());
                    }
                }
            } catch (Exception e) {
                log.error("对象转 Map 失败: {}", e.getMessage());
            }
        }
        return result;
    }
}
