package com.consume.service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import redis.clients.jedis.JedisPool;

/**
 * C2 相册 Redis Stream 消费者：{@code news4:c2:new:t}，仅处理 /t。
 * 关闭：{@code c2.channel.album-enabled=false}。
 */
@Component
@ConditionalOnProperty(name = "news4.c2.notify-transport", havingValue = "redis")
public class C2NotifyRedisAlbumConsumer {

    @Autowired
    private JedisPool jedisPool;

    @Autowired
    private C2NotifyDispatcher dispatcher;

    @Value("${news4.c2.notify-listen.enabled:true}")
    private boolean enabled;

    @Value("${c2.channel.album-enabled:true}")
    private boolean channelEnabled;

    @Value("${news4.redis-prefix:news4:}")
    private String redisPrefix;

    @Value("${news4.c2.notify-stream-t:c2:new:t}")
    private String notifyAlbumStreamSuffix;

    @Value("${news4.c2.notify-group-t:news4-c2-notify-t}")
    private String groupName;

    @Value("${news4.c2.notify-worker-threads-t:16}")
    private int workerThreads;

    @Value("${news4.c2.notify-read-block-ms-t:2000}")
    private int blockMs;

    @Value("${news4.c2.notify-read-count-t:16}")
    private int readCount;

    @Value("${news4.c2.notify-claim-idle-ms-t:180000}")
    private long claimIdleMs;

    @Value("${news4.c2.notify-claim-count-t:20}")
    private int claimCount;

    private C2NotifyRedisStreamRunner runner;

    @PostConstruct
    public void start() {
        if (!enabled || !channelEnabled) {
            return;
        }
        runner = new C2NotifyRedisStreamRunner(
                jedisPool, dispatcher,
                redisPrefix + notifyAlbumStreamSuffix,
                groupName,
                workerThreads, blockMs, readCount, claimIdleMs, claimCount,
                true, "album");
        runner.start();
    }

    @PreDestroy
    public void stop() {
        if (runner != null) {
            runner.stop();
        }
    }
}
