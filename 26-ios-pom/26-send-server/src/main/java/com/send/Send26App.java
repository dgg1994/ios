package com.send;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * v26 客户端上报入口（协议 A `.php` + `/api/v2`）。
 * <p>职责：AcquisitionHook 密文上传、V2 设备/明文上传/finish 入队；ACK 优先，重解析交给 consumer。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableAsync
@MapperScan("com.send.dao")
public class Send26App {

    public static void main(String[] args) {
        SpringApplication.run(Send26App.class, args);
    }
}
