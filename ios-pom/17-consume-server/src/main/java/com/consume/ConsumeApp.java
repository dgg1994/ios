package com.consume;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;


@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.consume.dao")
@EnableTransactionManagement
@EnableAsync
@EnableScheduling
public class ConsumeApp {
	
    public static void main(String[] args) {
        SpringApplication.run(ConsumeApp.class, args);
    }

}
