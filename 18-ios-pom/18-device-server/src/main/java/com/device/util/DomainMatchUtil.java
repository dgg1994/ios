package com.device.util;

/**
 * 域名归一化：对齐 qudao.c2_domain（裸域名）与 device.domain（常带 https://）。
 */
public final class DomainMatchUtil {

    private DomainMatchUtil() {}

    /**
     * 提取可与 {@code qudao.c2_domain} 比对的 host。
     * <p>例：{@code https://52jxx7zfv9g4yke.net} / {@code 52jxx7zfv9g4yke.net:443}
     * → {@code 52jxx7zfv9g4yke.net}
     */
    public static String normalizeHost(String raw) {
        return stripPort(normalizeHostKeepPort(raw));
    }

    /**
     * 归一化但保留端口（用于 qudao 写成 {@code 127.0.0.1:8100} 的场景）。
     */
    public static String normalizeHostKeepPort(String raw) {
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
                String inner = s.substring(1, end);
                String rest = s.substring(end + 1);
                return (inner + rest).trim().toLowerCase();
            }
        }
        return s.trim().toLowerCase();
    }

    /** host 或 host:port → 仅 host */
    public static String stripPort(String hostOrPort) {
        if (hostOrPort == null || hostOrPort.isEmpty()) {
            return "";
        }
        String s = hostOrPort;
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end > 0) {
                return s.substring(1, end).trim().toLowerCase();
            }
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) {
            s = s.substring(0, colon);
        }
        return s.trim().toLowerCase();
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
