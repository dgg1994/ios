package com.send.web.acq;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * 协议 A：只回客户端约定 JSON，不写库、不落盘。
 */
@RestController
@RequestMapping("/api")
public class AcqController {

    private static final SecureRandom RANDOM = new SecureRandom();

    @PostMapping(value = "/handshake.php", consumes = MediaType.ALL_VALUE)
    public Map<String, Object> handshake(HttpServletRequest request) throws Exception {
        drainBytes(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("session_token", newToken("local-session"));
        body.put("device_id", "local-device");
        return body;
    }

    @PostMapping(value = "/session.php", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Map<String, Object>> session(
            HttpServletRequest request,
            @RequestParam(value = "action", required = false, defaultValue = "start") String action,
            @RequestParam(value = "session_token", required = false) String sessionToken) throws Exception {
        byte[] raw = drainBytes(request);
        String act = StringUtils.defaultIfBlank(action, "start").trim().toLowerCase(Locale.ROOT);
        if (!"start".equals(act) && !"end".equals(act) && !"finish".equals(act)) {
            return ResponseEntity.badRequest().body(okFalse("unknown action: " + act));
        }
        String token = StringUtils.trimToEmpty(sessionToken);
        if (token.isEmpty()) {
            token = StringUtils.trimToEmpty(request.getHeader("x-session-token"));
        }
        if (token.isEmpty()) {
            token = tokenFromJson(raw, request.getContentType());
        }
        if (token.isEmpty()) {
            token = newToken("local-session");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("session_token", token);
        body.put("acquisition_id", newToken("local-acq"));
        return ResponseEntity.ok(body);
    }

    @PostMapping(value = "/upload.php", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            HttpServletRequest request,
            @RequestParam(value = "session_token", required = false) String sessionToken,
            @RequestParam(value = "acquisition_id", required = false) String acquisitionId) throws Exception {
        drainBytes(request);
        String token = StringUtils.trimToEmpty(sessionToken);
        if (token.isEmpty()) {
            return ResponseEntity.badRequest().body(okFalse("缺少 session_token"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("session_token", token);
        body.put("acquisition_id", StringUtils.defaultString(acquisitionId));
        return ResponseEntity.ok(body);
    }

    private static byte[] drainBytes(HttpServletRequest request) throws Exception {
        return request.getInputStream().readAllBytes();
    }

    private static String tokenFromJson(byte[] raw, String contentType) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        String ctype = StringUtils.defaultString(contentType).toLowerCase(Locale.ROOT);
        if (!ctype.contains("application/json")) {
            return "";
        }
        try {
            JSONObject data = JSON.parseObject(new String(raw, StandardCharsets.UTF_8));
            return data == null ? "" : StringUtils.trimToEmpty(data.getString("session_token"));
        } catch (Exception e) {
            return "";
        }
    }

    private static String newToken(String prefix) {
        byte[] buf = new byte[8];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder(prefix).append('-');
        for (byte b : buf) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static Map<String, Object> okFalse(String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", error);
        return body;
    }
}
