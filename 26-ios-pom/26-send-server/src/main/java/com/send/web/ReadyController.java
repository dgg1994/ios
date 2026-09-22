package com.send.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.send.config.V26SendProperties;

/**
 * 对齐 Python {@code GET /api/v2/system/ready}。
 */
@RestController
public class ReadyController {

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private V26SendProperties props;

    @GetMapping({"/", "/api/v1/system/ready", "/api/v2/system/ready"})
    public Map<String, Object> ready() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "26-send-server");
        data.put("mysql", probeMysql());
        data.put("redis", probeRedis());
        data.put("redisWrite", probeRedisWrite());
        data.put("parseQueue", props != null && props.getQueue() != null
                ? props.getQueue().getDeviceParse() : null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("message", "ok");
        body.put("data", data);
        return body;
    }

    private String probeMysql() {
        if (jdbcTemplate == null) {
            return "skip";
        }
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return "up";
        } catch (Exception e) {
            return "down";
        }
    }

    private String probeRedis() {
        if (stringRedisTemplate == null) {
            return "skip";
        }
        try {
            stringRedisTemplate.hasKey("__v26_ready_ping__");
            return "up";
        } catch (Exception e) {
            return "down";
        }
    }

    private String probeRedisWrite() {
        if (stringRedisTemplate == null) {
            return "skip";
        }
        try {
            String key = "queue:__v26_ready_probe";
            stringRedisTemplate.opsForList().rightPush(key, "ping");
            Long len = stringRedisTemplate.opsForList().size(key);
            stringRedisTemplate.delete(key);
            return "up:" + len;
        } catch (Exception e) {
            return "down:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }
}
