package com.consume.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;
import com.consume.dto.C2NotifyMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * C2 通知 Kafka 消费者（c2_notify_worker 的 Kafka 通道）。
 *
 * topic = {@code c2.kafka.topic}（默认 c2_records），消息体为 {@link C2NotifyMessage} 的 JSON。
 * 解析后交给 {@link C2NotifyDispatcher} 直接执行业务。
 *
 * <p>通道开关：仅当 {@code news4.c2.notify-transport=kafka} 时本 Bean 才会被创建，
 * 否则不启动 Kafka 监听（与 Redis 通道二选一）。</p>
 *
 * 入队/业务失败时抛出异常，由 Spring Kafka 默认错误处理器重试（re-seek）。
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "news4.c2.notify-transport", havingValue = "kafka")
public class KafkaConsumer {

    @Autowired
    private C2NotifyDispatcher dispatcher;

    /**
     * 消费 c2_records 通知消息。
     * topic / group 均可配置，同机多套部署请使用不同 topic（或至少不同 group-id）。
     */
    @KafkaListener(
            topics = "${c2.kafka.topic:c2_records}",
            groupId = "${spring.kafka.consumer.group-id:test-consumer-group}")
    public void consume(String message) {
        C2NotifyMessage msg;
        try {
            msg = JSON.parseObject(message, C2NotifyMessage.class);
        } catch (Exception e) {
            log.info("异常日志:[c2_notify][kafka] 解析失败，ACK 丢弃, err={}, body={}", e.getMessage(), message);
            // 解析失败视为应 ACK 丢弃，不重试
            return;
        }

        boolean shouldAck = dispatcher.handle("kafka", "kafka", msg);
        if (!shouldAck) {
            // 业务未就绪/失败 → 不 ACK，抛异常触发 Spring Kafka 重试（re-seek）
            throw new RetryConsumeException("[c2_notify][kafka] 业务未就绪/失败，触发重试, id="
                    + (msg == null ? null : msg.getId()));
        }
    }
}
