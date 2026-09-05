package com.consume.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * PATH_HANDLERS 注册表 + dispatch 入口（对齐 XADD.md §4 PATH_HANDLERS / dispatch_after_archive）。
 *
 * 启动时收集所有 {@link C2Handler} Bean，按 path() 建立映射；
 * 未知 path 不进业务（ACK 跳过）。
 */
@Component
public class C2HandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(C2HandlerRegistry.class);

    private final Map<String, C2Handler> pathHandlers;

    @Autowired
    public C2HandlerRegistry(List<C2Handler> handlers) {
        Map<String, C2Handler> map = new HashMap<>();
        if (handlers != null) {
            for (C2Handler h : handlers) {
                String p = h.path();
                if (p == null || p.isEmpty()) {
                    continue;
                }
                map.put(p, h);
                log.debug("正常日志:[c2] 注册 handler path={}", p);
            }
        }
        this.pathHandlers = Collections.unmodifiableMap(map);
        log.info("正常日志:[c2] handlers 就绪 paths={}", map.keySet());
    }

    /** 是否存在该 path 的 handler */
    public boolean supports(String path) {
        return path != null && pathHandlers.containsKey(path);
    }

    /** 获取 handler；不存在返回 null */
    public C2Handler get(String path) {
        return path == null ? null : pathHandlers.get(path);
    }

    /** 已注册的路径集合（只读） */
    public Map<String, C2Handler> all() {
        return pathHandlers;
    }
}
