package com.consume.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 调用独立 OCR 服务（ocr-server）的 RestTemplate。
 * 走 Nacos 服务名时需 {@link LoadBalanced}。
 */
@Configuration
public class OcrClientConfig {

    @Value("${news4.album.mnemonic-filter.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${news4.album.mnemonic-filter.read-timeout-ms:60000}")
    private int readTimeoutMs;

    @Bean
    @LoadBalanced
    public RestTemplate ocrLoadBalancedRestTemplate() {
        return buildRestTemplate();
    }

    /** 直连 base-url 时使用（不经负载均衡） */
    @Bean
    public RestTemplate ocrDirectRestTemplate() {
        return buildRestTemplate();
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(500, connectTimeoutMs));
        factory.setReadTimeout(Math.max(1000, readTimeoutMs));
        return new RestTemplate(factory);
    }
}
