package com.binding;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.binding.dao")
@EnableTransactionManagement
@EnableAsync
public class BindingApp {

    public static void main(String[] args) {
        SpringApplication.run(BindingApp.class, args);
    }
}
