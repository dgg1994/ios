package com.admin.auth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Service;

import com.admin.config.V26AdminProperties;
import com.admin.dao.AdminPermissionDao;
import com.admin.dao.AdminRoleDao;
import com.admin.dao.AdminRolePermissionDao;
import com.admin.dao.AdminUserDao;
import com.admin.entity.AdminPermissionEntity;
import com.admin.entity.AdminRoleEntity;
import com.admin.entity.AdminRolePermissionEntity;
import com.admin.entity.AdminUserEntity;
import com.admin.service.LoginRateLimitService;
import com.admin.util.AdminUnauthorizedException;
import com.admin.util.LoginIpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    public static final String COOKIE = "admin_token";

    private final AdminUserDao userDao;
    private final AdminRoleDao roleDao;
    private final AdminRolePermissionDao rolePermDao;
    private final AdminPermissionDao permDao;
    private final PasswordService passwordService;
    private final JwtService jwtService;
    private final V26AdminProperties props;
    private final LoginRateLimitService rateLimitService;

    public Map<String, Object> login(String username, String password, String clientIp, HttpServletResponse resp) {
        String ip = clientIp == null || clientIp.isBlank() ? "-" : clientIp.trim();
        if (rateLimitService.blocked(ip)) {
            throw new AdminUnauthorizedException("尝试次数过多，请 10 分钟后再试");
        }
        String uname = username == null ? "" : username.trim();
        AdminUserEntity user = userDao.selectOne(new QueryWrapper<AdminUserEntity>().eq("username", uname));
        if (user == null || user.getStatus() == null || user.getStatus() != 1
                || !passwordService.matches(password, user.getPasswordHash())) {
            rateLimitService.fail(ip);
            throw new AdminUnauthorizedException("用户名或密码错误");
        }
        AdminRoleEntity role = user.getRoleId() == null ? null : roleDao.selectById(user.getRoleId());
        if (role == null || role.getStatus() == null || role.getStatus() != 1) {
            throw new AdminUnauthorizedException("账号已禁用或未分配有效角色");
        }
        if (!ipAllowed(role.getCode(), user.getLoginIpWhitelist(), ip)) {
            throw new AdminUnauthorizedException("当前网络不允许登录该账号");
        }
        user.setLastLoginAt(new Date());
        userDao.updateById(user);
        rateLimitService.clear(ip);

        int tv = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        String token = jwtService.issue(user.getId(), role.getCode(), tv);
        Cookie cookie = new Cookie(COOKIE, token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(props.getJwt().getExpireMinutes() * 60);
        resp.addCookie(cookie);

        List<String> codes = loadPermissionCodes(role.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("username", user.getUsername());
        data.put("displayName", displayName(user));
        data.put("role", role.getCode());
        data.put("roleName", role.getName());
        data.put("superadmin", isSuperRole(role));
        data.put("permissions", codes);
        data.put("menu", buildMenu(isSuperRole(role), codes));
        data.put("version", props.getVersion());
        return data;
    }

    public Map<String, Object> session(AdminContext ctx) {
        AdminUserEntity user = ctx.getUser();
        AdminRoleEntity role = user.getRoleId() == null ? null : roleDao.selectById(user.getRoleId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", user.getUsername());
        data.put("displayName", displayName(user));
        data.put("role", ctx.getRoleCode());
        data.put("roleName", role == null ? "" : role.getName());
        data.put("superadmin", ctx.isSuperadmin());
        data.put("permissions", new ArrayList<>(ctx.getPermissions()));
        data.put("menu", buildMenu(ctx.isSuperadmin(), ctx.getPermissions()));
        data.put("version", props.getVersion());
        return data;
    }

    public void logout(HttpServletResponse resp) {
        Cookie cookie = new Cookie(COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        resp.addCookie(cookie);
    }

    public AdminContext loadFromToken(String token) {
        Claims claims;
        try {
            claims = jwtService.parse(token);
        } catch (Exception e) {
            return null;
        }
        if (!"admin".equals(String.valueOf(claims.get("typ")))) {
            return null;
        }
        int uid;
        int tv;
        try {
            uid = Integer.parseInt(claims.getSubject());
            Object rawTv = claims.get("tv");
            tv = rawTv == null ? 0 : Integer.parseInt(String.valueOf(rawTv));
        } catch (Exception e) {
            return null;
        }
        AdminUserEntity user = userDao.selectById(uid);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            return null;
        }
        int dbTv = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        if (tv != dbTv) {
            return null;
        }
        AdminRoleEntity role = user.getRoleId() == null ? null : roleDao.selectById(user.getRoleId());
        if (role == null || role.getStatus() == null || role.getStatus() != 1) {
            return null;
        }
        Set<String> codes = new HashSet<>(loadPermissionCodes(role.getId()));
        return new AdminContext(user, role.getCode(), codes);
    }

    public boolean ipAllowed(String roleCode, String dbRaw, String ip) {
        java.util.List<String> entries;
        if (roleCode != null && "superadmin".equalsIgnoreCase(roleCode)) {
            entries = props.getAdminIpWhitelist() == null ? java.util.List.of() : props.getAdminIpWhitelist();
        } else {
            entries = LoginIpUtil.normalize(dbRaw);
        }
        return LoginIpUtil.allowed(ip, entries);
    }

    public List<String> loadPermissionCodes(Integer roleId) {
        List<AdminRolePermissionEntity> links = rolePermDao.selectList(
                new QueryWrapper<AdminRolePermissionEntity>().eq("role_id", roleId));
        if (links.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = links.stream().map(AdminRolePermissionEntity::getPermissionId).collect(Collectors.toList());
        List<AdminPermissionEntity> perms = permDao.selectBatchIds(ids);
        return perms.stream()
                .filter(p -> p.getStatus() != null && p.getStatus() == 1)
                .map(AdminPermissionEntity::getCode)
                .collect(Collectors.toList());
    }

    private boolean isSuperRole(AdminRoleEntity role) {
        if (role == null) {
            return false;
        }
        return (role.getId() != null && role.getId() == 1) || "superadmin".equalsIgnoreCase(role.getCode());
    }

    private String displayName(AdminUserEntity user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        return user.getUsername();
    }

    private List<Map<String, Object>> buildMenu(boolean superadmin, Collection<String> codes) {
        List<AdminPermissionEntity> rows = permDao.selectList(
                new QueryWrapper<AdminPermissionEntity>()
                        .eq("perm_type", "menu")
                        .eq("status", 1)
                        .orderByAsc("sort")
                        .orderByAsc("id"));
        Set<String> allowed = codes == null ? Set.of() : new HashSet<>(codes);
        List<AdminPermissionEntity> visible = new ArrayList<>();
        for (AdminPermissionEntity p : rows) {
            if (superadmin || allowed.contains(p.getCode())) {
                visible.add(p);
            }
        }
        Set<Integer> ids = visible.stream().map(AdminPermissionEntity::getId).collect(Collectors.toSet());
        Map<Integer, List<AdminPermissionEntity>> children = new LinkedHashMap<>();
        List<AdminPermissionEntity> roots = new ArrayList<>();
        for (AdminPermissionEntity p : visible) {
            Integer pid = p.getParentId() == null ? 0 : p.getParentId();
            if (pid > 0 && ids.contains(pid)) {
                children.computeIfAbsent(pid, k -> new ArrayList<>()).add(p);
            } else {
                roots.add(p);
            }
        }
        List<Map<String, Object>> tree = new ArrayList<>();
        for (AdminPermissionEntity root : roots) {
            List<AdminPermissionEntity> kids = children.getOrDefault(root.getId(), List.of());
            if (!kids.isEmpty() || (root.getPath() != null && !root.getPath().isBlank())) {
                Map<String, Object> node = menuItem(root);
                node.put("children", kids.stream().map(this::menuItem).collect(Collectors.toList()));
                tree.add(node);
            }
        }
        return tree;
    }

    private Map<String, Object> menuItem(AdminPermissionEntity p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("code", p.getCode());
        m.put("name", p.getName());
        m.put("nameEn", p.getNameEn());
        m.put("path", p.getPath());
        m.put("icon", p.getIcon());
        m.put("groupKey", p.getGroupKey());
        return m;
    }
}
