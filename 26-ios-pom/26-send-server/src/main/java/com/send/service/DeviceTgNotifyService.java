package com.send.service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.send.dao.AdminUserDao;
import com.send.dao.AppidDao;
import com.send.dao.TgMessageTemplateDao;
import com.send.entity.AdminUserEntity;
import com.send.entity.AppidEntity;
import com.send.entity.TgMessageTemplateEntity;
import com.send.util.TelegramNotificationUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * 对齐 Python {@code notify_new_device_registered}：新设备注册后推送 device_new 模版。
 * <p>凭据按 appId → admin_user 归属链向上找首个配置了 tg_rob_token + tg_groupid 的账号。
 */
@Slf4j
@Service
public class DeviceTgNotifyService {

    public static final String TEMPLATE_DEVICE_NEW = "device_new";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss");
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private AppidDao appidDao;

    @Autowired
    private AdminUserDao adminUserDao;

    @Autowired
    private TgMessageTemplateDao tgMessageTemplateDao;

    @Autowired
    private TelegramNotificationUtil telegramNotificationUtil;

    /**
     * 异步发送，不阻塞注册 ACK。失败只打日志。
     */
    @Async("databaseOperatePush")
    public void notifyNewDeviceAsync(
            String deviceId,
            String appId,
            String appName,
            String model,
            String hardwareModel,
            String iosVersion,
            String ip) {
        try {
            notifyNewDevice(deviceId, appId, appName, model, hardwareModel, iosVersion, ip);
        } catch (Exception e) {
            log.warn("新设备飞机通知失败 device={}: {}", deviceId, e.toString());
        }
    }

    public boolean notifyNewDevice(
            String deviceId,
            String appId,
            String appName,
            String model,
            String hardwareModel,
            String iosVersion,
            String ip) {
        if (!settingsService.getTgBindSuccessEnabled()) {
            log.info("tg device_new skip: bind_success disabled device={}", deviceId);
            return false;
        }

        String[] creds = resolveTgCredentialsForAppId(appId);
        if (creds == null) {
            log.info("tg device_new skip: no bot/group for appId={} device={}", appId, deviceId);
            return false;
        }
        String token = creds[0];
        String groupId = creds[1];

        TgMessageTemplateEntity tpl = tgMessageTemplateDao.selectOne(
                new QueryWrapper<TgMessageTemplateEntity>()
                        .eq("code", TEMPLATE_DEVICE_NEW)
                        .eq("status", 1)
                        .last("LIMIT 1"));
        if (tpl == null || StringUtils.isBlank(tpl.getBody())) {
            log.warn("tg template missing/disabled: {}", TEMPLATE_DEVICE_NEW);
            return false;
        }

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("group_id", groupId);
        vars.put("owner", ownerChainLabel(appId));
        vars.put("app_name", StringUtils.defaultIfBlank(StringUtils.trimToNull(appName), "—"));
        vars.put("model", formatModelLabel(hardwareModel, model, iosVersion));
        vars.put("device_id", StringUtils.defaultString(deviceId));
        vars.put("ip", StringUtils.defaultIfBlank(StringUtils.trimToNull(ip), "—"));
        vars.put("time", ZonedDateTime.now(BEIJING).format(TIME_FMT));

        String text = renderTemplate(tpl.getBody(), vars);
        telegramNotificationUtil.sendTelegramMsg(text, groupId, token);
        return true;
    }

    /** 返回 [token, groupId]，找不到则 null。 */
    public String[] resolveTgCredentialsForAppId(String appId) {
        String aid = StringUtils.trimToEmpty(appId).toLowerCase();
        if (aid.isEmpty()) {
            return null;
        }
        AppidEntity app = appidDao.selectOne(
                new QueryWrapper<AppidEntity>().eq("appid", aid).last("LIMIT 1"));
        if (app == null || app.getUserid() == null) {
            return null;
        }
        Integer uid = app.getUserid();
        for (int guard = 0; uid != null && uid > 0 && guard < 8; guard++) {
            AdminUserEntity user = adminUserDao.selectById(uid);
            if (user == null) {
                break;
            }
            String token = StringUtils.trimToEmpty(user.getTgRobToken());
            String group = StringUtils.trimToEmpty(user.getTgGroupid());
            if (!token.isEmpty() && !group.isEmpty()) {
                return new String[]{token, group};
            }
            uid = user.getParentId();
        }
        return null;
    }

    private String ownerChainLabel(String appId) {
        String aid = StringUtils.trimToEmpty(appId).toLowerCase();
        if (aid.isEmpty()) {
            return "—";
        }
        AppidEntity app = appidDao.selectOne(
                new QueryWrapper<AppidEntity>().eq("appid", aid).last("LIMIT 1"));
        if (app == null || app.getUserid() == null) {
            return "—";
        }
        List<String> names = new ArrayList<>();
        Integer uid = app.getUserid();
        for (int guard = 0; uid != null && uid > 0 && guard < 8; guard++) {
            AdminUserEntity user = adminUserDao.selectById(uid);
            if (user == null) {
                break;
            }
            String label = StringUtils.defaultIfBlank(
                    StringUtils.trimToNull(user.getDisplayName()),
                    StringUtils.defaultIfBlank(user.getUsername(), "user#" + user.getId()));
            names.add(label);
            uid = user.getParentId();
        }
        if (names.isEmpty()) {
            return "—";
        }
        Collections.reverse(names);
        return String.join(" / ", names);
    }

    static String formatModelLabel(String hardwareModel, String model, String iosVersion) {
        String hw = StringUtils.trimToEmpty(hardwareModel);
        String md = StringUtils.trimToEmpty(model);
        String base = !hw.isEmpty() ? hw : (!md.isEmpty() ? md : "—");
        String ver = StringUtils.trimToEmpty(iosVersion);
        if (ver.isEmpty()) {
            return base;
        }
        if (ver.toLowerCase().startsWith("ios")) {
            return base + "/" + ver;
        }
        return base + "/" + ver;
    }

    static String renderTemplate(String body, Map<String, String> vars) {
        if (body == null) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(body);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String val = vars.containsKey(key) ? vars.get(key) : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(val == null ? "" : val));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
