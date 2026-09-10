package com.consume.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.ExponentialBackOff;

import com.consume.service.RetryConsumeException;

/**
 * Kafka 消费容错与双 topic 容器工厂（主队列 + /t 相册队列）。
 * 相册通道使用更小的 max.poll.records，避免 OCR/解图抢 CPU 时 poll 过慢触发 rebalance → CommitFailedException。
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

    /**
     * 相册专用 ConsumerFactory：更小批次 + 更长 max.poll.interval，降低 CommitFailedException。
     */
    @Bean
    public ConsumerFactory<String, String> albumKafkaConsumerFactory(
            KafkaProperties kafkaProperties,
            @Value("${c2.kafka.consumer.max-poll-records-t:100}") int maxPollRecordsT,
            @Value("${c2.kafka.consumer.max-poll-interval-ms-t:300000}") int maxPollIntervalMsT,
            @Value("${c2.kafka.consumer.session-timeout-ms-t:60000}") int sessionTimeoutMsT,
            @Value("${c2.kafka.consumer.heartbeat-interval-ms-t:20000}") int heartbeatIntervalMsT,
            @Value("${c2.kafka.consumer.request-timeout-ms-t:120000}") int requestTimeoutMsT,
            @Value("${c2.kafka.consumer.default-api-timeout-ms-t:120000}") int defaultApiTimeoutMsT) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties());
        int sessionMs = Math.max(10_000, sessionTimeoutMsT);
        int heartbeatMs = Math.max(3_000, Math.min(heartbeatIntervalMsT, sessionMs / 3));
        int requestMs = Math.max(sessionMs, requestTimeoutMsT);
        int apiMs = Math.max(requestMs, defaultApiTimeoutMsT);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Math.max(1, maxPollRecordsT));
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, Math.max(sessionMs * 2, maxPollIntervalMsT));
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionMs);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, heartbeatMs);
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestMs);
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, apiMs);
        log.info("正常日志:[c2_notify][kafka] 相册 ConsumerFactory max.poll.records={}, max.poll.interval.ms={}, "
                        + "session.timeout.ms={}, heartbeat.interval.ms={}, request.timeout.ms={}, default.api.timeout.ms={}",
                props.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG),
                props.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG),
                props.get(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG),
                props.get(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG),
                props.get(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG),
                props.get(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG));
        return new DefaultKafkaConsumerFactory<>(props);
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
            @Qualifier("albumKafkaConsumerFactory") ConsumerFactory<String, String> albumConsumerFactory,
            @Qualifier("kafkaAlbumErrorHandler") DefaultErrorHandler errorHandler,
            @Value("${c2.kafka.listener.concurrency-t:16}") int concurrency) {
        return buildFactory(albumConsumerFactory, errorHandler, concurrency, "相册通道");
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
        // 同步提交：避免异步 commit 失败被静默拖过，LAG 看起来永远不动
        factory.getContainerProperties().setSyncCommits(true);
        log.info("正常日志:[c2_notify][kafka] {} ListenerContainerFactory 已就绪, concurrency={}, syncCommits=true",
                tag, concurrency);
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

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff) {
            @Override
            public void handleOtherException(Exception thrownException, Consumer<?, ?> consumer,
                    MessageListenerContainer container, boolean batchListener) {
                // CommitFailed：分区已在 rebalance 中易主。DefaultErrorHandler 没有 record 上下文，
                // 调用 super 会再抛 IllegalStateException 刷屏；这里只记录并返回，下一轮 poll 会重入组。
                // 根因必须靠减小 max.poll.records / 增大 max.poll.interval.ms，否则会反复 CommitFailed、LAG 不降。
                if (isCommitFailed(thrownException)) {
                    log.info("异常日志:[c2_notify][kafka] CommitFailed → CURRENT-OFFSET 将停住、日志仍可能继续；"
                                    + "多为 rebalance/本机过载。降 concurrency 并保证 describe 有稳定 CONSUMER-ID。 err={}",
                            thrownException.getMessage());
                    return;
                }
                super.handleOtherException(thrownException, consumer, container, batchListener);
            }
        };
        handler.addRetryableExceptions(RetryConsumeException.class);
        handler.setCommitRecovered(true);
        handler.setLogLevel(KafkaException.Level.DEBUG);
        return handler;
    }

    private static boolean isCommitFailed(Throwable t) {
        while (t != null) {
            if (t instanceof CommitFailedException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
