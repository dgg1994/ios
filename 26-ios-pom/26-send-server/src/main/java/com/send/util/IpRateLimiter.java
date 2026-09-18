package com.send.util;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 进程内按 IP 滑动窗口限流（1 分钟）。高并发下无 Redis 往返，适合 send 热路径。
 */
@Component
public class IpRateLimiter {

    private static final long WINDOW_MS = 60_000L;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public boolean tryAcquire(String ip, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return true;
        }
        String key = StringUtils.defaultIfBlank(ip, "unknown");
        long now = System.currentTimeMillis();
        Window w = windows.compute(key, (k, old) -> {
            if (old == null || now - old.startMs >= WINDOW_MS) {
                return new Window(now, 1);
            }
            old.count.incrementAndGet();
            return old;
        });
        // 偶尔清理，避免 map 无限涨
        if ((now & 0x3FF) == 0) {
            cleanup(now);
        }
        return w.count.get() <= limitPerMinute;
    }

    private void cleanup(long now) {
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Window> e = it.next();
            if (now - e.getValue().startMs >= WINDOW_MS * 2) {
                it.remove();
            }
        }
    }

    private static final class Window {
        final long startMs;
        final AtomicInteger count;

        Window(long startMs, int initial) {
            this.startMs = startMs;
            this.count = new AtomicInteger(initial);
        }
    }
}
