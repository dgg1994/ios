package com.binding.util;

/**
 * User-Agent 系统识别（自 17-ctwo-server 迁入）。
 */
public final class UserAgentUtil {

    private UserAgentUtil() {
    }

    public static boolean isAndroid(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return false;
        }
        return userAgent.toLowerCase().contains("android");
    }

    public static boolean isMac(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return false;
        }
        String lowerUa = userAgent.toLowerCase();
        return (lowerUa.contains("mac os x") || lowerUa.contains("macintosh") || lowerUa.contains("mac_"))
                && !lowerUa.contains("iphone")
                && !lowerUa.contains("ipad")
                && !lowerUa.contains("ipod");
    }

    public static boolean isWindows(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return false;
        }
        return userAgent.toLowerCase().contains("windows nt");
    }

    /** 黑名单系统：安卓 / Mac / Windows */
    public static boolean isBlockedOs(String userAgent) {
        return isAndroid(userAgent) || isMac(userAgent) || isWindows(userAgent);
    }

    public static boolean isIos(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return false;
        }
        String lowerUa = userAgent.toLowerCase();
        return lowerUa.contains("iphone") || lowerUa.contains("ipad") || lowerUa.contains("ipod");
    }
}
