package com.consume.service;

/**
 * Kafka 消费需重试（记录未就绪 / 业务失败）。
 * 不填充堆栈，避免高并发重试时栈展开拖慢吞吐。
 */
public class RetryConsumeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RetryConsumeException(String message) {
        super(message, null, false, false);
    }
}
