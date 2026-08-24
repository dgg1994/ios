package com.device.util;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import lombok.extern.slf4j.Slf4j;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class TelegramNotificationUtil {
	
	@Value("${telegram.mornitor_bot_token}")
	private String MORNITOR_BOT_TOKEN;


	private final RestTemplate restTemplate;
    
    /** 连接超时（毫秒）：Telegram 海外节点握手较慢，预留 5s */
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    /** 读超时（毫秒）：防止网络丢包时线程无限挂起 */
    private static final int READ_TIMEOUT_MS = 10_000;

    public TelegramNotificationUtil() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }
	
	public void sendTelegramBindingMsg(String message,String groupId) {
        try {
            // 构建请求参数
            String telegramApiUrl = String.format("https://api.telegram.org/bot%s/sendMessage", MORNITOR_BOT_TOKEN);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("chat_id", groupId);
            requestBody.put("text", message);
            requestBody.put("parse_mode", "HTML");
            requestBody.put("disable_web_page_preview", true);
            HttpEntity<Map<String, Object>> httpEntity = new HttpEntity<>(requestBody, headers);
            // 发送 POST 请求
            ResponseEntity<String> response = restTemplate.postForEntity(telegramApiUrl, httpEntity, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("正常日志:Telegram 通知发送成功");
            } else {
                log.info("错误日志:Telegram 通知发送失败，响应码：{}", response.getStatusCodeValue());
            }
        } catch (Exception e) {
            // Telegram 通知失败不影响主流程
            log.info("错误日志:Telegram 通知发送失败: {}", e.getMessage(), e);
        }
    }

}
