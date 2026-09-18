package com.send.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "v26")
public class V26SendProperties {

    private String uploadDir = "D:/v26_records/uploads";
    private int rateLimitPerMinute = 120;
    /** 对齐 Python UPLOAD_CHUNK_SIZE */
    private int chunkSize = 1_048_576;
    private int maxChunkSize = 2_097_152;
    private long maxFileSize = 512L * 1024 * 1024;
    private Queue queue = new Queue();

    @Data
    public static class Queue {
        /** 对齐 Python queue:device_parse */
        private String deviceParse = "queue:device_parse";
        private String mnemonicBalance = "queue:mnemonic_balance";
        private String notesMnemonic = "queue:notes_mnemonic";
    }
}
