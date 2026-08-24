package com.device;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;


@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.device.dao")
@EnableTransactionManagement
@EnableAsync
public class Device18App {
	
	   public static void main(String[] args) {
	        SpringApplication.run(Device18App.class, args);
	    }

}
