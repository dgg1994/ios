package com.consume.config;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * /t 相册解图专用线程池：与 Kafka/Redis 消费线程隔离。
 * 队列满时拒绝并计数打日志（不 CallerRuns，避免反压消费线程）。
 */
@Configuration
public class AlbumAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AlbumAsyncConfig.class);

    private static final AtomicLong REJECTED = new AtomicLong();

    @Value("${news4.album.pool.core-size:8}")
    private int coreSize;

    @Value("${news4.album.pool.max-size:24}")
    private int maxSize;

    @Value("${news4.album.pool.queue-capacity:128}")
    private int queueCapacity;

    @Bean(name = "albumMaterializeExecutor")
    public Executor albumMaterializeExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(Math.max(2, coreSize));
        exec.setMaxPoolSize(Math.max(coreSize, maxSize));
        // 队列不宜过大：任务携带 fileBlob，过大易 OOM
        exec.setQueueCapacity(Math.max(16, queueCapacity));
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("album-mat-");
        exec.setWaitForTasksToCompleteOnShutdown(false);
        exec.setRejectedExecutionHandler((r, pool) -> {
            long n = REJECTED.incrementAndGet();
            log.info("异常日志:[album] 解图队列已满，丢弃任务 rejected={}, active={}, queue={}",
                    n, pool.getActiveCount(), pool.getQueue().size());
            if (r instanceof AlbumRejectAware) {
                ((AlbumRejectAware) r).onRejected();
            }
        });
        exec.initialize();
        log.info("正常日志:[album] 解图线程池已就绪, core={}, max={}, queue={}",
                coreSize, maxSize, queueCapacity);
        return exec;
    }

    /** 被拒绝时可回调标记 album 失败 */
    public interface AlbumRejectAware extends Runnable {
        void onRejected();
    }
}
