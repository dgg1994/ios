package com.send.service;

import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.send.dao.SettingDao;
import com.send.entity.SettingEntity;

@Service
public class SettingsService {

    public static final String KEY_CLIENT_DEBUG = "client.debug";
    public static final String KEY_DO_KEYCHAIN = "client.do_keychain";
    public static final String KEY_TG_BIND_SUCCESS = "tg.bind_success_enabled";

    private static final long CACHE_TTL_MS = 5_000L;

    @Autowired
    private SettingDao settingDao;

    private final ConcurrentHashMap<String, CachedBool> boolCache = new ConcurrentHashMap<>();

    public boolean getClientDebug() {
        return getBoolCached(KEY_CLIENT_DEBUG, false);
    }

    public boolean getDoKeychain() {
        return getBoolCached(KEY_DO_KEYCHAIN, true);
    }

    /** 新设备注册成功是否推飞机（模版 device_new）。 */
    public boolean getTgBindSuccessEnabled() {
        return getBoolCached(KEY_TG_BIND_SUCCESS, true);
    }

    private boolean getBoolCached(String key, boolean defaultValue) {
        long now = System.currentTimeMillis();
        CachedBool c = boolCache.get(key);
        if (c != null && now - c.at < CACHE_TTL_MS) {
            return c.value;
        }
        boolean v = getBool(key, defaultValue);
        boolCache.put(key, new CachedBool(v, now));
        return v;
    }

    private boolean getBool(String key, boolean defaultValue) {
        SettingEntity row = settingDao.selectOne(
                new QueryWrapper<SettingEntity>().eq("config_key", key).last("LIMIT 1"));
        if (row == null || StringUtils.isBlank(row.getConfigValue())) {
            return defaultValue;
        }
        if (row.getStatus() != null && row.getStatus() == 0) {
            return defaultValue;
        }
        String v = row.getConfigValue().trim();
        String type = StringUtils.defaultIfBlank(row.getValueType(), "string").toLowerCase();
        if ("bool".equals(type) || "boolean".equals(type)) {
            return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
        }
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static final class CachedBool {
        final boolean value;
        final long at;

        CachedBool(boolean value, long at) {
            this.value = value;
            this.at = at;
        }
    }
}
