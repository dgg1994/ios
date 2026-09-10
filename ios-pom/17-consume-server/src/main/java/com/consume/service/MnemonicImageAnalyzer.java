package com.consume.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * /t 解密后图片：调用独立 ocr-server 做 OCR+BIP39 判定。
 * 仅通过者才应上云入库。enabled=false 时跳过过滤（全部上云）。
 */
@Component
public class MnemonicImageAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MnemonicImageAnalyzer.class);

    @Value("${news4.album.mnemonic-filter.enabled:true}")
    private boolean enabled;

    /** Nacos 服务名，默认 ocr-server */
    @Value("${news4.album.mnemonic-filter.service-name:ocr-server}")
    private String serviceName;

    /**
     * 非空则直连该地址（如 http://127.0.0.1:8210），不走 Nacos。
     * 为空则用 http://{service-name}
     */
    @Value("${news4.album.mnemonic-filter.base-url:}")
    private String baseUrl;

    @Autowired
    @Qualifier("ocrLoadBalancedRestTemplate")
    private RestTemplate ocrLoadBalancedRestTemplate;

    @Autowired
    @Qualifier("ocrDirectRestTemplate")
    private RestTemplate ocrDirectRestTemplate;

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return true 像助记词，应上云；false 丢弃
     */
    public boolean looksLikeMnemonic(byte[] imageBytes, Integer recordId) {
        if (!enabled) {
            return true;
        }
        if (imageBytes == null || imageBytes.length == 0) {
            log.info("正常日志:[album] 过滤丢弃, recordId={}, reason=empty_image", recordId);
            return false;
        }
        try {
            boolean direct = baseUrl != null && !baseUrl.trim().isEmpty();
            String root = direct ? trimSlash(baseUrl.trim())
                    : ("http://" + (serviceName == null || serviceName.isEmpty() ? "ocr-server" : serviceName.trim()));
            String url = root + "/ocr/mnemonic-check";
            RestTemplate rt = direct ? ocrDirectRestTemplate : ocrLoadBalancedRestTemplate;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            ResponseEntity<String> resp = rt.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(imageBytes, headers), String.class);

            JSONObject body = JSON.parseObject(resp.getBody());
            if (body == null) {
                log.info("正常日志:[album] 过滤丢弃, recordId={}, reason=ocr_empty_response", recordId);
                return false;
            }
            boolean pass = body.getBooleanValue("pass");
            String reason = body.getString("reason");
            int hits = body.getIntValue("bip39Hits");
            long costMs = body.getLongValue("costMs");
            if (pass) {
                log.info("正常日志:[album] 过滤通过, recordId={}, reason={}, hits={}, costMs={}",
                        recordId, reason, hits, costMs);
            } else {
                log.info("正常日志:[album] 过滤丢弃, recordId={}, reason={}, hits={}, costMs={}",
                        recordId, reason, hits, costMs);
            }
            return pass;
        } catch (Exception e) {
            log.info("异常日志:[album] OCR 服务调用失败，丢弃不落库, recordId={}, err={}",
                    recordId, e.toString());
            return false;
        }
    }

    private static String trimSlash(String s) {
        if (s.endsWith("/")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }
}
