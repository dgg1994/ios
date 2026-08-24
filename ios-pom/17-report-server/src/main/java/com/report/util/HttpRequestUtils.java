package com.report.util;

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

    private HttpRequestUtils() {
    }

    /**
     * 读 body 为字节数组；异常返回 null。
     */
    public static byte[] readBodyBytes(HttpServletRequest request) {
        try (InputStream in = request.getInputStream()) {
            return StreamUtils.copyToByteArray(in);
        } catch (IOException | RuntimeException e) {
            log.info("错误日志:读取body失败, uri={}, ct={}, cl={}, ce={}, te={}, err={}",
                    request.getRequestURI(),
                    request.getContentType(),
                    request.getContentLength(),
                    request.getHeader("Content-Encoding"),
                    request.getHeader("Transfer-Encoding"),
                    e.toString());
            return null;
        }
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
