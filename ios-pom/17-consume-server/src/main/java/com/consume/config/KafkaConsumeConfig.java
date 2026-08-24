package com.consume.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import com.consume.service.RetryConsumeException;

/**
 * Kafka 消费容错：业务失败回退 offset 重试。
 * RetryConsumeException（/t 等设备等）为预期行为，降级日志级别避免刷 ERROR 堆栈。
 */
@Configuration
@ConditionalOnProperty(name = "news4.c2.notify-transport", havingValue = "kafka")
public class KafkaConsumeConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumeConfig.class);

    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(15_000L);
        // 约 5 分钟仍等不到 /a 则跳过，避免永久卡住分区
        backOff.setMaxElapsedTime(300_000L);
        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) ->
                log.info("异常日志:[c2_notify][kafka] 重试耗尽，跳过本条, topic={}, partition={}, offset={}, err={}",
                        record.topic(), record.partition(), record.offset(),
                        ex == null ? "" : ex.getMessage()),
                backOff);
        handler.addRetryableExceptions(RetryConsumeException.class);
        handler.setCommitRecovered(true);
        // Seek/重试不再打 ERROR 堆栈（框架默认 Level.ERROR）
        handler.setLogLevel(KafkaException.Level.DEBUG);
        return handler;
    }
}
