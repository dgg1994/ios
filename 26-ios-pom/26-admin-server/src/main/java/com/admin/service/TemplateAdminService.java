package com.admin.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.admin.dao.TgMessageTemplateDao;
import com.admin.entity.TgMessageTemplateEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TemplateAdminService {

    private final TgMessageTemplateDao templateDao;

    public Map<String, Object> list() {
        List<TgMessageTemplateEntity> rows = templateDao.selectList(
                new QueryWrapper<TgMessageTemplateEntity>().orderByAsc("id"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (TgMessageTemplateEntity t : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("code", t.getCode());
            m.put("name", t.getName());
            m.put("body", t.getBody());
            m.put("variables", t.getVariables());
            m.put("remark", t.getRemark());
            m.put("status", t.getStatus());
            items.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("count", items.size());
        return data;
    }

    public String save(int id, Map<String, Object> body) {
        TgMessageTemplateEntity row = templateDao.selectById(id);
        if (row == null) {
            return "模版不存在";
        }
        String text = body == null || body.get("body") == null ? "" : String.valueOf(body.get("body"));
        text = text.replaceAll("^\\n+|\\n+$", "");
        if (text.trim().isEmpty()) {
            return "模版内容不能为空";
        }
        if (text.length() > 8000) {
            return "模版过长";
        }
        row.setBody(text);
        if (body != null && body.get("name") != null) {
            String name = String.valueOf(body.get("name")).trim();
            if (!name.isEmpty()) {
                row.setName(name.length() > 64 ? name.substring(0, 64) : name);
            }
        }
        if (body != null && body.get("status") != null) {
            String st = String.valueOf(body.get("status")).trim();
            row.setStatus("1".equals(st) ? 1 : 0);
        }
        row.setUpdatedAt(new Date());
        templateDao.updateById(row);
        return null;
    }
}
