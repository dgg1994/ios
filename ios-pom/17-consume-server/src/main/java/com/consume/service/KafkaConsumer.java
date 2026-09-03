package com.consume.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;
import com.consume.dto.C2NotifyMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * C2 主通道 Kafka 消费者：{@code c2.kafka.topic}（默认 c2_records），不含 /t。
 * /t 走 {@link KafkaAlbumConsumer}；主 topic 中遗留 /t 消息会 ACK 跳过。
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "news4.c2.notify-transport", havingValue = "kafka")
public class KafkaConsumer {

    @Autowired
    private C2NotifyDispatcher dispatcher;

    @KafkaListener(
            topics = "${c2.kafka.topic:c2_records}",
            groupId = "${spring.kafka.consumer.group-id:consumer-group}",
            containerFactory = "mainKafkaListenerContainerFactory")
    public void consume(String message) {
        dispatch(message, false);
    }

    static void dispatch(C2NotifyDispatcher dispatcher, String message, boolean albumChannel) {
        C2NotifyMessage msg;
        try {
            msg = JSON.parseObject(message, C2NotifyMessage.class);
        } catch (Exception e) {
            log.info("异常日志:[c2_notify][kafka] 解析失败，ACK 丢弃, err={}, body={}", e.getMessage(), message);
            return;
        }

        boolean shouldAck = dispatcher.handle("kafka", "kafka", msg, albumChannel);
        if (!shouldAck) {
            throw new RetryConsumeException("[c2_notify][kafka] 业务未就绪/失败，触发重试, id="
                    + (msg == null ? null : msg.getId()));
        }
    }

    private void dispatch(String message, boolean albumChannel) {
        dispatch(dispatcher, message, albumChannel);
    }
}
