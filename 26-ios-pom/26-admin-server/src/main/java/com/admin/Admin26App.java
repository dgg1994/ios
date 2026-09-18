package com.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * v26 管理端（对应 Python {@code app/admin} + {@code /api/admin}）。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableAsync
@EnableConfigurationProperties
@MapperScan("com.admin.dao")
public class Admin26App {

    public static void main(String[] args) {
        SpringApplication.run(Admin26App.class, args);
    }
}
