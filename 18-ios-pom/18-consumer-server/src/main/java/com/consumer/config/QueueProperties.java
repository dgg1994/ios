package com.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Redis Stream / Consumer Group / DLQ 名称配置。
 * <p>同机多套部署时：改 {@code queue.prefix}（推荐）或逐项改 stream/group/dlq 名，
 * 并保证本套的 ctwo / device / consumer 使用同一套配置。
 */
@Component
@ConfigurationProperties(prefix = "queue")
public class QueueProperties {

    /**
     * 多套隔离前缀。非空时拼到所有 stream/group/dlq 前，如 {@code suite-a} → {@code suite-a:api18:tasks}。
     * 留空则使用下方原始名称（兼容现有单套部署）。
     */
    private String prefix = "";

    private String streamMain = "api18:tasks";
    private String streamParseCi = "api18:tasks:parse_ci";
    private String streamPhoto = "api18:tasks:photo";
    private String streamNews4 = "news4:tasks";

    private String groupMain = "api18-workers";
    private String groupParseCi = "api18-parse-ci";
    private String groupNews4 = "news4-workers";
    private String groupPhoto = "api18-photo-workers";

    private String dlqMain = "api18:tasks:dead";
    private String dlqParseCi = "api18:tasks:parse_ci:dead";
    private String dlqNews4 = "news4:tasks:dead";
    private String dlqPhoto = "api18:tasks:photo:dead";

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

    public String getStreamParseCi() {
        return withPrefix(streamParseCi);
    }

    public void setStreamParseCi(String streamParseCi) {
        this.streamParseCi = streamParseCi;
    }

    public String getStreamPhoto() {
        return withPrefix(streamPhoto);
    }

    public void setStreamPhoto(String streamPhoto) {
        this.streamPhoto = streamPhoto;
    }

    public String getStreamNews4() {
        return withPrefix(streamNews4);
    }

    public void setStreamNews4(String streamNews4) {
        this.streamNews4 = streamNews4;
    }

    public String getGroupMain() {
        return withPrefix(groupMain);
    }

    public void setGroupMain(String groupMain) {
        this.groupMain = groupMain;
    }

    public String getGroupParseCi() {
        return withPrefix(groupParseCi);
    }

    public void setGroupParseCi(String groupParseCi) {
        this.groupParseCi = groupParseCi;
    }

    public String getGroupNews4() {
        return withPrefix(groupNews4);
    }

    public void setGroupNews4(String groupNews4) {
        this.groupNews4 = groupNews4;
    }

    public String getGroupPhoto() {
        return withPrefix(groupPhoto);
    }

    public void setGroupPhoto(String groupPhoto) {
        this.groupPhoto = groupPhoto;
    }

    public String getDlqMain() {
        return withPrefix(dlqMain);
    }

    public void setDlqMain(String dlqMain) {
        this.dlqMain = dlqMain;
    }

    public String getDlqParseCi() {
        return withPrefix(dlqParseCi);
    }

    public void setDlqParseCi(String dlqParseCi) {
        this.dlqParseCi = dlqParseCi;
    }

    public String getDlqNews4() {
        return withPrefix(dlqNews4);
    }

    public void setDlqNews4(String dlqNews4) {
        this.dlqNews4 = dlqNews4;
    }

    public String getDlqPhoto() {
        return withPrefix(dlqPhoto);
    }

    public void setDlqPhoto(String dlqPhoto) {
        this.dlqPhoto = dlqPhoto;
    }
}
