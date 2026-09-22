package com.send.service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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
import com.send.util.UploadContentPolicy;

/**
 * 对齐 Python {@code /api/v1/uploads}：先开会话，再按 chunkIndex 落盘，齐片后合并。
 */
@Service
public class V1UploadService {

    private static final int LOCK_STRIPES = 512;
    private final Object[] locks = new Object[LOCK_STRIPES];

    public V1UploadService() {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    @Autowired
    private V26SendProperties props;

    @Autowired
    private UploadFileDao uploadFileDao;

    @Transactional
    public Map<String, Object> createSession(String headerDeviceId, String fileName, Long fileSize) {
        String device = DeviceValidate.validateXDeviceIdHeader(headerDeviceId);
        if (fileSize == null || fileSize < 0) {
            throw new BizException(400, "fileSize 非法");
        }
        if (fileSize > props.getMaxFileSize()) {
            throw new BizException(400, "文件过大");
        }
        String name = UploadContentPolicy.safeFileName(fileName);
        int chunkSize = Math.max(1, props.getChunkSize());
        int expected = fileSize <= 0 ? 1 : (int) ((fileSize + chunkSize - 1) / chunkSize);
        String uploadId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        String day = LocalDate.now().toString();
        Path sessionDir = assertUnderRoot(root().resolve(day).resolve(device).resolve(uploadId));
        try {
            Files.createDirectories(assertUnderRoot(sessionDir.resolve("chunks")));
        } catch (Exception e) {
            throw new BizException(500, "无法创建上传目录：" + e.getMessage());
        }
        String sessionPath = day + "/" + device + "/" + uploadId;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("uploadId", uploadId);
        meta.put("deviceId", device);
        meta.put("fileName", name);
        meta.put("fileSize", fileSize);
        meta.put("chunkSize", chunkSize);
        meta.put("chunkCount", expected);
        meta.put("expectedChunks", expected);
        meta.put("status", "PENDING");
        meta.put("storagePath", null);
        meta.put("diskPath", null);
        meta.put("sessionPath", sessionPath);
        meta.put("date", day);
        writeMeta(sessionDir, meta);

        UploadFileEntity row = new UploadFileEntity();
        row.setUploadId(uploadId);
        row.setDeviceId(device);
        row.setFileName(name);
        row.setFileSize(fileSize);
        row.setChunkSize((long) chunkSize);
        row.setExpectedChunks(expected);
        row.setStatus("PENDING");
        row.setSessionPath(sessionPath);
        row.setCreatedAt(new Date());
        row.setUpdatedAt(new Date());
        uploadFileDao.insert(row);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("uploadId", uploadId);
        data.put("fileName", name);
        data.put("chunkSize", chunkSize);
        data.put("expectedChunks", expected);
        data.put("status", "PENDING");
        return data;
    }

    public Map<String, Object> saveChunk(String headerDeviceId, String uploadId, int chunkIndex, byte[] body) {
        String device = DeviceValidate.validateXDeviceIdHeader(headerDeviceId);
        String uid = DeviceValidate.validateUploadId(uploadId);
        if (chunkIndex < 0) {
            throw new BizException(400, "chunkIndex 不能为负");
        }
        if (body == null || body.length == 0) {
            throw new BizException(400, "body 不能为空");
        }
        if (body.length > Math.max(1, props.getMaxChunkSize())) {
            throw new BizException(400, "分片过大，最大 " + props.getMaxChunkSize() + " 字节");
        }
        Object lock = locks[Math.floorMod(uid.hashCode(), LOCK_STRIPES)];
        synchronized (lock) {
            UploadFileEntity row = uploadFileDao.selectOne(
                    new QueryWrapper<UploadFileEntity>().eq("uploadId", uid).last("LIMIT 1"));
            if (row == null || StringUtils.isBlank(row.getSessionPath())) {
                throw new BizException(400, "上传会话不存在，请先调用 POST /api/v1/uploads");
            }
            if (!device.equalsIgnoreCase(StringUtils.trimToEmpty(row.getDeviceId()))) {
                throw new BizException(400, "X-Device-Id 与上传会话不匹配");
            }
            int expected = row.getExpectedChunks() == null ? 0 : row.getExpectedChunks();
            if (expected > 0 && chunkIndex >= expected) {
                throw new BizException(400, "chunkIndex 超出范围，expectedChunks=" + expected);
            }
            Path sessionDir = pathFromRel(row.getSessionPath());
            if (chunkIndex == 0) {
                UploadContentPolicy.rejectBadContent(body, row.getFileName());
            }
            try {
                Files.createDirectories(assertUnderRoot(sessionDir.resolve("chunks")));
                Path target = chunkPath(sessionDir, chunkIndex);
                Path tmp = assertUnderRoot(target.resolveSibling(target.getFileName() + ".tmp"));
                Files.write(tmp, body);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception atomic) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException(500, "upload failed: " + e.getMessage());
            }

            String digest = sha256Hex(body);
            List<Integer> received = listReceived(sessionDir);
            boolean complete = expected > 0 && received.size() >= expected && containsRange(received, expected);
            if (complete && !"COMPLETED".equalsIgnoreCase(StringUtils.trimToEmpty(row.getStatus()))) {
                try {
                    Merged merged = merge(sessionDir, device, row.getFileName(), expected);
                    row.setFileName(merged.fileName);
                    row.setFileSize(merged.size);
                    row.setSha256(merged.sha256);
                    row.setExpectedChunks(expected);
                    row.setStatus("COMPLETED");
                    row.setStoragePath(merged.storagePath);
                    row.setDiskPath(merged.diskPath);
                    row.setCompletedAt(new Date());
                    row.setUpdatedAt(new Date());
                    uploadFileDao.updateById(row);
                    Map<String, Object> meta = readMeta(sessionDir);
                    meta.put("status", "COMPLETED");
                    meta.put("storagePath", merged.storagePath);
                    meta.put("diskPath", merged.diskPath);
                    meta.put("fileSize", merged.size);
                    meta.put("sha256", merged.sha256);
                    meta.put("fileName", merged.fileName);
                    writeMeta(sessionDir, meta);
                } catch (BizException e) {
                    row.setStatus("REJECTED");
                    row.setUpdatedAt(new Date());
                    uploadFileDao.updateById(row);
                    throw e;
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("uploadId", uid);
            data.put("chunkIndex", chunkIndex);
            data.put("chunkSize", body.length);
            if ("COMPLETED".equalsIgnoreCase(StringUtils.trimToEmpty(row.getStatus()))) {
                data.put("sha256", row.getSha256());
                data.put("status", "COMPLETED");
                data.put("fileName", row.getFileName());
                data.put("fileSize", row.getFileSize());
                data.put("chunkCount", row.getExpectedChunks());
                data.put("storagePath", row.getStoragePath());
            } else {
                data.put("sha256", digest);
                data.put("status", "PENDING");
                data.put("fileName", null);
                data.put("fileSize", null);
                data.put("chunkCount", null);
                data.put("storagePath", null);
            }
            return data;
        }
    }

    private Merged merge(Path sessionDir, String device, String fileName, int expected) {
        String name = UploadContentPolicy.safeFileName(fileName);
        Path deviceDir = assertUnderRoot(sessionDir.getParent());
        Path tmp = assertUnderRoot(deviceDir.resolve("." + name + ".merging"));
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new BizException(500, "sha256 unavailable");
        }
        long total = 0;
        byte[] head = new byte[0];
        try (OutputStream out = Files.newOutputStream(tmp)) {
            for (int i = 0; i < expected; i++) {
                Path part = chunkPath(sessionDir, i);
                if (!Files.exists(part)) {
                    throw new BizException(409, "缺少分片 chunkIndex=" + i);
                }
                try (InputStream in = Files.newInputStream(part)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (n == 0) {
                            continue;
                        }
                        if (head.length < 512) {
                            int need = Math.min(512 - head.length, n);
                            byte[] grown = new byte[head.length + need];
                            System.arraycopy(head, 0, grown, 0, head.length);
                            System.arraycopy(buf, 0, grown, head.length, need);
                            head = grown;
                        }
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        total += n;
                    }
                }
            }
        } catch (BizException e) {
            deleteQuiet(tmp);
            throw e;
        } catch (Exception e) {
            deleteQuiet(tmp);
            throw new BizException(500, "upload failed: " + e.getMessage());
        }
        if (total > props.getMaxFileSize()) {
            deleteQuiet(tmp);
            throw new BizException(400, "文件过大");
        }
        try {
            UploadContentPolicy.rejectBadContent(head, name);
        } catch (BizException e) {
            deleteQuiet(tmp);
            throw e;
        }
        Path dest = assertUnderRoot(deviceDir.resolve(name));
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e2) {
                deleteQuiet(tmp);
                throw new BizException(500, "upload failed: " + e2.getMessage());
            }
        }
        String day = deviceDir.getParent().getFileName().toString();
        Merged merged = new Merged();
        merged.fileName = name;
        merged.size = total;
        merged.sha256 = toHex(md.digest());
        merged.storagePath = device + "/" + name;
        merged.diskPath = day + "/" + device + "/" + name;
        return merged;
    }

    private Path chunkPath(Path sessionDir, int index) {
        if (index < 0 || index > 1_000_000) {
            throw new BizException(400, "chunkIndex 非法");
        }
        return assertUnderRoot(sessionDir.resolve("chunks").resolve(String.format("%06d.bin", index)));
    }

    private List<Integer> listReceived(Path sessionDir) {
        List<Integer> indexes = new ArrayList<>();
        Path chunks = sessionDir.resolve("chunks");
        if (!Files.isDirectory(chunks)) {
            return indexes;
        }
        try (var stream = Files.list(chunks)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".bin")).forEach(p -> {
                String stem = p.getFileName().toString();
                stem = stem.substring(0, stem.length() - 4);
                try {
                    indexes.add(Integer.parseInt(stem));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            });
        } catch (Exception ignored) {
            return indexes;
        }
        indexes.sort(Integer::compareTo);
        return indexes;
    }

    private static boolean containsRange(List<Integer> received, int expected) {
        if (received.size() < expected) {
            return false;
        }
        for (int i = 0; i < expected; i++) {
            if (!received.contains(i)) {
                return false;
            }
        }
        return true;
    }

    private void writeMeta(Path sessionDir, Map<String, Object> meta) {
        try {
            Files.createDirectories(sessionDir);
            Path path = assertUnderRoot(sessionDir.resolve("meta.json"));
            Path tmp = assertUnderRoot(path.resolveSibling("meta.tmp"));
            Files.writeString(tmp, JSON.toJSONString(meta, true), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomic) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new BizException(500, "无法创建上传目录：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMeta(Path sessionDir) {
        Path path = sessionDir.resolve("meta.json");
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> meta = JSON.parseObject(Files.readString(path), Map.class);
            return meta == null ? new LinkedHashMap<>() : meta;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Path pathFromRel(String rel) {
        Path p = root();
        for (String part : rel.replace('\\', '/').split("/")) {
            if (StringUtils.isNotBlank(part) && !".".equals(part) && !"..".equals(part)) {
                p = p.resolve(part);
            }
        }
        return assertUnderRoot(p);
    }

    private Path root() {
        return Path.of(props.getUploadDir()).toAbsolutePath().normalize();
    }

    private Path assertUnderRoot(Path path) {
        Path root = root();
        Path norm = path.toAbsolutePath().normalize();
        if (!norm.startsWith(root)) {
            throw new BizException(400, "非法路径");
        }
        return norm;
    }

    private static String sha256Hex(byte[] body) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            return null;
        }
    }

    private static String toHex(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void deleteQuiet(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static final class Merged {
        String fileName;
        long size;
        String sha256;
        String storagePath;
        String diskPath;
    }
}
