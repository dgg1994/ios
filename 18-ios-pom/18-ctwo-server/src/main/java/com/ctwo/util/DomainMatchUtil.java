package com.ctwo.util;

/**
 * 域名归一化：对齐 qudao.c2_domain（裸域名）与 device.domain（常带 https://）。
 */
public final class DomainMatchUtil {

    private DomainMatchUtil() {}

    public static String normalizeHost(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        int comma = s.indexOf(',');
        if (comma >= 0) {
            s = s.substring(0, comma).trim();
        }
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end > 0) {
                s = s.substring(1, end);
            }
        } else {
            int colon = s.lastIndexOf(':');
            if (colon > 0 && s.indexOf(':') == colon) {
                s = s.substring(0, colon);
            }
        }
        return s.trim().toLowerCase();
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
