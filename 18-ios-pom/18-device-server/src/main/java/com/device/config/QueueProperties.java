package com.device.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Redis Stream 名称配置（与 18-consumer-server 的 queue.* 对齐）。
 */
@Component
@ConfigurationProperties(prefix = "queue")
public class QueueProperties {

    private String prefix = "";

    private String streamMain = "api18:tasks";

    public String withPrefix(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (prefix == null || prefix.trim().isEmpty()) {
            return name;
        }
        String p = prefix.trim();
        if (p.endsWith(":")) {
            p = p.substring(0, p.length() - 1);
        }
        return p + ":" + name;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String getStreamMain() {
        return withPrefix(streamMain);
    }

    public void setStreamMain(String streamMain) {
        this.streamMain = streamMain;
    }
}
