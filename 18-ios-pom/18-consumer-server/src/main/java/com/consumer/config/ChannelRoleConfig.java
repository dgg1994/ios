package com.consumer.config;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * 启动时打印本实例消费角色（主通道 / 相册 / 全开）。
 * 配置：{@code consumer.channel.main-enabled}、{@code consumer.channel.album-enabled}。
 */
@Configuration
public class ChannelRoleConfig {

    private static final Logger log = LoggerFactory.getLogger(ChannelRoleConfig.class);

    @Resource
    private ConsumerProperties props;

    @PostConstruct
    public void logRole() {
        boolean main = props.isMainChannelEnabled();
        boolean album = props.isAlbumChannelEnabled();
        if (!main && !album) {
            log.warn("【channel】main/album 均已关闭，本实例不会消费任何 Redis 任务队列");
            return;
        }
        String role;
        if (main && album) {
            role = "全开(主+相册)";
        } else if (main) {
            role = "仅主通道";
        } else {
            role = "仅相册/photo";
        }
        log.info("【channel】消费角色={} main-enabled={} album-enabled={}", role, main, album);
    }
}
