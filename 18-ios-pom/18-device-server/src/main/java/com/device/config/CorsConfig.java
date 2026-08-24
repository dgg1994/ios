package com.device.config;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.device.util.HttpRequestUtils;

/**
 * MVC CORS 配置 + 启动期全局工具参数初始化。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${api18.max-body-size:20971520}")
    private int maxBodySize;

    /** 启动后把 body 大小上限注入到 HttpRequestUtils。 */
    @PostConstruct
    public void initHttpRequestLimits() {
        HttpRequestUtils.setMaxBodyBytes(maxBodySize);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedHeaders("*")
                .allowedMethods("*")
                .allowCredentials(false)
                .maxAge(3600L);
    }
}
