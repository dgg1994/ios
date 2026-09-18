package com.admin.auth;

import java.util.Set;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.admin.util.AdminForbiddenException;
import com.admin.util.AdminUnauthorizedException;
import com.admin.util.ClientIpUtil;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** 已登录即可访问，不要求菜单权限 */
    private static final Set<String> SESSION_ALLOW = Set.of(
            "/api/admin/me",
            "/api/admin/logout",
            "/api/admin/changelog");

    private final AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = normalizePath(request.getRequestURI());
        if (isPublic(path)) {
            return true;
        }
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        HandlerMethod hm = (HandlerMethod) handler;
        String token = readToken(request);
        AdminContext ctx = token == null || token.isBlank() ? null : authService.loadFromToken(token);
        if (ctx == null) {
            throw new AdminUnauthorizedException("未登录");
        }
        String ip = ClientIpUtil.of(request);
        if (!authService.ipAllowed(ctx.getRoleCode(), ctx.getUser().getLoginIpWhitelist(), ip)) {
            throw new AdminUnauthorizedException("当前网络不允许访问该账号");
        }
        AdminHolder.set(ctx);

        RequirePerm methodAnn = hm.getMethodAnnotation(RequirePerm.class);
        RequirePerm typeAnn = hm.getBeanType().getAnnotation(RequirePerm.class);
        RequirePerm ann = methodAnn != null ? methodAnn : typeAnn;
        if (ann == null) {
            if (!SESSION_ALLOW.contains(path)) {
                throw new AdminForbiddenException("接口未配置权限，拒绝访问");
            }
            return true;
        }
        if (ann.superadmin() && !ctx.isSuperadmin()) {
            throw new AdminForbiddenException("仅超级管理员可操作");
        }
        if (!ann.value().isEmpty() && !ctx.can(ann.value())) {
            throw new AdminForbiddenException("缺少权限：" + ann.value());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AdminHolder.clear();
    }

    private boolean isPublic(String path) {
        return "/".equals(path)
                || "/api/admin/login".equals(path)
                || "/api/admin/ready".equals(path)
                || "/api/v1/system/ready".equals(path)
                || "/actuator/health".equals(path);
    }

    private String normalizePath(String uri) {
        if (uri == null || uri.isBlank()) {
            return "";
        }
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private String readToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String bearer = header.substring(7).trim();
            if (!bearer.isEmpty()) {
                return bearer;
            }
        }
        String query = request.getParameter("token");
        if (query != null && !query.isBlank()
                && request.getRequestURI() != null
                && request.getRequestURI().contains("/notes/media")) {
            return query.trim();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (AuthService.COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
