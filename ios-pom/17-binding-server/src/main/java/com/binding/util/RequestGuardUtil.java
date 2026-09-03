package com.binding.util;

import javax.servlet.http.HttpServletRequest;

/**
 * 17 binding /a 入站门禁：仅拒绝空 body。
 * /a 不强制要求 x-ts（解密时无 x-ts 走 decryptSafely 内部逻辑）。
 */
public final class RequestGuardUtil {

    private RequestGuardUtil() {
    }

    public static boolean shouldProcess(HttpServletRequest request, String rawBody) {
        return rawBody != null && !rawBody.trim().isEmpty();
    }
}
