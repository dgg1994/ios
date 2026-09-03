package com.consume.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

@Configuration
public class AmazonS3Config {

	@Value("${s3.access_key}")
	private String accessKeyId;

	@Value("${s3.secret_key}")
	private String secretAccessKey;

	@Value("${s3.aws_region}")
	private String region;

	@Bean
	public AmazonS3 amazonS3() {
		System.out.println("s3配置初始化加载完成");
		BasicAWSCredentials awsCredentials = new BasicAWSCredentials(accessKeyId, secretAccessKey);
		return AmazonS3ClientBuilder.standard().withRegion(Regions.fromName(region)) // 设置 AWS 区域
				.withCredentials(new AWSStaticCredentialsProvider(awsCredentials)) // 设置访问凭证
				.build();
	}

}
