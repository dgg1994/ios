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
 * C2 通知分发器：消费通知后直接执行业务（对齐 XADD.md §2.3 + §3.3 的合并流程）。
 *
 * 步骤：
 *   1. RECV：打印 transport / entry / id / path（规范化）
 *   2. 解析失败或缺 id → ACK 丢弃
 *   3. path == /a 且 device_bind_python=false → SKIP，ACK，不处理
 *      （device_bind_python=true 时走 HandleA：解密 + 查/建设备）
 *   4. LOAD：按 id 加载 c2_records（内置短重试）；仍不存在 → ACK 丢弃（避免堵分区）
 *   5. path 不在 PATH_HANDLERS → ACK 跳过（不进业务）
 *   6. HANDLER：调用对应 handle_*；BodyNotReadyException 或失败 → 不 ACK（等待重试）
 *   7. 成功 → ACK notify 消息
 *
 * 返回 true 表示调用方应当 ACK 通知消息；返回 false 表示不应 ACK（等待重试）。
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

    /**
     * 主通道是否处理旧 topic 里遗留的 /t。
     * 清积压时设 true；日常生产应 false（/t 只走相册通道）。
     */
    @Value("${c2.kafka.consume-legacy-t-on-main:false}")
    private boolean consumeLegacyTOnMain;

    public boolean handle(String transport, String notifyEntry, C2NotifyMessage msg) {
        return handle(transport, notifyEntry, msg, false);
    }

    /**
     * @param albumChannel true=仅处理 /t（相册专用通道）；false=主通道（默认跳过 /t）
     */
    public boolean handle(String transport, String notifyEntry, C2NotifyMessage msg, boolean albumChannel) {
        if (msg == null) {
            log.info("异常日志:[c2_notify][1/4-RECV] transport={}, entry={}, 解析失败 → ACK 丢弃",
                    transport, notifyEntry);
            return true;
        }
        String id = msg.getId();
        String path = C2PathUtil.normalize(msg.getPath());
        String kind = msg.getKind();

        if (albumChannel && !C2PathUtil.isAlbumPath(path)) {
            log.info("正常日志:[c2_notify][SKIP-非/t] 相册通道忽略, id={}, path={}, entry={}", id, path, notifyEntry);
            return true;
        }
        if (!albumChannel && C2PathUtil.isAlbumPath(path) && !consumeLegacyTOnMain) {
            log.info("正常日志:[c2_notify][SKIP-/t] 主通道忽略 /t（请走相册通道）, id={}, entry={}", id, notifyEntry);
            return true;
        }
        if (!albumChannel && C2PathUtil.isAlbumPath(path) && consumeLegacyTOnMain) {
            log.info("正常日志:[c2_notify][LEGACY-/t] 主通道处理旧积压 /t, id={}, entry={}", id, notifyEntry);
        }

        logRecv(transport, id, path, kind, msg.getVersion(), notifyEntry);

        // 2. 缺 id → ACK 丢弃
        if (id == null || id.isEmpty()) {
            log.info("异常日志:[c2_notify][2/4] 缺 id，ACK 丢弃, entry={}", notifyEntry);
            return true;
        }

        // 3. /a 且 Python 不绑机 → SKIP，ACK
        if ("/a".equals(path) && !deviceBindPython) {
            log.info("正常日志:[c2_notify][SKIP-/a] device_bind_python=false, id={}, entry={}, ACK 跳过",
                    id, notifyEntry);
            return true;
        }

        // 业务总开关关闭 → 直接 ACK
        if (!workEnabled) {
            log.info("正常日志:[c2_notify] work.enabled=false, 仅 ACK 不处理, id={}, path={}", id, path);
            return true;
        }

        long recordId;
        try {
            recordId = Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            log.info("异常日志:[c2_notify][2/4] 非法 id，ACK 丢弃, id={}, entry={}", id, notifyEntry);
            return true;
        }

        // 4. LOAD c2_records（loader 内已短重试；仍无行则 ACK 丢弃，避免毒消息堵分区）
        C2RecordsEntity record = recordLoader.loadById(recordId);
        if (record == null) {
            log.info("异常日志:[c2_notify][LOAD] c2_records 不存在，ACK 丢弃（不重试堵分区）, id={}, entry={}",
                    recordId, notifyEntry);
            return true;
        }

        // 5. 未知 path → ACK 跳过
        if (!handlerRegistry.supports(path)) {
            log.info("正常日志:[c2_notify] 未知 path，ACK 跳过不进业务, id={}, path={}", recordId, path);
            return true;
        }

        // 6. HANDLER
        C2Handler handler = handlerRegistry.get(path);
        C2HandlerContext ctx = new C2HandlerContext(record, notifyEntry, transport);
        try {
            log.debug("正常日志:[c2_notify][4/6-HANDLER] 调用 {}, id={}, path={}",
                    handler.getClass().getSimpleName(), recordId, path);
            handler.handle(ctx);
        } catch (BodyNotReadyException e) {
            log.info("正常日志:[c2_notify][BODY-WAIT] body 未就绪，不 ACK 等待重试, id={}, path={}, err={}",
                    recordId, path, e.getMessage());
            return false;
        } catch (Throwable t) {
            log.info("异常日志:[c2_notify][HANDLER] 业务失败，不 ACK 等待重试, id={}, path={}, err={}",
                    recordId, path, t.toString());
            return false;
        }

        // 7. 成功 → ACK
        logDone(transport, recordId, path, kind, notifyEntry);
        return true;
    }

    /** /t 流量大，详细日志在 HandleT；其它 path 打简单消费日志便于 grep 排查 */
    private void logRecv(String transport, String id, String path, String kind, String version, String notifyEntry) {
        if ("/t".equals(path)) {
            log.debug("正常日志:[c2_notify][RECV] transport={}, entry={}, id={}, kind={}, version={}, path={}",
                    transport, notifyEntry, id, kind, version, path);
            return;
        }
        log.info("正常日志:[c2_notify][消费-收] transport={}, id={}, path={}, kind={}, version={}",
                transport, id, path, kind, version);
    }

    private void logDone(String transport, long recordId, String path, String kind, String notifyEntry) {
        if ("/t".equals(path)) {
            log.debug("正常日志:[c2_notify][ACK] 处理成功, id={}, path={}, entry={}", recordId, path, notifyEntry);
            return;
        }
        log.info("正常日志:[c2_notify][消费-成] transport={}, id={}, path={}, kind={}",
                transport, recordId, path, kind);
    }
}
