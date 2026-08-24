package com.consume.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.alibaba.druid.pool.DruidDataSource;

/**
 * 显式绑定 spring.datasource 的 Druid 池参数（initialSize/maxActive 等）。
 * 仅写 type: DruidDataSource 时，Spring Boot 不会绑定这些自定义字段，池会落到默认 maxActive=8。
 */
@Configuration
public class DruidConfig {

    private static final Logger log = LoggerFactory.getLogger(DruidConfig.class);

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSource dataSource() {
        DruidDataSource ds = new DruidDataSource();
        ds.setTestWhileIdle(true);
        ds.setTestOnBorrow(false);
        ds.setTestOnReturn(false);
        ds.setValidationQuery("SELECT 1");
        log.info("正常日志:[datasource] DruidDataSource 已创建");
        return ds;
    }
}
