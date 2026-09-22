package com.admin.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.admin.auth.AdminContext;
import com.admin.auth.PasswordService;
import com.admin.dao.AdminRoleDao;
import com.admin.dao.AdminUserDao;
import com.admin.entity.AdminRoleEntity;
import com.admin.entity.AdminUserEntity;
import com.admin.util.LoginIpUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final Pattern USER_RE = Pattern.compile("^[A-Za-z0-9._\\-]{3,64}$");
    private static final Map<String, String[]> CREATABLE = Map.of(
            "superadmin", new String[] {"agent", "channel", "salesman"},
            "agent", new String[] {"channel", "salesman"},
            "channel", new String[] {"salesman"},
            "salesman", new String[] {});
    private static final Map<String, String> PARENT_ROLE = Map.of(
            "agent", "superadmin",
            "channel", "agent",
            "salesman", "channel");
    private static final String[] COLLECT_CHAINS = {"tron", "eth", "bsc", "btc", "sol"};
    private static final Pattern COLLECT_RE = Pattern.compile("^[\\w.\\-:@/+]+$");

    private final ScopeService scopeService;
    private final AdminRoleDao roleDao;
    private final AdminUserDao userDao;
    private final PasswordService passwordService;
    private final TelegramSendService telegramSendService;

    public Map<String, Object> tree(AdminContext ctx) {
        List<AdminUserEntity> users = scopeService.allUsers();
        Set<Integer> visible = scopeService.visibleUserIds(ctx);
        Map<Integer, List<AdminUserEntity>> byParent = scopeService.indexByParent(users);
        Map<Integer, AdminUserEntity> byId = new HashMap<>();
        for (AdminUserEntity u : users) {
            byId.put(u.getId(), u);
        }
        List<Map<String, Object>> tree = new ArrayList<>();
        List<Map<String, Object>> orphans = new ArrayList<>();
        if (ctx.isSuperadmin()) {
            for (AdminUserEntity u : users) {
                if (u.getParentId() == null && "superadmin".equalsIgnoreCase(scopeService.roleCode(u))) {
                    tree.add(node(u, 0, byParent, byId, visible, ctx));
                }
            }
            for (AdminUserEntity u : users) {
                String code = scopeService.roleCode(u);
                if (!"superadmin".equalsIgnoreCase(code)
                        && (u.getParentId() == null || !byId.containsKey(u.getParentId()))) {
                    orphans.add(node(u, 1, byParent, byId, visible, ctx));
                }
            }
        } else {
            tree.add(node(ctx.getUser(), 0, byParent, byId, visible, ctx));
        }
        int active = 0;
        int disabled = 0;
        int count = 0;
        for (AdminUserEntity u : users) {
            if (visible != null && !visible.contains(u.getId())) {
                continue;
            }
            count++;
            if (u.getStatus() != null && u.getStatus() == 1) {
                active++;
            } else {
                disabled++;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tree", tree);
        data.put("orphans", orphans);
        data.put("count", count);
        data.put("active_count", active);
        data.put("disabled_count", disabled);
        data.put("actor_role", ctx.getRoleCode());
        data.put("actor_id", ctx.getUser().getId());
        data.put("can_edit_collect", ctx.isSuperadmin());
        data.put("roles", assignableRoles(ctx));
        data.put("parent_options", parentOptions(ctx, users, visible, byId));
        return data;
    }

    public String create(AdminContext ctx, Map<String, Object> body) {
        String username = str(body, "username");
        String displayName = str(body, "display_name");
        if (displayName.isEmpty()) {
            displayName = username;
        }
        String password = str(body, "password");
        String roleCode = str(body, "role_code");
        String tgToken = str(body, "tg_rob_token");
        String tgGroup = str(body, "tg_groupid");
        String ipRaw = str(body, "login_ip_whitelist");
        int parentId = num(body.get("parent_id"), 0);
        if (!USER_RE.matcher(username).matches()) {
            return "用户名需 3–64 位，仅字母数字._-";
        }
        if (displayName.length() > 64) {
            return "显示名过长";
        }
        if (password.length() < 6) {
            return "密码至少 6 位";
        }
        if (password.length() > 72) {
            return "密码过长";
        }
        if (tgToken.length() > 255) {
            return "机器人 ID 过长";
        }
        if (tgGroup.length() > 128) {
            return "飞机群 ID 过长";
        }
        String ipErr = LoginIpUtil.validate(ipRaw);
        if (ipErr != null) {
            return ipErr;
        }
        String actorCode = ctx.getRoleCode();
        if (!contains(CREATABLE.getOrDefault(actorCode, new String[0]), roleCode)) {
            return "当前账号无权创建该类型用户";
        }
        AdminRoleEntity role = roleDao.selectOne(new QueryWrapper<AdminRoleEntity>().eq("code", roleCode).eq("status", 1).last("LIMIT 1"));
        if (role == null) {
            return "角色不存在或已禁用";
        }
        if (userDao.selectOne(new QueryWrapper<AdminUserEntity>().eq("username", username).last("LIMIT 1")) != null) {
            return "用户名已存在";
        }
        String needParent = PARENT_ROLE.get(roleCode);
        if (needParent == null) {
            return "无法创建该角色";
        }
        if (actorCode.equals(needParent)) {
            parentId = ctx.getUser().getId();
        }
        if (parentId <= 0) {
            return "请选择所属上级账号";
        }
        AdminUserEntity parent = userDao.selectById(parentId);
        if (parent == null) {
            return "上级账号不存在";
        }
        if (parent.getStatus() == null || parent.getStatus() != 1) {
            return "上级账号已停用";
        }
        if (!needParent.equals(scopeService.roleCode(parent))) {
            return "上级须为" + needParent + "角色";
        }
        Set<Integer> visible = scopeService.visibleUserIds(ctx);
        if (visible != null && !visible.contains(parent.getId()) && !parent.getId().equals(ctx.getUser().getId())) {
            return "无权在该上级下创建账号";
        }
        if ("agent".equals(actorCode) && "salesman".equals(roleCode)
                && (parent.getParentId() == null || !parent.getParentId().equals(ctx.getUser().getId()))) {
            return "只能选择自己线下的渠道账号";
        }
        AdminUserEntity row = new AdminUserEntity();
        row.setUsername(username);
        row.setPasswordHash(passwordService.hash(password));
        row.setDisplayName(displayName);
        row.setRoleId(role.getId());
        row.setParentId(parent.getId());
        row.setStatus(1);
        row.setTgRobToken(tgToken);
        row.setTgGroupid(tgGroup);
        row.setLoginIpWhitelist(JSON.toJSONString(LoginIpUtil.normalize(ipRaw)));
        row.setTokenVersion(0);
        Date now = new Date();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        userDao.insert(row);
        return null;
    }

    public String update(AdminContext ctx, int userId, Map<String, Object> body) {
        AdminUserEntity user = loadVisible(ctx, userId);
        if (user == null) {
            return "无权操作该账号";
        }
        String displayName = str(body, "display_name");
        String password = str(body, "password");
        String tgToken = str(body, "tg_rob_token");
        String tgGroup = str(body, "tg_groupid");
        if (!displayName.isEmpty() && displayName.length() > 64) {
            return "显示名过长";
        }
        if (!password.isEmpty()) {
            if (password.length() < 6) {
                return "密码至少 6 位";
            }
            if (password.length() > 72) {
                return "密码过长";
            }
        }
        if (tgToken.length() > 255) {
            return "机器人 ID 过长";
        }
        if (tgGroup.length() > 128) {
            return "飞机群 ID 过长";
        }
        if (!displayName.isEmpty()) {
            user.setDisplayName(displayName);
        }
        if (!password.isEmpty()) {
            user.setPasswordHash(passwordService.hash(password));
            user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        }
        user.setTgRobToken(tgToken);
        user.setTgGroupid(tgGroup);
        user.setUpdatedAt(new Date());
        userDao.updateById(user);
        return null;
    }

    public Map<String, Object> tgTest(AdminContext ctx, int userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        AdminUserEntity user = loadVisible(ctx, userId);
        if (user == null) {
            data.put("ok", false);
            data.put("message", "无权操作该账号");
            return data;
        }
        String token = user.getTgRobToken() == null ? "" : user.getTgRobToken().trim();
        String group = user.getTgGroupid() == null ? "" : user.getTgGroupid().trim();
        if (token.isEmpty() || group.isEmpty()) {
            data.put("ok", false);
            data.put("message", "该账号未同时配置机器人 ID 与飞机群 ID");
            return data;
        }
        String text = "[测试通知]\n账号：" + user.getUsername()
                + "\n显示名：" + (user.getDisplayName() == null || user.getDisplayName().isBlank() ? user.getUsername() : user.getDisplayName())
                + "\n时间：" + com.admin.util.TimeLabels.beijing(new Date())
                + "\n来源：后台用户管理 · 测试通知";
        Map<String, Object> sent = telegramSendService.send(token, group, text);
        data.put("ok", Boolean.TRUE.equals(sent.get("ok")));
        data.put("message", sent.get("message"));
        data.put("username", user.getUsername());
        data.put("tg_groupid", group);
        return data;
    }

    public String saveCollect(AdminContext ctx, int userId, Map<String, Object> body) {
        AdminUserEntity user = loadVisible(ctx, userId);
        if (user == null) {
            return "无权操作该账号";
        }
        if (!canEditCollect(ctx, user)) {
            if ("agent".equals(ctx.getRoleCode()) && ctx.getUser().getId().equals(userId)) {
                return "代理仅可查看归集地址，不可修改";
            }
            if (!"agent".equals(scopeService.roleCode(user))) {
                return "仅代理账号可配置归集地址";
            }
            return "仅总后台可设定归集地址";
        }
        String[] vals = {
                str(body, "tron"), str(body, "eth"), str(body, "bsc"), str(body, "btc"), str(body, "sol")
        };
        List<Map<String, String>> payload = new ArrayList<>();
        for (int i = 0; i < COLLECT_CHAINS.length; i++) {
            String addr = vals[i];
            if (!addr.isEmpty()) {
                if (addr.length() > 128) {
                    return COLLECT_CHAINS[i].toUpperCase() + " 地址过长";
                }
                if (!COLLECT_RE.matcher(addr).matches()) {
                    return COLLECT_CHAINS[i].toUpperCase() + " 地址格式不合法";
                }
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("chain", COLLECT_CHAINS[i]);
            item.put("address", addr);
            payload.add(item);
        }
        user.setCollectaddress(JSON.toJSONString(payload));
        user.setUpdatedAt(new Date());
        userDao.updateById(user);
        return null;
    }

    public String saveLoginIp(AdminContext ctx, int userId, Object raw) {
        AdminUserEntity user = loadVisible(ctx, userId);
        if (user == null) {
            return "无权操作该账号";
        }
        Map<Integer, AdminUserEntity> byId = new HashMap<>();
        for (AdminUserEntity u : scopeService.allUsers()) {
            byId.put(u.getId(), u);
        }
        if (!canEditLoginIp(ctx, user, byId)) {
            if (ctx.getUser().getId().equals(userId)) {
                return "不能修改自己的登录 IP 白名单";
            }
            if ("superadmin".equalsIgnoreCase(scopeService.roleCode(user))) {
                return "总后台登录白名单请在配置 ADMIN_IP_WHITELIST 中配置";
            }
            return "无权修改该账号的登录 IP 白名单";
        }
        String err = LoginIpUtil.validate(raw);
        if (err != null) {
            return err;
        }
        user.setLoginIpWhitelist(JSON.toJSONString(LoginIpUtil.normalize(raw)));
        user.setUpdatedAt(new Date());
        userDao.updateById(user);
        return null;
    }

    public String toggle(AdminContext ctx, int userId, boolean enable) {
        AdminUserEntity user = loadVisible(ctx, userId);
        if (user == null) {
            return "无权操作该账号";
        }
        if (user.getId().equals(ctx.getUser().getId())) {
            return "不能停用当前登录账号";
        }
        if (!enable && "superadmin".equalsIgnoreCase(scopeService.roleCode(user))
                && user.getStatus() != null && user.getStatus() == 1) {
            int others = 0;
            for (AdminUserEntity u : scopeService.allUsers()) {
                if (!u.getId().equals(user.getId()) && "superadmin".equalsIgnoreCase(scopeService.roleCode(u))
                        && u.getStatus() != null && u.getStatus() == 1) {
                    others++;
                }
            }
            if (others < 1) {
                return "不能停用唯一的超级管理员";
            }
        }
        user.setStatus(enable ? 1 : 0);
        user.setUpdatedAt(new Date());
        userDao.updateById(user);
        return null;
    }

    public Map<String, String> collectMap(AdminUserEntity user) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String c : COLLECT_CHAINS) {
            map.put(c, "");
        }
        if (user == null || user.getCollectaddress() == null || user.getCollectaddress().isBlank()) {
            return map;
        }
        try {
            List<?> raw = JSON.parseArray(user.getCollectaddress());
            if (raw != null) {
                for (Object o : raw) {
                    if (!(o instanceof Map)) {
                        continue;
                    }
                    Map<?, ?> m = (Map<?, ?>) o;
                    String chain = String.valueOf(m.get("chain") == null ? "" : m.get("chain")).trim().toLowerCase();
                    String addr = String.valueOf(m.get("address") == null ? "" : m.get("address")).trim();
                    if (map.containsKey(chain)) {
                        map.put(chain, addr);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private Map<String, Object> node(AdminUserEntity u, int depth, Map<Integer, List<AdminUserEntity>> byParent,
            Map<Integer, AdminUserEntity> byId, Set<Integer> visible, AdminContext actor) {
        AdminRoleEntity role = u.getRoleId() == null ? null : roleDao.selectById(u.getRoleId());
        String code = role == null ? "" : role.getCode();
        Map<String, String> cmap = collectMap(u);
        boolean collectSet = "agent".equals(code) && cmap.values().stream().anyMatch(v -> v != null && !v.isBlank());
        List<String> ips = LoginIpUtil.normalize(u.getLoginIpWhitelist());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("display_name", u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getUsername() : u.getDisplayName());
        m.put("role_code", code);
        m.put("role_name", role == null ? "未分配" : role.getName());
        m.put("status", u.getStatus());
        m.put("parent_id", u.getParentId());
        AdminUserEntity parent = u.getParentId() == null ? null : byId.get(u.getParentId());
        m.put("parent_username", parent == null ? "—" : parent.getUsername());
        m.put("is_superadmin", "superadmin".equalsIgnoreCase(code));
        m.put("tg_rob_token", u.getTgRobToken() == null ? "" : u.getTgRobToken());
        m.put("tg_groupid", u.getTgGroupid() == null ? "" : u.getTgGroupid());
        m.put("collectaddress", cmap);
        m.put("collect_map", cmap);
        m.put("collect_set", collectSet);
        m.put("show_collect", canViewCollect(actor, u, code));
        m.put("edit_collect", canEditCollect(actor, u));
        m.put("login_ip_whitelist", ips);
        m.put("login_ip_text", String.join("\n", ips));
        m.put("login_ip_set", !ips.isEmpty());
        m.put("show_login_ip", canViewLoginIp(actor, u, byId));
        m.put("edit_login_ip", canEditLoginIp(actor, u, byId));
        m.put("is_self", actor.getUser().getId().equals(u.getId()));
        m.put("depth", depth);
        List<Map<String, Object>> children = new ArrayList<>();
        List<AdminUserEntity> kids = byParent.get(u.getId());
        if (kids != null) {
            kids.sort((a, b) -> {
                int oa = roleOrder(scopeService.roleCode(a));
                int ob = roleOrder(scopeService.roleCode(b));
                if (oa != ob) {
                    return Integer.compare(oa, ob);
                }
                return Integer.compare(a.getId(), b.getId());
            });
            for (AdminUserEntity k : kids) {
                if (visible != null && !visible.contains(k.getId())) {
                    continue;
                }
                children.add(node(k, depth + 1, byParent, byId, visible, actor));
            }
        }
        m.put("children", children);
        m.put("descendant_count", countDesc(children));
        return m;
    }

    private int countDesc(List<Map<String, Object>> children) {
        int n = 0;
        for (Map<String, Object> c : children) {
            n += 1 + (Integer) c.getOrDefault("descendant_count", 0);
        }
        return n;
    }

    private int roleOrder(String code) {
        if ("superadmin".equals(code)) {
            return 0;
        }
        if ("agent".equals(code)) {
            return 1;
        }
        if ("channel".equals(code)) {
            return 2;
        }
        if ("salesman".equals(code)) {
            return 3;
        }
        return 9;
    }

    private List<Map<String, Object>> assignableRoles(AdminContext ctx) {
        String[] allowed = CREATABLE.getOrDefault(ctx.getRoleCode(), new String[0]);
        List<Map<String, Object>> out = new ArrayList<>();
        if (allowed.length == 0) {
            return out;
        }
        List<AdminRoleEntity> roles = roleDao.selectList(new QueryWrapper<AdminRoleEntity>().eq("status", 1).orderByAsc("id"));
        for (AdminRoleEntity r : roles) {
            if (contains(allowed, r.getCode())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.getId());
                m.put("code", r.getCode());
                m.put("name", r.getName());
                out.add(m);
            }
        }
        return out;
    }

    private Map<String, List<Map<String, Object>>> parentOptions(AdminContext ctx, List<AdminUserEntity> users,
            Set<Integer> visible, Map<Integer, AdminUserEntity> byId) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        result.put("agent", new ArrayList<>());
        result.put("channel", new ArrayList<>());
        result.put("salesman", new ArrayList<>());
        String actorCode = ctx.getRoleCode();
        for (Map.Entry<String, String> e : PARENT_ROLE.entrySet()) {
            String roleCode = e.getKey();
            String parentRole = e.getValue();
            if (!contains(CREATABLE.getOrDefault(actorCode, new String[0]), roleCode)) {
                continue;
            }
            if (actorCode.equals(parentRole)) {
                Map<String, Object> opt = new LinkedHashMap<>();
                opt.put("id", ctx.getUser().getId());
                opt.put("label", label(ctx.getUser()));
                opt.put("role_code", actorCode);
                opt.put("parent_label", "当前账号");
                result.get(roleCode).add(opt);
                continue;
            }
            for (AdminUserEntity u : users) {
                if (visible != null && !visible.contains(u.getId())) {
                    continue;
                }
                if (!parentRole.equals(scopeService.roleCode(u))) {
                    continue;
                }
                if (u.getStatus() == null || u.getStatus() != 1) {
                    continue;
                }
                AdminUserEntity p = u.getParentId() == null ? null : byId.get(u.getParentId());
                Map<String, Object> opt = new LinkedHashMap<>();
                opt.put("id", u.getId());
                opt.put("label", label(u));
                opt.put("role_code", parentRole);
                opt.put("parent_label", p == null ? "" : label(p));
                result.get(roleCode).add(opt);
            }
        }
        return result;
    }

    private AdminUserEntity loadVisible(AdminContext ctx, int userId) {
        Set<Integer> visible = scopeService.visibleUserIds(ctx);
        if (visible != null && !visible.contains(userId)) {
            return null;
        }
        return userDao.selectById(userId);
    }

    private boolean canViewCollect(AdminContext actor, AdminUserEntity target, String targetCode) {
        if (!"agent".equals(targetCode)) {
            return false;
        }
        if (actor.isSuperadmin()) {
            return true;
        }
        return "agent".equals(actor.getRoleCode()) && actor.getUser().getId().equals(target.getId());
    }

    private boolean canEditCollect(AdminContext actor, AdminUserEntity target) {
        return actor.isSuperadmin() && "agent".equals(scopeService.roleCode(target));
    }

    private boolean canEditLoginIp(AdminContext actor, AdminUserEntity target, Map<Integer, AdminUserEntity> byId) {
        if (actor.getUser().getId().equals(target.getId())) {
            return false;
        }
        String code = scopeService.roleCode(target);
        if (!"agent".equals(code) && !"channel".equals(code) && !"salesman".equals(code)) {
            return false;
        }
        if (actor.isSuperadmin()) {
            return true;
        }
        return isAncestor(actor.getUser().getId(), target, byId);
    }

    private boolean canViewLoginIp(AdminContext actor, AdminUserEntity target, Map<Integer, AdminUserEntity> byId) {
        if ("superadmin".equalsIgnoreCase(scopeService.roleCode(target))) {
            return false;
        }
        if (actor.getUser().getId().equals(target.getId())) {
            return true;
        }
        return canEditLoginIp(actor, target, byId);
    }

    private boolean isAncestor(int ancestorId, AdminUserEntity target, Map<Integer, AdminUserEntity> byId) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        Integer pid = target.getParentId();
        while (pid != null && pid > 0) {
            if (pid == ancestorId) {
                return true;
            }
            if (!seen.add(pid)) {
                return false;
            }
            AdminUserEntity p = byId.get(pid);
            if (p == null) {
                return false;
            }
            pid = p.getParentId();
        }
        return false;
    }

    private String label(AdminUserEntity u) {
        String name = u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getUsername() : u.getDisplayName();
        return name.equals(u.getUsername()) ? u.getUsername() : name + "（" + u.getUsername() + "）";
    }

    private boolean contains(String[] arr, String v) {
        for (String s : arr) {
            if (s.equals(v)) {
                return true;
            }
        }
        return false;
    }

    private String str(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return "";
        }
        return String.valueOf(body.get(key)).trim();
    }

    private int num(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
