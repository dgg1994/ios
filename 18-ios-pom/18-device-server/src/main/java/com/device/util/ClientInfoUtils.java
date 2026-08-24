package com.device.util;

import javax.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ClientInfoUtils {

    public static final String ATTR_CLIENT_DOMAIN = "clientDomain";
    public static final String ATTR_CLIENT_PROTOCOL = "clientProtocol";
    public static final String ATTR_CLIENT_FULL_DOMAIN = "clientFullDomain";
    public static final String ATTR_CLIENT_REAL_IP = "clientRealIP";

    // 获取客户端域名（仅 host，剥离端口 + 兼容逗号脏数据）
    public static String getClientDomain(HttpServletRequest request) {
        // 1. X-Forwarded-Host
        String forwardedHost = firstValue(request.getHeader("X-Forwarded-Host"));
        if (StringUtils.hasText(forwardedHost)) {
            // 移除端口号（如果有）
            return stripPort(forwardedHost);
        }

        // 2. Host
        String host = firstValue(request.getHeader("Host"));
        if (StringUtils.hasText(host)) {
            return stripPort(host);
        }
        // 3. ServerName
        return request.getServerName();
    }

    // 获取完整域名（包含端口）
    public static String getClientDomainWithPort(HttpServletRequest request) {
        String forwardedHost = firstValue(request.getHeader("X-Forwarded-Host"));
        if (StringUtils.hasText(forwardedHost)) {
            return forwardedHost;
        }

        String host = firstValue(request.getHeader("Host"));
        if (StringUtils.hasText(host)) {
            return host;
        }

        String domain = request.getServerName();
        int port = request.getServerPort();
        if (port != 80 && port != 443) {
            domain += ":" + port;
        }
        return domain;
    }

    // 获取完整 URL（包含协议和域名）
    // 兼容多层代理 / X-Forwarded-* 脏数据
    public static String getClientFullDomain(HttpServletRequest request) {
        String protocol = firstValue(request.getHeader("X-Forwarded-Proto"));
        if (!StringUtils.hasText(protocol) || "unknown".equalsIgnoreCase(protocol)) {
            protocol = request.getScheme();
        }
        // 限定为 http / https，避免被脏数据污染
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            protocol = request.getScheme();
        }

        String domain = getClientDomainWithPort(request);
        return protocol + "://" + domain;
    }
    
    
    public static String getClientDomainTwo(HttpServletRequest request) {
        String protocol = firstValue(request.getHeader("X-Forwarded-Proto"));
        if (!StringUtils.hasText(protocol) || "unknown".equalsIgnoreCase(protocol)) {
            protocol = request.getScheme();
        }
        String domain = getClientDomainWithPort(request);
        return domain;
    }

    // 获取客户端真实 IP
    public static String getClientRealIP(HttpServletRequest request) {
        String ip = firstValue(request.getHeader("X-Forwarded-For"));
        if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        ip = firstValue(request.getHeader("X-Real-IP"));
        if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        return request.getRemoteAddr();
    }

    // 从 request 属性获取（如果有 Filter 预先设置）
    public static String getClientDomainFromAttribute(HttpServletRequest request) {
        Object domain = request.getAttribute(ATTR_CLIENT_DOMAIN);
        return domain != null ? domain.toString() : getClientDomain(request);
    }

    // ---------- 私有辅助 ----------

    /**
     * 逗号分隔的 header 值取第一个并 trim（处理多层代理脏数据）。
     * 例：{@code "jnbb5pl98fcgh1y.xyz,jnbb5pl98fcgh1y.xyz"} → {@code "jnbb5pl98fcgh1y.xyz"}
     */
    private static String firstValue(String v) {
        if (v == null) {
            return null;
        }
        int idx = v.indexOf(',');
        return (idx > 0 ? v.substring(0, idx) : v).trim();
    }

    private static String stripPort(String host) {
        if (host == null) {
            return null;
        }
        int colon = host.indexOf(':');
        return (colon > 0 ? host.substring(0, colon) : host).trim();
    }
}
