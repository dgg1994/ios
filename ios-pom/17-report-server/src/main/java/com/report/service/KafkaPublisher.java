package com.report.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.report.dto.C2KafkaMessage;
import com.report.entity.C2RecordsEntity;
import com.report.util.C2PathUtil;

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

    @Value("${c2.kafka.topic-t:c2_records_t}")
    private String albumTopic;

    /**
     * 发布 c2_records 业务消息。
     * 必须在 c2_records 入库成功之后调用（DB 主键在此时已回填到 entity）。
     */
    @Async("kafkaPublisherExecutor")
    public void publish(C2RecordsEntity record) {
        try {
            String kind = record.getKind() == null ? "unknown" : record.getKind();
            String path = record.getPath();
            C2KafkaMessage message = new C2KafkaMessage(
                    record.getId() == null ? "" : String.valueOf(record.getId()),
                    kind,
                    record.getVersion(),
                    path,
                    String.valueOf(System.currentTimeMillis())
            );
            String value = JSON.toJSONString(message);
            String targetTopic = resolveTopic(path);
            // 用 recordId 作 key：哈希打散各分区（null key 走粘性分区，突发时会严重倾斜）
            String key = (record.getId() != null) ? String.valueOf(record.getId()) : kind;
            kafkaTemplate.send(targetTopic, key, value);
            log.debug("正常日志:kafka 发送成功, topic={}, key={}, id={}, path={}",
                    targetTopic, key, message.getId(), path);
        } catch (Exception e) {
            log.info("错误日志:kafka 发送失败, kind={}, path={}, err={}",
                    record.getKind(), record.getPath(), e.getMessage());
        }
    }

    private String resolveTopic(String path) {
        return C2PathUtil.isAlbumPath(path) ? albumTopic : topic;
    }
}
