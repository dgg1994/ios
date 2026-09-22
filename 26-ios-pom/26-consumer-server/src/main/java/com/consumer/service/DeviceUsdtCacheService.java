package com.consumer.service;

import java.math.BigDecimal;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.consumer.dao.AddressDao;
import com.consumer.dao.DeviceDao;
import com.consumer.dao.MnemonicDao;
import com.consumer.entity.AddressEntity;
import com.consumer.entity.DeviceEntity;
import com.consumer.entity.MnemonicEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 对齐 Python device_usdt_cache：用库内 address.usdt_bal 最大值更新 devices.wallet_usdt_max，
 * 保留历史更大值，供管理端「USDT&gt;10」筛选。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceUsdtCacheService {

    private final DeviceDao deviceDao;
    private final MnemonicDao mnemonicDao;
    private final AddressDao addressDao;

    public Double refreshFromDb(String deviceId) {
        String did = StringUtils.trimToEmpty(deviceId);
        if (did.isEmpty()) {
            return null;
        }
        DeviceEntity device = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", did).last("LIMIT 1"));
        if (device == null && !did.equals(did.toLowerCase())) {
            device = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", did.toLowerCase()).last("LIMIT 1"));
            if (device != null) {
                did = device.getDeviceId();
            }
        }
        if (device == null) {
            return null;
        }
        BigDecimal dbMax = maxUsdtFromAddresses(did);
        BigDecimal mx = dbMax;
        if (device.getWalletUsdtMax() != null) {
            try {
                mx = mx.max(BigDecimal.valueOf(device.getWalletUsdtMax()));
            } catch (Exception ignored) {
            }
        }
        double v = mx.doubleValue();
        if (device.getWalletUsdtMax() != null && Math.abs(device.getWalletUsdtMax() - v) < 1e-12) {
            return device.getWalletUsdtMax();
        }
        device.setWalletUsdtMax(v);
        deviceDao.updateById(device);
        return v;
    }

    /** 包内缓存 USDT 与当前值取较大者。 */
    public void bumpWithPackage(String deviceId, java.math.BigDecimal packageMax) {
        if (deviceId == null || deviceId.isBlank() || packageMax == null || packageMax.signum() <= 0) {
            return;
        }
        String did = deviceId.trim();
        DeviceEntity device = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", did).last("LIMIT 1"));
        if (device == null && !did.equals(did.toLowerCase())) {
            device = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", did.toLowerCase()).last("LIMIT 1"));
        }
        if (device == null) {
            return;
        }
        double prev = device.getWalletUsdtMax() == null ? 0d : device.getWalletUsdtMax();
        double next = Math.max(prev, packageMax.doubleValue());
        if (device.getWalletUsdtMax() != null && Math.abs(prev - next) < 1e-9) {
            return;
        }
        device.setWalletUsdtMax(next);
        deviceDao.updateById(device);
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
            List<AddressEntity> rows = addressDao.selectList(new QueryWrapper<AddressEntity>().eq("mnemonic_id", m.getId()));
            if (rows == null) {
                continue;
            }
            for (AddressEntity a : rows) {
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
