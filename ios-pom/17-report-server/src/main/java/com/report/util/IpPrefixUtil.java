package com.report.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IP 前缀工具（支持 IPv4 和 IPv6）。
 */
public final class IpPrefixUtil {

    private static final Logger log = LoggerFactory.getLogger(IpPrefixUtil.class);

    private IpPrefixUtil() {
    }

    /**
     * 取 IP 前 N 段（带末尾分隔符）。
     */
    public static String ipPrefix(String ip, int segments) {
        if (ip == null) {
            return "";
        }
        String s = ip.trim();
        if (s.isEmpty()) {
            return "";
        }

        int segs = Math.max(segments, 1);

        // 判断是 IPv4 还是 IPv6
        if (s.contains(".") && !s.contains(":")) {
            // IPv4（排除 IPv6 中可能包含的 . 如 IPv4-mapped）
            return ipv4Prefix(s, segs);
        } else if (s.contains(":")) {
            // IPv6（包括压缩格式）
            return ipv6Prefix(s, segs);
        } else {
            log.info("错误日志:无法识别的IP格式: {}", s);
            return "";
        }
    }

    /**
     * 处理 IPv4 地址
     */
    private static String ipv4Prefix(String ip, int segments) {
        String[] parts = ip.split("\\.");
        int n = Math.min(segments, parts.length);

        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) {
            if (k > 0) {
                sb.append('.');
            }
            sb.append(parts[k]);
        }
        if (n > 0) {
            sb.append('.');
        }
        return sb.toString();
    }

    /**
     * 处理 IPv6 地址（支持压缩格式）
     */
    private static String ipv6Prefix(String ip, int segments) {
        // 1. 处理带端口的情况：[2001:fd8:be25::1]:8080
        String cleanIp = ip;
        if (ip.startsWith("[") && ip.contains("]:")) {
            cleanIp = ip.substring(1, ip.indexOf("]:"));
        }
        // 去掉末尾的端口（不带方括号的情况）
        if (cleanIp.contains(":") && cleanIp.lastIndexOf(":") > cleanIp.indexOf(":")) {
            // 如果最后一个冒号后面是数字（可能是端口），但不是 IPv6 的组（最多4位hex）
            String lastPart = cleanIp.substring(cleanIp.lastIndexOf(":") + 1);
            if (lastPart.matches("\\d+") && !lastPart.matches("[0-9a-fA-F]{1,4}")) {
                cleanIp = cleanIp.substring(0, cleanIp.lastIndexOf(":"));
            }
        }

        // 2. 展开压缩格式（:: → :0000:）
        String expanded = expandIPv6(cleanIp);
        if (expanded.isEmpty()) {
            return "";
        }

        // 3. 按 : 分割
        String[] parts = expanded.split(":");
        int n = Math.min(segments, parts.length);

        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) {
            if (k > 0) {
                sb.append(':');
            }
            sb.append(parts[k]);
        }
        if (n > 0) {
            sb.append(':');
        }
        return sb.toString();
    }

    /**
     * 展开 IPv6 压缩格式
     * 例如: "2001:fd8:be25::1" → "2001:fd8:be25:0:0:0:0:1"
     */
    private static String expandIPv6(String ip) {
        if (!ip.contains("::")) {
            return ip;
        }

        try {
            // 分割前后部分
            String[] parts = ip.split("::");
            String[] left = parts[0].isEmpty() ? new String[0] : parts[0].split(":");
            String[] right = parts.length > 1 && !parts[1].isEmpty() 
                    ? parts[1].split(":") : new String[0];

            // 计算需要填充的零组数
            int totalGroups = 8;
            int missingGroups = totalGroups - left.length - right.length;

            if (missingGroups < 0) {
                log.info("异常日志:IPv6 格式异常: {}", ip);
                return ip;
            }

            // 构建完整地址
            StringBuilder expanded = new StringBuilder();
            for (String group : left) {
                expanded.append(group).append(':');
            }
            for (int i = 0; i < missingGroups; i++) {
                if (i == 0 && left.length == 0) {
                    expanded.append("0");
                }
                expanded.append("0");
                if (i < missingGroups - 1 || right.length > 0) {
                    expanded.append(':');
                }
            }
            for (int i = 0; i < right.length; i++) {
                if (i > 0 || (left.length == 0 && missingGroups == 0)) {
                    expanded.append(':');
                }
                expanded.append(right[i]);
            }

            return expanded.toString();
        } catch (Exception e) {
            log.info("错误日志:展开 IPv6 失败: {}, error={}", ip, e.getMessage());
            return ip;
        }
    }
}