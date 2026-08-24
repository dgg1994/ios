package com.ctwo.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ctwo.util.DateTimeUtils;

/**
 * Capture 落盘服务：负责整包 body 的阈值分流与文件落盘。
 *
 * <p>对应接口文档 §0.6 capture 通用流程：
 * <ul>
 *   <li>≤ {@code body_inline_max} → {@code storage=inline}（body 入 Redis）</li>
 *   <li>> {@code body_inline_max} → {@code storage=file}（落盘 .bin，body 列置空）</li>
 *   <li>skip_store / 空 body → {@code storage=skip}</li>
 * </ul>
 *
 * <p>落盘文件名：{@code {uploadDir}/{YYYYMMDD}/{kind}/{device}_{sha16}_{size}.bin}
 *
 * <p>本服务只做「整包」落盘；{@code /war} 纯流式落盘由 WarService 单独处理
 * （request.stream 边读边写临时文件，避免二次缓冲整包）。
 *
 * <p>配置项直接由 application.yml 的 {@code api18.*} 通过 {@code @Value} 注入本类字段。
 */
@Service
public class CaptureStorageService {

    private static final Logger log = LoggerFactory.getLogger(CaptureStorageService.class);

    /** 小包入队阈值（字节）：≤ 该值走 inline；> 该值落盘 .bin */
    @Value("${api18.body-inline-max:262144}")
    private long bodyInlineMax;

    /** 落盘根目录；文件名：{dir}/{YYYYMMDD}/{kind}/{device}_{sha16}_{size}.bin */
    @Value("${api18.upload-dir:data/uploads}")
    private String uploadDir;

    public long getBodyInlineMax() {
        return bodyInlineMax;
    }

    public String getUploadDir() {
        return uploadDir;
    }

    /**
     * 把整包 body 落盘到 .bin。
     *
     * @param bodyBytes body 字节数组（非空）
     * @param kind       归一 kind（如 u/nb/event/war）
     * @param device     设备 UUID（缺失用 "unknown"）
     * @return 落盘相对路径 {@code {YYYYMMDD}/{kind}/{device}_{sha16}_{size}.bin}；
     *         落盘失败返回 null（调用方应回退为 inline）
     */
    public String dumpToFile(byte[] bodyBytes, String kind, String device) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return null;
        }
        String sha256 = sha256Hex(bodyBytes);
        String sha16 = sha256.substring(0, 16);
        String date = DateTimeUtils.DUMP_DATE.format(Instant.now());
        String dev = (device == null || device.isEmpty()) ? "unknown" : device;
        String size = Integer.toString(bodyBytes.length);

        String relPath = date + "/" + kind + "/" + dev + "_" + sha16 + "_" + size + ".bin";
        Path absPath = Paths.get(uploadDir, relPath);
        try {
            Files.createDirectories(absPath.getParent());
            Files.write(absPath, bodyBytes);
            String fullPath = absPath.toAbsolutePath().toString().replace('\\', '/');
            log.info("文件写入成功 kind={} device={} size={} path={}", kind, dev, size, fullPath);
            return fullPath;
        } catch (IOException e) {
            log.error("文件写入失败 kind={} device={} size={} path={} err={}",
                    kind, dev, size, absPath, e.toString(), e);
            return null;
        }
    }

    /**
     * 把临时文件 promote 到正式落盘位置（供 /war 流式落盘复用）。
     *
     * @param tmpPath 临时文件绝对路径
     * @param kind    kind（如 war）
     * @param device  设备 UUID
     * @param sha256 文件 sha256（用于文件名 sha16）
     * @param size    文件字节数
     * @return 落盘相对路径；失败返回 null
     */
    public String promoteTempFile(Path tmpPath, String kind, String device, String sha256, long size) {
        if (tmpPath == null || !Files.isRegularFile(tmpPath)) {
            return null;
        }
        String sha16 = (sha256 == null || sha256.length() < 16) ? "0000000000000000" : sha256.substring(0, 16);
        String date = DateTimeUtils.DUMP_DATE.format(Instant.now());
        String dev = (device == null || device.isEmpty()) ? "unknown" : device;
        String relPath = date + "/" + kind + "/" + dev + "_" + sha16 + "_" + size + ".bin";
        Path absPath = Paths.get(uploadDir, relPath);
        try {
            Files.createDirectories(absPath.getParent());
            Files.move(tmpPath, absPath, StandardCopyOption.REPLACE_EXISTING);
            String fullPath = absPath.toAbsolutePath().toString().replace('\\', '/');
            log.info("promoteTempFile ok kind={} device={} size={} path={}", kind, dev, size, fullPath);
            return fullPath;
        } catch (IOException e) {
            log.error("promoteTempFile FAIL kind={} device={} path={} err={}",
                    kind, dev, absPath, e.toString(), e);
            return null;
        }
    }

    /** 计算 sha256 十六进制（小写）。Java 11 兼容（无 HexFormat）。 */
    public String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** byte[] → 小写 hex 字符串（Java 11 兼容，替代 HexFormat）。供 WarService 流式 sha256 复用。 */
    public static String toHex(byte[] bytes) {
        char[] table = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = table[v >>> 4];
            out[i * 2 + 1] = table[v & 0x0F];
        }
        return new String(out);
    }
}
