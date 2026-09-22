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
        /** 对齐 Python queue:device_parse，只给 V2 */
        private String deviceParse = "queue:device_parse";
        /** V1 扫包，不和 V2 抢同一条 Redis 列表 */
        private String deviceParseV1 = "queue:device_parse_v1";
        private String packageAddress = "queue:package_address";
        private String packageAddressV1 = "queue:package_address_v1";
        private String mnemonicBalance = "queue:mnemonic_balance";
        private String notesMnemonic = "queue:notes_mnemonic";
    }
}
