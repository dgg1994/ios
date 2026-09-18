package com.admin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.admin.dao.TragetDao;
import com.admin.entity.TragetEntity;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BundleAdminService {

    private static final Pattern BUNDLE_RE = Pattern.compile("^[A-Za-z0-9._-]+$");

    private final TragetDao tragetDao;

    public Map<String, Object> list() {
        List<TragetEntity> rows = tragetDao.selectList(new QueryWrapper<TragetEntity>().eq("status", 1).orderByAsc("id"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (TragetEntity t : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("appName", t.getAppName());
            m.put("bundleId", t.getBundleId());
            m.put("paths", parsePaths(t.getPaths()));
            m.put("status", t.getStatus());
            items.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("count", items.size());
        return data;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> save(Object payload) {
        List<Map<String, Object>> items;
        if (payload instanceof List) {
            items = (List<Map<String, Object>>) payload;
        } else if (payload instanceof Map) {
            Object inner = ((Map<?, ?>) payload).get("items");
            if (!(inner instanceof List)) {
                return fail("保存数据格式错误");
            }
            items = (List<Map<String, Object>>) inner;
        } else {
            return fail("保存数据格式错误");
        }
        List<TragetEntity> existing = tragetDao.selectList(null);
        Map<String, TragetEntity> byBundle = new LinkedHashMap<>();
        for (TragetEntity e : existing) {
            e.setStatus(0);
            tragetDao.updateById(e);
            if (e.getBundleId() != null && !e.getBundleId().isBlank()) {
                byBundle.putIfAbsent(e.getBundleId().trim(), e);
            }
        }
        int n = 0;
        for (Map<String, Object> raw : items) {
            String appName = str(raw.get("appName"));
            String bundleId = str(raw.get("bundleId"));
            if (appName.isEmpty() || bundleId.isEmpty() || !BUNDLE_RE.matcher(bundleId).matches()) {
                continue;
            }
            Object paths = raw.get("paths");
            String pathJson = paths instanceof List ? JSON.toJSONString(paths)
                    : (paths == null ? "[\"Documents\"]" : String.valueOf(paths));
            TragetEntity row = byBundle.get(bundleId);
            if (row == null) {
                row = new TragetEntity();
                row.setBundleId(bundleId);
                row.setAppName(appName);
                row.setPaths(pathJson);
                row.setStatus(1);
                tragetDao.insert(row);
                byBundle.put(bundleId, row);
            } else {
                row.setAppName(appName);
                row.setPaths(pathJson);
                row.setStatus(1);
                tragetDao.updateById(row);
            }
            n++;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("saved", n);
        return data;
    }

    private List<String> parsePaths(String raw) {
        try {
            return JSON.parseArray(raw, String.class);
        } catch (Exception e) {
            return List.of("Documents");
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private Map<String, Object> fail(String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        return m;
    }
}
