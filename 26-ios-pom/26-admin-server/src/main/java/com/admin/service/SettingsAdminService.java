package com.admin.service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.admin.dao.SettingDao;
import com.admin.entity.SettingEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SettingsAdminService {

    private final SettingDao settingDao;

    public Map<String, Object> list() {
        List<SettingEntity> rows = settingDao.selectList(new QueryWrapper<SettingEntity>().eq("status", 1).orderByAsc("sort").orderByAsc("id"));
        List<Map<String, Object>> items = rows.stream().map(this::toMap).collect(Collectors.toList());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("count", items.size());
        return data;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> save(Map<String, Object> body) {
        if (body == null) {
            return Map.of("ok", false, "error", "空数据");
        }
        Map<String, Object> values = body;
        if (body.get("items") instanceof Map) {
            values = (Map<String, Object>) body.get("items");
        }
        int n = 0;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if ("items".equals(e.getKey())) {
                continue;
            }
            SettingEntity row = settingDao.selectOne(new QueryWrapper<SettingEntity>().eq("config_key", e.getKey()));
            if (row == null) {
                continue;
            }
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue());
            row.setConfigValue(v);
            row.setUpdatedAt(new Date());
            settingDao.updateById(row);
            n++;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("updated", n);
        return data;
    }

    private Map<String, Object> toMap(SettingEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("configKey", s.getConfigKey());
        m.put("configValue", s.getConfigValue());
        m.put("valueType", s.getValueType());
        m.put("label", s.getLabel());
        m.put("description", s.getDescription());
        m.put("helpText", s.getHelpText());
        m.put("category", s.getCategory());
        m.put("sort", s.getSort());
        return m;
    }
}
