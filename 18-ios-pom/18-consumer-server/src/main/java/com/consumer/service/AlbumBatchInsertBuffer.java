package com.consumer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.consumer.dao.AlbumDao;
import com.consumer.entity.AlbumEntity;

/**
 * 上云/落盘成功后的 album 批量入库缓冲：凑满 batch-size 或按间隔刷库（对齐 17）。
 */
@Component
public class AlbumBatchInsertBuffer {

    private static final Logger log = LoggerFactory.getLogger(AlbumBatchInsertBuffer.class);

    private final ConcurrentLinkedQueue<AlbumEntity> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger(0);

    @Resource
    private AlbumDao albumDao;

    @Value("${photo.batch-insert-enabled:true}")
    private boolean enabled;

    @Value("${photo.batch-insert-size:50}")
    private int batchSize;

    /**
     * 入队；关闭批量时同步单条插入。
     */
    public boolean offer(AlbumEntity row) {
        if (row == null) {
            return false;
        }
        if (!enabled) {
            return insertOne(row);
        }
        queue.offer(row);
        int n = queued.incrementAndGet();
        if (n >= Math.max(1, batchSize)) {
            flush();
        }
        return true;
    }

    @Scheduled(fixedDelayString = "${photo.batch-insert-interval-ms:200}")
    public void scheduledFlush() {
        if (enabled && queued.get() > 0) {
            flush();
        }
    }

    @PreDestroy
    public void destroy() {
        flush();
    }

    public synchronized void flush() {
        int limit = Math.max(1, batchSize);
        while (true) {
            List<AlbumEntity> batch = new ArrayList<>(limit);
            while (batch.size() < limit) {
                AlbumEntity e = queue.poll();
                if (e == null) {
                    break;
                }
                queued.decrementAndGet();
                batch.add(e);
            }
            if (batch.isEmpty()) {
                return;
            }
            try {
                albumDao.insertBatchIgnore(batch);
                log.info("【photo】批量入库 size={}", batch.size());
            } catch (Exception ex) {
                log.warn("【photo】批量入库失败，降级单条 size={} err={}", batch.size(), ex.getMessage());
                for (AlbumEntity e : batch) {
                    insertOne(e);
                }
            }
        }
    }

    private boolean insertOne(AlbumEntity row) {
        try {
            albumDao.insert(row);
            return true;
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains("Duplicate") || msg.contains("duplicate")) {
                return true;
            }
            log.warn("【photo】单条入库失败 recordId={} sha={} err={}",
                    row.getC2RecordId(), row.getFileSha256(), msg);
            return false;
        }
    }
}
