package com.consumer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * v26 异步消费（对应 Python {@code parse_queue} / wallet_parse / notes）。
 * <p>可多实例注册 Nacos；队列消费用 Redis List/Stream 竞争，天然水平扩展。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableAsync
@EnableScheduling
@MapperScan("com.consumer.dao")
public class Consumer26App {

    public static void main(String[] args) {
        SpringApplication.run(Consumer26App.class, args);
    }
}
