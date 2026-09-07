package com.orc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class OrcApp {

    public static void main(String[] args) {
        SpringApplication.run(OrcApp.class, args);
    }
}
