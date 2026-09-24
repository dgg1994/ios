package com.consume.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

/**
 * S3 仅在 aws_region / access_key 有有效值时创建客户端。
 * 本地空配置时跳过，避免 Regions.fromName("") 启动失败。
 */
@Configuration
public class AmazonS3Config {

    private static final Logger log = LoggerFactory.getLogger(AmazonS3Config.class);

    @Value("${s3.access_key:}")
    private String accessKeyId;

    @Value("${s3.secret_key:}")
    private String secretAccessKey;

    @Value("${s3.aws_region:}")
    private String region;

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${s3.aws_region:}')")
    public AmazonS3 amazonS3() {
        String r = region == null ? "" : region.trim();
        log.info("正常日志:[s3] 初始化 AmazonS3, region={}", r);
        BasicAWSCredentials awsCredentials = new BasicAWSCredentials(accessKeyId, secretAccessKey);
        return AmazonS3ClientBuilder.standard()
                .withRegion(Regions.fromName(r))
                .withCredentials(new AWSStaticCredentialsProvider(awsCredentials))
                .build();
    }
}
