package com.send.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.send.config.V26SendProperties;
import com.send.dao.UploadFileDao;
import com.send.entity.UploadFileEntity;
import com.send.util.BizException;
import com.send.util.DeviceValidate;

@Service
public class V2UploadService {

    private static final String ANNOUNCED_PREFIX = "announced:";

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    private V26SendProperties props;

    @Autowired
    private UploadFileDao uploadFileDao;

    public String resolveDeviceId(String explicit, String fileName) {
        if (StringUtils.isNotBlank(explicit)) {
            try {
                return DeviceValidate.validateDeviceId(explicit, "X-Device-Id", true);
            } catch (BizException ignored) {
                // fall through
            }
        }
        String found = DeviceValidate.extractUuidFromName(fileName);
        return found != null ? found : "unknown";
    }

    @Transactional
    public Map<String, Object> createPlainUpload(String fileName, String deviceId, byte[] body,
            Long declaredFileSize) {
        byte[] data = body == null ? new byte[0] : body;
        int chunkSize = Math.max(1, props.getChunkSize());
        if (data.length > props.getMaxFileSize()) {
            throw new BizException(400, data.length > 0 ? "分块过大" : "文件过大");
        }
        if (declaredFileSize != null && declaredFileSize < 0) {
            throw new BizException(400, "fileSize 非法");
        }
        if (declaredFileSize != null && declaredFileSize > props.getMaxFileSize()) {
            throw new BizException(400, "文件过大");
        }

        String name = DeviceValidate.validateFilenameSegment(
                StringUtils.defaultIfBlank(fileName, "upload_v2_" + UUID.randomUUID().toString().substring(0, 12) + ".bin"));
        String device = safeDeviceDir(deviceId);
        String uploadId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        String day = LocalDate.now().toString();
        Path destDir = assertUnderRoot(root().resolve(day).resolve(device));
        Object lock = locks.computeIfAbsent("plain:" + uploadId, k -> new Object());
        synchronized (lock) {
            try {
                Files.createDirectories(destDir);
                Path dest = uniqueDest(destDir, name, uploadId);
                name = dest.getFileName().toString();
                Files.write(dest, data);
                long size = Files.size(dest);
                String diskPath = day + "/" + device + "/" + name;
                String storagePath = device + "/" + name;
                boolean hasData = data.length > 0;

                int expected;
                String sessionPath = null;
                if (hasData && declaredFileSize == null) {
                    // 一次整文件
                    expected = 1;
                } else if (declaredFileSize != null && declaredFileSize > 0) {
                    expected = ceilChunks(declaredFileSize, chunkSize);
                    sessionPath = ANNOUNCED_PREFIX + declaredFileSize;
                } else {
                    expected = 0;
                }

                UploadFileEntity row = new UploadFileEntity();
                row.setUploadId(uploadId);
                row.setDeviceId(device);
                row.setFileName(name);
                row.setFileSize(size);
                row.setSha256(hasData ? sha256Hex(data) : null);
                row.setChunkSize((long) chunkSize);
                row.setExpectedChunks(expected);
                row.setStatus(hasData ? "COMPLETED" : "PENDING");
                row.setStoragePath(storagePath);
                row.setDiskPath(diskPath);
                row.setSessionPath(sessionPath);
                row.setCompletedAt(hasData ? new Date() : null);
                row.setCreatedAt(new Date());
                row.setUpdatedAt(new Date());
                uploadFileDao.insert(row);
                return plainResult(row);
            } catch (IOException e) {
                throw new BizException(500, "upload failed: " + e.getMessage());
            }
        }
    }

    @Transactional
    public Map<String, Object> appendPlainChunk(String uploadId, byte[] body, Integer chunkIndex) {
        if (body == null || body.length == 0) {
            throw new BizException(400, "上传内容为空");
        }
        String uid = DeviceValidate.validateUploadId(uploadId);
        Object lock = locks.computeIfAbsent("plain:" + uid, k -> new Object());
        synchronized (lock) {
            UploadFileEntity row = uploadFileDao.selectOne(
                    new QueryWrapper<UploadFileEntity>().eq("uploadId", uid).last("LIMIT 1"));
            if (row == null) {
                throw new BizException(400, "uploadId 不存在");
            }
            int chunkSize = row.getChunkSize() == null ? props.getChunkSize() : row.getChunkSize().intValue();
            chunkSize = Math.max(1, chunkSize);
            // 服务端控制单片上限 = 配置/会话的 chunkSize
            if (body.length > chunkSize) {
                throw new BizException(400, "分块过大");
            }
            if ("COMPLETED".equalsIgnoreCase(StringUtils.trimToEmpty(row.getStatus()))) {
                throw new BizException(400, "上传已完成");
            }

            Path dest = pathFromDiskPath(row.getDiskPath());
            try {
                Files.createDirectories(dest.getParent());
                long current = Files.exists(dest) ? Files.size(dest) : (row.getFileSize() == null ? 0L : row.getFileSize());
                if (current + body.length > props.getMaxFileSize()) {
                    throw new BizException(400, "文件过大");
                }
                try (OutputStream out = Files.newOutputStream(dest,
                        Files.exists(dest)
                                ? new StandardOpenOption[]{StandardOpenOption.APPEND}
                                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE})) {
                    out.write(body);
                }
                long size = Files.size(dest);
                row.setFileSize(size);
                row.setSha256(null);

                int expected = row.getExpectedChunks() == null ? 0 : row.getExpectedChunks();
                // expectedChunks / numberOfChunks 开会话时已按 fileSize 算好，分片过程不再改写
                boolean last = isLastChunk(row, chunkIndex, size, expected, chunkSize);
                if (last) {
                    row.setStatus("COMPLETED");
                    row.setCompletedAt(new Date());
                } else {
                    row.setStatus("PENDING");
                    row.setCompletedAt(null);
                }
                row.setUpdatedAt(new Date());
                uploadFileDao.updateById(row);
                return plainResult(row);
            } catch (IOException e) {
                throw new BizException(500, "upload failed: " + e.getMessage());
            }
        }
    }

    public void dumpDeviceJson(Map<String, Object> payload, String deviceId) {
        try {
            String day = LocalDate.now().toString();
            String device = safeDeviceDir(deviceId);
            Path destDir = assertUnderRoot(root().resolve(day).resolve(device));
            Files.createDirectories(destDir);
            String ts = String.valueOf(System.currentTimeMillis());
            Path path = destDir.resolve("devices_" + ts + ".json");
            Files.write(path, JSON.toJSONString(payload, true).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // best-effort
        }
    }

    /**
     * numberOfChunks / expectedChunks 一律用开会话时写入的 expectedChunks
     * （= ceil(fileSize / chunkSize)），不随已写入字节重算。
     */
    private Map<String, Object> plainResult(UploadFileEntity row) {
        int chunkSize = row.getChunkSize() == null ? props.getChunkSize() : row.getChunkSize().intValue();
        long fileSize = row.getFileSize() == null ? 0L : row.getFileSize();
        int expected = row.getExpectedChunks() == null ? 0 : row.getExpectedChunks();
        int numberOfChunks = expected > 0 ? expected : ceilChunks(fileSize, chunkSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("uploadId", row.getUploadId());
        result.put("chunkSize", chunkSize);
        result.put("fileName", row.getFileName());
        result.put("fileSize", fileSize);
        result.put("deviceId", row.getDeviceId());
        result.put("diskPath", row.getDiskPath());
        result.put("status", row.getStatus() == null ? "PENDING" : row.getStatus());
        result.put("expectedChunks", numberOfChunks);
        result.put("numberOfChunks", numberOfChunks);
        return result;
    }

    private boolean isLastChunk(UploadFileEntity row, Integer chunkIndex, long writtenSize,
            int expected, int chunkSize) {
        if (expected > 0 && chunkIndex != null) {
            return chunkIndex + 1 >= expected;
        }
        long announced = parseAnnounced(row.getSessionPath());
        if (announced > 0) {
            return writtenSize >= announced;
        }
        if (expected > 0) {
            // 无 chunkIndex 时按累计字节是否达到「满片 * (n-1) + 至少 1」近似；更稳用 announced
            long minComplete = (long) (expected - 1) * chunkSize + 1;
            return writtenSize >= minComplete;
        }
        return true;
    }

    private long parseAnnounced(String sessionPath) {
        if (StringUtils.isBlank(sessionPath) || !sessionPath.startsWith(ANNOUNCED_PREFIX)) {
            return 0L;
        }
        try {
            return Long.parseLong(sessionPath.substring(ANNOUNCED_PREFIX.length()).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private int ceilChunks(long fileSize, int chunkSize) {
        if (fileSize <= 0 || chunkSize <= 0) {
            return 0;
        }
        return (int) ((fileSize + chunkSize - 1) / chunkSize);
    }

    private Path uniqueDest(Path destDir, String name, String uploadId) {
        Path dest = assertUnderRoot(destDir.resolve(name));
        if (Files.exists(dest)) {
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String suffix = dot > 0 ? name.substring(dot) : "";
            dest = assertUnderRoot(destDir.resolve(stem + "_" + uploadId.substring(0, 8) + suffix));
        }
        return dest;
    }

    private Path pathFromDiskPath(String diskPath) {
        if (StringUtils.isBlank(diskPath)) {
            throw new BizException(400, "uploadId 无存储路径");
        }
        Path p = root();
        for (String part : diskPath.replace('\\', '/').split("/")) {
            if (StringUtils.isNotBlank(part)) {
                p = p.resolve(part);
            }
        }
        return assertUnderRoot(p);
    }

    private String safeDeviceDir(String deviceId) {
        try {
            return DeviceValidate.validateDeviceId(deviceId, "deviceId", true);
        } catch (BizException e) {
            return "unknown";
        }
    }

    private Path root() {
        return Paths.get(props.getUploadDir()).toAbsolutePath().normalize();
    }

    private Path assertUnderRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root())) {
            throw new BizException(400, "路径越界");
        }
        return normalized;
    }

    private String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(body);
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
