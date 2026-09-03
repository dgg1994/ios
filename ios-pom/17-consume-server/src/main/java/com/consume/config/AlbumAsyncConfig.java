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

    @Value("${news4.album.upload-pool.core-size:8}")
    private int uploadCoreSize;

    @Value("${news4.album.upload-pool.max-size:16}")
    private int uploadMaxSize;

    @Value("${news4.album.upload-pool.queue-capacity:2000}")
    private int uploadQueueCapacity;

    private static final AtomicLong UPLOAD_REJECTED = new AtomicLong();

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

    /**
     * S3 上传专用线程池：与解图池隔离，任务只带路径不带 fileBlob，不影响 album-mat 速度。
     * 队列满时不 CallerRuns，保持 status=3 由补偿任务扫库。
     */
    @Bean(name = "albumUploadExecutor")
    public Executor albumUploadExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(Math.max(2, uploadCoreSize));
        exec.setMaxPoolSize(Math.max(uploadCoreSize, uploadMaxSize));
        exec.setQueueCapacity(Math.max(64, uploadQueueCapacity));
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("album-s3-");
        exec.setWaitForTasksToCompleteOnShutdown(false);
        exec.setRejectedExecutionHandler((r, pool) -> {
            long n = UPLOAD_REJECTED.incrementAndGet();
            log.info("异常日志:[album] S3 上传队列已满，保留 status=3 待补偿 rejected={}, active={}, queue={}",
                    n, pool.getActiveCount(), pool.getQueue().size());
            if (r instanceof AlbumRejectAware) {
                ((AlbumRejectAware) r).onRejected();
            }
        });
        exec.initialize();
        log.info("正常日志:[album] S3 上传线程池已就绪, core={}, max={}, queue={}",
                uploadCoreSize, uploadMaxSize, uploadQueueCapacity);
        return exec;
    }

    /** 被拒绝时可回调标记 album 失败 / 保留待补偿 */
    public interface AlbumRejectAware extends Runnable {
        void onRejected();
    }
}
