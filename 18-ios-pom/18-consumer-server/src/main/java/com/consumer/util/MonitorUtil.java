package com.consumer.util;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;

import cn.hutool.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class MonitorUtil{
	
    private static String API_POST;//接口域名
    
    private static String API_KEY;//app key
    
    private static String SIGN_KEY;//签名私钥
    
    private static int NOTIFY_TIMEOUT = 30000; // 设置默认超时时间
    
    private static int NOTIFY_CONNECT_TIMEOUT = 10000; // 设置默认连接超时

    @Value("${monitor.api_post}")
    public void setApiPost(String apiPost) {
    	MonitorUtil.API_POST = apiPost;
    }

    @Value("${monitor.api_key}")
    public void setApikEY(String apiKey) {
    	MonitorUtil.API_KEY = apiKey;
    }
    
    @Value("${monitor.sign_key}")
    public void setSignkey(String signkey) {
    	MonitorUtil.SIGN_KEY = signkey;
    }
    
    /**
     * HMAC-SHA256 加密
     */
    private static String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(rawHmac);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 encryption failed", e);
        }
    }

    /**
     * 字节数组转十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 递归排序 Map（处理嵌套数组）
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> recursiveSort(Map<String, Object> map) {
        Map<String, Object> sortedMap = new TreeMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                // 递归排序嵌套 Map
                sortedMap.put(entry.getKey(), recursiveSort((Map<String, Object>) value));
            } else if (value instanceof List) {
                // 处理数组，保持原样
                sortedMap.put(entry.getKey(), value);
            } else {
                sortedMap.put(entry.getKey(), value);
            }
        }
        return sortedMap;
    }

    /**
     * 生成签名（对应 PHP 的 makesign 方法）
     * @param data 参数数组
     * @param secret 密钥
     * @return 签名
     */
    private static String makeSign(Map<String, Object> data, String secret) {
        if (data == null) {
            return "";
        }
        // 1. 移除 sign 字段
        Map<String, Object> filteredData = new HashMap<>(data);
        filteredData.remove("sign");
        // 2. 递归排序
        Map<String, Object> sortedData = recursiveSort(filteredData);
        // 3. 转为 JSON 字符串（使用与 PHP 相同的选项）
        String jsonStr = JSON.toJSONString(sortedData, 
                SerializerFeature.DisableCircularReferenceDetect,
                SerializerFeature.WriteMapNullValue,
                SerializerFeature.BrowserCompatible);
        // 4. 使用 HMAC-SHA256 生成签名
        return hmacSha256(jsonStr, secret);
    }

    /**
     * POST 请求
     */
    @SuppressWarnings("unchecked")
    public static void walletPost(String method, Object object) {
        try {
            String jsonDataString = JSON.toJSONString(object);
            Map<String, Object> params = JSON.parseObject(jsonDataString, Map.class);
            // 生成签名
            String sign = makeSign(params, SIGN_KEY);
            // 构建请求参数（包含签名）
            Map<String, Object> requestParams = new HashMap<>(params);
            requestParams.put("sign", sign);
            String finalJsonData = JSON.toJSONString(requestParams);
            String url = API_POST + method;
            HttpRequest httpRequest = HttpRequest.post(url);
            httpRequest.timeout(NOTIFY_TIMEOUT)
                    .body(finalJsonData)
                    .charset(StandardCharsets.UTF_8)
                    .setConnectionTimeout(NOTIFY_CONNECT_TIMEOUT);
            
            // 添加请求头
            httpRequest.header("Content-Type", "application/json;charset=UTF-8");
            httpRequest.header("APIKEY", API_KEY);
            
            // 执行 HTTP 请求
            String dataStr = httpRequest.execute().body();

        } catch (Exception e) {
            log.error("walletPost FAIL", e);
        }
    }
    
    
}
