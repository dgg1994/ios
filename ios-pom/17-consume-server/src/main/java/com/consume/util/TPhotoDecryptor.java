package com.consume.util;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * /t 照片解密（对齐 Python decrypt_t_photo.py）。
 *
 * 客户端流程：JPEG → 7z AES-256（密码 = "Ek8pl31K2yeHgQwy" + 表单字段 ts）
 * → StartHeader 前 32 字节 XOR 混淆（模式 0x1234567800ABCDEF）→ multipart 上传 .dat。
 *
 * 解密：还原 7z StartHeader → 用密码解 7z → 取最大文件即图片。
 */
public final class TPhotoDecryptor {

    public static final String EVENT_KEY_PREFIX = "Ek8pl31K2yeHgQwy";

    /** 0x1234567800ABCDEF 的 LE qword，重复两次 = 16 字节混淆模式 */
    private static final byte[] OBFUSCATION_PAT = new byte[16];
    static {
        byte[] q = new byte[8];
        ByteBuffer.wrap(q).order(ByteOrder.LITTLE_ENDIAN).putLong(0x1234567800ABCDEFL);
        System.arraycopy(q, 0, OBFUSCATION_PAT, 0, 8);
        System.arraycopy(q, 0, OBFUSCATION_PAT, 8, 8);
    }

    private static final byte[] SEVEN_Z_MAGIC =
            new byte[]{0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};

    private TPhotoDecryptor() {
    }

    public static class ImageDetail {
        public final byte[] image;
        public final String imageName;
        public final String ext;

        public ImageDetail(byte[] image, String imageName, String ext) {
            this.image = image;
            this.imageName = imageName;
            this.ext = ext;
        }
    }

    /** 还原被 XOR 混淆的 7z StartHeader，保留后续 packed streams。 */
    public static byte[] deobfuscatePlzmaHeader(byte[] blob) {
        if (blob == null || blob.length < 32) {
            throw new IllegalArgumentException("file 过短，不像 plzma .dat");
        }
        byte[] mid = new byte[16];
        for (int i = 0; i < 16; i++) {
            mid[i] = (byte) (blob[i] ^ OBFUSCATION_PAT[i]);
        }
        long offset = readUInt64LE(mid, 0);
        long size = readUInt64LE(mid, 8);
        long need = 32L + offset + size;
        if (need > blob.length) {
            throw new IllegalArgumentException("file 被截断（缺 " + (need - blob.length)
                    + "B）：7z 需要 " + need + "B，实际 " + blob.length
                    + "B。offset=" + offset + " size=" + size);
        }
        byte[] nextHeader = new byte[(int) size];
        System.arraycopy(blob, (int) (32 + offset), nextHeader, 0, (int) size);
        long nextCrc = crc32(nextHeader) & 0xFFFFFFFFL;

        // body_12_32 = mid(16) + uint32LE(nextCrc) = 20 字节
        byte[] body = new byte[20];
        System.arraycopy(mid, 0, body, 0, 16);
        writeUInt32LE(body, 16, (int) nextCrc);
        long startCrc = crc32(body) & 0xFFFFFFFFL;

        // hdr = magic(6) + 0x00 0x04 + uint32LE(startCrc) + body(20) = 32 字节
        byte[] hdr = new byte[32];
        System.arraycopy(SEVEN_Z_MAGIC, 0, hdr, 0, 6);
        hdr[6] = 0x00;
        hdr[7] = 0x04;
        writeUInt32LE(hdr, 8, (int) startCrc);
        System.arraycopy(body, 0, hdr, 12, 20);

        byte[] out = new byte[blob.length];
        System.arraycopy(hdr, 0, out, 0, 32);
        System.arraycopy(blob, 32, out, 32, blob.length - 32);
        return out;
    }

    public static String sevenZPassword(String ts) {
        return EVENT_KEY_PREFIX + (ts == null ? "" : ts.trim());
    }

    /** 解密 .dat → {image, image_name, ext}；取 7z 内最大文件为图片。 */
    public static ImageDetail decryptDatToImage(byte[] fileBlob, String formTs) {
        String ts = formTs == null ? "" : formTs.trim();
        if (fileBlob == null || fileBlob.length == 0) {
            throw new IllegalArgumentException("file_blob 为空");
        }
        if (ts.isEmpty()) {
            throw new IllegalArgumentException("缺少表单 ts");
        }
        byte[] archive = deobfuscatePlzmaHeader(fileBlob);
        byte[] best = null;
        String bestName = "";
        long bestSize = -1;
        try (SevenZFile zf = SevenZFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(archive))
                .setPassword(sevenZPassword(ts).toCharArray())
                .get()) {
            SevenZArchiveEntry e;
            while ((e = zf.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                long sz = e.getSize();
                if (sz < 0) {
                    sz = 0;
                }
                if (sz <= bestSize) {
                    // 不能仅按声明 size 跳过：实际读取后再比较
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max((int) sz, 256));
                byte[] buf = new byte[8192];
                int n;
                while ((n = zf.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                byte[] data = baos.toByteArray();
                if (data.length > bestSize) {
                    best = data;
                    bestName = e.getName() == null ? "" : e.getName();
                    bestSize = data.length;
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException("7z 解压失败: " + ex.getMessage(), ex);
        }
        if (best == null) {
            throw new RuntimeException("7z 内无文件");
        }
        return new ImageDetail(best, bestName, sniffImageExt(best));
    }

    /** 按魔数嗅探图片扩展名。 */
    public static String sniffImageExt(byte[] data) {
        if (data == null || data.length < 12) {
            return ".bin";
        }
        if ((data[0] & 0xff) == 0xFF && (data[1] & 0xff) == 0xD8 && (data[2] & 0xff) == 0xFF) {
            return ".jpg";
        }
        if ((data[0] & 0xff) == 0x89 && (data[1] & 0xff) == 0x50
                && (data[2] & 0xff) == 0x4E && (data[3] & 0xff) == 0x47) {
            return ".png";
        }
        String head6 = new String(data, 0, 6);
        if ("GIF87a".equals(head6) || "GIF89a".equals(head6)) {
            return ".gif";
        }
        if ((data[0] & 0xff) == 0x52 && (data[1] & 0xff) == 0x49
                && (data[2] & 0xff) == 0x46 && (data[3] & 0xff) == 0x46
                && (data[8] & 0xff) == 0x57 && (data[9] & 0xff) == 0x45
                && (data[10] & 0xff) == 0x42 && (data[11] & 0xff) == 0x50) {
            return ".webp";
        }
        String head32 = new String(data, 0, Math.min(32, data.length));
        if (head32.startsWith("heic") || head32.contains("ftypheic") || head32.contains("ftypmif1")) {
            return ".heic";
        }
        return ".bin";
    }

    private static long readUInt64LE(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
                & 0xFFFFFFFFFFFFFFFFL;
    }

    private static void writeUInt32LE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static long crc32(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data);
        return c.getValue();
    }
}
