package com.binding.util;

/**
 * C2 path 规范化（与 consume-server C2PathUtil 对齐）。
 */
public final class C2PathUtil {

    private C2PathUtil() {
    }

    public static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty() || !p.startsWith("/")) {
            p = "/" + p;
        }
        return p;
    }

    public static boolean isAlbumPath(String path) {
        return "/t".equals(normalize(path));
    }
}
