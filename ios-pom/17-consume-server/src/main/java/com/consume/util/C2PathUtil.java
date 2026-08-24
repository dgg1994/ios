package com.consume.util;

/**
 * C2 路径规范化（对齐 news4 侧 normalize_c2_path）。
 *
 * 规则：
 *   1. 去掉 ?query
 *   2. 去掉尾部 /
 *   3. 保证以 / 开头
 *
 * 例：/event?x=1 → /event ；/a/ → /a ；event → /event
 */
public final class C2PathUtil {

    private C2PathUtil() {
    }

    public static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        // 去掉 query
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        // 去掉尾部 /
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        // 保证以 / 开头
        if (p.isEmpty() || !p.startsWith("/")) {
            p = "/" + p;
        }
        return p;
    }
}
