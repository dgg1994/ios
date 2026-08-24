package com.binding.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.util.StreamUtils;

import com.alibaba.fastjson.JSON;

/**
 * HTTP 请求工具：抽取所有 controller 复用的读 body / dump headers 逻辑。
 * 方法全部 static 无状态，线程安全。
 */
public final class HttpRequestUtils {

    private HttpRequestUtils() {
    }

    /** 读 body 为 UTF-8 字符串；异常返回 null（与 ctwo-server 行为一致） */
    public static String readBody(HttpServletRequest request) {
        try (InputStream in = request.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
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
