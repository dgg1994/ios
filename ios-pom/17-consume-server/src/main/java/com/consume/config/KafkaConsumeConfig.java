package com.consume.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import com.consume.service.RetryConsumeException;

/**
 * Kafka 消费容错：业务失败回退 offset 重试；耗尽后写入 DLQ topic（可回放），不再静默 skip。
 */
@Configuration
@ConditionalOnProperty(name = "news4.c2.notify-transport", havingValue = "kafka")
public class KafkaConsumeConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumeConfig.class);

    @Value("${c2.kafka.topic:c2_records}")
    private String mainTopic;

    @Value("${c2.kafka.dlq-topic:}")
    private String dlqTopic;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(15_000L);
        // 约 5 分钟仍等不到 /a 则进 DLQ，避免永久卡住分区
        backOff.setMaxElapsedTime(300_000L);

        String dlt = (dlqTopic == null || dlqTopic.trim().isEmpty())
                ? mainTopic + ".DLT"
                : dlqTopic.trim();

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) -> {
                    log.info("异常日志:[c2_notify][kafka] 重试耗尽 → DLQ, topic={}, partition={}, offset={}, dlt={}, err={}",
                            record.topic(), record.partition(), record.offset(), dlt,
                            ex == null ? "" : ex.getMessage());
                    // 固定分区 0，避免依赖主 topic 分区数；按 key 仍可后续扩展
                    return new TopicPartition(dlt, 0);
                });

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addRetryableExceptions(RetryConsumeException.class);
        handler.setCommitRecovered(true);
        handler.setLogLevel(KafkaException.Level.DEBUG);
        log.info("正常日志:[c2_notify][kafka] ErrorHandler 已启用 DLQ topic={}", dlt);
        return handler;
    }
}
