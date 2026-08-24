package com.report.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.report.entity.C2RecordsEntity;
import com.report.service.C2RecordService;

/**
 * report-server 请求落盘 + 入库工具
 * - 目录按日期+小时切分：{dir}/{yyyyMMdd}/{HH}/
 * - 文件名按请求时间戳（毫秒）：{headers-prefix}-{millis}.txt
 * - 入库：c2_records（headers/body 内联已注释，按需启用；当前只存路径）
 */
@Component
public class RequestDumper {

    private static final Logger log = LoggerFactory.getLogger(RequestDumper.class);

    /** C2 系列版本号，report-server 当前服务 17 系列；如启用 18 在此改 / 加 yml 字段 */
    private static final String C2_VERSION = "17";

    @Value("${report.dump.dir:logs/report}")
    private String dumpDir;

    @Value("${report.dump.headers-prefix:headers}")
    private String headersPrefix;

    @Value("${report.dump.bodies-prefix:bodies}")
    private String bodiesPrefix;

    @Autowired
    private C2RecordService c2RecordService;

    @Autowired
    private IpUtil ipUtil;

    /** headers / bodies 落盘按"小时"分锁：
     */
    private static final ConcurrentHashMap<String, Object> DUMP_LOCKS = new ConcurrentHashMap<>();

    private static Object lockFor(String key) {
        return DUMP_LOCKS.computeIfAbsent(key, k -> new Object());
    }

    /**
     * 落盘 header (JSON) + body (raw text) + 异步入 c2_records
     */
    public String dump(String endpoint, HttpServletRequest request) {
        try {
            // ---- 1. 先读 body / headers（抢时间窗，避免被磁盘 IO 占住 HTTP 线程）
            byte[] bodyBytes = HttpRequestUtils.readBodyBytes(request);
            // 共享 headers JSON（DB + 文件都复用）
            String headersJson = HttpRequestUtils.toHeadersJson(request);

            // multipart/form-data 的 body 含 boundary + 二进制 part 数据，
            String contentType = request.getContentType();
            boolean isMultipart = contentType != null
                    && contentType.toLowerCase().contains("multipart/form-data");

            String body;
            String bodyText;
            if (isMultipart && bodyBytes != null && bodyBytes.length > 0) {
                body = "raw64:" + Base64.getEncoder().encodeToString(bodyBytes);
                bodyText = body;
            } else {
                body = bodyBytes == null ? null : new String(bodyBytes, StandardCharsets.UTF_8);
                bodyText = body == null ? "" : body;
            }

            // ---- 2. 再做磁盘 IO（目录创建 + 文件写入）----
            long now = System.currentTimeMillis();
            Instant instant = Instant.ofEpochMilli(now);
            double capturedAt = now / 1000.0;

            // 按日期+小时切分目录
            String hourPath = DateTimeUtils.formatDumpHourPath(instant);
            Path dir = Paths.get(dumpDir, hourPath);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            // 高并发下同毫秒多请求会写到同一文件，加随机后缀保证文件名唯一
            String suffix = Long.toHexString(ThreadLocalRandom.current().nextLong());

            // ---- headers：每行一条请求的原始 header JSON（无包装）----
            Path headersPath = dir.resolve(headersPrefix + "-" + now + "-" + suffix + ".txt");
            appendRaw(headersPath, headersJson + System.lineSeparator(), hourPath + ":headers");

            // ---- body：multipart 为 raw64:base64，其他为 UTF-8 原文 ----
            Path bodyPath = dir.resolve(bodiesPrefix + "-" + now + "-" + suffix + ".txt");

            appendRaw(bodyPath, bodyText, hourPath + ":bodies");
            // ---- 异步入 c2_records ----
            C2RecordsEntity rec = new C2RecordsEntity();
            rec.setKind(toKind(endpoint));
            rec.setMethod(request.getMethod());
            rec.setClientIp(ipUtil.getClientIp(request));
            rec.setPath(request.getRequestURI());
            rec.setXTs(request.getHeader("x-ts"));
            rec.setCapturedAt(capturedAt);
            rec.setDeviceId(null);
//            rec.setBody(bodyText);
            rec.setVersion(C2_VERSION);
            rec.setHeadersPath(headersPath.toAbsolutePath().toString());
            rec.setBodyPath(bodyPath.toAbsolutePath().toString());
            log.info("正常日志:准备入库 c2_records, kind={}, path={}", rec.getKind(), rec.getPath());
            c2RecordService.record(rec);
            return body;
        } catch (Exception e) {
        	log.warn("错误日志:请求归档失败，c2_records 未入库, endpoint={}, uri={}, err={}", endpoint, request.getRequestURI(), e.toString());
            return null;
        }
    }

    /** 按 key 选锁对象，synchronized 块内原子写入。 */
    private static void appendRaw(Path file, String content, String lockKey) {
        Object lock = lockFor(lockKey);
        synchronized (lock) {
            try {
                Files.write(file, content.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
            	log.info("异常日志:文件写入异常，文件={}, 锁标识={}, 错误={}", file, lockKey, e.getMessage());
            }
        }
    }

    /** endpoint → kind 规则：c2- + 下划线转中划线（小写） */
    private static String toKind(String endpoint) {
        return "c2-" + endpoint.replace('_', '-');
    }
    
 
}
