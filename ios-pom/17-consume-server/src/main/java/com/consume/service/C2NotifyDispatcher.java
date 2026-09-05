package com.consume.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.consume.dto.C2NotifyMessage;
import com.consume.entity.C2RecordsEntity;
import com.consume.util.C2PathUtil;

/**
 * C2 通知分发器：消费通知后执行业务。
 * 返回 true=ACK；false=不 ACK 等待重试。
 */
@Component
public class C2NotifyDispatcher {

    private static final Logger log = LoggerFactory.getLogger(C2NotifyDispatcher.class);

    @Autowired
    private C2RecordLoader recordLoader;

    @Autowired
    private C2HandlerRegistry handlerRegistry;

    @Value("${news4.device-bind-python:true}")
    private boolean deviceBindPython;

    @Value("${news4.c2.work.enabled:true}")
    private boolean workEnabled;

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
            log.info("异常日志:[c2] body 未就绪，重试, id={}, path={}, err={}",
                    recordId, path, e.getMessage());
            return false;
        } catch (Throwable t) {
            log.info("异常日志:[c2] 业务失败，重试, id={}, path={}, err={}",
                    recordId, path, t.toString());
            return false;
        }

        log.info("正常日志:[c2] 成 path={}, id={}", path, recordId);
        return true;
    }
}
