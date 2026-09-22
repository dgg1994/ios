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
    @Resource(name = "archiveScanExecutorV1")
    private ExecutorService archiveScanExecutorV1;
    @Resource(name = "balanceHttpExecutor")
    private ExecutorService balanceHttpExecutor;
    @Resource(name = "balanceHttpExecutorV1")
    private ExecutorService balanceHttpExecutorV1;

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("archiveScan", one(archiveScanExecutor));
        m.put("archiveScanV1", one(archiveScanExecutorV1));
        m.put("balanceHttp", one(balanceHttpExecutor));
        m.put("balanceHttpV1", one(balanceHttpExecutorV1));
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
