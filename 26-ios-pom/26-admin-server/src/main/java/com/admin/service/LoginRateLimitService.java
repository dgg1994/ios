package com.admin.service;

import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.admin.config.V26AdminProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginRateLimitService {

    private final StringRedisTemplate redis;
    private final V26AdminProperties props;

    public boolean blocked(String ip) {
        try {
            String raw = redis.opsForValue().get(key(ip));
            int n = raw == null ? 0 : Integer.parseInt(raw);
            return n >= Math.max(1, props.getLoginFailLimit());
        } catch (Exception e) {
            return false;
        }
    }

    public void fail(String ip) {
        try {
            String k = key(ip);
            Long n = redis.opsForValue().increment(k);
            if (n != null && n == 1L) {
                redis.expire(k, Math.max(60, props.getLoginFailWindowSec()), TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("login fail incr: {}", e.toString());
        }
    }

    public void clear(String ip) {
        try {
            redis.delete(key(ip));
        } catch (Exception ignored) {
        }
    }

    private String key(String ip) {
        return "admin_login_fail:" + (ip == null || ip.isBlank() ? "-" : ip.trim());
    }
}
