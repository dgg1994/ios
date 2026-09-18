package com.admin.service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TelegramSendService {

    private final RestTemplate http;

    public TelegramSendService() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(10000);
        this.http = new RestTemplate(f);
    }

    public Map<String, Object> send(String token, String groupId, String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (token == null || token.isBlank() || groupId == null || groupId.isBlank()) {
            out.put("ok", false);
            out.put("message", "未配置机器人或群 ID");
            return out;
        }
        try {
            String url = "https://api.telegram.org/bot" + token.trim() + "/sendMessage";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", groupId.trim());
            body.put("text", text == null ? "" : text);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept-Charset", StandardCharsets.UTF_8.name());
            ResponseEntity<String> resp = http.postForEntity(url, new HttpEntity<>(JSON.toJSONString(body), headers), String.class);
            JSONObject jo = JSON.parseObject(resp.getBody());
            boolean ok = jo != null && Boolean.TRUE.equals(jo.getBoolean("ok"));
            out.put("ok", ok);
            out.put("message", ok ? "已发送" : (jo == null ? "发送失败" : String.valueOf(jo.get("description"))));
            return out;
        } catch (Exception e) {
            log.warn("tg send fail: {}", e.toString());
            out.put("ok", false);
            out.put("message", "发送失败：" + e.getMessage());
            return out;
        }
    }
}
