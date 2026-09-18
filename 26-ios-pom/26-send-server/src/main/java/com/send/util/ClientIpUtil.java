package com.send.util;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;

public final class ClientIpUtil {

    private ClientIpUtil() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String[] headers = {
                "X-Forwarded-For",
                "X-Real-IP",
                "CF-Connecting-IP",
                "True-Client-IP"
        };
        for (String h : headers) {
            String v = request.getHeader(h);
            if (StringUtils.isNotBlank(v)) {
                String first = v.split(",")[0].trim();
                if (StringUtils.isNotBlank(first) && !"unknown".equalsIgnoreCase(first)) {
                    return first;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
