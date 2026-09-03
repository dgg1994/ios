package com.consume.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.consume.dao.AlbumDao;
import com.consume.entity.AlbumEntity;

/**
 * 监控 album.status=3（解密完成、待上云）。
 * 纯云存储模式不写本地盘，补偿无法从磁盘重传；仅打日志，依赖消息重投再次解图上传。
 */
@Component
@ConditionalOnProperty(name = "news4.album.cloud.enabled", havingValue = "true")
public class AlbumCloudCompensateJob {

    private static final Logger log = LoggerFactory.getLogger(AlbumCloudCompensateJob.class);

    @Autowired
    private AlbumDao albumDao;

    @Value("${news4.album.cloud.compensate-batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${news4.album.cloud.compensate-interval-ms:30000}")
    public void compensatePendingUploads() {
        int limit = Math.max(1, batchSize);
        List<AlbumEntity> pending;
        try {
            pending = albumDao.listPendingCloudUpload(limit);
        } catch (Exception e) {
            log.info("异常日志:[album] 扫待上云失败, err={}", e.getMessage());
            return;
        }
        if (pending == null || pending.isEmpty()) {
            return;
        }
        log.info("正常日志:[album] 待上云积压(status=3) count={}（无本地盘，需消息重投补传）",
                pending.size());
    }
}
