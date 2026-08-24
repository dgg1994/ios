package com.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import javax.annotation.Resource;

/**
 * Consumer Worker 运行参数（文档 §8）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "consumer")
public class ConsumerProperties {

    @Resource
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private QueueProperties queueProperties;

    /** 主队列并发处理数（默认 4，多实例部署可适当调小） */
    private int mainThreads = 4;

    /** parse_ci 并发处理数（默认 2，parse_ci 是 CPU 密集型，不建议过大） */
    private int parseCiThreads = 2;

    /** XAUTOCLAIM idle 毫秒（parse_ci 默认 30 分钟） */
    private long autoClaimIdleMs = 1800000L;

    /** 主队列 XAUTOCLAIM idle 毫秒（默认 10 分钟） */
    private long mainClaimIdleMs = 600000L;

    /** 单条消息最大重试次数（含首次消费，超过进 DLQ）；默认 5 次即最多重试 4 轮 */
    private int maxAttempts = 5;

    /**
     * 重试退避基准毫秒（指数退避：delay = base * 2^(attempt-1)）。
     * 默认 1000ms → 第 1 次重试等 1s，第 2 次 2s，第 3 次 4s，第 4 次 8s。
     */
    private long retryBackoffBaseMs = 1000L;

    /** DLQ 消息最长保留毫秒（默认 7 天），超期由 DLQ 清理线程批量删除 */
    private long dlqRetentionMs = 7L * 24 * 60 * 60 * 1000;

    /** 助记词 AES 密钥（须与 news4 一致） */
    private String mnemonicAesKey = "change_me_32bytes_key_xxxxxxxx";

    /** 是否向 news4:tasks 投递 wallet_derive */
    private boolean news4TaskEnabled = true;

    /** news4 队列名（来自 queue.stream-news4，支持 queue.prefix） */
    public String getNews4Stream() {
        return queueProperties.getStreamNews4();
    }

    /** 落盘根（同 API 进程 api18.upload-dir，war_unpack 读 .bin 用） */
    private String uploadDir = "data/uploads";

    /** 每次 XREADGROUP / XAUTOCLAIM 批量大小（主队列） */
    private int batchSize = 16;

    /** parse_ci 每次 XREADGROUP 批量大小（CPU 密集，默认 4） */
    private int parseCiBatchSize = 4;

    /** news4 每次 XREADGROUP 批量大小（默认 8） */
    private int news4BatchSize = 8;

    /** 主 consumer 轮询间隔 ms */
    private long pollIntervalMs = 500L;

    /** parse_ci consumer 轮询间隔 ms（长任务轮询可慢点） */
    private long parseCiPollIntervalMs = 2000L;

    /** news4 并发处理数（默认 2，news4 含 AES 解密 + 地址派生，CPU 密集） */
    private int news4Threads = 2;

    /** news4 consumer 轮询间隔 ms */
    private long news4PollIntervalMs = 1000L;

    /** news4 XAUTOCLAIM idle 毫秒（默认 10 分钟） */
    private long news4ClaimIdleMs = 600000L;

    /** photo 队列并发处理数（I/O 密集型，默认 2） */
    private int photoThreads = 2;

    /** photo consumer 轮询间隔 ms */
    private long photoPollIntervalMs = 1000L;

    /** photo XAUTOCLAIM idle 毫秒（默认 10 分钟） */
    private long photoClaimIdleMs = 600000L;

    /** photo 队列名（来自 queue.stream-photo，支持 queue.prefix） */
    public String getPhotoStream() {
        return queueProperties.getStreamPhoto();
    }

    /** photo 批量拉取大小 */
    private int photoBatchSize = 8;

    /** nb_notestore 并发（sqlite/gzip，默认 1～2） */
    private int nbNotestoreThreads = 2;

    /** nb_notestore 轮询间隔 ms */
    private long nbNotestorePollIntervalMs = 1500L;

    /** nb_notestore XAUTOCLAIM idle 毫秒（默认 15 分钟） */
    private long nbNotestoreClaimIdleMs = 900000L;

    /** nb_notestore 批量拉取大小 */
    private int nbNotestoreBatchSize = 4;

    /** 解密后图片落盘目录（nginx/后台静态目录应对齐） */
    private String photoDir = "/opt/news4/data/photos";

    /** 写入 album.image_path 的相对路径前缀；后台/nginx 需映射到 photo-dir */
    private String photoUrlPrefix = "admin/data/photos";

    /**
     * Tonhub 4 位 PIN 暴力破解的同步等待超时毫秒。
     * <p>超时后未命中则转入后台异步继续破解（通过 savePhraseLater 补写入库 + 推送 news4），
     * parse_ci worker 线程立即释放处理后续任务。
     * <ul>
     *   <li>默认 5000ms（5 秒）：PIN 0000-~1500 通常在 5s 内能命中，命中则同步返回</li>
     *   <li>设为 0：完全异步，worker 线程从不阻塞，所有结果走 savePhraseLater</li>
     *   <li>设为 30000+：恢复旧行为（30s 同步等待，可能阻塞 worker）</li>
     * </ul>
     */
    private long tonhubBruteSyncTimeoutMs = 5000L;

    /**
     * Tonhub PIN 暴力线程池线程数。0=自动（CPU/2，最少2，最多8）。
     * 串行化开启后（tonhub-brute-serialize=true），同时只有 permits 个 device 在爆破，
     * 不会打满 CPU，可用 CPU/2 线程加速单 device 爆破。
     * 可手动指定如 4 来强制使用更多线程。
     */
    private int tonhubBruteThreads = 0;

    /**
     * 是否开启 Tonhub 暴力串行化（Semaphore 限制同时爆破的 device 数）。
     * 默认 true：避免多 device 同时暴力打满 CPU，拖慢 parse_ci 主流程。
     * 拿不到许可的 parse_ci 任务直接跳过 Tonhub 扫描（不阻塞 worker，依赖消息重试机制后续再爆）。
     */
    private boolean tonhubBruteSerialize = true;

    /**
     * Tonhub 暴力串行化许可数。默认 1：同时只 1 个 device 在爆破。
     * 多实例部署时可设为实例数，每实例 1 个许可。
     */
    private int tonhubBruteSerializePermits = 1;

    /** consumerName（用于 XREADGROUP/XCLAIM 的区分不同实例）；null 用 host */
    private String consumerName;
}
