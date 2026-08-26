package com.binding.service.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.binding.entity.C2RecordsEntity;
import com.binding.response.ProbeResponse;
import com.binding.service.BindingService;
import com.binding.service.C2RecordService;
import com.binding.util.ClientInfoUtils;
import com.binding.util.DateTimeUtils;
import com.binding.util.EventDecryptor;
import com.binding.util.HttpRequestUtils;
import com.binding.util.IpUtil;

/**
 * /a 接口实现；/api/ip-sync 已合并到本服务；其它 C2 路径由 forward-server 转发到 17-report-server
 */
@RestController
@CrossOrigin
public class BindingServiceImpl implements BindingService {

    private static final Logger log = LoggerFactory.getLogger(BindingServiceImpl.class);

    /** POST /a 加密 C2 固定 ACK 原文（对齐 news2） */
    private static final String POST_ACK_BODY = "r2Uo9TwLS55BdCM6KK9RVg==";

    /** C2 系列版本号 */
    private static final String C2_VERSION = "17";

    /** c2_records.kind 取值 */
    private static final String KIND_C2_A = "c2-a";

    /** 落盘配置（由 application.yml 的 binding.dump.* 注入） */
    @Value("${binding.dump.dir:logs/a}")
    private String dumpDir;

    @Value("${binding.dump.headers-prefix:headers}")
    private String headersPrefix;

    @Value("${binding.dump.bodies-prefix:bodies}")
    private String bodiesPrefix;

    @Autowired
    private IpUtil ipUtil;

    @Autowired
    private C2RecordService c2RecordService;

    /** headers / bodies 落盘按"小时"分锁：
     *  key = "{yyyyMMdd/HH}:headers" 或 "{yyyyMMdd/HH}:bodies"
     *  不同小时目录互不阻塞；同一小时内 headers 与 bodies 也互不阻塞。
     *  使用 ConcurrentHashMap.computeIfAbsent 保证全局只一个锁对象。 */
    private static final ConcurrentHashMap<String, Object> DUMP_LOCKS = new ConcurrentHashMap<>();

    private static Object lockFor(String key) {
        return DUMP_LOCKS.computeIfAbsent(key, k -> new Object());
    }

    // ---------- GET /a ----------
    @Override
    public ResponseEntity<ProbeResponse> probe(HttpServletRequest request) {
        ProbeResponse body = new ProbeResponse(
                Boolean.TRUE,
                DateTimeUtils.formatUtcNow(),
                0L
        );
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }


    // ---------- 业务辅助 ----------
    private static Map<String, Object> decryptSafely(String xTs, String rawBody) {
        if (xTs == null || xTs.isEmpty() || rawBody == null || rawBody.isEmpty()) {
            return null;
        }
        Map<String, Object> r = EventDecryptor.decryptEventBody(xTs, rawBody);
        if (Boolean.TRUE.equals(r.get("success"))) {
            if (r.get("plaintext") instanceof JSONObject) {
                return r;
            }
            log.info("正常日志:/a 解密成功但无 JSON 明文, raw={}", r.get("raw"));
        } else {
            log.info("错误日志:/a 解密失败, error={}", r.get("error"));
        }
        return r;
    }

    private static String extractBodyText(Map<String, Object> decryptResult) {
        if (decryptResult == null) {
            return "";
        }
        Object pt = decryptResult.get("plaintext");
        if (pt instanceof JSONObject) {
            return JSON.toJSONString(pt);
        }
        Object raw = decryptResult.get("raw");
        return raw == null ? "" : raw.toString();
    }

    private static ResponseEntity<byte[]> fixedAck() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("x-ts", String.valueOf(System.currentTimeMillis()));
        byte[] ack = POST_ACK_BODY.getBytes(StandardCharsets.UTF_8);
        return new ResponseEntity<>(ack, h, HttpStatus.OK);
    }

    /** 落盘结果（含绝对路径），用于 c2_records 入库 */
    private static class DumpPath {
        final String headersPath;
        final String bodyPath;
        DumpPath(String h, String b) {
            this.headersPath = h;
            this.bodyPath = b;
        }
    }

    /**
     * 落盘调试：headers / bodies 各自独立锁 + 按小时分桶。
     */
    private DumpPath dumpToFiles(String headersJson, String bodyText, String xTs,
                                 Map<String, Object> decryptResult) {
        try {
            long now = System.currentTimeMillis();
            Instant instant = Instant.ofEpochMilli(now);

            // 按日期+小时切分目录
            String hourPath = DateTimeUtils.formatDumpHourPath(instant);  // yyyyMMdd/HH
            Path dir = Paths.get(dumpDir, hourPath);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            // 高并发：同毫秒多请求加随机后缀；锁按文件路径粒度，避免整小时串行
            String suffix = Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong());
            Path headersPath = dir.resolve(headersPrefix + "-" + now + "-" + suffix + ".txt");
            appendRaw(headersPath, headersJson + System.lineSeparator(), headersPath.toString());

            Path bodyPath = dir.resolve(bodiesPrefix + "-" + now + "-" + suffix + ".txt");
            appendRaw(bodyPath, bodyText, bodyPath.toString());
            return new DumpPath(headersPath.toAbsolutePath().toString(), bodyPath.toAbsolutePath().toString());
        } catch (Exception e) {
            log.info("异常日志:/a 文件落盘失败", e);
            return new DumpPath("", "");
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
                log.info("异常日志:/a appendRaw 失败, lockKey={}, file={}, err={}", lockKey, file, e.getMessage());
            }
        }
    }

	@Override
	public ResponseEntity<byte[]> bindReportPost(HttpServletRequest request) {
        String xTs = request.getHeader("x-ts");
        String rawBody = HttpRequestUtils.readBody(request);
        String bodyForRecord;
        String bodyForFile;
        Map<String, Object> decryptResult;
        try {
            decryptResult = decryptSafely(xTs, rawBody);
        } catch (Exception e) {
            log.info("异常日志:/a bindReport 异常", e);
            decryptResult = null;
        }
        bodyForRecord = extractBodyText(decryptResult);//解密数据
        bodyForFile = bodyForRecord;

        // 2) 共享 headers JSON（DB + 文件都复用）
        String headersJson = HttpRequestUtils.toHeadersJson(request);

        // 3) 落盘（headers + bodies 各自独立锁 + 小时目录 + 毫秒文件名）
        DumpPath path = dumpToFiles(headersJson, bodyForFile, xTs, decryptResult);
        //异步入 c2_records（不阻塞 HTTP 响应线程）
        String domain = ClientInfoUtils.getClientDomainTwo(request);//请求域名
        asyncRecordTwo(request, headersJson, bodyForRecord, xTs, path,domain);
        
        // 5) 响应：固定 Base64 ACK + x-ts
        return fixedAck();
	}
	
	
	private void asyncRecordTwo(HttpServletRequest request, String headersJson, String bodyText, String xTs,
			DumpPath path, String domain) {
		try {
			C2RecordsEntity rec = new C2RecordsEntity();
			rec.setKind(KIND_C2_A);
			rec.setMethod("POST");
			rec.setClientIp(ipUtil.getClientIp(request));
			rec.setPath(request.getRequestURI());
			rec.setXTs(xTs);
			rec.setCapturedAt(System.currentTimeMillis() / 1000.0);
			rec.setDeviceId(null);
			rec.setVersion(C2_VERSION);
			rec.setHeadersPath(path.headersPath);
			rec.setBodyPath(path.bodyPath);

	        String ip = ipUtil.getClientIp(request);//真实ip
	        log.info("正常日志:准备入库 c2_records, kind={}, path={}", rec.getKind(), rec.getPath());
			c2RecordService.recordTwo(rec, bodyText,domain,ip); // 走 @Async 线程池
		} catch (Exception e) {
			log.info("异常日志:/a c2_records 构建失败，未入库, uri={}, err={}", request.getRequestURI(), e.toString());
		}
	}
	
}
