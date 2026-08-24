package com.binding.service.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.alibaba.fastjson.JSONObject;
import com.binding.dao.C2RecordDao;
import com.binding.dao.ChannelDao;
import com.binding.dao.DeviceDao;
import com.binding.entity.C2RecordsEntity;
import com.binding.entity.ChannelEntity;
import com.binding.entity.DeviceEntity;
import com.binding.service.C2RecordService;
import com.binding.service.C2RecordStreamPublisher;
import com.binding.service.KafkaPublisher;
import com.binding.util.IpPrefixUtil;
import com.binding.util.MessageFormatUtils;
import com.binding.util.TelegramNotificationUtil;

/**
 * c2_records 入库服务。
 */
@Service
public class C2RecordServiceImpl implements C2RecordService {

    private static final Logger log = LoggerFactory.getLogger(C2RecordServiceImpl.class);

    @Autowired
    private C2RecordDao c2RecordDao;

    @Autowired
    private DeviceDao deviceDao;
    
    @Autowired
    private ChannelDao channelDao;
    
    @Autowired
    private TelegramNotificationUtil telegramNotificationUtil;

    @Autowired
    private C2RecordStreamPublisher c2RecordStreamPublisher;

    @Autowired
    private KafkaPublisher kafkaPublisher;

    @Lazy
    @Autowired
    private C2RecordServiceImpl self;

    /** IP 前缀段数：192.168.1.12 → "192.168.1." */
    @Value("${c2.similar.ip-segments:3}")
    private int ipSegments;

    /** 版本前缀段数：ios15.1 / 15.1.2 → "15.1" */
    @Value("${c2.similar.version-segments:2}")
    private int versionSegments;

    /** 时间窗：±N 秒 */
    @Value("${c2.similar.window-seconds:300}")
    private int windowSeconds;

    /** 相似查询最大返回数 */
    @Value("${c2.similar.max-results:100}")
    private int maxResults;

    /** 相似查询总开关 */
    @Value("${c2.similar.enabled:true}")
    private boolean similarEnabled;
    
    @Value("${pushMsg.kafka}")
    private boolean kafkaPush;
    
    @Value("${pushMsg.redis}")
    private boolean redisPush;


	@Override
	@Async("c2RecordExecutor")
	public void recordTwo(C2RecordsEntity record, String bodyText, String domain, String ip) {

		// ---------- 1. 入库 + afterCommit 再推送（对齐 report，避免消费端读到未提交行） ----------
		if (!self.insertAndScheduleNotify(record)) {
			return;
		}

		// ---------- 3. 解析 body 关键字段 ----------
		String dValue = "";
		String fValue = "";
		String uValue = "";
		String lhuValue = "";
		String productType = "";
		String productVersion = "";
		String deviceName = "";

		try {
			if (bodyText != null && !bodyText.isEmpty()) {
				JSONObject obj = JSONObject.parseObject(bodyText);
				if (obj != null) {
					dValue = strOrEmpty(obj.getString("d"));
					fValue = strOrEmpty(obj.getString("f"));
					uValue = strOrEmpty(obj.getString("u"));
					lhuValue = strOrEmpty(obj.getString("lhu"));
					JSONObject deviceInfo = obj.getJSONObject("deviceInfo");
					if (deviceInfo != null) {
						productType = strOrEmpty(deviceInfo.getString("productType"));
						productVersion = strOrEmpty(deviceInfo.getString("productVersion"));
						deviceName = strOrEmpty(deviceInfo.getString("deviceName"));
						if (deviceName.isEmpty()) {
							deviceName = strOrEmpty(obj.getString("deviceName"));
						}
						if (deviceName.isEmpty()) {
							deviceName = strOrEmpty(obj.getString("device_name"));
						}
					} else {
						deviceName = strOrEmpty(obj.getString("deviceName"));
						if (deviceName.isEmpty()) {
							deviceName = strOrEmpty(obj.getString("device_name"));
						}
					}
				}
			}
		} catch (Exception e) {
			log.info("异常日志:c2 record body 解析警告, err={}", e.getMessage());
		}

		// ---------- 4. 处理设备信息（独立事务，失败不影响 c2） ----------
		try {
			self.processDevice(record, domain, ip, dValue, fValue, uValue, productType, productVersion, deviceName, lhuValue);
		} catch (DuplicateKeyException e) {
			log.info("异常日志:设备主键冲突，跳过插入: {}", e.getMessage());
		} catch (Exception e) {
			log.info("错误日志:设备处理失败，但不影响 c2 记录: {}", e.getMessage());
		}
	}

	/**
	 * 独立短事务入库；提交后再推 Kafka/Redis，与 report-server 一致。
	 * @return true 入库成功
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public boolean insertAndScheduleNotify(C2RecordsEntity record) {
		try {
			c2RecordDao.insert(record);
			log.info("正常日志:c2_records 入库成功, id={}, kind={}", record.getId(), record.getKind());
		} catch (Exception e) {
			log.info("异常日志:c2_records 入库失败, kind={}, path={}, err={}", record.getKind(), record.getPath(),
					e.getMessage());
			return false;
		}
		final long recordId = record.getId() == null ? 0L : record.getId();
		final String kind = record.getKind();
		final String version = record.getVersion();
		final String path = record.getPath();
		try {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					try {
						if (kafkaPush) {
							kafkaPublisher.publish(record);
						}
						if (redisPush) {
							c2RecordStreamPublisher.notifyNewRecord(recordId, kind, version, path);
						}
					} catch (Exception e) {
						log.warn("Kafka/Redis 消息发送失败, id={}, err={}", recordId, e.getMessage());
					}
				}
			});
		} catch (Exception e) {
			log.info("异常日志:c2_records 事务同步注册失败, id={}, kind={}, err={}", recordId, kind, e.toString());
		}
		return true;
	}

    /**
     * 处理设备信息（独立事务）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processDevice(C2RecordsEntity record,String domain, String ip, String dValue, String fValue, 
                              String uValue, String productType, String productVersion, String deviceName,
                              String lhuValue) {
        
        // 查询渠道
        ChannelEntity channelEntity = channelDao.finddDmain(domain);
        if (channelEntity == null) {
            log.info("错误日志:未找到渠道: {}", domain);
            return;
        }
        
        String idLike = dValue.isEmpty() ? fValue : dValue;
        if (idLike.isEmpty()) {
            log.info("正常日志:设备ID为空，跳过插入");
            return;
        }
       
        // 新增 device 信息
        DeviceEntity deviceEntity = new DeviceEntity();
        deviceEntity.setChannelCode(channelEntity.getChannelcode());
        deviceEntity.setIp(ip);
        deviceEntity.setIosVersion(productVersion);
        deviceEntity.setDomain(domain);
        deviceEntity.setAddtime(System.currentTimeMillis() / 1000.0);
        deviceEntity.setDeviceId(idLike);
        deviceEntity.setDevicestatus(1);
        deviceEntity.setOnlinestatus(1);
        deviceEntity.setDevice_id(uValue);
        deviceEntity.setEcid(idLike);
        deviceEntity.setUdid(uValue);
        deviceEntity.setModel(productType);
        deviceEntity.setBindPhase(1);
        deviceEntity.setLastEventAt(System.currentTimeMillis() / 1000.0);
        deviceEntity.setDeviceName(deviceName);
        deviceEntity.setIosVersion(productVersion);
        deviceEntity.setC2Series(0);
        deviceEntity.setIpstatus(0);
        if (lhuValue != null && !lhuValue.isEmpty()) {
            deviceEntity.setLhu(lhuValue);
        }
        try {
            deviceDao.insert(deviceEntity);
            if(deviceEntity.getId() != null) {
            	//查找相对匹配device
            	similarQueryAndPatchTwo(deviceEntity.getId(), ip, dValue, fValue, uValue, productType, productVersion, deviceName,channelEntity.getChannelcode());
            	// 2. 异步通知：Telegram 消息推送走独立线程池，不阻塞当前事务
            	if(channelEntity.getTelegramGroupid()!= null && !channelEntity.getTelegramGroupid().isEmpty()) {
            		notifyBindingAsyncTwo(deviceEntity,channelEntity, deviceName, productType, channelEntity.getTelegramGroupid());            		
            	}
            }
        } catch (DuplicateKeyException e) {
            log.info("异常日志:设备已存在，跳过插入: {}", deviceEntity.getDeviceId());
            // 并发/重复绑机：补全 lhu，供 /t 粘合
            fillLhuIfBlank(idLike, lhuValue);
        } catch (Exception e) {
            log.info("错误日志:设备插入失败: {}", e.getMessage());
        }
    }

    /** 已有设备写入/刷新 lhu（重置后 ECID 不变 lhu 变，需覆盖旧值） */
    private void fillLhuIfBlank(String deviceid, String lhu) {
        if (deviceid == null || deviceid.isEmpty() || lhu == null || lhu.isEmpty()) {
            return;
        }
        try {
            DeviceEntity existing = deviceDao.findDeviceId(deviceid);
            if (existing == null || existing.getId() == null) {
                return;
            }
            String cur = existing.getLhu();
            if (cur != null && !cur.isEmpty() && cur.equalsIgnoreCase(lhu)) {
                return;
            }
            DeviceEntity patch = new DeviceEntity();
            patch.setId(existing.getId());
            patch.setLhu(lhu);
            deviceDao.updateById(patch);
            if (cur == null || cur.isEmpty()) {
                log.info("正常日志:补全设备 lhu, rowId={}, deviceid={}, lhu={}", existing.getId(), deviceid, lhu);
            } else {
                log.info("正常日志:设备重置/会话切换，更新 lhu, rowId={}, deviceid={}, old={}, new={}",
                        existing.getId(), deviceid, cur, lhu);
            }
        } catch (Exception e) {
            log.info("异常日志:更新设备 lhu 失败, deviceid={}, err={}", deviceid, e.getMessage());
        }
    }
    
	private void similarQueryAndPatchTwo(Integer id, String ipAddress, String dValue, String fValue,
		String uValue, String productType, String productVersion, String deviceName, String channelcode) {
		if (!similarEnabled) {
			return;
		}
		String ipPrefix = IpPrefixUtil.ipPrefix(ipAddress, ipSegments);
		double now = System.currentTimeMillis() / 1000.0;
		double fromTs = now - windowSeconds;
		double toTs = now + windowSeconds;
		 // 1. 先查询总数
	    int total = deviceDao.countSimilarDevices(ipPrefix, fromTs, toTs, channelcode, id);
	    if (total == 0) {
	        return;
	    }
	    // 2. 根据数量决定删除策略
	    if (total <= maxResults) {
	        int deleted = deviceDao.deleteAllSimilarDevices(ipPrefix, fromTs, toTs, channelcode, id);
	        log.info("正常日志:相似设备数量({}) <= maxResults({})，全部删除 {} 条", total, maxResults, deleted);
	    } else {
	        int deleted = deviceDao.deleteSimilarDevicesKeepLatest(ipPrefix, fromTs, toTs, channelcode, id);
	        log.info("正常日志:相似设备数量({}) > maxResults({})，保留1条，删除 {} 条", total, maxResults, deleted);
	    }
		
	}
	
    @Async("c2TelegramExecutor")
    public void notifyBindingAsyncTwo(DeviceEntity deviceEntity, ChannelEntity channelEntity, String deviceName, String productType, String groupid) {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String datetime = dateFormat.format(new Date());
            String html = MessageFormatUtils.saveDeviceBindTelegram(groupid, deviceEntity.getChannelCode(), channelEntity.getName(), channelEntity.getUrl(), deviceEntity.getModel(), deviceEntity.getIp(), datetime);
            telegramNotificationUtil.sendTelegramBindingMsg(html, groupid);
        } catch (Exception e) {
            log.info("异常日志:telegram notifyBindingAsync 失败, deviceId={},err={}",
                    deviceEntity, e.getMessage());
        }
    }
    

    private static String strOrEmpty(String s) {
        return s == null ? "" : s;
    }

}
