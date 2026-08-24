package com.consume.service;

/**
 * C2 业务处理器接口（对齐 XADD.md §4 PATH_HANDLERS[path]）。
 *
 * 各实现按 path 处理：解密 → 落库解密结果 → 业务落库 → 关联 c2_records.device_id。
 */
public interface C2Handler {

    /**
     * 该 handler 负责的规范化路径，如 "/event"。
     */
    String path();

    /**
     * 处理一条 c2 记录。
     *
     * @param ctx 上下文（已加载 c2_records 并完成解密）
     * @throws Exception 业务失败时抛出，由 dispatcher 决定是否重试
     */
    void handle(C2HandlerContext ctx) throws Exception;
}
