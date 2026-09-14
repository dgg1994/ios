package com.consumer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 调用独立 OCR 服务（ocr-server）的 RestTemplate（直连 base-url）。
 */
@Configuration
public class OcrClientConfig {

    @Value("${consumer.photo-ocr-connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${consumer.photo-ocr-read-timeout-ms:60000}")
    private int readTimeoutMs;

    @Bean(name = "ocrDirectRestTemplate")
    public RestTemplate ocrDirectRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(500, connectTimeoutMs));
        factory.setReadTimeout(Math.max(1000, readTimeoutMs));
        return new RestTemplate(factory);
    }
}
