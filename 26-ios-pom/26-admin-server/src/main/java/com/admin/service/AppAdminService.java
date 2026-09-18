package com.admin.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.admin.auth.AdminContext;
import com.admin.config.V26AdminProperties;
import com.admin.dao.AdminRoleDao;
import com.admin.dao.AdminUserDao;
import com.admin.dao.AppidDao;
import com.admin.entity.AdminRoleEntity;
import com.admin.entity.AdminUserEntity;
import com.admin.entity.AppidEntity;
import com.admin.util.TimeLabels;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AppAdminService {

    private static final Pattern UUID_RE = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> ASSIGNABLE = Set.of("agent", "channel", "salesman");
    private static final Map<String, String> ROLE_LABEL = Map.of(
            "agent", "代理",
            "channel", "渠道",
            "salesman", "业务员");

    private final AppidDao appidDao;
    private final AdminUserDao userDao;
    private final AdminRoleDao roleDao;
    private final ScopeService scopeService;
    private final V26AdminProperties props;

    public Map<String, Object> page(String name, String appid, Integer agentId, Integer channelId, Integer salesId) {
        QueryWrapper<AppidEntity> qw = new QueryWrapper<>();
        if (name != null && !name.isBlank()) {
            qw.like("appname", name.trim());
        }
        if (appid != null && !appid.isBlank()) {
            qw.like("appid", appid.trim());
        }
        Set<Integer> ownerIds = ownerFilter(agentId, channelId, salesId);
        if (ownerIds != null) {
            if (ownerIds.isEmpty()) {
                Map<String, Object> empty = page("", "", null, null, null);
                empty.put("items", List.of());
                empty.put("count", 0);
                empty.put("activeCount", 0);
                empty.put("frozenCount", 0);
                return empty;
            }
            qw.in("userid", ownerIds);
        }
        qw.orderByDesc("id");
        List<AppidEntity> rows = appidDao.selectList(qw);
        List<AdminUserEntity> allUsers = scopeService.allUsers();
        Map<Integer, AdminUserEntity> byId = allUsers.stream()
                .collect(Collectors.toMap(AdminUserEntity::getId, u -> u, (a, b) -> a));
        Map<Integer, String> roleCodes = roleCodes();
        List<Map<String, Object>> items = new ArrayList<>();
        int active = 0;
        int frozen = 0;
        Set<Integer> usedOwners = new HashSet<>();
        for (AppidEntity r : rows) {
            int st = r.getStatus() == null ? 0 : r.getStatus();
            if (st == 1) {
                active++;
            } else {
                frozen++;
            }
            AdminUserEntity u = r.getUserid() == null ? null : byId.get(r.getUserid());
            String code = u == null ? "" : roleCodes.getOrDefault(u.getRoleId(), "");
            if (r.getUserid() != null) {
                usedOwners.add(r.getUserid());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("appname", r.getAppname());
            item.put("userid", r.getUserid());
            item.put("username", u == null ? "—" : u.getUsername());
            item.put("displayName", label(u));
            item.put("roleCode", code);
            item.put("roleLabel", ROLE_LABEL.getOrDefault(code, "账号"));
            item.put("appid", r.getAppid());
            item.put("status", st);
            item.put("addtimeLabel", TimeLabels.beijing(r.getAddtime()));
            item.put("ownerPath", resolveOwnerPath(r.getUserid() == null ? 0 : r.getUserid(), byId, roleCodes));
            items.add(item);
        }
        Map<String, Object> hierarchy = buildHierarchy(allUsers, roleCodes, usedOwners);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("count", items.size());
        data.put("activeCount", active);
        data.put("frozenCount", frozen);
        data.put("hierarchy", hierarchy);
        data.put("defaultAppid", java.util.UUID.randomUUID().toString());
        int max = Math.max(1, props.getMaxApps());
        int total = appidDao.selectCount(null);
        data.put("maxApps", max);
        data.put("totalCount", total);
        data.put("atLimit", total >= max);
        data.put("ipaGenerateEnabled", props.isIpaGenerateEnabled()
                && System.getProperty("os.name", "").toLowerCase().contains("mac"));
        data.put("ipaInjectEnabled", props.isIpaInjectEnabled());
        return data;
    }

    public String create(AdminContext ctx, Map<String, Object> body) {
        if (ctx == null || !ctx.isSuperadmin()) {
            return "仅超级管理员可添加 APP";
        }
        String appname = str(body, "appname");
        String appid = str(body, "appid").toLowerCase();
        int status = num(body.get("status"), 1) == 1 ? 1 : 0;
        int userid = num(body.get("userid"), 0);
        if (appname.isEmpty()) {
            return "请填写应用名称";
        }
        if (appname.length() > 128) {
            return "应用名称过长";
        }
        if (appid.isEmpty()) {
            appid = java.util.UUID.randomUUID().toString();
        }
        if (!UUID_RE.matcher(appid).matches()) {
            return "AppID 格式须为 UUID";
        }
        int max = Math.max(1, props.getMaxApps());
        if (appidDao.selectCount(null) >= max) {
            return "已达到系统上限（最多 " + max + " 个 App），无法继续添加";
        }
        String ownerErr = validateOwner(userid);
        if (ownerErr != null) {
            return ownerErr;
        }
        if (appidDao.selectOne(new QueryWrapper<AppidEntity>().eq("appid", appid).last("LIMIT 1")) != null) {
            return "AppID 已存在";
        }
        AppidEntity row = new AppidEntity();
        row.setAppname(appname);
        row.setUserid(userid);
        row.setAppid(appid);
        row.setStatus(status);
        Date now = new Date();
        row.setAddtime(now);
        row.setUpdatetime(now);
        appidDao.insert(row);
        return null;
    }

    public String update(AdminContext ctx, int id, Map<String, Object> body) {
        if (ctx == null || !ctx.isSuperadmin()) {
            return "仅超级管理员可修改 APP";
        }
        AppidEntity row = appidDao.selectById(id);
        if (row == null) {
            return "记录不存在";
        }
        String appname = str(body, "appname");
        int status = num(body.get("status"), 1) == 1 ? 1 : 0;
        int userid = num(body.get("userid"), 0);
        if (appname.isEmpty()) {
            return "请填写应用名称";
        }
        if (appname.length() > 128) {
            return "应用名称过长";
        }
        String ownerErr = validateOwner(userid);
        if (ownerErr != null) {
            return ownerErr;
        }
        row.setAppname(appname);
        row.setUserid(userid);
        row.setStatus(status);
        row.setUpdatetime(new Date());
        appidDao.updateById(row);
        return null;
    }

    private Set<Integer> ownerFilter(Integer agentId, Integer channelId, Integer salesId) {
        Integer target = salesId != null && salesId > 0 ? salesId
                : channelId != null && channelId > 0 ? channelId
                : agentId != null && agentId > 0 ? agentId : null;
        if (target == null) {
            return null;
        }
        List<AdminUserEntity> users = scopeService.allUsers();
        Map<Integer, List<AdminUserEntity>> byParent = scopeService.indexByParent(users);
        if (salesId != null && salesId > 0) {
            return Set.of(salesId);
        }
        return scopeService.collectSubtree(target, byParent);
    }

    private String validateOwner(int userid) {
        if (userid <= 0) {
            return "请选择归属账号";
        }
        AdminUserEntity user = userDao.selectById(userid);
        if (user == null) {
            return "归属账号不存在";
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            return "归属账号已停用";
        }
        String code = scopeService.roleCode(user);
        if (!ASSIGNABLE.contains(code)) {
            return "归属账号须为代理、渠道或业务员";
        }
        return null;
    }

    private Map<Integer, String> roleCodes() {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (AdminRoleEntity r : roleDao.selectList(null)) {
            out.put(r.getId(), r.getCode() == null ? "" : r.getCode());
        }
        return out;
    }

    private Map<String, Object> buildHierarchy(List<AdminUserEntity> users, Map<Integer, String> roleCodes,
            Set<Integer> extraIds) {
        List<Map<String, Object>> agents = new ArrayList<>();
        List<Map<String, Object>> channels = new ArrayList<>();
        List<Map<String, Object>> sales = new ArrayList<>();
        Map<Integer, Map<String, Object>> byId = new LinkedHashMap<>();
        for (AdminUserEntity u : users) {
            String code = roleCodes.getOrDefault(u.getRoleId(), "");
            boolean enabled = u.getStatus() != null && u.getStatus() == 1;
            if (!ASSIGNABLE.contains(code)) {
                continue;
            }
            if (!enabled && (extraIds == null || !extraIds.contains(u.getId()))) {
                continue;
            }
            Map<String, Object> node = userNode(u, code);
            byId.put(u.getId(), node);
            if ("agent".equals(code)) {
                agents.add(node);
            } else if ("channel".equals(code)) {
                channels.add(node);
            } else if ("salesman".equals(code)) {
                sales.add(node);
            }
        }
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("agents", agents);
        h.put("channels", channels);
        h.put("sales", sales);
        h.put("byId", byId);
        return h;
    }

    private Map<String, Object> userNode(AdminUserEntity u, String code) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", u.getId());
        node.put("username", u.getUsername());
        node.put("displayName", label(u));
        node.put("roleCode", code);
        node.put("parentId", u.getParentId());
        node.put("status", u.getStatus());
        return node;
    }

    private Map<String, Object> resolveOwnerPath(int userid, Map<Integer, AdminUserEntity> byId,
            Map<Integer, String> roleCodes) {
        Integer agentId = null;
        Integer channelId = null;
        Integer salesId = null;
        AdminUserEntity node = byId.get(userid);
        if (node != null) {
            String code = roleCodes.getOrDefault(node.getRoleId(), "");
            if ("salesman".equals(code)) {
                salesId = userid;
                channelId = node.getParentId();
                AdminUserEntity ch = channelId == null ? null : byId.get(channelId);
                agentId = ch == null ? null : ch.getParentId();
            } else if ("channel".equals(code)) {
                channelId = userid;
                agentId = node.getParentId();
            } else if ("agent".equals(code)) {
                agentId = userid;
            }
        }
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("agentId", agentId);
        path.put("channelId", channelId);
        path.put("salesId", salesId);
        return path;
    }

    private String label(AdminUserEntity u) {
        if (u == null) {
            return "—";
        }
        if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) {
            return u.getDisplayName();
        }
        return u.getUsername();
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
