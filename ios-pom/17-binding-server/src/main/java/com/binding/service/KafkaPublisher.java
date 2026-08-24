package com.binding.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.binding.dto.C2KafkaMessage;
import com.binding.entity.C2RecordsEntity;

import lombok.extern.slf4j.Slf4j;

/**
 * C2 业务 Kafka 发布器。
 */
@Service
@Slf4j
public class KafkaPublisher {

//    private static final Logger log = LoggerFactory.getLogger(KafkaPublisher.class);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${c2.kafka.topic:c2_records}")
    private String topic;

    /**
     * 发布 c2_records 业务消息。
     * 必须在 c2_records 入库成功之后调用（DB 主键在此时已回填到 entity）。
     */
    @Async("kafkaPublisherExecutor")
    public void publish(C2RecordsEntity record) {
        try {
            String kind = record.getKind() == null ? "unknown" : record.getKind();
            C2KafkaMessage message = new C2KafkaMessage(
                    record.getId() == null ? "" : String.valueOf(record.getId()),
                    kind,
                    record.getVersion(),
                    record.getPath(),
                    String.valueOf(System.currentTimeMillis())
            );
            String value = JSON.toJSONString(message);
            // key = recordId：打散分区，避免同 path（尤其 /t）挤在同一分区互相阻塞
            String key = (record.getId() != null) ? String.valueOf(record.getId()) : kind;
            kafkaTemplate.send(topic, key, value);
            log.info("正常日志:kafka 发送成功, topic={}, key={}, id={}", topic, key, message.getId());
        } catch (Exception e) {
            log.info("错误日志:kafka 发送失败, kind={}, path={}, err={}",
                    record.getKind(), record.getPath(), e.getMessage());
        }
    }
}
