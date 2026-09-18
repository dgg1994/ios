package com.send.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.send.dao.TragetDao;
import com.send.entity.TragetEntity;

@Service
public class TragetConfigService {

    private static final List<String> DEFAULT_PATHS = Collections.singletonList("Documents");
    private static final long CACHE_TTL_MS = 5_000L;

    @Autowired
    private TragetDao tragetDao;

    private volatile Map<String, List<String>> cachedMap = Collections.emptyMap();
    private volatile long cachedAt;

    public Map<String, List<String>> fetchEnabledBundleIdsMap() {
        long now = System.currentTimeMillis();
        Map<String, List<String>> hit = cachedMap;
        if (!hit.isEmpty() && now - cachedAt < CACHE_TTL_MS) {
            return hit;
        }
        synchronized (this) {
            if (!cachedMap.isEmpty() && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
                return cachedMap;
            }
            List<TragetEntity> rows = tragetDao.selectList(
                    new QueryWrapper<TragetEntity>().eq("status", 1).orderByAsc("id"));
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (TragetEntity row : rows) {
                out.put(row.getBundleId(), normalizePaths(row.getPaths()));
            }
            cachedMap = Collections.unmodifiableMap(out);
            cachedAt = System.currentTimeMillis();
            return cachedMap;
        }
    }

    private List<String> normalizePaths(String raw) {
        if (StringUtils.isBlank(raw)) {
            return new ArrayList<>(DEFAULT_PATHS);
        }
        List<String> parts = new ArrayList<>();
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            try {
                JSONArray arr = JSON.parseArray(trimmed);
                for (int i = 0; i < arr.size(); i++) {
                    parts.add(String.valueOf(arr.get(i)).trim());
                }
            } catch (Exception e) {
                return new ArrayList<>(DEFAULT_PATHS);
            }
        } else {
            for (String p : trimmed.replace("\n", ",").split(",")) {
                parts.add(p.trim());
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (StringUtils.isNotBlank(p) && seen.add(p)) {
                out.add(p);
            }
        }
        return out.isEmpty() ? new ArrayList<>(DEFAULT_PATHS) : out;
    }
}
