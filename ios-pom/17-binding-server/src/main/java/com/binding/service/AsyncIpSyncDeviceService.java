package com.binding.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.binding.dao.DeviceDao;
import com.binding.entity.DeviceEntity;
import com.binding.query.IpSyncQuery;
import com.binding.util.IpPrefixUtil;

/**
 * /api/ip-sync 异步建机（自 17-ctwo-server 迁入）。
 */
@Service
public class AsyncIpSyncDeviceService {

    private static final Logger log = LoggerFactory.getLogger(AsyncIpSyncDeviceService.class);

    @Value("${c2.similar.ip-segments:3}")
    private int ipSegments;

    @Value("${c2.similar.window-seconds:300}")
    private int windowSeconds;

    @Value("${c2.similar.enabled:true}")
    private boolean similarEnabled;

    @Autowired
    private DeviceDao deviceDao;

    @Async("ipSyncDeviceExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertAsync(IpSyncQuery query, String ip) {
        try {
            String deviceVersion = query.getDeviceVersion();
            if (isIos18(deviceVersion)) {
                log.info("iOS 18 版本，跳过入库, deviceVersion={}", deviceVersion);
                return;
            }
            if (!similarEnabled) {
                return;
            }
            String ipPrefix = IpPrefixUtil.ipPrefix(ip, ipSegments);

            double now = System.currentTimeMillis() / 1000.0;
            double fromTs = now - windowSeconds;
            double toTs = now + windowSeconds;
            int total = deviceDao.countIpSyncDevices(ipPrefix, fromTs, toTs, query.getChannelCode());
            if (total > 0) {
                return;
            }

            DeviceEntity deviceEntity = new DeviceEntity();
            deviceEntity.setChannelCode(query.getChannelCode());
            deviceEntity.setIp(ip);
            deviceEntity.setIosVersion(query.getDeviceVersion());
            deviceEntity.setDomain(query.getDomain());
            deviceEntity.setAddtime(System.currentTimeMillis() / 1000.0);
            String uniqueDeviceId = new StringBuilder()
                    .append(query.getChannelCode()).append("|")
                    .append(ip).append("|")
                    .append(query.getDeviceVersion())
                    .toString();
            deviceEntity.setDeviceId(uniqueDeviceId);
            deviceEntity.setDevicestatus(0);
            deviceEntity.setOnlinestatus(0);
            deviceEntity.setBindPhase(0);
            deviceEntity.setLastEventAt(System.currentTimeMillis() / 1000.0);
            deviceEntity.setC2Series(0);
            deviceEntity.setIpstatus(0);
            try {
                deviceDao.insert(deviceEntity);
            } catch (Exception e) {
                log.info("设备已存在，跳过入库");
            }
        } catch (Exception e) {
            log.info("ip-sync device async insert failed, channelCode={}, ip={}, err={}",
                    query.getChannelCode(), ip, e.getMessage());
        }
    }

    private boolean isIos18(String version) {
        if (version == null || version.isEmpty()) {
            return false;
        }
        return version.matches("(?i).*?18(\\.\\d+)?.*");
    }
}
