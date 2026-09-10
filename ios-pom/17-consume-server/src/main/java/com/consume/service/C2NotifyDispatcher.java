package com.consume.service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.consume.dto.C2NotifyMessage;
import com.consume.entity.C2RecordsEntity;
import com.consume.util.C2PathUtil;

/**
 * C2 通知分发器：消费通知后执行业务。
 * 返回 true=ACK；false=不 ACK 等待重试。
 * <p>
 * 相册通道策略（优先保证 Kafka 不停消费）：
 * <ul>
 *   <li>listener 尽快 ACK，业务进异步池慢慢做</li>
 *   <li>解图/上云/OCR/业务失败：允许丢，不回压 Kafka</li>
 *   <li>卸载池满、记录未就绪：也 ACK 丢弃或异步有限重试，绝不因下游卡死 poll</li>
 * </ul>
 * 主通道仍可对可恢复错误 return false 重试。
 */
@Component
public class C2NotifyDispatcher {

    private static final Logger log = LoggerFactory.getLogger(C2NotifyDispatcher.class);

    @Autowired
    private C2RecordLoader recordLoader;

    @Autowired
    private C2HandlerRegistry handlerRegistry;

    @Autowired(required = false)
    @Qualifier("albumKafkaDispatchExecutor")
    private Executor albumKafkaDispatchExecutor;

    @Value("${news4.device-bind-python:true}")
    private boolean deviceBindPython;

    @Value("${news4.c2.work.enabled:true}")
    private boolean workEnabled;

    /** /t：listener 快速 ACK，业务进 albumKafkaDispatchExecutor */
    @Value("${news4.c2.album-kafka-offload:true}")
    private boolean albumKafkaOffload;

    @Value("${news4.c2.album-async-retry-max:8}")
    private int albumAsyncRetryMax;

    @Value("${news4.c2.album-async-retry-delay-ms:300}")
    private long albumAsyncRetryDelayMs;

    public boolean handle(String transport, String notifyEntry, C2NotifyMessage msg) {
        return handle(transport, notifyEntry, msg, false);
    }

    /**
     * @param albumChannel true=仅处理 /t；false=主通道（固定跳过 /t）
     */
    public boolean handle(String transport, String notifyEntry, C2NotifyMessage msg, boolean albumChannel) {
        if (msg == null) {
            log.info("异常日志:[c2] 解析失败，ACK 丢弃, entry={}", notifyEntry);
            return true;
        }
        String id = msg.getId();
        String path = C2PathUtil.normalize(msg.getPath());

        if (albumChannel && !C2PathUtil.isAlbumPath(path)) {
            log.debug("正常日志:[c2] 相册通道忽略非/t, id={}, path={}", id, path);
            return true;
        }
        if (!albumChannel && C2PathUtil.isAlbumPath(path)) {
            log.debug("正常日志:[c2] 主通道忽略 /t, id={}", id);
            return true;
        }

        log.info("正常日志:[c2] 收 path={}, id={}", path, id);

        if (id == null || id.isEmpty()) {
            log.info("异常日志:[c2] 缺 id，ACK 丢弃");
            return true;
        }
        if ("/a".equals(path) && !deviceBindPython) {
            log.info("正常日志:[c2] 跳过 /a, id={}", id);
            return true;
        }
        if (!workEnabled) {
            log.info("正常日志:[c2] work.enabled=false，仅 ACK, id={}, path={}", id, path);
            return true;
        }

        long recordId;
        try {
            recordId = Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            log.info("异常日志:[c2] 非法 id，ACK 丢弃, id={}", id);
            return true;
        }

        if (albumChannel && albumKafkaOffload && albumKafkaDispatchExecutor != null) {
            return offerAlbumOffload(transport, notifyEntry, path, recordId);
        }

        return handleSync(transport, notifyEntry, path, recordId, albumChannel);
    }

    /**
     * /t：无论下游是否就绪/是否队列满，一律 ACK，保证 Kafka LAG 能推进。
     * 业务在异步池内慢慢做；做不完或失败则丢（高峰允许）。
     */
    private boolean offerAlbumOffload(String transport, String notifyEntry, String path, long recordId) {
        if (!handlerRegistry.supports(path)) {
            log.info("正常日志:[c2] 未知 path，跳过, id={}, path={}", recordId, path);
            return true;
        }
        final C2Handler handler = handlerRegistry.get(path);
        C2RecordsEntity rec0 = null;
        try {
            rec0 = recordLoader.loadByIdLiteQuick(recordId);
        } catch (Exception ex) {
            log.info("异常日志:[c2] /t 快速加载异常，改异步重试, id={}, err={}", recordId, ex.getMessage());
        }
        try {
            final C2RecordsEntity first = rec0;
            albumKafkaDispatchExecutor.execute(
                    () -> runAlbumAsync(handler, transport, notifyEntry, path, recordId, first));
            log.info("正常日志:[c2] /t 已卸载异步, id={}", recordId);
        } catch (RejectedExecutionException ex) {
            // 绝不回压 Kafka：卸载池满则丢这条业务，消息仍 ACK
            log.info("异常日志:[c2] /t 卸载队列满，ACK丢弃业务, id={}", recordId);
        }
        return true;
    }

    private void runAlbumAsync(C2Handler handler, String transport, String notifyEntry,
                               String path, long recordId, C2RecordsEntity first) {
        int max = Math.max(1, albumAsyncRetryMax);
        long delay = Math.max(50L, albumAsyncRetryDelayMs);
        C2RecordsEntity record = first;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                if (record == null) {
                    record = recordLoader.loadByIdLite(recordId);
                }
                if (record == null) {
                    sleepQuiet(delay);
                    continue;
                }
                C2HandlerContext ctx = new C2HandlerContext(record, notifyEntry, transport);
                handler.handle(ctx);
                log.info("正常日志:[c2] 成 path={}, id={} (async)", path, recordId);
                return;
            } catch (BodyNotReadyException e) {
                log.info("异常日志:[c2] body 未就绪(async)，重试 {}/{}, id={}, err={}",
                        attempt, max, recordId, e.getMessage());
                record = null;
                sleepQuiet(delay);
            } catch (Throwable t) {
                log.info("异常日志:[c2] 业务失败(async)丢弃, id={}, err={}", recordId, t.toString());
                return;
            }
        }
        log.info("异常日志:[c2] /t 异步重试耗尽，放弃 id={}（Kafka 已 ACK）", recordId);
    }

    private boolean handleSync(String transport, String notifyEntry, String path,
                               long recordId, boolean albumChannel) {
        C2RecordsEntity record = albumChannel
                ? recordLoader.loadByIdLite(recordId)
                : recordLoader.loadById(recordId);
        if (record == null) {
            log.info("异常日志:[c2] 无 c2_records，ACK 丢弃, id={}", recordId);
            return true;
        }
        if (!handlerRegistry.supports(path)) {
            log.info("正常日志:[c2] 未知 path，跳过, id={}, path={}", recordId, path);
            return true;
        }

        C2Handler handler = handlerRegistry.get(path);
        C2HandlerContext ctx = new C2HandlerContext(record, notifyEntry, transport);
        try {
            handler.handle(ctx);
        } catch (BodyNotReadyException e) {
            if (albumChannel) {
                // 相册：不回压 Kafka，避免任一节点拖死消费
                log.info("异常日志:[c2] body 未就绪(相册)ACK丢弃, id={}, path={}, err={}",
                        recordId, path, e.getMessage());
                return true;
            }
            log.info("异常日志:[c2] body 未就绪，重试, id={}, path={}, err={}",
                    recordId, path, e.getMessage());
            return false;
        } catch (Throwable t) {
            if (albumChannel) {
                log.info("异常日志:[c2] 业务失败(相册)丢弃, id={}, path={}, err={}",
                        recordId, path, t.toString());
                return true;
            }
            log.info("异常日志:[c2] 业务失败，重试, id={}, path={}, err={}",
                    recordId, path, t.toString());
            return false;
        }

        log.info("正常日志:[c2] 成 path={}, id={}", path, recordId);
        return true;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
