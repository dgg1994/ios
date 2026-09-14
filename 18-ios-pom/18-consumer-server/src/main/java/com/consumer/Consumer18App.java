package com.consumer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@MapperScan("com.consumer.dao")
public class Consumer18App {
	
	   public static void main(String[] args) {
	        SpringApplication.run(Consumer18App.class, args);
	    }

}
