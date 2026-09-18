package com.admin.parse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * 对齐 Python imtoken_wallet：walletsV2 + WalletModel 多身份拆卡。
 */
@Component
public class ImTokenWallet {

    public static class Identity {
        private String walletId = "";
        private String name = "";
        private String passwordHint = "";
        private boolean hasKeystore;
        private String sourceFingerprint = "";

        public String getWalletId() {
            return walletId;
        }

        public String getName() {
            return name;
        }

        public String getPasswordHint() {
            return passwordHint;
        }

        public boolean isHasKeystore() {
            return hasKeystore;
        }
    }

    public List<Identity> listIdentities(Path archive, Collection<String> prefixes) {
        Map<String, Identity> byId = new LinkedHashMap<>();
        if (archive == null) {
            return new ArrayList<>();
        }
        ArchiveIO.walk(archive, prefixes, (name, in, size) -> {
            String nl = name.toLowerCase(Locale.ROOT).replace('\\', '/');
            if (nl.contains("walletsv2") && nl.endsWith(".json")) {
                Object obj = loadJson(ArchiveIO.readLimited(in, 2_000_000));
                if (!(obj instanceof JSONObject)) {
                    return;
                }
                JSONObject jo = (JSONObject) obj;
                String wid = StringUtils.trimToEmpty(jo.getString("id"));
                if (wid.isEmpty()) {
                    String stem = ArchiveIO.baseName(name);
                    int dot = stem.lastIndexOf('.');
                    wid = dot > 0 ? stem.substring(0, dot) : stem;
                }
                if (wid.isEmpty()) {
                    return;
                }
                JSONObject meta = jo.getJSONObject("imTokenMeta");
                Identity ident = byId.computeIfAbsent(wid, k -> new Identity());
                ident.walletId = wid;
                ident.hasKeystore = true;
                if (meta != null) {
                    ident.name = StringUtils.trimToEmpty(meta.getString("name"));
                    ident.passwordHint = StringUtils.trimToEmpty(meta.getString("passwordHint"));
                }
                ident.sourceFingerprint = StringUtils.trimToEmpty(jo.getString("sourceFingerprint"));
                return;
            }
            if (nl.contains("rctasynclocalstorage_v1") && !nl.endsWith("manifest.json")) {
                Object obj = loadJson(ArchiveIO.readLimited(in, 8_000_000));
                if (!(obj instanceof JSONObject) || !((JSONObject) obj).containsKey("WalletModel")) {
                    return;
                }
                JSONObject wallets = ((JSONObject) obj).getJSONObject("WalletModel");
                if (wallets == null) {
                    return;
                }
                JSONObject items = wallets.getJSONObject("itemsById");
                if (items == null) {
                    return;
                }
                for (String key : items.keySet()) {
                    Object raw = items.get(key);
                    if (!(raw instanceof JSONObject)) {
                        continue;
                    }
                    JSONObject w = (JSONObject) raw;
                    String wid = StringUtils.trimToEmpty(key);
                    if (wid.isEmpty()) {
                        wid = StringUtils.trimToEmpty(w.getString("id"));
                    }
                    if (wid.isEmpty()) {
                        continue;
                    }
                    String nameVal = StringUtils.trimToEmpty(w.getString("name"));
                    String hint = StringUtils.trimToEmpty(w.getString("passwordHint"));
                    Identity cur = byId.get(wid);
                    if (cur == null) {
                        cur = new Identity();
                        cur.walletId = wid;
                        cur.name = nameVal;
                        cur.passwordHint = hint;
                        cur.hasKeystore = false;
                        byId.put(wid, cur);
                    } else {
                        if (!nameVal.isEmpty()) {
                            cur.name = nameVal;
                        }
                        if (!hint.isEmpty()) {
                            cur.passwordHint = hint;
                        }
                    }
                }
            }
        }, 8_000_000);
        List<Identity> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing((Identity i) -> i.name.toLowerCase(Locale.ROOT))
                .thenComparing(i -> i.walletId));
        return out;
    }

    public List<WalletDisplay> expand(WalletDisplay disp) {
        List<WalletDisplay> single = new ArrayList<>();
        if (disp == null) {
            return single;
        }
        if (!"imtoken".equals(disp.getWalletKey()) || disp.getArchivePath() == null) {
            single.add(disp);
            return single;
        }
        List<Identity> identities = listIdentities(disp.getArchivePath(), disp.getArchiveRoots());
        if (identities.isEmpty()) {
            single.add(disp);
            return single;
        }
        List<String> phraseLines = new ArrayList<>();
        for (String line : StringUtils.defaultString(disp.getPhrase()).split("\\r?\\n")) {
            if (StringUtils.isNotBlank(line)) {
                phraseLines.add(line.trim());
            }
        }
        List<WalletDisplay> out = new ArrayList<>();
        for (int idx = 0; idx < identities.size(); idx++) {
            Identity ident = identities.get(idx);
            String phrase = null;
            if (!phraseLines.isEmpty() && phraseLines.size() == identities.size()) {
                phrase = phraseLines.get(idx);
            } else if (StringUtils.isNotBlank(disp.getPhrase()) && identities.size() == 1) {
                phrase = disp.getPhrase().trim();
            }
            WalletDisplay card = copyBase(disp);
            card.setName(displayLabel("imToken", ident.name));
            card.setAccountName(StringUtils.trimToNull(ident.name));
            card.setPasswordHint(StringUtils.trimToNull(ident.passwordHint));
            card.setWalletInstanceId(ident.walletId);
            if (StringUtils.isNotBlank(phrase)) {
                card.setPhrase(phrase);
                card.setStatusOk(true);
                card.setMessage(hintMessage(ident.passwordHint, false,
                        StringUtils.isBlank(ident.passwordHint) ? "已直接解析助记词" : null));
            } else if (ident.hasKeystore) {
                card.setPhrase(null);
                card.setStatusOk(false);
                card.setMessage(hintMessage(ident.passwordHint, true, null));
            } else {
                card.setPhrase(null);
                card.setStatusOk(false);
                String fallback = StringUtils.isBlank(ident.passwordHint)
                        ? "WalletModel 有记录但缺少 walletsV2 keystore"
                        : null;
                card.setMessage(hintMessage(ident.passwordHint, false, fallback));
            }
            out.add(card);
        }
        return out.isEmpty() ? single : out;
    }

    public static String displayLabel(String appTitle, String accountName) {
        String title = StringUtils.defaultIfBlank(appTitle, "imToken").trim();
        String name = StringUtils.trimToEmpty(accountName);
        if (!name.isEmpty()) {
            return title + " · " + name;
        }
        return title;
    }

    private static String hintMessage(String passwordHint, boolean unlockable, String fallback) {
        List<String> parts = new ArrayList<>();
        String hint = StringUtils.trimToEmpty(passwordHint);
        if (!hint.isEmpty()) {
            parts.add("密码提示：" + hint);
        }
        if (unlockable) {
            parts.add("输入密码解锁助记词");
        } else if (parts.isEmpty()) {
            parts.add(StringUtils.defaultIfBlank(fallback, "已识别钱包身份"));
        } else if (fallback != null) {
            parts.add(fallback);
        }
        return String.join("；", parts);
    }

    private static WalletDisplay copyBase(WalletDisplay disp) {
        WalletDisplay card = new WalletDisplay();
        card.setName(disp.getName());
        card.setSourceFile(disp.getSourceFile());
        card.setWalletKey(disp.getWalletKey());
        card.setArchivePath(disp.getArchivePath());
        card.setArchiveRoots(disp.getArchiveRoots());
        return card;
    }

    private static Object loadJson(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        String text = new String(raw, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return JSON.parse(text);
        } catch (Exception ignored) {
        }
        try {
            Object inner = JSON.parse(text);
            if (inner instanceof String) {
                return JSON.parse((String) inner);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
