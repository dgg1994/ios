package com.consumer.worker;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

/**
 * 收集各 Redis worker，供 ready / 积压监控读取。
 */
@Component
public class ConsumerWorkerRegistry {

    private final List<RedisListWorker> workers = new CopyOnWriteArrayList<>();

    public void register(RedisListWorker worker) {
        if (worker != null) {
            workers.add(worker);
        }
    }

    public List<RedisListWorker> all() {
        return workers;
    }
}
