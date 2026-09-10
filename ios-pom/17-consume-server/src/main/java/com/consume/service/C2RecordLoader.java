package com.consume.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.consume.dao.C2RecordDao;
import com.consume.entity.C2RecordsEntity;

/**
 * c2_records 加载器（对齐 XADD.md load_c2_record_for_handle）。
 *
 * Java 侧常「先发通知后提交事务」，因此消费时记录可能尚未落库，
 * 这里内置短重试：找不到时按间隔重试若干次。
 *
 * /t 相册：{@link #loadByIdLite} 只读库 + headers（小），body 由 HandleT 按 .bin/.txt 再读，
 * 避免在 Kafka 热路径把大 body 读成 String。
 */
@Component
public class C2RecordLoader {

    private static final Logger log = LoggerFactory.getLogger(C2RecordLoader.class);

    @Autowired
    private C2RecordDao c2RecordDao;

    @Value("${news4.c2.work.load-retry-max:5}")
    private int retryMax;

    @Value("${news4.c2.work.load-retry-delay-ms:500}")
    private long retryDelayMs;

    /**
     * 轻量加载：DB + headers，不读 body 文件（/t 专用）。
     */
    public C2RecordsEntity loadByIdLite(long id) {
        return loadInternal(id, true, retryMax, retryDelayMs);
    }

    /**
     * /t Kafka 快速 ACK 路径：最多 2 次短重试，避免在 listener 线程上 sleep 数秒。
     */
    public C2RecordsEntity loadByIdLiteQuick(long id) {
        return loadInternal(id, true, Math.min(2, Math.max(1, retryMax)), Math.min(100L, retryDelayMs));
    }

    /**
     * 完整加载：DB + headers + body 文本（非 /t 路径）。
     */
    public C2RecordsEntity loadById(long id) {
        return loadInternal(id, false, retryMax, retryDelayMs);
    }

    private C2RecordsEntity loadInternal(long id, boolean lite) {
        return loadInternal(id, lite, retryMax, retryDelayMs);
    }

    private C2RecordsEntity loadInternal(long id, boolean lite, int maxAttempts, long delayMs) {
        int attempts = Math.max(1, maxAttempts);
        long delay = Math.max(20L, delayMs);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                C2RecordsEntity rec = c2RecordDao.selectById(id);
                if (rec != null) {
                    if (!filesReady(rec, lite) && attempt < attempts) {
                        log.debug("异常日志:[c2_work] 落盘文件未就绪, id={}, attempt={}, lite={}, headers={}, body={}",
                                id, attempt, lite, rec.getHeadersPath(), rec.getBodyPath());
                        sleep(delay);
                        continue;
                    }
                    if (lite) {
                        fillHeadersOnly(rec);
                    } else {
                        fillContentFromFiles(rec);
                    }
                    return rec;
                }
            } catch (Exception e) {
                log.info("异常日志:[c2_work] 加载 c2_records 异常, id={}, attempt={}, err={}",
                        id, attempt, e.getMessage());
            }
            if (attempt < attempts) {
                sleep(delay);
            }
        }
        log.info("异常日志:[c2_work] 加载 c2_records 未就绪, id={}, 重试 {} 次后仍不存在", id, attempts);
        return null;
    }

    /** lite：headers+body 路径文件都要存在；完整加载同样 */
    private boolean filesReady(C2RecordsEntity rec, boolean lite) {
        if (!fileReady(rec.getHeadersPath())) {
            return false;
        }
        // body 文件必须存在（lite 也检查，避免 handler 读不到）
        return fileReady(rec.getBodyPath());
    }

    private static boolean fileReady(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return true;
        }
        java.io.File f = new java.io.File(filePath);
        return f.isFile();
    }

    private void fillHeadersOnly(C2RecordsEntity rec) {
        if (rec == null) {
            return;
        }
        rec.setHeaders(readFileText(rec.getHeadersPath(), rec.getId(), "headers"));
        // body 不读入内存字符串
        rec.setBody(null);
    }

    /** 从落盘文件读取 headers / body（report-server 只落盘文件，内联列为空） */
    private void fillContentFromFiles(C2RecordsEntity rec) {
        if (rec == null) {
            return;
        }
        rec.setHeaders(readFileText(rec.getHeadersPath(), rec.getId(), "headers"));
        String bodyPath = rec.getBodyPath();
        if (isBinaryBodyPath(bodyPath)) {
            // 二进制 body 不转 String，由业务按路径读字节
            rec.setBody(null);
            return;
        }
        rec.setBody(readFileText(bodyPath, rec.getId(), "body"));
    }

    /** 是否为 multipart 二进制落盘（新链路 .bin；旧 raw64 仍为 .txt） */
    public static boolean isBinaryBodyPath(String bodyPath) {
        if (bodyPath == null || bodyPath.isEmpty()) {
            return false;
        }
        String p = bodyPath.replace('\\', '/').toLowerCase();
        return p.endsWith(".bin");
    }

    /**
     * 读取 /t multipart 字节：优先 .bin；兼容旧 raw64 文本文件。
     */
    public static byte[] readMultipartBytes(C2RecordsEntity rec) {
        if (rec == null) {
            return null;
        }
        String bodyPath = rec.getBodyPath();
        String inline = rec.getBody();
        try {
            if (isBinaryBodyPath(bodyPath)) {
                return Files.readAllBytes(Paths.get(bodyPath));
            }
            String text = inline;
            if ((text == null || text.isEmpty()) && bodyPath != null && !bodyPath.isEmpty()) {
                text = new String(Files.readAllBytes(Paths.get(bodyPath)), StandardCharsets.UTF_8).trim();
            }
            if (text == null || text.isEmpty()) {
                return null;
            }
            if (text.startsWith("raw64:")) {
                return com.consume.util.MultipartParser.decodeRaw64(text);
            }
            return text.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            LoggerFactory.getLogger(C2RecordLoader.class)
                    .info("异常日志:[c2_work] 读取 multipart 字节失败, id={}, path={}, err={}",
                            rec.getId(), bodyPath, e.getMessage());
            return null;
        }
    }

    /** 读取落盘文件内容（UTF-8，trim）；文件不存在或读取失败返回空串 */
    private String readFileText(String filePath, Number recordId, String tag) {
        if (filePath == null || filePath.isEmpty()) {
            return "";
        }
        try {
            java.io.File f = new java.io.File(filePath);
            if (!f.exists() || !f.isFile()) {
                log.info("异常日志:[c2_work] {}_path 文件不存在, id={}, path={}", tag, recordId, filePath);
                return "";
            }
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (log.isDebugEnabled()) {
                log.debug("正常日志:[c2_work] {} 从文件读取, id={}, path={}, len={}", tag, recordId, filePath, text.length());
            }
            return text;
        } catch (Exception e) {
            log.info("异常日志:[c2_work] 读取 {}_path 文件失败, id={}, path={}, err={}",
                    tag, recordId, filePath, e.getMessage());
            return "";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

