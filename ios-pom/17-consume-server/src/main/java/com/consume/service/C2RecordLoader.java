package com.consume.service;

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
 * Java 侧常「先 XADD 后提交事务」，因此 work/notify 消费时记录可能尚未落库，
 * 这里内置短重试：找不到时按间隔重试若干次。
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
     * 按 id 加载 c2_records；找不到时短重试；最终仍找不到返回 null。
     *
     * report-server 把 headers / body 落盘到 headers_path / body_path 文件，
     * c2_records.headers / body 内联列为空，因此这里直接从落盘文件读取内容
     * （headers JSON 文本 + body 原文），后续业务基于文件内容处理。
     */
    public C2RecordsEntity loadById(long id) {
        for (int attempt = 1; attempt <= retryMax; attempt++) {
            try {
                C2RecordsEntity rec = c2RecordDao.selectById(id);
                if (rec != null) {
                    if (!filesReady(rec) && attempt < retryMax) {
                        log.debug("异常日志:[c2_work] 落盘文件未就绪, id={}, attempt={}, headers={}, body={}",
                                id, attempt, rec.getHeadersPath(), rec.getBodyPath());
                        sleep(retryDelayMs);
                        continue;
                    }
                    fillContentFromFiles(rec);
                    return rec;
                }
            } catch (Exception e) {
                log.info("异常日志:[c2_work] 加载 c2_records 异常, id={}, attempt={}, err={}",
                        id, attempt, e.getMessage());
            }
            if (attempt < retryMax) {
                sleep(retryDelayMs);
            }
        }
        log.info("异常日志:[c2_work] 加载 c2_records 未就绪, id={}, 重试 {} 次后仍不存在", id, retryMax);
        return null;
    }

    /** headers_path / body_path 已填写时，文件必须存在才算就绪 */
    private boolean filesReady(C2RecordsEntity rec) {
        return fileReady(rec.getHeadersPath()) && fileReady(rec.getBodyPath());
    }

    private static boolean fileReady(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return true;
        }
        java.io.File f = new java.io.File(filePath);
        return f.isFile();
    }

    /** 从落盘文件读取 headers / body（report-server 只落盘文件，内联列为空） */
    private void fillContentFromFiles(C2RecordsEntity rec) {
        if (rec == null) {
            return;
        }
        rec.setHeaders(readFileText(rec.getHeadersPath(), rec.getId(), "headers"));
        rec.setBody(readFileText(rec.getBodyPath(), rec.getId(), "body"));
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
