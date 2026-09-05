package com.consume.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.consume.dao.DeviceDao;
import com.consume.entity.DeviceEntity;

/**
 * 设备写入独立短事务：插入成功立即提交，供并发消费线程马上 SELECT 到，
 * 避免「撞唯一键 → 立刻重查仍为空」（对方事务尚未提交）。
 */
@Component
public class DeviceWriteService {

    private static final Logger log = LoggerFactory.getLogger(DeviceWriteService.class);

    @Autowired
    private DeviceDao deviceDao;

    /**
     * 尝试插入；成功返回带 id 的实体。撞唯一键抛出 DuplicateKeyException 由调用方重查。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public DeviceEntity insertNew(DeviceEntity device) {
        deviceDao.insert(device);
        return device;
    }

    /**
     * 按 deviceid 查询（独立只读短事务，能读到其它已提交插入）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public DeviceEntity findByDeviceId(String deviceid) {
        if (deviceid == null || deviceid.isEmpty()) {
            return null;
        }
        return deviceDao.findDeviceId(deviceid);
    }

    /**
     * 插入或（撞键后）轮询查出已有行。
     */
    public DeviceEntity insertOrFind(DeviceEntity device, String... lookupKeys) {
        try {
            DeviceEntity inserted = insertNew(device);
            if (inserted != null && inserted.getId() != null) {
                return inserted;
            }
        } catch (DuplicateKeyException de) {
            log.debug("正常日志:[c2_handlers] 设备已存在（并发新建），等待提交后重查, deviceid={}",
                    device == null ? null : device.getDeviceId());
        } catch (Exception e) {
            // 部分驱动/包装可能不是 DuplicateKeyException
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("Duplicate") || msg.contains("duplicate")) {
                log.debug("正常日志:[c2_handlers] 设备唯一键冲突，等待提交后重查, deviceid={}, err={}",
                        device == null ? null : device.getDeviceId(), msg);
            } else {
                throw e;
            }
        }
        DeviceEntity found = pollFind(lookupKeys);
        if (found == null && device != null && device.getDeviceId() != null) {
            found = pollFind(device.getDeviceId());
        }
        return found;
    }

    private DeviceEntity pollFind(String... keys) {
        if (keys == null) {
            return null;
        }
        // 最多约 5s，覆盖接口侧/其它消费线程长事务提交窗口，减少 Kafka 抛错重试
        for (int i = 0; i < 50; i++) {
            for (String key : keys) {
                if (key == null || key.isEmpty()) {
                    continue;
                }
                DeviceEntity e = findByDeviceId(key);
                if (e != null && e.getId() != null) {
                    if (i > 0) {
                        log.debug("正常日志:[c2_handlers] 并发建机后重查命中, attempt={}, deviceid={}, rowId={}",
                                i, e.getDeviceId(), e.getId());
                    }
                    return e;
                }
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
