package com.consumer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

/**
 * Redis 配置：
 * <ul>
 *   <li>RedisTemplate（保留，供非 Stream 场景）</li>
 *   <li>JedisPool（Consumer 主流程：Stream 命令集在 Jedis 版本间更稳定）</li>
 * </ul>
 */
@Configuration
public class RedisTemplateConfig {

    @Value("${spring.redis.host:127.0.0.1}")
    private String host;
    @Value("${spring.redis.port:6379}")
    private int port;
    @Value("${spring.redis.password:}")
    private String password;
    @Value("${spring.redis.database:0}")
    private int database;
    @Value("${spring.redis.timeout:5000ms}")
    private String timeout;
    @Value("${spring.redis.jedis.pool.max-active:48}")
    private int jedisMaxActive;
    @Value("${spring.redis.jedis.pool.max-idle:24}")
    private int jedisMaxIdle;
    @Value("${spring.redis.jedis.pool.min-idle:4}")
    private int jedisMinIdle;

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        StringRedisSerializer s = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer json = new GenericJackson2JsonRedisSerializer();
        template.setKeySerializer(s);
        template.setHashKeySerializer(s);
        template.setValueSerializer(json);
        template.setHashValueSerializer(json);
        template.afterPropertiesSet();
        return template;
    }

    @SuppressWarnings("deprecation")
	@Bean(destroyMethod = "close")
    public JedisPool jedisPool() {
        JedisPoolConfig cfg = new JedisPoolConfig();
        // 多实例部署：每实例连接数按实际并发需求配置，避免3实例×128=384压垮Redis
        cfg.setMaxTotal(jedisMaxActive);
        cfg.setMaxIdle(jedisMaxIdle);
        cfg.setMinIdle(jedisMinIdle);
        cfg.setBlockWhenExhausted(true);
        cfg.setMaxWaitMillis(5000L);
        // 解析 5000ms → 5000
        int tout = Protocol.DEFAULT_TIMEOUT;
        if (timeout != null && !timeout.isEmpty()) {
            String digits = timeout.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) tout = Integer.parseInt(digits);
        }
        boolean hasPwd = password != null && !password.isEmpty();
        if (hasPwd) {
            return new JedisPool(cfg, host, port, tout, password, database);
        }
        return new JedisPool(cfg, host, port, tout, null, database);
    }
}
