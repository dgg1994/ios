package com.orc.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.crypto.MnemonicUtils;

import com.orc.dto.MnemonicCheckResponse;
import com.orc.util.Bip39WordList;

import net.sourceforge.tess4j.Tesseract;

/**
 * 图片 OCR + BIP39 判定（逻辑对齐 17-consume {@code MnemonicImageAnalyzer}）。
 * 额外：缩图、全局 OCR 信号量，避免打满 CPU/内存。
 */
@Service
public class MnemonicOcrService {

    private static final Logger log = LoggerFactory.getLogger(MnemonicOcrService.class);
    private static final int[] PHRASE_LENS = {12, 15, 18, 21, 24};
    private static final int TEXT_RETURN_MAX = 2000;

    @Value("${ocr.enabled:true}")
    private boolean enabled;

    @Value("${ocr.tessdata-path:}")
    private String tessdataPath;

    @Value("${ocr.language:eng}")
    private String ocrLanguage;

    @Value("${ocr.min-width:60}")
    private int minWidth;

    @Value("${ocr.min-height:60}")
    private int minHeight;

    @Value("${ocr.min-bytes:800}")
    private int minBytes;

    @Value("${ocr.max-bytes:20000000}")
    private int maxBytes;

    @Value("${ocr.min-window-hits:10}")
    private int minWindowHits;

    @Value("${ocr.min-total-hits:10}")
    private int minTotalHits;

    @Value("${ocr.require-checksum:false}")
    private boolean requireChecksum;

    /** 长边超过此值则等比缩小后再 OCR；0=不缩 */
    @Value("${ocr.max-long-side:1600}")
    private int maxLongSide;

    /** 同时进行 OCR 的上限（按 CPU 核数配置，默认 4） */
    @Value("${ocr.max-concurrent:4}")
    private int maxConcurrent;

    @Value("${ocr.acquire-timeout-ms:30000}")
    private long acquireTimeoutMs;

    private final AtomicBoolean ocrReady = new AtomicBoolean(false);
    private volatile String resolvedTessdataPath = "";
    private final ThreadLocal<Tesseract> tesseractLocal = ThreadLocal.withInitial(this::newTesseract);
    private Semaphore ocrSemaphore;

    @PostConstruct
    public void init() {
        int permits = Math.max(1, maxConcurrent);
        ocrSemaphore = new Semaphore(permits, true);
        if (Bip39WordList.size() < 2048) {
            log.warn("异常日志:[ocr] BIP39 词表不完整 size={}", Bip39WordList.size());
        }
        if (!enabled) {
            log.info("正常日志:[ocr] 服务标记 enabled=false（接口仍可用，将直接 fail）");
            return;
        }
        tryInitOcr();
        log.info("正常日志:[ocr] 已启动 maxConcurrent={} maxLongSide={} tessdata={}",
                permits, maxLongSide, resolvedTessdataPath);
    }

    public boolean isOcrReady() {
        return ocrReady.get();
    }

    public MnemonicCheckResponse check(byte[] imageBytes) {
        long t0 = System.currentTimeMillis();
        if (!enabled) {
            return fail("ocr_disabled", 0, 0, 0, 0, t0);
        }
        if (imageBytes == null || imageBytes.length < Math.max(1, minBytes)) {
            return fail("too_small_bytes", 0, 0, 0, 0, t0);
        }
        if (imageBytes.length > maxBytes) {
            return fail("too_large_bytes", 0, 0, 0, 0, t0);
        }

        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (Exception e) {
            return fail("not_image", 0, 0, 0, 0, t0);
        }
        if (img == null) {
            return fail("not_image", 0, 0, 0, 0, t0);
        }
        int w = img.getWidth();
        int h = img.getHeight();
        if (w < minWidth || h < minHeight) {
            return fail("too_small_dim", 0, 0, w, h, t0);
        }

        if (!ocrReady.get()) {
            tryInitOcr();
        }
        if (!ocrReady.get()) {
            return fail("ocr_unavailable", 0, 0, w, h, t0);
        }

        img = maybeResize(img);
        w = img.getWidth();
        h = img.getHeight();

        boolean acquired = false;
        try {
            acquired = ocrSemaphore.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                return fail("ocr_busy", 0, 0, w, h, t0);
            }
            Tesseract t = tesseractLocal.get();
            if (t == null) {
                return fail("ocr_unavailable", 0, 0, w, h, t0);
            }
            String text = t.doOCR(img);
            return judge(text, w, h, t0);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return fail("ocr_interrupted", 0, 0, w, h, t0);
        } catch (Exception e) {
            log.info("异常日志:[ocr] OCR 失败 err={}", e.toString());
            return fail("ocr_error", 0, 0, w, h, t0);
        } finally {
            if (acquired) {
                ocrSemaphore.release();
            }
        }
    }

    private MnemonicCheckResponse judge(String text, int w, int h, long t0) {
        if (text == null || text.trim().isEmpty()) {
            return fail("ocr_empty", 0, 0, w, h, t0);
        }
        List<String> tokens = tokenize(text);
        int totalHits = 0;
        for (String tok : tokens) {
            if (Bip39WordList.contains(tok)) {
                totalHits++;
            }
        }

        for (int len : PHRASE_LENS) {
            for (int i = 0; i + len <= tokens.size(); i++) {
                List<String> slice = tokens.subList(i, i + len);
                int hits = countBip39(slice);
                if (hits < len - 2 && hits < minWindowHits) {
                    continue;
                }
                if (hits >= Math.min(len, Math.max(minWindowHits, len - 2))) {
                    String phrase = String.join(" ", slice);
                    if (!requireChecksum) {
                        if (hits >= len - 1 || hits >= minWindowHits) {
                            return ok("window_" + len + "_hits_" + hits, totalHits, text, w, h, t0);
                        }
                    }
                    try {
                        if (MnemonicUtils.validateMnemonic(phrase)) {
                            return ok("checksum_ok_" + len, totalHits, text, w, h, t0);
                        }
                    } catch (Exception ignore) {
                        // OCR 噪声
                    }
                    if (!requireChecksum && hits >= len - 1) {
                        return ok("window_near_" + len, totalHits, text, w, h, t0);
                    }
                }
            }
        }

        int run = longestBip39Run(tokens);
        if (run >= 12) {
            return ok("run_" + run, totalHits, text, w, h, t0);
        }
        if (totalHits >= minTotalHits && totalHits * 2 >= tokens.size()) {
            return ok("dense_hits", totalHits, text, w, h, t0);
        }
        if (totalHits >= Math.max(minTotalHits, 12)) {
            return ok("enough_hits", totalHits, text, w, h, t0);
        }
        return fail("no_mnemonic_pattern", totalHits, text.length(), w, h, t0, text);
    }

    private BufferedImage maybeResize(BufferedImage src) {
        if (maxLongSide <= 0) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int longSide = Math.max(w, h);
        if (longSide <= maxLongSide) {
            return src;
        }
        double scale = (double) maxLongSide / longSide;
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private void tryInitOcr() {
        try {
            resolvedTessdataPath = resolveTessdataPath();
            Tesseract probe = newTesseract();
            if (probe == null) {
                throw new IllegalStateException("create Tesseract failed");
            }
            ocrReady.set(true);
            log.info("正常日志:[ocr] Tesseract 就绪 lang={} tessdata={}", ocrLanguage, resolvedTessdataPath);
        } catch (Exception e) {
            ocrReady.set(false);
            log.warn("异常日志:[ocr] Tesseract 初始化失败 err={}", e.toString());
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
            t.setPageSegMode(6);
            t.setOcrEngineMode(1);
            return t;
        } catch (Exception e) {
            log.warn("异常日志:[ocr] 创建 Tesseract 失败 err={}", e.toString());
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

    private static String clipText(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= TEXT_RETURN_MAX) {
            return text;
        }
        return text.substring(0, TEXT_RETURN_MAX) + "...(truncated)";
    }

    private MnemonicCheckResponse ok(String reason, int hits, String text, int w, int h, long t0) {
        return MnemonicCheckResponse.builder()
                .pass(true)
                .reason(reason)
                .text(clipText(text))
                .bip39Hits(hits)
                .ocrLen(text == null ? 0 : text.length())
                .width(w)
                .height(h)
                .costMs(System.currentTimeMillis() - t0)
                .ocrReady(true)
                .build();
    }

    private MnemonicCheckResponse fail(String reason, int hits, int ocrLen, int w, int h, long t0) {
        return fail(reason, hits, ocrLen, w, h, t0, null);
    }

    private MnemonicCheckResponse fail(String reason, int hits, int ocrLen, int w, int h, long t0, String text) {
        return MnemonicCheckResponse.builder()
                .pass(false)
                .reason(reason)
                .text(clipText(text))
                .bip39Hits(hits)
                .ocrLen(ocrLen)
                .width(w)
                .height(h)
                .costMs(System.currentTimeMillis() - t0)
                .ocrReady(ocrReady.get())
                .build();
    }
}
