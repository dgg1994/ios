package com.report.util;

/**
 * 版本号工具。
 */
public final class VersionUtil {

    private VersionUtil() {
    }

    /**
     * 取 version 的前 N 段前缀（去掉前导非数字）。
     */
    public static String versionPrefix(String version, int segments) {
        if (version == null) {
            return "";
        }
        String v = version.trim();
        if (v.isEmpty()) {
            return "";
        }
        int segs = segments <= 0 ? 1 : segments;

        // 1. 去掉前导非数字字符（"ios17" / "iOS15.1" → "17" / "15.1"）
        int i = 0;
        while (i < v.length() && !Character.isDigit(v.charAt(i))) {
            i++;
        }
        v = v.substring(i);
        if (v.isEmpty()) {
            return "";
        }

        // 2. 按 "." 切分，取前 segs 段
        String[] parts = v.split("\\.");
        int n = Math.min(segs, parts.length);

        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) {
            if (k > 0) {
                sb.append('.');
            }
            sb.append(parts[k]);
        }
        return sb.toString();
    }
}
