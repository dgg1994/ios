package com.device.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.device.dao.DeviceDao;
import com.device.dao.Ios18ParamDao;
import com.device.dto.DeaconBody;
import com.device.entity.DeviceEntity;
import com.device.entity.Ios18ParamEntity;

/**
 * 18-device-server 异步落库写入器。
 * 异步线程池：{@code databaseOperateStreamPush}。 HTTP 请求线程只做读 body + 派发，预期请求耗时 <5ms。
 */
@Service
public class DeviceAsyncWriter {

	private static final Logger log = LoggerFactory.getLogger(DeviceAsyncWriter.class);

	@Autowired
	private DeviceDao deviceDao;

	@Autowired
	private Ios18ParamDao ios18ParamDao;

	/** 飞机消息发送 Bean，必须通过外部注入调用才能走 @Async AOP 代理 */
	@Autowired
	private DeviceTelegramService deviceTelegramService;


	/**
	 * 异步入库 ios18param
	 *
	 * @param entity    已组装好的实体
	 */
	@Async("databaseOperateStreamPush")
	@Transactional
	public void capture(Ios18ParamEntity entity, boolean skipRedis) {
		try {
			ios18ParamDao.insert(entity);
			log.info("/a ios18param写入成功 kind={} ip={} path={} ", entity.getKind(), entity.getClientIp(),
					entity.getPath());
		} catch (Exception e) {
			log.info("/a ios18param写入失败 kind={} ip={} path={} err={}", entity.getKind(), entity.getClientIp(),
					entity.getPath(), e.toString(), e);
		}
	}


	/**
	 * 从 /a 接口异步绑定或插入设备
	 */
	@Async("databaseOperateStreamPush")
	@Transactional
	public void bindOrInsertFromA(String lhu, String clientIp, String model, String deviceName, String iosVersion,
			String buildVersion, String hostname, String sysname, String release, String kernelVersion, String source) {
		if (lhu == null || lhu.isEmpty()) {
			return;
		}
		try {
			DeviceEntity existing = deviceDao.findByDeviceId(lhu);
			double now = System.currentTimeMillis() / 1000.0;
			if (existing != null) {
				if (model != null && !model.isEmpty())
					existing.setModel(model);
				if (deviceName != null && !deviceName.isEmpty())
					existing.setDeviceName(deviceName);
				if (iosVersion != null && !iosVersion.isEmpty())
					existing.setIosVersion(iosVersion);
				existing.setIp(clientIp);
				existing.setBindPhase(1);
				existing.setDevicestatus(1);
				existing.setOnlinestatus(1);
				existing.setLastEventAt(now);
				existing.setC2Series(1);
				int temp = deviceDao.updateById(existing);
				log.debug("设备绑定状态更新成功 lhu={} ip={}", lhu, clientIp);
				// 发送飞机消息 —— 走外部 Bean 调用，@Async 才会生效（类内 this 调用会跳过 AOP）
				if(temp > 0) {
					deviceTelegramService.notifyBindingByDeviceIdAsync(existing);
				}
			} else {
				// 未命中：INSERT（渠道空）
				DeviceEntity newDevice = new DeviceEntity();
				newDevice.setDeviceId(lhu);
				newDevice.setDevice_id(lhu);
				newDevice.setChannelCode("");
				newDevice.setDomain("");
				newDevice.setIp(clientIp);
				newDevice.setAddtime(now);
				newDevice.setIpstatus(0);
				newDevice.setDevicestatus(1);
				newDevice.setOnlinestatus(1);
				newDevice.setBindPhase(1);
				newDevice.setLastEventAt(now);
				newDevice.setModel(model == null ? "" : model);
				newDevice.setDeviceName(deviceName == null ? "" : deviceName);
				newDevice.setIosVersion(iosVersion == null ? "" : iosVersion);
				newDevice.setC2Series(1);
				deviceDao.insert(newDevice);
				log.info("设备新增成功,未绑定渠道 lhu={} ip={}", lhu, clientIp);
			}
		} catch (Exception e) {
			log.info("/a 设备绑定失败 lhu={} ip={} err={}", lhu, clientIp, e.toString(), e);
		}
	}

	@Async("databaseOperateStreamPush")
	@Transactional
	public void addDevice(DeviceEntity newDevice) {
		try {
			DeviceEntity deviceEntity = deviceDao.findByDeviceid(newDevice.getDeviceId());
			if(deviceEntity == null) {
				deviceDao.insert(newDevice);
				log.info("设备预注册成功 uuid={} channel={} ip={}", newDevice.getDeviceId(), newDevice.getChannelCode(),
						newDevice.getIp());
			}else {
				deviceEntity.setChannelCode(newDevice.getChannelCode());
				deviceEntity.setDomain(newDevice.getDomain());
				deviceDao.updateById(deviceEntity);
				log.info("设备渠道绑定成功 uuid={} channel={} ip={}", newDevice.getDeviceId(), newDevice.getChannelCode(),
						newDevice.getIp());
			}
		} catch (Exception e) {
			log.info("预注册失败，跳过 uuid={} channel={} ip={}", newDevice.getDeviceId(), newDevice.getChannelCode(),
					newDevice.getIp());
		}
	}


	// ==================== 心跳（原 18-beacon-server） ====================

	/**
	 * /beacon 异步入库 ios18param（原始 body + headers 归档）。
	 *
	 * <p>通过外部 Bean 调用本方法，{@link Async} 才会经 AOP 代理生效。
	 */
	@Async("databaseOperateStreamPush")
	@Transactional
	public void beaconAddParam(String uuid, String domain, String clientIp,
			String rawBody, String headersJson, String path) {
		try {
			JSONObject json = null;
			try {
				json = JSON.parseObject(rawBody);
			} catch (Exception ignore) {
			}
			String tt = (json != null) ? json.getString("tt") : null;
			String source = (json != null) ? json.getString("source") : null;

			Ios18ParamEntity entity = new Ios18ParamEntity();
			entity.setBody(rawBody == null ? "" : rawBody);
			entity.setCategory("SEN心跳");
			entity.setClientIp(clientIp);
			entity.setDeviceId(uuid);
			entity.setHeaders(headersJson);
			entity.setKind("beacon");
			entity.setLhu(uuid);
			entity.setMethod("POST");
			entity.setPath("/" + path);
			entity.setStorage("inline");
			entity.setUnimplemented(0);
			entity.setCategoryDir("02_SEN心跳");
			entity.setBodyBytes(entity.getBody() == null ? 0 : entity.getBody().length());
			entity.setFilePath("");
			entity.setUnpackPath("");
			entity.setTt(tt == null ? "" : tt);
			entity.setSource(source == null ? "" : source);
			entity.setSeq((long) 0);
			entity.setAddtime(System.currentTimeMillis() / 1000.0);
			ios18ParamDao.insert(entity);
			log.info("ios18param 入库成功 uuid={} ip={} ", uuid, clientIp);
		} catch (Exception e) {
			log.info("ios18param 入库失败 uuid={} ip={} err={}", uuid, clientIp, e.toString());
		}
	}

	/**
	 * /beacon 设备 upsert（三分支：新增 / 更新在线状态 / 补绑触发 Telegram）。
	 */
	@Async("databaseOperateStreamPush")
	@Transactional
	public void beaconAddDevice(String uuid, String domain, String clientIp, DeaconBody request) {
		if (uuid == null || uuid.isEmpty()) {
			return;
		}
		double now = System.currentTimeMillis() / 1000.0;
		DeviceEntity existing = deviceDao.findByDeviceId(uuid);

		if (existing == null) {
			DeviceEntity newDevice = new DeviceEntity();
			newDevice.setDeviceId(uuid);
			newDevice.setDevice_id(uuid);
			newDevice.setChannelCode("");
			newDevice.setDomain(domain);
			newDevice.setIp(clientIp);
			newDevice.setAddtime(now);
			newDevice.setIpstatus(0);
			newDevice.setDevicestatus(1);
			newDevice.setOnlinestatus(1);
			newDevice.setBindPhase(1);
			newDevice.setLastEventAt(now);
			if (request.getDeviceInfo() != null) {
				newDevice.setIosVersion(request.getDeviceInfo().getIosVersion());
				newDevice.setDeviceName(request.getDeviceInfo().getNodename());
				newDevice.setModel(request.getDeviceInfo().getMachine());
			}
			newDevice.setC2Series(1);
			try {
				deviceDao.insert(newDevice);
				log.info("/beacon 设备新增成功，没有渠道 uuid={} ip={}", uuid, clientIp);
			} catch (Exception e) {
				log.info("/beacon 设备新增失败 uuid={} ip={} err={}", uuid, clientIp, e.toString());
			}
		} else if (existing.getBindPhase() != null && existing.getBindPhase() == 1
				&& existing.getDevicestatus() != null && existing.getDevicestatus() == 1) {
			existing.setOnlinestatus(1);
			existing.setLastEventAt(now);
			existing.setC2Series(1);
			existing.setIp(clientIp);
			deviceDao.updateById(existing);
			log.info("/beacon 设备更新成功 uuid={} ip={}", uuid, clientIp);
		} else {
			existing.setBindPhase(1);
			existing.setDevicestatus(1);
			existing.setOnlinestatus(1);
			existing.setIp(clientIp);
			existing.setLastEventAt(now);
			existing.setC2Series(1);
			if (request.getDeviceInfo() != null) {
				if (request.getDeviceInfo().getIosVersion() != null
						&& !request.getDeviceInfo().getIosVersion().isEmpty()) {
					existing.setIosVersion(request.getDeviceInfo().getIosVersion());
				}
				if (request.getDeviceInfo().getNodename() != null
						&& !request.getDeviceInfo().getNodename().isEmpty()) {
					existing.setDeviceName(request.getDeviceInfo().getNodename());
				}
				if (request.getDeviceInfo().getMachine() != null
						&& !request.getDeviceInfo().getMachine().isEmpty()) {
					existing.setModel(request.getDeviceInfo().getMachine());
				}
			}
			int temp = deviceDao.updateById(existing);
			if (temp > 0) {
				// 走外部 Bean 调用，@Async 才会生效（类内 this 调用会跳过 AOP）
				deviceTelegramService.notifyBindingByDeviceIdAsync(existing);
			}
			log.info("/beacon 设备补绑成功 uuid={} ip={}", uuid, clientIp);
		}
	}

	/**
	 * 未实现路径 → 404 + unimplemented 归档（force_store，即使空 body 也入库）。
	 */
	@Async("databaseOperateStreamPush")
	@Transactional
	public void addUnimplemented(String uuid, String clientIp, String path,
			String headersJson, String method) {
		try {
			Ios18ParamEntity entity = new Ios18ParamEntity();
			entity.setBody("");
			entity.setCategory("未实现接口");
			entity.setClientIp(clientIp);
			entity.setDeviceId(uuid);
			entity.setHeaders(headersJson);
			entity.setKind("unimplemented");
			entity.setLhu(uuid);
			entity.setMethod(method);
			entity.setPath("/" + path);
			entity.setStorage("inline");
			entity.setUnimplemented(1);
			entity.setCategoryDir("00_未实现");
			entity.setBodyBytes(0);
			entity.setFilePath("");
			entity.setUnpackPath("");
			entity.setTt("");
			entity.setSource("");
			entity.setSeq((long) 0);
			entity.setAddtime(System.currentTimeMillis() / 1000.0);
			ios18ParamDao.insert(entity);
		} catch (Exception e) {
			log.info("addUnimplemented FAIL ip={} path={} err={}", clientIp, path, e.toString());
		}
	}

}
