package com.consume.config;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Redis / Jedis 连接池配置。
 *
 * 与 binding-server / report-server 保持一致，使用自定义 redis.* 属性，
 * 而非 spring.redis.*，以便直接使用 Jedis 操作 Stream（XADD / XREADGROUP / XACK）。
 */
@Configuration
@ConditionalOnProperty(name = "news4.c2.notify-transport", havingValue = "redis")
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Value("${redis.host:127.0.0.1}")
    private String host;

    @Value("${redis.port:6379}")
    private int port;

    @Value("${redis.password:}")
    private String password;

    @Value("${redis.db:0}")
    private int db;

    @Value("${redis.pool.maxTotal:32}")
    private int maxTotal;

    @Value("${redis.pool.maxIdle:8}")
    private int maxIdle;

    @Value("${redis.pool.minIdle:0}")
    private int minIdle;

    @Value("${redis.timeout:3000}")
    private int timeoutMs;

    private JedisPool pool;

    @SuppressWarnings("deprecation")
	@PostConstruct
    public void init() {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(maxTotal);
        cfg.setMaxIdle(maxIdle);
        cfg.setMinIdle(minIdle);
        cfg.setTestWhileIdle(true);
        cfg.setTestOnBorrow(false);
        cfg.setTestOnReturn(false);
        cfg.setTimeBetweenEvictionRunsMillis(30_000);
        this.pool = new JedisPool(cfg, host, port, timeoutMs,
                (password == null || password.isEmpty()) ? null : password, db);
        log.debug("正常日志:[redis] JedisPool 已就绪, host={}, port={}, db={}, maxTotal={}",
                host, port, db, maxTotal);
    }

    @Bean
    public JedisPool jedisPool() {
        return pool;
    }

    @PreDestroy
    public void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
            log.debug("正常日志:[redis] JedisPool 已关闭");
        }
    }
}
