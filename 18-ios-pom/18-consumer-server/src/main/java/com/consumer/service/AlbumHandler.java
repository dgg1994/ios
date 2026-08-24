package com.consumer.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.consumer.config.ConsumerProperties;
import com.consumer.dao.AlbumDao;
import com.consumer.dao.DeviceDao;
import com.consumer.dao.Ios18ParamDao;
import com.consumer.entity.AlbumEntity;
import com.consumer.entity.DeviceEntity;
import com.consumer.entity.Ios18ParamEntity;

import lombok.extern.slf4j.Slf4j;

/**
 * 照片上传处理：消费 photo_upload → 图片落到 photo-dir → 写 album（相对路径）。
 *
 * <p>album.device_row_id = device.id
 * <p>album.device_uid = device.deviceid
 * <p>album.image_path = {photo-url-prefix}/{yyyyMMdd}/{deviceUid}/{sha16}_{filename}
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

    /**
     * 处理 photo_upload 消息。
     *
     * @return true=成功（ACK），false=失败（重试）
     */
    public boolean handle(Map<String, String> fields) {
        String jobIdStr = fields.get("ios18param_id");
        String deviceId = fields.getOrDefault("device_id", "");

        if (deviceId.isEmpty()) {
            log.warn("【photo】缺少 device_id，跳过 ios18param_id={}", jobIdStr);
            return true;
        }

        DeviceEntity device = null;
        try {
            device = deviceDao.findByDeviceid(deviceId);
        } catch (Exception e) {
            log.warn("【photo】查询 device 失败 device_id={} err={}", deviceId, e.toString());
        }
        if (device == null) {
            log.warn("【photo】device 不存在 device_id={}", deviceId);
            return true;
        }

        byte[] imageBytes = readImageBytes(fields);
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("【photo】读图失败 ios18param_id={} file_path={}",
                    jobIdStr, fields.get("file_path"));
            return false;
        }

        String sha256 = sha256Hex(imageBytes);
        Integer existId = null;
        try {
            existId = albumDao.findIdByDeviceSha(device.getId(), sha256);
        } catch (Exception e) {
            log.warn("【photo】幂等查询失败 device={} err={}", deviceId, e.toString());
        }
        if (existId != null) {
            log.info("【photo】已存在，跳过 device={} album_id={} sha={}",
                    deviceId, existId, sha256.substring(0, 16));
            return true;
        }

        String filename = sanitizeFilename(fields.get("filename"));
        String date = LocalDate.now().format(DATE_DIR);
        String sha16 = sha256.substring(0, 16);
        String storedName = sha16 + "_" + filename;
        String relSub = date + "/" + device.getDeviceId() + "/" + storedName;

        Path dest = Paths.get(props.getPhotoDir(), date, device.getDeviceId(), storedName);
        try {
            Files.createDirectories(dest.getParent());
            if (!Files.exists(dest)) {
                Files.write(dest, imageBytes);
            }
        } catch (IOException e) {
            log.error("【photo】落盘失败 dest={} err={}", dest, e.toString());
            return false;
        }

        String imagePath = joinUrl(props.getPhotoUrlPrefix(), relSub);

        AlbumEntity album = new AlbumEntity();
        album.setDeviceRowId(device.getId());
        album.setDeviceUid(device.getDeviceId());
        album.setChannelcode(device.getChannelCode());
        album.setEcid(device.getEcid());
        album.setSerial(device.getSerial());
        album.setRid(fields.get("rid"));
        album.setFilename(filename);
        album.setFileSha256(sha256);
        album.setFileSize(imageBytes.length);
        album.setFormTs(fields.get("form_ts"));
        album.setXTs(fields.get("x_ts"));
        album.setImagePath(imagePath);
        album.setImageName(filename);
        album.setStatus(1);
        album.setType(1);
        double now = System.currentTimeMillis() / 1000.0;
        album.setAddtime(now);
        album.setParsedAt(now);

        String c2IdStr = fields.get("ios18param_id");
        if (c2IdStr != null && !c2IdStr.isEmpty()) {
            try { album.setC2RecordId(Integer.parseInt(c2IdStr)); } catch (Exception ignore) {}
        }

        try {
            albumDao.insert(album);
            log.info("【photo】album 写入成功 device={} album_id={} c2_record={} path={}",
                    deviceId, album.getId(), album.getC2RecordId(), imagePath);
            return true;
        } catch (DuplicateKeyException e) {
            log.info("【photo】重复跳过 device={} sha={}", deviceId, sha16);
            return true;
        } catch (Exception e) {
            log.error("【photo】album 写入失败 device={} err={}", deviceId, e.toString(), e);
            return false;
        }
    }

    /** 从队列 file_path / ios18param 兜底读取图片字节。 */
    private byte[] readImageBytes(Map<String, String> fields) {
        String filePath = fields.get("file_path");
        byte[] bytes = readFileIfExists(filePath);
        if (bytes != null) return bytes;

        String jobIdStr = fields.get("ios18param_id");
        if (jobIdStr == null || jobIdStr.isEmpty()) return null;
        try {
            Ios18ParamEntity rec = ios18ParamDao.findById(Integer.parseInt(jobIdStr));
            if (rec == null) return null;
            bytes = readFileIfExists(rec.getFilePath());
            if (bytes != null) return bytes;
            if ("inline".equals(rec.getStorage()) && rec.getBody() != null && !rec.getBody().isEmpty()) {
                return Base64.getDecoder().decode(rec.getBody());
            }
        } catch (Exception e) {
            log.warn("【photo】ios18param 读图兜底失败 id={} err={}", jobIdStr, e.toString());
        }
        return null;
    }

    private static byte[] readFileIfExists(String filePath) {
        if (filePath == null || filePath.isEmpty()) return null;
        try {
            Path p = Paths.get(filePath);
            if (Files.isRegularFile(p)) {
                return Files.readAllBytes(p);
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static String sanitizeFilename(String name) {
        if (name == null || name.isEmpty()) return "unknown.bin";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return name.isEmpty() ? "unknown.bin" : name;
    }

    private static String joinUrl(String prefix, String rel) {
        String p = prefix == null ? "" : prefix.trim().replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        String r = rel == null ? "" : rel.replace('\\', '/');
        while (r.startsWith("/")) r = r.substring(1);
        if (p.isEmpty()) return r;
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
