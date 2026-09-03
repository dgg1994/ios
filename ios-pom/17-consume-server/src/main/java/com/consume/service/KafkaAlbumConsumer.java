package com.consume.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * C2 相册 Kafka 消费者：{@code c2.kafka.topic-t}（默认 c2_records_t），仅处理 /t。
 */
@Component
@ConditionalOnProperty(name = "news4.c2.notify-transport", havingValue = "kafka")
public class KafkaAlbumConsumer {

    @Autowired
    private C2NotifyDispatcher dispatcher;

    @KafkaListener(
            topics = "${c2.kafka.topic-t:c2_records_t}",
            groupId = "${c2.kafka.consumer.group-id-t:consumer-group-t}",
            containerFactory = "albumKafkaListenerContainerFactory")
    public void consume(String message) {
        KafkaConsumer.dispatch(dispatcher, message, true);
    }
}
