package com.admin.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.admin.auth.AdminContext;
import com.admin.dao.AdminPermissionDao;
import com.admin.dao.AdminRoleDao;
import com.admin.dao.AdminRolePermissionDao;
import com.admin.entity.AdminPermissionEntity;
import com.admin.entity.AdminRoleEntity;
import com.admin.entity.AdminRolePermissionEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PermissionAdminService {

    private static final Set<String> SUPERADMIN_ONLY = Set.of("menu.bundles", "menu.app_manager", "menu.permissions");

    private final AdminRoleDao roleDao;
    private final AdminPermissionDao permDao;
    private final AdminRolePermissionDao rolePermDao;

    public Map<String, Object> page() {
        List<AdminRoleEntity> roles = roleDao.selectList(new QueryWrapper<AdminRoleEntity>().orderByAsc("id"));
        List<AdminPermissionEntity> perms = permDao.selectList(new QueryWrapper<AdminPermissionEntity>()
                .eq("perm_type", "menu")
                .eq("status", 1)
                .orderByAsc("sort")
                .orderByAsc("id"));
        List<AdminRolePermissionEntity> links = rolePermDao.selectList(null);
        Map<Integer, List<Integer>> rolePermIds = new LinkedHashMap<>();
        for (AdminRoleEntity r : roles) {
            rolePermIds.put(r.getId(), new ArrayList<>());
        }
        for (AdminRolePermissionEntity link : links) {
            rolePermIds.computeIfAbsent(link.getRoleId(), k -> new ArrayList<>()).add(link.getPermissionId());
        }
        List<Map<String, Object>> roleItems = new ArrayList<>();
        for (AdminRoleEntity r : roles) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("code", r.getCode());
            m.put("name", r.getName());
            m.put("remark", r.getRemark());
            m.put("status", r.getStatus());
            m.put("superadmin", "superadmin".equalsIgnoreCase(r.getCode()));
            m.put("permissionIds", rolePermIds.getOrDefault(r.getId(), List.of()));
            roleItems.add(m);
        }
        List<Map<String, Object>> permItems = perms.stream().map(this::permMap).collect(Collectors.toList());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roles", roleItems);
        data.put("permissions", permItems);
        data.put("lockedCodes", SUPERADMIN_ONLY);
        return data;
    }

    @Transactional
    public String save(AdminContext ctx, Map<String, Object> body) {
        if (ctx == null || !ctx.isSuperadmin()) {
            return "仅总后台可修改权限配置";
        }
        Map<Integer, List<Integer>> bindings = parseBindings(body == null ? null : body.get("bindings"));
        List<AdminRoleEntity> roles = roleDao.selectList(new QueryWrapper<AdminRoleEntity>().orderByAsc("id"));
        if (roles.isEmpty()) {
            return "角色数据为空";
        }
        List<AdminPermissionEntity> menus = permDao.selectList(new QueryWrapper<AdminPermissionEntity>()
                .eq("perm_type", "menu")
                .eq("status", 1));
        Set<Integer> allMenuIds = menus.stream().map(AdminPermissionEntity::getId).collect(Collectors.toSet());
        Set<Integer> lockedIds = menus.stream()
                .filter(p -> SUPERADMIN_ONLY.contains(p.getCode()))
                .map(AdminPermissionEntity::getId)
                .collect(Collectors.toSet());
        Set<Integer> roleIds = roles.stream().map(AdminRoleEntity::getId).collect(Collectors.toSet());
        for (Integer rid : bindings.keySet()) {
            if (!roleIds.contains(rid)) {
                return "存在无效角色";
            }
        }
        for (AdminRoleEntity role : roles) {
            Set<Integer> desired;
            if ("superadmin".equalsIgnoreCase(role.getCode())) {
                desired = new HashSet<>(allMenuIds);
            } else {
                desired = new HashSet<>(bindings.getOrDefault(role.getId(), List.of()));
                desired.retainAll(allMenuIds);
                desired.removeAll(lockedIds);
            }
            List<AdminRolePermissionEntity> existing = rolePermDao.selectList(
                    new QueryWrapper<AdminRolePermissionEntity>().eq("role_id", role.getId()));
            Set<Integer> existingMenu = existing.stream()
                    .map(AdminRolePermissionEntity::getPermissionId)
                    .filter(allMenuIds::contains)
                    .collect(Collectors.toSet());
            Set<Integer> toDel = new HashSet<>(existingMenu);
            toDel.removeAll(desired);
            Set<Integer> toAdd = new HashSet<>(desired);
            toAdd.removeAll(existingMenu);
            if (!toDel.isEmpty()) {
                rolePermDao.delete(new QueryWrapper<AdminRolePermissionEntity>()
                        .eq("role_id", role.getId())
                        .in("permission_id", toDel));
            }
            for (Integer pid : toAdd) {
                AdminRolePermissionEntity row = new AdminRolePermissionEntity();
                row.setRoleId(role.getId());
                row.setPermissionId(pid);
                rolePermDao.insert(row);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, List<Integer>> parseBindings(Object raw) {
        Map<Integer, List<Integer>> out = new LinkedHashMap<>();
        if (!(raw instanceof Map)) {
            return out;
        }
        Map<?, ?> map = (Map<?, ?>) raw;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            int rid;
            try {
                rid = Integer.parseInt(String.valueOf(e.getKey()));
            } catch (Exception ex) {
                continue;
            }
            List<Integer> ids = new ArrayList<>();
            Object val = e.getValue();
            if (val instanceof List) {
                for (Object x : (List<Object>) val) {
                    try {
                        ids.add(Integer.parseInt(String.valueOf(x)));
                    } catch (Exception ignored) {
                    }
                }
            }
            out.put(rid, ids);
        }
        return out;
    }

    private Map<String, Object> permMap(AdminPermissionEntity p) {
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
