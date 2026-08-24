package com.report.dto;

import com.alibaba.fastjson.annotation.JSONField;

/**
 * C2 业务 Kafka 消息（极简格式）。
 */
public class C2KafkaMessage {

    @JSONField(ordinal = 1)
    private String id;

    @JSONField(ordinal = 2)
    private String kind;

    @JSONField(ordinal = 3)
    private String version;

    @JSONField(ordinal = 4)
    private String path;

    @JSONField(name = "enqueued_at", ordinal = 5)
    private String enqueuedAt;

    public C2KafkaMessage() {
    }

    public C2KafkaMessage(String id, String kind, String version, String path, String enqueuedAt) {
        this.id = id;
        this.kind = kind;
        this.version = version;
        this.path = path;
        this.enqueuedAt = enqueuedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getEnqueuedAt() { return enqueuedAt; }
    public void setEnqueuedAt(String enqueuedAt) { this.enqueuedAt = enqueuedAt; }
}