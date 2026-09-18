package com.send.web.v2;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.send.service.DeviceAsyncWriter;
import com.send.util.BizException;
import com.send.util.ClientIpUtil;
import com.send.util.DeviceValidate;

@RestController
@RequestMapping("/api/v2")
public class V2FinishController {

    @Autowired
    private DeviceAsyncWriter deviceAsyncWriter;

    @PostMapping("/finish")
    public Map<String, Object> finish(
            HttpServletRequest request,
            @RequestHeader(value = "X-Device-Id", required = false) String xDeviceId) throws Exception {
        String deviceId = parseDeviceId(request, xDeviceId);
        deviceAsyncWriter.persistFinishAndEnqueue(deviceId, ClientIpUtil.resolve(request), "v2");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return body;
    }

    private String parseDeviceId(HttpServletRequest request, String xDeviceId) throws Exception {
        byte[] raw = request.getInputStream().readAllBytes();
        String contentType = StringUtils.defaultString(request.getContentType()).toLowerCase();
        JSONObject payload = null;
        if (raw.length > 0) {
            try {
                payload = JSON.parseObject(new String(raw, StandardCharsets.UTF_8));
            } catch (Exception e) {
                if (contentType.contains("application/json")) {
                    throw new BizException(400, "JSON 无效");
                }
            }
        }
        if (payload != null) {
            String did = StringUtils.trimToNull(payload.getString("deviceId"));
            if (did != null) {
                return DeviceValidate.validateDeviceId(did, "deviceId", true);
            }
            if (StringUtils.isNotBlank(xDeviceId)) {
                return DeviceValidate.validateDeviceId(xDeviceId, "X-Device-Id", true);
            }
            throw new BizException(400, "deviceId: 不能为空");
        }
        if (StringUtils.isNotBlank(xDeviceId)) {
            return DeviceValidate.validateDeviceId(xDeviceId, "X-Device-Id", true);
        }
        throw new BizException(400, "需要 JSON deviceId 或 X-Device-Id");
    }
}
