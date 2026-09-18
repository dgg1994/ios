package com.admin.auth;

public final class AdminHolder {

    private static final ThreadLocal<AdminContext> CTX = new ThreadLocal<>();

    private AdminHolder() {
    }

    public static void set(AdminContext ctx) {
        CTX.set(ctx);
    }

    public static AdminContext get() {
        return CTX.get();
    }

    public static AdminContext require() {
        AdminContext ctx = CTX.get();
        if (ctx == null) {
            throw new com.admin.util.AdminUnauthorizedException("未登录");
        }
        return ctx;
    }

    public static void clear() {
        CTX.remove();
    }
}
