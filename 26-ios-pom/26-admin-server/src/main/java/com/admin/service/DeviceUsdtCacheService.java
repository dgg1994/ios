package com.admin.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import com.admin.dao.AddressDao;
import com.admin.dao.DeviceDao;
import com.admin.dao.MnemonicDao;
import com.admin.entity.AddressEntity;
import com.admin.entity.DeviceEntity;
import com.admin.entity.MnemonicEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 对齐 Python device_usdt_cache：用 address.usdt_bal 刷新 devices.wallet_usdt_max。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceUsdtCacheService {

    private final DeviceDao deviceDao;
    private final MnemonicDao mnemonicDao;
    private final AddressDao addressDao;

    public Double refreshFromDb(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        String did = deviceId.trim();
        DeviceEntity device = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", did).last("LIMIT 1"));
        if (device == null) {
            device = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", did.toLowerCase()).last("LIMIT 1"));
        }
        if (device == null) {
            return null;
        }
        did = device.getDeviceId();
        BigDecimal dbMax = maxUsdtFromAddresses(did);
        BigDecimal mx = dbMax;
        if (device.getWalletUsdtMax() != null) {
            try {
                mx = mx.max(BigDecimal.valueOf(device.getWalletUsdtMax()));
            } catch (Exception ignored) {
            }
        }
        // 单地址刷新时也可 bump：取当前值与新值较大者
        double v = mx.doubleValue();
        device.setWalletUsdtMax(v);
        deviceDao.updateById(device);
        return v;
    }

    /** 用单笔 USDT 余额抬高缓存（刷新余额后调用）。 */
    public void bumpWithUsdt(String deviceId, String usdtBal) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        BigDecimal u = parseAmount(usdtBal);
        if (u.compareTo(BigDecimal.ZERO) <= 0) {
            refreshFromDb(deviceId);
            return;
        }
        DeviceEntity device = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", deviceId.trim()).last("LIMIT 1"));
        if (device == null) {
            device = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", deviceId.trim().toLowerCase()).last("LIMIT 1"));
        }
        if (device == null) {
            return;
        }
        double prev = device.getWalletUsdtMax() == null ? 0d : device.getWalletUsdtMax();
        double next = Math.max(prev, u.doubleValue());
        if (device.getWalletUsdtMax() != null && Math.abs(prev - next) < 1e-12) {
            return;
        }
        device.setWalletUsdtMax(next);
        deviceDao.updateById(device);
    }

    /** 列表筛 USDT&gt;10 前，给尚未缓存的设备补一次库内最大值。 */
    public int backfillMissing(int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        List<DeviceEntity> rows = deviceDao.selectList(new QueryWrapper<DeviceEntity>()
                .isNull("wallet_usdt_max")
                .last("LIMIT " + lim));
        int n = 0;
        if (rows == null) {
            return 0;
        }
        for (DeviceEntity d : rows) {
            try {
                if (refreshFromDb(d.getDeviceId()) != null) {
                    n++;
                }
            } catch (Exception e) {
                log.warn("backfill wallet_usdt_max fail device={}: {}", d.getDeviceId(), e.toString());
            }
        }
        return n;
    }

    private BigDecimal maxUsdtFromAddresses(String deviceId) {
        List<MnemonicEntity> mns = mnemonicDao.selectList(new QueryWrapper<MnemonicEntity>().eq("deviceId", deviceId));
        if (mns == null || mns.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal mx = BigDecimal.ZERO;
        for (MnemonicEntity m : mns) {
            if (m.getId() == null) {
                continue;
            }
            List<AddressEntity> addrs = addressDao.selectList(new QueryWrapper<AddressEntity>().eq("mnemonic_id", m.getId()));
            if (addrs == null) {
                continue;
            }
            for (AddressEntity a : addrs) {
                BigDecimal u = parseAmount(a.getUsdtBal());
                if (u.compareTo(mx) > 0) {
                    mx = u;
                }
            }
        }
        return mx;
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
