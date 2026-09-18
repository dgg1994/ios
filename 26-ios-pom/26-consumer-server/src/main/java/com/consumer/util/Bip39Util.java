package com.consumer.util;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * BIP39 英文单词表工具类。
 *
 * <p>启动时从 GitHub 下载标准 BIP39 英文单词表（2048 词），缓存到本地文件。
 * 提供 entropy → mnemonic 转换和 mnemonic 校验功能。
 *
 * <p>对应 Python 版 recover_hq8_mnemonics.py 中的 _wordlist() / entropy_to_mnemonic() / validate_mnemonic()。
 */
@Component
@Slf4j
public class Bip39Util {

    private static final String BIP39_URL =
            "https://raw.githubusercontent.com/bitcoin/bips/master/bip-0039/english.txt";

    /** BIP39 助记词正则：12-24 个连续小写单词。静态编译一次，避免每次 searchPhrase 重复编译。 */
    private static final Pattern PHRASE_PATTERN =
            Pattern.compile("\\b([a-z]+(?: [a-z]+){11,23})\\b");

    @Value("${v26.upload-dir:D:/v26_records/uploads}")
    private String uploadDir;

    private volatile List<String> wordlist = null;
    /** 词→索引映射：validateMnemonic 的 O(1) 查词，替代 wordlist.indexOf 的 O(n) 扫描 */
    private volatile Map<String, Integer> wordIndex = null;

    @PostConstruct
    public void init() {
        Path cachePath = getCachePath();
        // 1. 尝试从本地缓存加载
        if (loadFromFile(cachePath)) {
            return;
        }
        // 2. 尝试从 classpath 加载
        if (loadFromClasspath()) {
            // 保存到缓存
            saveCache(cachePath);
            return;
        }
        // 3. 尝试从 GitHub 下载
        if (downloadWordlist(cachePath)) {
            return;
        }
        log.warn("BIP39 wordlist NOT loaded! entropyToMnemonic/validateMnemonic will use fallback validation.");
    }

    /** 是否已加载单词表 */
    public boolean isReady() {
        return wordlist != null && wordlist.size() == 2048 && wordIndex != null;
    }

    /**
     * 熵 → BIP39 助记词（对应 Python entropy_to_mnemonic）。
     *
     * @param entropy 熵字节（长度必须 16/20/24/28/32）
     * @return 助记词字符串（空格分隔），失败返回 null
     */
    public String entropyToMnemonic(byte[] entropy) {
        if (!isReady() || entropy == null) return null;
        if (entropy.length != 16 && entropy.length != 20 &&
            entropy.length != 24 && entropy.length != 28 && entropy.length != 32) {
            return null;
        }
        try {
            // 熵 → 二进制串
            StringBuilder bits = new StringBuilder();
            for (byte b : entropy) {
                bits.append(String.format("%8s", Integer.toBinaryString(b & 0xFF))
                        .replace(' ', '0'));
            }
            // 校验和 = SHA256(entropy) 的前 N 位（N = entropy.length / 4）
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(entropy);
            int csLen = entropy.length / 4;
            String hashBits = String.format("%8s", Integer.toBinaryString(hash[0] & 0xFF))
                    .replace(' ', '0');
            bits.append(hashBits.substring(0, csLen));

            // 每 11 位 → 一个单词索引
            StringBuilder mn = new StringBuilder();
            for (int i = 0; i + 11 <= bits.length(); i += 11) {
                int idx = Integer.parseInt(bits.substring(i, i + 11), 2);
                if (idx >= wordlist.size()) return null;
                if (mn.length() > 0) mn.append(' ');
                mn.append(wordlist.get(idx));
            }
            return mn.toString();
        } catch (Exception e) {
            log.error("entropyToMnemonic FAIL: {}", e.toString());
            return null;
        }
    }

    /**
     * 校验 BIP39 助记词（对应 Python validate_mnemonic）。
     * 1. 单词数必须是 12/15/18/21/24
     * 2. 每个词必须在单词表中
     * 3. 校验和必须匹配
     *
     * @param mnemonic 助记词字符串
     * @return true 如果校验通过
     */
    public boolean validateMnemonic(String mnemonic) {
        if (mnemonic == null || mnemonic.isEmpty()) return false;
        if (!isReady()) return fallbackValidate(mnemonic);

        String[] ws = mnemonic.trim().toLowerCase().split("\\s+");
        if (ws.length != 12 && ws.length != 15 &&
            ws.length != 18 && ws.length != 21 && ws.length != 24) {
            return false;
        }
        try {
            // 每个词 → 11 位二进制（用 HashMap O(1) 查词，替代原 indexOf 的 O(n) 线性扫描）
            StringBuilder bits = new StringBuilder();
            for (String w : ws) {
                Integer idx = wordIndex.get(w);
                if (idx == null) return false;
                bits.append(String.format("%11s", Integer.toBinaryString(idx))
                        .replace(' ', '0'));
            }
            // 拆分熵和校验和
            int entBits = ws.length * 11 / 33 * 32;
            int csBits = ws.length * 11 - entBits;
            // 提取熵字节
            String entStr = bits.substring(0, entBits);
            int entBytes = entBits / 8;
            byte[] ent = new byte[entBytes];
            for (int i = 0; i < entBytes; i++) {
                ent[i] = (byte) Integer.parseInt(entStr.substring(i * 8, i * 8 + 8), 2);
            }
            // 计算期望的校验和
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(ent);
            String expectedCs = String.format("%8s", Integer.toBinaryString(hash[0] & 0xFF))
                    .replace(' ', '0').substring(0, csBits);
            return bits.substring(entBits).equals(expectedCs);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 在文本中搜索 BIP39 助记词（对应 Python BIP39_RE）。
     * 使用 12-24 连续小写单词的正则，再用 validateMnemonic 校验。
     *
     * @param text 待搜索的文本
     * @return 找到的助记词，未找到返回 null
     */
    public String searchPhrase(String text) {
        List<String> all = scanAllMnemonics(text, 24, 12);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 从文本提取全部合法 BIP39（长窗优先，避免 24 词再切 12 词）。
     */
    public List<String> scanAllMnemonics(String text, int... lengths) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int[] lens = (lengths == null || lengths.length == 0) ? new int[] {24, 12} : lengths;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        String normalized = text.trim().replaceAll("\\s+", " ").replaceAll("^[\"']|[\"']$", "").toLowerCase();
        if (validateMnemonic(normalized) && wordCountIn(normalized, lens)) {
            seen.add(normalized);
            out.add(normalized);
        }
        if (!isReady()) {
            return out;
        }
        java.util.regex.Matcher tm = Pattern.compile("[a-z]+").matcher(text.toLowerCase());
        List<String> tokens = new ArrayList<>();
        while (tm.find()) {
            tokens.add(tm.group());
        }
        boolean[] used = new boolean[tokens.size()];
        for (int n : lens) {
            if (n <= 0) {
                continue;
            }
            for (int i = 0; i <= tokens.size() - n; i++) {
                boolean overlap = false;
                for (int j = i; j < i + n; j++) {
                    if (used[j]) {
                        overlap = true;
                        break;
                    }
                }
                if (overlap) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                boolean allIn = true;
                for (int j = i; j < i + n; j++) {
                    String t = tokens.get(j);
                    if (wordIndex.get(t) == null) {
                        allIn = false;
                        break;
                    }
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(t);
                }
                if (!allIn) {
                    continue;
                }
                String candidate = sb.toString();
                if (!validateMnemonic(candidate)) {
                    continue;
                }
                for (int j = i; j < i + n; j++) {
                    used[j] = true;
                }
                if (seen.add(candidate)) {
                    out.add(candidate);
                }
            }
        }
        return out;
    }

    public String hexEntropyToMnemonic(String hex) {
        if (hex == null) {
            return null;
        }
        String raw = hex.replaceAll("[^0-9a-fA-F]", "");
        if (raw.length() != 32 && raw.length() != 40 && raw.length() != 48
                && raw.length() != 56 && raw.length() != 64) {
            return null;
        }
        int len = raw.length() / 2;
        byte[] ent = new byte[len];
        for (int i = 0; i < len; i++) {
            ent[i] = (byte) Integer.parseInt(raw.substring(i * 2, i * 2 + 2), 16);
        }
        String phrase = entropyToMnemonic(ent);
        return (phrase != null && validateMnemonic(phrase)) ? phrase : null;
    }

    private static boolean wordCountIn(String phrase, int[] lens) {
        int n = phrase.trim().split("\\s+").length;
        for (int l : lens) {
            if (n == l) {
                return true;
            }
        }
        return false;
    }

    // ==================== 内部方法 ====================

    private Path getCachePath() {
        String base = uploadDir == null || uploadDir.isEmpty() ? "data/uploads" : uploadDir;
        Path p = Paths.get(base, "bip39_english.txt");
        // 如果目录不可写，兜底到系统 temp 目录
        Path parent = p.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (Exception e) {
                return Paths.get(System.getProperty("java.io.tmpdir"), "bip39_english.txt");
            }
        }
        return p;
    }

    private boolean loadFromFile(Path path) {
        try {
            if (!Files.isRegularFile(path)) return false;
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<String> words = new ArrayList<>(2048);
            for (String line : lines) {
                String w = line.trim();
                if (!w.isEmpty()) words.add(w);
            }
            if (words.size() == 2048) {
                wordlist = words;
                wordIndex = buildIndex(words);
                return true;
            }
            log.warn("BIP39 cache file has {} words (expected 2048), ignoring", words.size());
            return false;
        } catch (Exception e) {
            log.warn("loadFromFile FAIL: {}", e.toString());
            return false;
        }
    }

    private boolean loadFromClasspath() {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("bip39_english.txt")) {
            if (is == null) return false;
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            List<String> words = new ArrayList<>(2048);
            for (String line : content.split("\n")) {
                String w = line.trim();
                if (!w.isEmpty()) words.add(w);
            }
            if (words.size() == 2048) {
                wordlist = words;
                wordIndex = buildIndex(words);
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean downloadWordlist(Path cachePath) {
        try {
            URL url = new URL(BIP39_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            if (conn.getResponseCode() != 200) {
                log.warn("BIP39 download HTTP {}", conn.getResponseCode());
                return false;
            }
            try (InputStream is = conn.getInputStream()) {
                byte[] data = is.readAllBytes();
                String text = new String(data, StandardCharsets.UTF_8);
                List<String> words = new ArrayList<>(2048);
                for (String line : text.split("\n")) {
                    String w = line.trim();
                    if (!w.isEmpty()) words.add(w);
                }
                if (words.size() != 2048) {
                    log.warn("Downloaded BIP39 wordlist has {} words (expected 2048)", words.size());
                    return false;
                }
                wordlist = words;
                wordIndex = buildIndex(words);
                // 保存到缓存
                saveCache(cachePath);
                return true;
            }
        } catch (Exception e) {
            log.warn("downloadWordlist FAIL: {}", e.toString());
            return false;
        }
    }

    /** 构建 词→索引 的 HashMap，validateMnemonic 用 O(1) 查询替代 O(n) indexOf */
    private static Map<String, Integer> buildIndex(List<String> words) {
        Map<String, Integer> idx = new HashMap<>(words.size() * 2);
        for (int i = 0; i < words.size(); i++) idx.put(words.get(i), i);
        return Collections.unmodifiableMap(idx);
    }

    private void saveCache(Path cachePath) {
        try {
            Files.createDirectories(cachePath.getParent());
            StringBuilder sb = new StringBuilder();
            for (String w : wordlist) {
                sb.append(w).append('\n');
            }
            Files.write(cachePath, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("saveCache FAIL: {}", e.toString());
        }
    }

    /** 无单词表时的兜底校验：单词数 + 全小写 + 每词 3-8 字符 */
    private boolean fallbackValidate(String mnemonic) {
        if (mnemonic == null) return false;
        String[] ws = mnemonic.trim().toLowerCase().split("\\s+");
        if (ws.length != 12 && ws.length != 15 &&
            ws.length != 18 && ws.length != 21 && ws.length != 24) {
            return false;
        }
        for (String w : ws) {
            if (w.length() < 3 || w.length() > 8) return false;
            for (char c : w.toCharArray()) {
                if (c < 'a' || c > 'z') return false;
            }
        }
        return true;
    }
}
