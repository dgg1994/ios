package com.device.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * RedisTemplate 序列化配置。
 *
 * <p>RedisTemplate 默认用 JDK 序列化，会把对象序列化成二进制字节写入 Redis，
 * 导致 Stream / Hash / String 等操作读取出来是乱码。
 * 这里强制：
 * <ul>
 *   <li>key / hashKey / hashValue → {@link StringRedisSerializer}（直接字符串）</li>
 *   <li>value → {@link GenericJackson2JsonRedisSerializer}（JSON 字符串）</li>
 * </ul>
 * 注意：Stream 字段值必须用 StringRedisSerializer，否则 "512" 会被 JSON 序列化成
 * "\"512\""（带引号），Consumer 读取后 parseInt 会失败。
 */
@Configuration
public class RedisTemplateConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
