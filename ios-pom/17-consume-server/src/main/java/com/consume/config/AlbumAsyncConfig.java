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
 * 解图队列满时丢弃图片（高峰允许丢图），不抛异常、不 CallerRuns，避免堵死消费。
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

    @Value("${news4.album.filter-pool.core-size:4}")
    private int filterCoreSize;

    @Value("${news4.album.filter-pool.max-size:8}")
    private int filterMaxSize;

    @Value("${news4.album.filter-pool.queue-capacity:1024}")
    private int filterQueueCapacity;

    /** Kafka /t：listener 只投递，业务在此池执行，避免 poll 超时 CommitFailed */
    @Value("${news4.album.kafka-dispatch-pool.core-size:32}")
    private int dispatchCoreSize;

    @Value("${news4.album.kafka-dispatch-pool.max-size:64}")
    private int dispatchMaxSize;

    @Value("${news4.album.kafka-dispatch-pool.queue-capacity:8000}")
    private int dispatchQueueCapacity;

    private static final AtomicLong UPLOAD_REJECTED = new AtomicLong();
    private static final AtomicLong FILTER_REJECTED = new AtomicLong();
    private static final AtomicLong DISPATCH_REJECTED = new AtomicLong();

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
            // 高峰允许丢图：不抛异常、不回压重试，保证 Kafka/业务线程不被堵死，队列内任务慢慢消化
            long n = REJECTED.incrementAndGet();
            if (n == 1L || n % 100L == 0L) {
                log.info("异常日志:[album] 解图队列已满，丢弃图片(允许高峰丢图) rejected={}, active={}, queue={}",
                        n, pool.getActiveCount(), pool.getQueue().size());
            }
            if (r instanceof AlbumRejectAware) {
                ((AlbumRejectAware) r).onRejected();
            }
        });
        exec.initialize();
        log.debug("正常日志:[album] 解图线程池已就绪, core={}, max={}, queue={}",
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
        log.debug("正常日志:[album] S3 上传线程池已就绪, core={}, max={}, queue={}",
                uploadCoreSize, uploadMaxSize, uploadQueueCapacity);
        return exec;
    }

    /**
     * 助记词过滤池：调 ocr-server HTTP，与解图/S3 隔离。
     */
    @Bean(name = "albumFilterExecutor")
    public Executor albumFilterExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(Math.max(1, filterCoreSize));
        exec.setMaxPoolSize(Math.max(filterCoreSize, filterMaxSize));
        exec.setQueueCapacity(Math.max(32, filterQueueCapacity));
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("album-filter-");
        exec.setWaitForTasksToCompleteOnShutdown(false);
        exec.setRejectedExecutionHandler((r, pool) -> {
            long n = FILTER_REJECTED.incrementAndGet();
            log.info("异常日志:[album] 助记词过滤队列已满，丢弃 rejected={}, active={}, queue={}",
                    n, pool.getActiveCount(), pool.getQueue().size());
            if (r instanceof AlbumRejectAware) {
                ((AlbumRejectAware) r).onRejected();
            }
        });
        exec.initialize();
        log.debug("正常日志:[album] 助记词过滤线程池已就绪, core={}, max={}, queue={}",
                filterCoreSize, filterMaxSize, filterQueueCapacity);
        return exec;
    }

    /**
     * /t Kafka 热路径卸载：读盘/解析/绑设备在此执行，listener 一律 ACK。
     * 队列满时抛 RejectedExecutionException，由调用方 ACK 并丢弃业务（不回压 Kafka）。
     */
    @Bean(name = "albumKafkaDispatchExecutor")
    public Executor albumKafkaDispatchExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(Math.max(4, dispatchCoreSize));
        exec.setMaxPoolSize(Math.max(dispatchCoreSize, dispatchMaxSize));
        exec.setQueueCapacity(Math.max(256, dispatchQueueCapacity));
        exec.setKeepAliveSeconds(60);
        exec.setThreadNamePrefix("album-kafka-");
        exec.setWaitForTasksToCompleteOnShutdown(false);
        exec.setRejectedExecutionHandler((r, pool) -> {
            long n = DISPATCH_REJECTED.incrementAndGet();
            log.info("异常日志:[album] Kafka 卸载队列已满，回压重试 rejected={}, active={}, queue={}",
                    n, pool.getActiveCount(), pool.getQueue().size());
            throw new java.util.concurrent.RejectedExecutionException("album-kafka-dispatch-full");
        });
        exec.initialize();
        log.info("正常日志:[album] Kafka 卸载线程池已就绪, core={}, max={}, queue={}",
                dispatchCoreSize, dispatchMaxSize, dispatchQueueCapacity);
        return exec;
    }

    /** 被拒绝时可回调标记 album 失败 / 保留待补偿 */
    public interface AlbumRejectAware extends Runnable {
        void onRejected();
    }
}
