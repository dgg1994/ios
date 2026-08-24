package com.consume.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * HTTP 请求头解析工具（从 c2_records.headers JSON 文本中提取关键字段）。
 */
public final class HeadersUtil {

    private HeadersUtil() {
    }

    /** headers 字段为 JSON 文本时解析为 JSONObject；非法返回 null */
    public static JSONObject parse(String headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        try {
            return JSON.parseObject(headers);
        } catch (Exception e) {
            return null;
        }
    }

    /** 取 header 值（大小写不敏感：先原 key，再小写，再大写） */
    public static String header(String headers, String name) {
        JSONObject obj = parse(headers);
        return header(obj, name);
    }

    public static String header(JSONObject obj, String name) {
        if (obj == null || name == null) {
            return "";
        }
        if (obj.containsKey(name)) {
            return nullToEmpty(obj.getString(name));
        }
        String lower = name.toLowerCase();
        if (obj.containsKey(lower)) {
            return nullToEmpty(obj.getString(lower));
        }
        String upper = name.toUpperCase();
        if (obj.containsKey(upper)) {
            return nullToEmpty(obj.getString(upper));
        }
        // 遍历 key 大小写不敏感匹配
        for (String k : obj.keySet()) {
            if (k != null && k.equalsIgnoreCase(name)) {
                return nullToEmpty(obj.getString(k));
            }
        }
        return "";
    }

    /**
     * 提取请求域名（去掉端口），对齐 report-server 的 ClientInfoUtils.getClientDomain：
     * 优先 X-Forwarded-Host（多层代理可能有逗号脏数据，取第一段），其次 Host。
     */
    public static String domainFromHost(String headers) {
        return domainFromHost(parse(headers));
    }

    public static String domainFromHost(JSONObject obj) {
        String fwd = firstValue(header(obj, "X-Forwarded-Host"));
        if (!fwd.isEmpty()) {
            return stripPort(fwd);
        }
        String host = header(obj, "Host");
        if (host.isEmpty()) {
            return "";
        }
        return stripPort(host);
    }

    /** 逗号分隔的 header 值取第一个并 trim（"a.com,a.com" → "a.com"） */
    private static String firstValue(String v) {
        if (v == null) {
            return "";
        }
        int idx = v.indexOf(',');
        String r = (idx > 0 ? v.substring(0, idx) : v).trim();
        return r == null ? "" : r;
    }

    private static String stripPort(String host) {
        if (host == null) {
            return "";
        }
        int colon = host.indexOf(':');
        return (colon > 0 ? host.substring(0, colon) : host).trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
