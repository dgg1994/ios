package com.consumer.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import javax.annotation.Resource;

import org.springframework.stereotype.Component;

@Component
public class DispatchPoolSnapshot {

    @Resource(name = "archiveScanExecutor")
    private ExecutorService archiveScanExecutor;
    @Resource(name = "balanceHttpExecutor")
    private ExecutorService balanceHttpExecutor;

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("archiveScan", one(archiveScanExecutor));
        m.put("balanceHttp", one(balanceHttpExecutor));
        return m;
    }

    private static Map<String, Object> one(ExecutorService exec) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!(exec instanceof ThreadPoolExecutor)) {
            m.put("type", exec == null ? "null" : exec.getClass().getSimpleName());
            return m;
        }
        ThreadPoolExecutor p = (ThreadPoolExecutor) exec;
        m.put("active", p.getActiveCount());
        m.put("pool", p.getPoolSize());
        m.put("max", p.getMaximumPoolSize());
        m.put("queue", p.getQueue().size());
        m.put("queueCap", p.getQueue().size() + p.getQueue().remainingCapacity());
        m.put("completed", p.getCompletedTaskCount());
        return m;
    }
}
