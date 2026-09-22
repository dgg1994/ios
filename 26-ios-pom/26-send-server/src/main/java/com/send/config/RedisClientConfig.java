package com.send.config;

import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.protocol.ProtocolVersion;

/**
 * Redis 开了 requirepass 时，Lettuce 默认先发 RESP3 的 HELLO，服务端会在 AUTH 之前拒绝。
 * 对齐 Python：固定 RESP2，先走经典 AUTH。
 */
@Configuration
public class RedisClientConfig {

    @Bean
    public LettuceClientConfigurationBuilderCustomizer redisResp2() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .protocolVersion(ProtocolVersion.RESP2)
                .timeoutOptions(TimeoutOptions.enabled())
                .build());
    }
}
