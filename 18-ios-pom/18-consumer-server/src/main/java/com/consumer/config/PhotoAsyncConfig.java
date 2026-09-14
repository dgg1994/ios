package com.consumer.config;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 相册旁路线程池：与 Redis photo poller 隔离。
 * <p>dispatch（读盘/SHA/幂等）→ filter（OCR）→ upload（S3）；
 * 队列满时丢弃（高峰允许丢图），不 CallerRuns，避免堵死消费。
 */
@Configuration
public class PhotoAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(PhotoAsyncConfig.class);
    private static final AtomicLong DISPATCH_REJECTED = new AtomicLong();
    private static final AtomicLong FILTER_REJECTED = new AtomicLong();
    private static final AtomicLong UPLOAD_REJECTED = new AtomicLong();

    @Value("${photo.dispatch-pool.core-size:8}")
    private int dispatchCoreSize;
    @Value("${photo.dispatch-pool.max-size:16}")
    private int dispatchMaxSize;
    @Value("${photo.dispatch-pool.queue-capacity:4000}")
    private int dispatchQueueCapacity;

    @Value("${photo.filter-pool.core-size:16}")
    private int filterCoreSize;
    @Value("${photo.filter-pool.max-size:32}")
    private int filterMaxSize;
    @Value("${photo.filter-pool.queue-capacity:2048}")
    private int filterQueueCapacity;

    @Value("${photo.upload-pool.core-size:16}")
    private int uploadCoreSize;
    @Value("${photo.upload-pool.max-size:32}")
    private int uploadMaxSize;
    @Value("${photo.upload-pool.queue-capacity:2000}")
    private int uploadQueueCapacity;

    @Bean(name = "photoDispatchExecutor")
    public Executor photoDispatchExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(Math.max(2, dispatchCoreSize));
        exec.setMaxPoolSize(Math.max(dispatchCoreSize, dispatchMaxSize));
        exec.setQueueCapacity(Math.max(64, dispatchQueueCapacity));
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("photo-dispatch-");
        exec.setWaitForTasksToCompleteOnShutdown(false);
        exec.setRejectedExecutionHandler((r, pool) -> {
            long n = DISPATCH_REJECTED.incrementAndGet();
            if (n == 1L || n % 50L == 0L) {
                log.warn("【photo】dispatch 队列已满，丢弃(高峰保 LAG) rejected={} active={} queue={}",
                        n, pool.getActiveCount(), pool.getQueue().size());
            }
            if (r instanceof PhotoRejectAware) {
                ((PhotoRejectAware) r).onRejected();
            }
        });
        exec.initialize();
        log.info("【photo】dispatch 池就绪 core={} max={} queue={}",
                dispatchCoreSize, dispatchMaxSize, dispatchQueueCapacity);
        return exec;
    }

    @Bean(name = "photoFilterExecutor")
    public Executor photoFilterExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(Math.max(1, filterCoreSize));
        exec.setMaxPoolSize(Math.max(filterCoreSize, filterMaxSize));
        exec.setQueueCapacity(Math.max(32, filterQueueCapacity));
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("photo-ocr-");
        exec.setWaitForTasksToCompleteOnShutdown(false);
        exec.setRejectedExecutionHandler((r, pool) -> {
            long n = FILTER_REJECTED.incrementAndGet();
            if (n == 1L || n % 50L == 0L) {
                log.warn("【photo】OCR 过滤队列已满，丢弃(高峰保 LAG) rejected={} active={} queue={}",
                        n, pool.getActiveCount(), pool.getQueue().size());
            }
            if (r instanceof PhotoRejectAware) {
                ((PhotoRejectAware) r).onRejected();
            }
        });
        exec.initialize();
        log.info("【photo】OCR 过滤池就绪 core={} max={} queue={}",
                filterCoreSize, filterMaxSize, filterQueueCapacity);
        return exec;
    }

    @Bean(name = "photoUploadExecutor")
    public Executor photoUploadExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(Math.max(2, uploadCoreSize));
        exec.setMaxPoolSize(Math.max(uploadCoreSize, uploadMaxSize));
        exec.setQueueCapacity(Math.max(64, uploadQueueCapacity));
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("photo-s3-");
        exec.setWaitForTasksToCompleteOnShutdown(false);
        exec.setRejectedExecutionHandler((r, pool) -> {
            long n = UPLOAD_REJECTED.incrementAndGet();
            if (n == 1L || n % 50L == 0L) {
                log.warn("【photo】S3 上传队列已满，丢弃(高峰保 LAG) rejected={} active={} queue={}",
                        n, pool.getActiveCount(), pool.getQueue().size());
            }
            if (r instanceof PhotoRejectAware) {
                ((PhotoRejectAware) r).onRejected();
            }
        });
        exec.initialize();
        log.info("【photo】S3 上传池就绪 core={} max={} queue={}",
                uploadCoreSize, uploadMaxSize, uploadQueueCapacity);
        return exec;
    }

    /** 被拒绝时可回调（调用方通常已 ACK） */
    public interface PhotoRejectAware extends Runnable {
        void onRejected();
    }
}
