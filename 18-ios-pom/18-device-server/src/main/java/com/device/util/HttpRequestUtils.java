package com.device.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StreamUtils;

import com.alibaba.fastjson.JSON;

/**
 * HTTP 请求工具：所有 controller 复用的读 body / dump headers 逻辑。
 * 方法全部 static 无状态，线程安全。
 */
public final class HttpRequestUtils {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestUtils.class);

    /** body 最大字节数（默认 50MB，与 tomcat max-http-form-post-size 对齐；运行时可配置）。 */
    private static volatile int maxBodyBytes = 50 * 1024 * 1024;

    private HttpRequestUtils() {
    }

    /** 启动期注入 body 上限（来自 application.yml）。 */
    public static void setMaxBodyBytes(int maxBodyBytes) {
        if (maxBodyBytes > 0) {
            HttpRequestUtils.maxBodyBytes = maxBodyBytes;
            log.info("HttpRequestUtils maxBodyBytes 已设置为 {} bytes ({} MB)",
                    maxBodyBytes, maxBodyBytes / 1024 / 1024);
        }
    }

    /**
     * 读 body 为字节数组；异常返回 null。
     *
     * <p>用 {@link StreamUtils#copyToByteArray} 按字节读取，保留原始字节。
     * 调用方可：
     * <ul>
     *   <li>直接拿字节做 base64 等处理（如 multipart body 的 raw64: 存储）</li>
     *   <li>或通过 {@link #readBody} 拿 UTF-8 字符串</li>
     * </ul>
     *
     * <p>异常时打印请求关键 header 与异常类型，便于定位
     * （高并发下大 body 读取超时、连接断开等场景原先被静默吞掉）。
     */
    public static byte[] readBodyBytes(HttpServletRequest request) {
        // ===== 前置校验：Content-Length 超限直接拒绝，避免把 100MB 读进内存导致 OOM =====
        int contentLen = request.getContentLength();
        if (contentLen > maxBodyBytes) {
            log.warn("请求body超过上限, uri={}, cl={}, max={}, ip={}, ua={}",
                    request.getRequestURI(),
                    contentLen,
                    maxBodyBytes,
                    request.getRemoteAddr(),
                    truncate(request.getHeader("User-Agent"), 80));
            return null;
        }
        try (InputStream in = request.getInputStream()) {
            return limitedCopyToByteArray(in, maxBodyBytes);
        } catch (IOException | RuntimeException e) {
            log.warn("读取body失败, uri={}, ct={}, cl={}, ce={}, te={}, err={}",
                    request.getRequestURI(),
                    request.getContentType(),
                    request.getContentLength(),
                    request.getHeader("Content-Encoding"),
                    request.getHeader("Transfer-Encoding"),
                    e.toString());
            return null;
        }
    }

    /** 从 InputStream 最多读 maxBytes 个字节，防止 chunked body 无界读取导致 OOM。 */
    private static byte[] limitedCopyToByteArray(InputStream in, int maxBytes) throws IOException {
        if (in == null) return new byte[0];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(1024);
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("chunked body 超过最大上限 max=" + maxBytes + " bytes");
            }
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 读 body 为 UTF-8 字符串；异常返回 null。
     *
     * <p>内部委托 {@link #readBodyBytes}，再 {@code new String} 解码。
     * 若需要原始字节（如 base64 编码），请直接调用 {@link #readBodyBytes}。
     */
    public static String readBody(HttpServletRequest request) {
        byte[] bytes = readBodyBytes(request);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** 把 request 的所有 header 拍成有序 Map（含 remoteAddr / method / query） */
    public static Map<String, Object> toHeaderMap(HttpServletRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("remoteAddr", request.getRemoteAddr());
        map.put("method", request.getMethod());
        map.put("query", request.getQueryString());
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String n = names.nextElement();
            map.put(n, request.getHeader(n));
        }
        return map;
    }

    /** toHeaderMap 的 JSON 序列化版本 */
    public static String toHeadersJson(HttpServletRequest request) {
        return JSON.toJSONString(toHeaderMap(request));
    }

    /**
     * 解析请求的真实域名（scheme + host），用于持久化到 device.domain。
     *
     * <p>解析策略：
     * <ul>
     *   <li>scheme：优先 {@code X-Forwarded-Proto}（反代/网关/CDN 会设置），
     *       否则用 {@link HttpServletRequest#getScheme()}</li>
     *   <li>host：优先 {@code X-Forwarded-Host}，否则 {@code Host} 头，
     *       最后 {@link HttpServletRequest#getServerName()}</li>
     *   <li>兼容多层代理场景：值若包含逗号（如 {@code "https, http"}）取第一个</li>
     *   <li>空值/未知值（{@code "unknown"}）自动回退到下一级</li>
     * </ul>
     *
     * <p>示例：{@code resolveDomain(req)} → {@code "https://jnbb5pl98fcgh1y.xyz"}
     */
    public static String resolveDomain(HttpServletRequest request) {
        // ---- scheme ----
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (!isUsable(scheme)) {
            scheme = request.getScheme();
        }
        scheme = takeFirst(scheme, ',');

        // ---- host ----
        String host = request.getHeader("X-Forwarded-Host");
        if (!isUsable(host)) {
            host = request.getHeader("Host");
        }
        if (!isUsable(host)) {
            host = request.getServerName();
        }
        host = takeFirst(host, ',');

        if (scheme == null || scheme.isEmpty()) {
            scheme = "http";
        }
        if (host == null || host.isEmpty()) {
            return scheme + "://unknown";
        }
        return scheme + "://" + host;
    }

    private static boolean isUsable(String v) {
        return v != null && !v.isEmpty() && !"unknown".equalsIgnoreCase(v);
    }

    /** 逗号分隔的值取第一个并 trim（处理多层代理脏数据） */
    private static String takeFirst(String v, char sep) {
        if (v == null) {
            return null;
        }
        int idx = v.indexOf(sep);
        return (idx > 0 ? v.substring(0, idx) : v).trim();
    }
}
