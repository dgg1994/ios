package com.admin.util;

import javax.servlet.http.HttpServletRequest;

public final class ClientIpUtil {

    private static final String[] HEADERS = {
            "CF-Connecting-IP", "True-Client-IP", "X-Real-IP", "X-Client-IP"
    };

    private ClientIpUtil() {
    }

    public static String of(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        for (String h : HEADERS) {
            String raw = request.getHeader(h);
            if (raw != null && !raw.isBlank()) {
                return raw.split(",")[0].trim();
            }
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String ip = request.getRemoteAddr();
        return ip == null ? "" : ip.trim();
    }
}
