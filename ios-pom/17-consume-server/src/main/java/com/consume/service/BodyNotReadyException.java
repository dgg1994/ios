package com.consume.service;

/**
 * /t body 未就绪异常（对齐 XADD.md §5.6：body 相对 Content-Length 过短 → 抛错，由队列重试）。
 *
 * dispatcher 捕获此异常时不 ACK，等待重新投递。
 */
public class BodyNotReadyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BodyNotReadyException(String message) {
        super(message, null, false, false);
    }
}
