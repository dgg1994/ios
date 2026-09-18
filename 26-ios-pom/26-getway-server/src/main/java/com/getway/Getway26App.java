package com.getway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * v26 对外统一入口（对齐 ios-pom {@code forward-server}）。
 * <p>按 Path 转发：客户端上报 → 26-send-server；管理端 JSON → 26-admin-server。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class Getway26App {

    public static void main(String[] args) {
        SpringApplication.run(Getway26App.class, args);
    }
}
