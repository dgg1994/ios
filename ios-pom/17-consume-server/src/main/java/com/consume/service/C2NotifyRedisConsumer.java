package com.consume.service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import redis.clients.jedis.JedisPool;

/**
 * C2 主通道 Redis Stream 消费者：{@code news4:c2:new}，不含 /t。
 * 关闭：{@code c2.channel.main-enabled=false}。
 */
@Component
@ConditionalOnProperty(name = "news4.c2.notify-transport", havingValue = "redis")
public class C2NotifyRedisConsumer {

    @Autowired
    private JedisPool jedisPool;

    @Autowired
    private C2NotifyDispatcher dispatcher;

    @Value("${news4.c2.notify-listen.enabled:true}")
    private boolean enabled;

    @Value("${c2.channel.main-enabled:true}")
    private boolean channelEnabled;

    @Value("${news4.redis-prefix:news4:}")
    private String redisPrefix;

    @Value("${news4.c2.notify-stream:c2:new}")
    private String notifyStreamSuffix;

    @Value("${news4.c2.notify-group:news4-c2-notify}")
    private String groupName;

    @Value("${news4.c2.notify-worker-threads:8}")
    private int workerThreads;

    @Value("${news4.c2.notify-read-block-ms:5000}")
    private int blockMs;

    @Value("${news4.c2.notify-read-count:10}")
    private int readCount;

    @Value("${news4.c2.notify-claim-idle-ms:180000}")
    private long claimIdleMs;

    @Value("${news4.c2.notify-claim-count:20}")
    private int claimCount;

    private C2NotifyRedisStreamRunner runner;

    @PostConstruct
    public void start() {
        if (!enabled || !channelEnabled) {
            return;
        }
        runner = new C2NotifyRedisStreamRunner(
                jedisPool, dispatcher,
                redisPrefix + notifyStreamSuffix,
                groupName,
                workerThreads, blockMs, readCount, claimIdleMs, claimCount,
                false, "main");
        runner.start();
    }

    @PreDestroy
    public void stop() {
        if (runner != null) {
            runner.stop();
        }
    }
}
