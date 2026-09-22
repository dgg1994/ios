package com.consumer.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

/**
 * 扫包池 / 查链 HTTP 池均从 yml 读取（对齐 18 news4.balance-pool）。
 */
@Slf4j
@Configuration
public class ConsumerPoolConfig {

    @Bean(name = "archiveScanExecutor", destroyMethod = "shutdown")
    public ExecutorService archiveScanExecutor(V26ConsumerProperties props) {
        return build("archive-scan-", props.getConsumer().getScanPool(), true);
    }

    @Bean(name = "archiveScanExecutorV1", destroyMethod = "shutdown")
    public ExecutorService archiveScanExecutorV1(V26ConsumerProperties props) {
        return build("archive-scan-v1-", props.getConsumer().getScanPoolV1(), true);
    }

    @Bean(name = "balanceHttpExecutor", destroyMethod = "shutdown")
    public ExecutorService balanceHttpExecutor(V26ConsumerProperties props) {
        return build("bal-http-", props.getConsumer().getBalanceHttpPool(), false);
    }

    @Bean(name = "balanceHttpExecutorV1", destroyMethod = "shutdown")
    public ExecutorService balanceHttpExecutorV1(V26ConsumerProperties props) {
        return build("bal-http-v1-", props.getConsumer().getBalanceHttpPoolV1(), false);
    }

    static ExecutorService build(String prefix, V26ConsumerProperties.Pool pool, boolean reject) {
        int max = Math.max(2, pool.getMaxSize());
        int core = Math.max(1, Math.min(pool.getCoreSize(), max));
        int q = Math.max(16, pool.getQueueCapacity());
        int keep = Math.max(10, pool.getKeepAliveSeconds());
        AtomicLong rejected = new AtomicLong();
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                core,
                max,
                keep,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(q),
                named(prefix),
                (r, e) -> {
                    long n = rejected.incrementAndGet();
                    if (n % 50 == 1) {
                        log.warn("[pool] {} full rejected={} active={} queue={}",
                                prefix, n, e.getActiveCount(), e.getQueue().size());
                    }
                    if (reject) {
                        throw new java.util.concurrent.RejectedExecutionException(prefix + "queue full");
                    }
                    r.run();
                });
        exec.allowCoreThreadTimeOut(true);
        log.info("[pool] {} core={} max={} queue={} reject={}", prefix, core, max, q, reject);
        return exec;
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
