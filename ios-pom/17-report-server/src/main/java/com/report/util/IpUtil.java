package com.report.util;

import javax.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

/**
 * IP 工具
 * - getClientIp：X-Forwarded-For > Proxy-Client-IP > WL-Proxy-Client-IP > X-Real-IP > remoteAddr
 * - isLocalIp：本机 / 内网判定
 */
@Component
public class IpUtil {

    /**
     * 获取客户端真实 IP（兼容代理链）
     */
    public String getClientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "Proxy-Client-IP", "WL-Proxy-Client-IP", "X-Real-IP"};
        for (String h : headers) {
            String v = request.getHeader(h);
            if (v != null && !v.isEmpty() && !"unknown".equalsIgnoreCase(v)) {
                int comma = v.indexOf(',');
                return (comma > 0 ? v.substring(0, comma) : v).trim();
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * 判断是否本机或内网 IP（127.0.0.1 / ::1 / 10.x / 192.168.x / 172.16-31.x）
     */
    public boolean isLocalIp(String ipAddress) {
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            return false;
        }
        String ip = ipAddress.trim();
        if ("127.0.0.1".equals(ip) || "::1".equals(ip)) {
            return true;
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int b0 = Integer.parseInt(parts[0]);
            int b1 = Integer.parseInt(parts[1]);
            if (b0 == 10) {
                return true;
            }
            if (b0 == 192 && b1 == 168) {
                return true;
            }
            if (b0 == 172 && b1 >= 16 && b1 <= 31) {
                return true;
            }
        } catch (NumberFormatException ignore) {
            return false;
        }
        return false;
    }
}
