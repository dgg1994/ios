package com.consume.config;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 启动时打印本实例消费角色（主通道 / 相册 / 全开）。
 * 配置项：{@code c2.channel.main-enabled}、{@code c2.channel.album-enabled}。
 */
@Configuration
public class C2ChannelRoleConfig {

    private static final Logger log = LoggerFactory.getLogger(C2ChannelRoleConfig.class);

    @Value("${c2.channel.main-enabled:true}")
    private boolean mainEnabled;

    @Value("${c2.channel.album-enabled:true}")
    private boolean albumEnabled;

    @Value("${news4.c2.notify-transport:kafka}")
    private String transport;

    @PostConstruct
    public void logRole() {
        if (!mainEnabled && !albumEnabled) {
            log.info("异常日志:[c2] channel 主/相册均已关闭，本实例不会消费任何 C2 通知 (transport={})",
                    transport);
            return;
        }
        String role;
        if (mainEnabled && albumEnabled) {
            role = "全开(主+相册)";
        } else if (mainEnabled) {
            role = "仅主通道";
        } else {
            role = "仅相册/t";
        }
        log.info("正常日志:[c2] 消费角色={}, transport={}, main-enabled={}, album-enabled={}",
                role, transport, mainEnabled, albumEnabled);
    }
}
