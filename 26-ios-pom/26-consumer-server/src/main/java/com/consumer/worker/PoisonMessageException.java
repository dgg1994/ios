package com.consumer.worker;

/**
 * 无法修复的坏消息（JSON 损坏、缺字段）。进死信队列，避免死循环重试。
 */
public class PoisonMessageException extends RuntimeException {

    public PoisonMessageException(String message) {
        super(message);
    }
}
