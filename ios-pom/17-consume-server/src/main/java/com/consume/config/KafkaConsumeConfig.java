package com.consume.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import com.consume.service.RetryConsumeException;

/**
 * Kafka 消费容错与双 topic 容器工厂（主队列 + /t 相册队列）。
 */
@Configuration
@ConditionalOnProperty(name = "news4.c2.notify-transport", havingValue = "kafka")
public class KafkaConsumeConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumeConfig.class);

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${c2.kafka.dlq-topic:}") String dlqTopic,
            @Value("${c2.kafka.topic:c2_records}") String mainTopic,
            @Value("${c2.kafka.retry.max-elapsed-ms:30000}") long maxElapsedMs) {
        DefaultErrorHandler handler = buildErrorHandler(kafkaTemplate, dlqTopic, mainTopic, maxElapsedMs);
        log.info("正常日志:[c2_notify][kafka] 主通道 ErrorHandler 已启用 DLQ topic={}, maxElapsedMs={}",
                dlqTopic == null || dlqTopic.trim().isEmpty() ? mainTopic + ".DLT" : dlqTopic.trim(),
                maxElapsedMs);
        return handler;
    }

    @Bean
    public DefaultErrorHandler kafkaAlbumErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${c2.kafka.dlq-topic-t:}") String dlqTopicT,
            @Value("${c2.kafka.topic-t:c2_records_t}") String albumTopic,
            @Value("${c2.kafka.retry.max-elapsed-ms-t:5000}") long maxElapsedMsT) {
        DefaultErrorHandler handler = buildErrorHandler(kafkaTemplate, dlqTopicT, albumTopic, maxElapsedMsT);
        log.info("正常日志:[c2_notify][kafka] 相册通道 ErrorHandler 已启用 DLQ topic={}, maxElapsedMs={}",
                dlqTopicT == null || dlqTopicT.trim().isEmpty() ? albumTopic + ".DLT" : dlqTopicT.trim(),
                maxElapsedMsT);
        return handler;
    }

    @Bean
    @Primary
    public ConcurrentKafkaListenerContainerFactory<String, String> mainKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            @Qualifier("kafkaErrorHandler") DefaultErrorHandler errorHandler,
            @Value("${spring.kafka.listener.concurrency:1}") int concurrency) {
        return buildFactory(consumerFactory, errorHandler, concurrency, "主通道");
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> albumKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            @Qualifier("kafkaAlbumErrorHandler") DefaultErrorHandler errorHandler,
            @Value("${c2.kafka.listener.concurrency-t:20}") int concurrency) {
        return buildFactory(consumerFactory, errorHandler, concurrency, "相册通道");
    }

    private static ConcurrentKafkaListenerContainerFactory<String, String> buildFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler,
            int concurrency,
            String tag) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(Math.max(1, concurrency));
        log.info("正常日志:[c2_notify][kafka] {} ListenerContainerFactory 已就绪, concurrency={}", tag, concurrency);
        return factory;
    }

    private static DefaultErrorHandler buildErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            String dlqTopic,
            String fallbackTopic,
            long maxElapsedMs) {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(15_000L);
        backOff.setMaxElapsedTime(Math.max(1000L, maxElapsedMs));

        String dlt = (dlqTopic == null || dlqTopic.trim().isEmpty())
                ? fallbackTopic + ".DLT"
                : dlqTopic.trim();

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) -> {
                    log.info("异常日志:[c2_notify][kafka] 重试耗尽 → DLQ, topic={}, partition={}, offset={}, dlt={}, err={}",
                            record.topic(), record.partition(), record.offset(), dlt,
                            ex == null ? "" : ex.getMessage());
                    return new TopicPartition(dlt, 0);
                });

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addRetryableExceptions(RetryConsumeException.class);
        handler.setCommitRecovered(true);
        handler.setLogLevel(KafkaException.Level.DEBUG);
        return handler;
    }
}
