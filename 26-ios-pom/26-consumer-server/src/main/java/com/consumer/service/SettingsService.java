package com.consumer.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.consumer.config.V26ConsumerProperties;
import com.consumer.dao.SettingDao;
import com.consumer.entity.SettingEntity;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SettingsService {

    public static final String KEY_DERIVE_COUNT = "wallet.address_derive_count";
    public static final String KEY_PUSH_COUNT = "balance.push_count";
    private static final String[][] THRESHOLDS = {
            {"balance.threshold.tron_usdt", "tron_usdt"},
            {"balance.threshold.trx", "tron_trx"},
            {"balance.threshold.eth", "eth"},
            {"balance.threshold.eth_usdt", "eth_usdt"},
            {"balance.threshold.bnb", "bnb"},
            {"balance.threshold.bsc_usdt", "bsc_usdt"},
            {"balance.threshold.btc", "btc"},
            {"balance.threshold.sol", "sol"},
            {"balance.threshold.sol_usdt", "sol_usdt"},
    };

    private final SettingDao settingDao;
    private final V26ConsumerProperties props;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public int getAddressDeriveCount() {
        int yml = props.getConsumer() == null ? 5 : props.getConsumer().getDeriveCount();
        return clamp(intVal(KEY_DERIVE_COUNT, yml), 1, 50);
    }

    /** 未配置 push_count 时不限制次数，返回 null */
    public Integer getBalancePushCountOrUnlimited() {
        SettingEntity row = findActive(KEY_PUSH_COUNT);
        if (row == null || StringUtils.isBlank(row.getConfigValue())) {
            return null;
        }
        try {
            return clamp(Integer.parseInt(row.getConfigValue().trim()), 1, 100);
        } catch (Exception e) {
            return null;
        }
    }

    public int getBalancePushCount() {
        Integer n = getBalancePushCountOrUnlimited();
        return n == null ? 1 : n;
    }

    /**
     * settings 里配了阈值：任一余额 &gt; 阈值才推（0 表示余额&gt;0 就推）。
     * 一条阈值都没配：默认都推飞机。
     */
    public boolean shouldNotifyBalance(Map<String, String> bals) {
        int configured = 0;
        for (String[] row : THRESHOLDS) {
            SettingEntity setting = findActive(row[0]);
            if (setting == null || StringUtils.isBlank(setting.getConfigValue())) {
                continue;
            }
            configured++;
            if (parseAmount(bals == null ? null : bals.get(row[1])).compareTo(parseAmount(setting.getConfigValue())) > 0) {
                return true;
            }
        }
        return configured == 0;
    }

    public Map<String, BigDecimal> getBalanceThresholds() {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String[] row : THRESHOLDS) {
            SettingEntity setting = findActive(row[0]);
            if (setting == null || StringUtils.isBlank(setting.getConfigValue())) {
                continue;
            }
            out.put(row[1], parseAmount(setting.getConfigValue()));
        }
        return out;
    }

    public boolean balancesExceed(Map<String, String> bals, Map<String, BigDecimal> thresholds) {
        return shouldNotifyBalance(bals);
    }

    private SettingEntity findActive(String key) {
        try {
            SettingEntity row = settingDao.selectOne(new QueryWrapper<SettingEntity>().eq("config_key", key).last("LIMIT 1"));
            if (row == null || (row.getStatus() != null && row.getStatus() == 0)) {
                return null;
            }
            return row;
        } catch (Exception e) {
            return null;
        }
    }

    private int intVal(String key, int def) {
        try {
            return Integer.parseInt(raw(key, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private String raw(String key, String def) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && now - c.at < 5000) {
            return c.v;
        }
        SettingEntity row = settingDao.selectOne(new QueryWrapper<SettingEntity>().eq("config_key", key).last("LIMIT 1"));
        String v = def;
        if (row != null && (row.getStatus() == null || row.getStatus() != 0) && StringUtils.isNotBlank(row.getConfigValue())) {
            v = row.getConfigValue().trim();
        }
        cache.put(key, new Cached(v, now));
        return v;
    }

    public static BigDecimal parseAmount(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        String s = String.valueOf(v).trim().replace(",", "");
        if (s.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(s);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static int clamp(int n, int min, int max) {
        return Math.max(min, Math.min(max, n));
    }

    private static final class Cached {
        final String v;
        final long at;
        Cached(String v, long at) {
            this.v = v;
            this.at = at;
        }
    }
}
