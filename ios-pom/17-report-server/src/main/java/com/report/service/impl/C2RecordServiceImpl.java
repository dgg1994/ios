package com.report.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.report.dao.C2RecordDao;
import com.report.entity.C2RecordsEntity;
import com.report.service.C2RecordService;
import com.report.service.C2RecordStreamPublisher;
import com.report.service.KafkaPublisher;

/**
 * c2_records 入库服务。
 */
@Service
public class C2RecordServiceImpl implements C2RecordService {

    private static final Logger log = LoggerFactory.getLogger(C2RecordServiceImpl.class);

    @Autowired
    private C2RecordDao c2RecordDao;

    @Autowired
    private C2RecordStreamPublisher c2RecordStreamPublisher;

    @Autowired
    private KafkaPublisher kafkaPublisher;
    
    @Value("${pushMsg.kafka}")
    private boolean kafkaPush;
    
    @Value("${pushMsg.redis}")
    private boolean redisPush;

	@Override
	@Async("c2RecordExecutor")
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(C2RecordsEntity record) {
		try {
			c2RecordDao.insert(record);
			log.info("正常日志:c2_records 入库成功, id={}, kind={}", record.getId(), record.getKind());
		} catch (Exception e) {
			log.info("异常日志:c2_records 插入失败, kind={}, path={}, err={}", record.getKind(), record.getPath(),
					e.getMessage());
			return;
		}
		final long recordId = record.getId() == null ? 0L : record.getId();
		final String kind = record.getKind();
		final String version = record.getVersion();
		final String path = record.getPath();

		try {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {

					if (kafkaPush) {
						// Kafka 消息发送
						kafkaPublisher.publish(record);
					}
					if (redisPush) {
						// Redis Stream 通知
						c2RecordStreamPublisher.notifyNewRecord(recordId, kind, version, path);
					}
				}
			});
		} catch (Exception e) {
			log.info("异常日志:c2_records 事务同步注册失败, id={}, kind={}, err={}", recordId, kind, e.toString());
		}
	}
}

