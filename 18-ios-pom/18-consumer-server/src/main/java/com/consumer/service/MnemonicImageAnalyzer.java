package com.consumer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.consumer.config.ConsumerProperties;

/**
 * 相册图片：调用 ocr-server 做 OCR+BIP39 判定。
 * photo-ocr-enabled=false 时跳过过滤（全部上云）；true 仅通过者上云入库。
 */
@Component
public class MnemonicImageAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MnemonicImageAnalyzer.class);

    @Autowired
    private ConsumerProperties props;

    @Autowired
    @Qualifier("ocrDirectRestTemplate")
    private RestTemplate ocrDirectRestTemplate;

    public boolean isEnabled() {
        return props != null && props.isPhotoOcrEnabled();
    }

    /**
     * @return true 应上云；false 丢弃
     */
    public boolean looksLikeMnemonic(byte[] imageBytes, Integer recordId) {
        if (!isEnabled()) {
            return true;
        }
        if (imageBytes == null || imageBytes.length == 0) {
            log.info("【photo-ocr】过滤丢弃 recordId={} reason=empty_image", recordId);
            return false;
        }
        String baseUrl = props.getPhotoOcrBaseUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            log.warn("【photo-ocr】已启用但 photo-ocr-base-url 为空，丢弃 recordId={}", recordId);
            return false;
        }
        try {
            String url = trimSlash(baseUrl.trim()) + "/ocr/mnemonic-check";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            ResponseEntity<String> resp = ocrDirectRestTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(imageBytes, headers), String.class);
            JSONObject body = JSON.parseObject(resp.getBody());
            if (body == null) {
                log.info("【photo-ocr】过滤丢弃 recordId={} reason=ocr_empty_response", recordId);
                return false;
            }
            boolean pass = body.getBooleanValue("pass");
            String reason = body.getString("reason");
            int hits = body.getIntValue("bip39Hits");
            long costMs = body.getLongValue("costMs");
            if (pass) {
                log.info("【photo-ocr】过滤通过 recordId={} reason={} hits={} costMs={}",
                        recordId, reason, hits, costMs);
            } else {
                log.info("【photo-ocr】过滤丢弃 recordId={} reason={} hits={} costMs={}",
                        recordId, reason, hits, costMs);
            }
            return pass;
        } catch (Exception e) {
            log.warn("【photo-ocr】调用失败，丢弃不落库 recordId={} err={}", recordId, e.toString());
            return false;
        }
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
