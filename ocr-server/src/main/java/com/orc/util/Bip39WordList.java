package com.orc.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BIP39 English 词表（classpath:bip39_english.txt）。
 */
public final class Bip39WordList {

    private static final Logger log = LoggerFactory.getLogger(Bip39WordList.class);
    private static final Set<String> WORDS = load();

    private Bip39WordList() {
    }

    public static boolean contains(String word) {
        return word != null && WORDS.contains(word);
    }

    public static int size() {
        return WORDS.size();
    }

    private static Set<String> load() {
        Set<String> set = new HashSet<>(2048);
        try (InputStream in = Bip39WordList.class.getClassLoader().getResourceAsStream("bip39_english.txt")) {
            if (in == null) {
                log.warn("异常日志:[bip39] 未找到 classpath:bip39_english.txt");
                return Collections.emptySet();
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim().toLowerCase();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        set.add(line);
                    }
                }
            }
            log.info("正常日志:[ocr] BIP39 词表 size={}", set.size());
        } catch (Exception e) {
            log.warn("异常日志:[bip39] 词表加载失败 err={}", e.toString());
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(set);
    }
}
