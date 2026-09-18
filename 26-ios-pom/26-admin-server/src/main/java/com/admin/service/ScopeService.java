package com.admin.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.admin.auth.AdminContext;
import com.admin.dao.AdminRoleDao;
import com.admin.dao.AdminUserDao;
import com.admin.dao.AppidDao;
import com.admin.entity.AdminRoleEntity;
import com.admin.entity.AdminUserEntity;
import com.admin.entity.AppidEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ScopeService {

    private final AdminUserDao userDao;
    private final AdminRoleDao roleDao;
    private final AppidDao appidDao;

    public String roleCode(AdminUserEntity user) {
        if (user == null || user.getRoleId() == null) {
            return "";
        }
        AdminRoleEntity role = roleDao.selectById(user.getRoleId());
        return role == null || role.getCode() == null ? "" : role.getCode();
    }

    public boolean isSuper(AdminContext ctx) {
        return ctx != null && ctx.isSuperadmin();
    }

    /** null = 不限制 */
    public Set<Integer> visibleUserIds(AdminContext ctx) {
        if (ctx.isSuperadmin()) {
            return null;
        }
        List<AdminUserEntity> all = userDao.selectList(null);
        return collectSubtree(ctx.getUser().getId(), indexByParent(all));
    }

    /** null = 不限制 */
    public Set<String> allowedAppids(AdminContext ctx) {
        Set<Integer> owners = visibleUserIds(ctx);
        if (owners == null) {
            return null;
        }
        if (owners.isEmpty()) {
            return Collections.emptySet();
        }
        List<AppidEntity> rows = appidDao.selectList(new QueryWrapper<AppidEntity>().in("userid", owners));
        Set<String> out = new HashSet<>();
        for (AppidEntity a : rows) {
            if (a.getAppid() != null && !a.getAppid().isBlank()) {
                out.add(a.getAppid().trim().toLowerCase());
            }
        }
        return out;
    }

    public boolean deviceVisible(AdminContext ctx, String appId) {
        Set<String> allowed = allowedAppids(ctx);
        if (allowed == null) {
            return true;
        }
        String aid = appId == null ? "" : appId.trim().toLowerCase();
        return !aid.isEmpty() && allowed.contains(aid);
    }

    public boolean rowVisible(AdminContext ctx, String appId, Integer userid) {
        Set<String> allowed = allowedAppids(ctx);
        Set<Integer> users = visibleUserIds(ctx);
        if (allowed == null && users == null) {
            return true;
        }
        String aid = appId == null ? "" : appId.trim().toLowerCase();
        if (!aid.isEmpty() && allowed != null && allowed.contains(aid)) {
            return true;
        }
        if (aid.isEmpty() && users != null && userid != null && users.contains(userid)) {
            return true;
        }
        return allowed == null && users == null;
    }

    public Map<String, String> ownerLabelsByAppid(Set<String> appids) {
        Map<String, String> out = new HashMap<>();
        if (appids == null || appids.isEmpty()) {
            return out;
        }
        List<AppidEntity> apps = appidDao.selectList(null);
        List<AdminUserEntity> users = userDao.selectList(null);
        Map<Integer, AdminUserEntity> byId = users.stream().collect(Collectors.toMap(AdminUserEntity::getId, u -> u, (a, b) -> a));
        Map<Integer, String> roleNames = new HashMap<>();
        for (AdminRoleEntity r : roleDao.selectList(null)) {
            roleNames.put(r.getId(), r.getName());
        }
        for (AppidEntity a : apps) {
            String key = a.getAppid() == null ? "" : a.getAppid().trim().toLowerCase();
            if (!appids.contains(key)) {
                continue;
            }
            AdminUserEntity u = byId.get(a.getUserid());
            if (u == null) {
                out.put(key, "—");
                continue;
            }
            String name = (u.getDisplayName() == null || u.getDisplayName().isBlank()) ? u.getUsername() : u.getDisplayName();
            out.put(key, name + "（" + u.getUsername() + "）");
        }
        return out;
    }

    public Map<String, String> appnamesByAppid(Set<String> appids) {
        Map<String, String> out = new HashMap<>();
        if (appids == null || appids.isEmpty()) {
            return out;
        }
        for (AppidEntity a : appidDao.selectList(null)) {
            String key = a.getAppid() == null ? "" : a.getAppid().trim().toLowerCase();
            if (appids.contains(key)) {
                out.put(key, a.getAppname());
            }
        }
        return out;
    }

    public Set<String> ownerFilterAppids(AdminContext ctx, Integer agentId, Integer channelId, Integer salesId) {
        Integer target = salesId != null && salesId > 0 ? salesId
                : channelId != null && channelId > 0 ? channelId
                : agentId != null && agentId > 0 ? agentId : null;
        if (target == null) {
            return null;
        }
        List<AdminUserEntity> users = allUsers();
        Set<Integer> visible = visibleUserIds(ctx);
        if (visible != null && !visible.contains(target)) {
            return Collections.emptySet();
        }
        Map<Integer, List<AdminUserEntity>> byParent = indexByParent(users);
        Set<Integer> owners;
        if (salesId != null && salesId > 0) {
            owners = new HashSet<>();
            owners.add(salesId);
        } else {
            owners = collectSubtree(target, byParent);
        }
        if (visible != null) {
            owners.retainAll(visible);
        }
        if (owners.isEmpty()) {
            return Collections.emptySet();
        }
        List<AppidEntity> rows = appidDao.selectList(new QueryWrapper<AppidEntity>().in("userid", owners));
        Set<String> out = new HashSet<>();
        for (AppidEntity a : rows) {
            if (a.getAppid() != null && !a.getAppid().isBlank()) {
                out.add(a.getAppid().trim().toLowerCase());
            }
        }
        return out;
    }

    public Map<String, Object> filterOwnerHierarchy(AdminContext ctx) {
        List<AdminUserEntity> users = allUsers();
        Set<Integer> visible = visibleUserIds(ctx);
        List<Map<String, Object>> agents = new ArrayList<>();
        List<Map<String, Object>> channels = new ArrayList<>();
        List<Map<String, Object>> sales = new ArrayList<>();
        for (AdminUserEntity u : users) {
            if (visible != null && !visible.contains(u.getId())) {
                continue;
            }
            if (u.getStatus() == null || u.getStatus() != 1) {
                continue;
            }
            String code = roleCode(u);
            if (!"agent".equals(code) && !"channel".equals(code) && !"salesman".equals(code)) {
                continue;
            }
            Map<String, Object> node = new java.util.LinkedHashMap<>();
            node.put("id", u.getId());
            node.put("username", u.getUsername());
            String name = (u.getDisplayName() == null || u.getDisplayName().isBlank()) ? u.getUsername() : u.getDisplayName();
            node.put("display_name", name);
            node.put("displayName", name);
            node.put("label", name.equals(u.getUsername()) ? u.getUsername() : name + "（" + u.getUsername() + "）");
            node.put("role_code", code);
            node.put("parent_id", u.getParentId());
            node.put("parentId", u.getParentId());
            if ("agent".equals(code)) {
                agents.add(node);
            } else if ("channel".equals(code)) {
                channels.add(node);
            } else {
                sales.add(node);
            }
        }
        Map<String, Object> h = new java.util.LinkedHashMap<>();
        h.put("agents", agents);
        h.put("channels", channels);
        h.put("sales", sales);
        return h;
    }

    public Integer useridOfAppid(String appId) {
        String aid = appId == null ? "" : appId.trim().toLowerCase();
        if (aid.isEmpty()) {
            return 0;
        }
        AppidEntity app = appidDao.selectOne(new QueryWrapper<AppidEntity>().eq("appid", aid).last("LIMIT 1"));
        return app == null || app.getUserid() == null ? 0 : app.getUserid();
    }

    public AdminUserEntity walkToAgent(AdminUserEntity owner) {
        if (owner == null) {
            return null;
        }
        List<AdminUserEntity> users = allUsers();
        Map<Integer, AdminUserEntity> byId = users.stream()
                .collect(Collectors.toMap(AdminUserEntity::getId, u -> u, (a, b) -> a));
        AdminUserEntity cur = owner;
        java.util.Set<Integer> seen = new HashSet<>();
        while (cur != null && cur.getId() != null && seen.add(cur.getId())) {
            String code = roleCode(cur);
            if ("agent".equals(code)) {
                return cur;
            }
            if ("superadmin".equalsIgnoreCase(code)) {
                return null;
            }
            cur = cur.getParentId() == null ? null : byId.get(cur.getParentId());
        }
        return null;
    }

    public List<AdminUserEntity> allUsers() {
        return userDao.selectList(new QueryWrapper<AdminUserEntity>().orderByAsc("id"));
    }

    public Map<Integer, List<AdminUserEntity>> indexByParent(List<AdminUserEntity> users) {
        Map<Integer, List<AdminUserEntity>> map = new HashMap<>();
        for (AdminUserEntity u : users) {
            map.computeIfAbsent(u.getParentId(), k -> new ArrayList<>()).add(u);
        }
        return map;
    }

    public Set<Integer> collectSubtree(Integer rootId, Map<Integer, List<AdminUserEntity>> byParent) {
        Set<Integer> out = new HashSet<>();
        walk(rootId, byParent, out);
        return out;
    }

    private void walk(Integer id, Map<Integer, List<AdminUserEntity>> byParent, Set<Integer> out) {
        if (id == null || !out.add(id)) {
            return;
        }
        List<AdminUserEntity> kids = byParent.get(id);
        if (kids == null) {
            return;
        }
        for (AdminUserEntity k : kids) {
            walk(k.getId(), byParent, out);
        }
    }
}
