package com.consumer.util;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;

/**
 * AWS S3 上传（相册专用：内存字节直传，不写本地业务盘）。
 */
@Component
public class S3FileUploadUtil {

    private static AmazonS3 amazonS3;
    private static String bucketName;

    @Autowired
    public void setAmazonS3(AmazonS3 amazonS3) {
        S3FileUploadUtil.amazonS3 = amazonS3;
    }

    @Value("${s3.file_bucket:}")
    public void setBucketName(String bucketName) {
        S3FileUploadUtil.bucketName = bucketName;
    }

    /**
     * @param data      图片字节
     * @param objectKey S3 key，如 photos/yyyyMMdd/device/sha_name.jpg
     * @param fileName  用于 Content-Type
     * @return 可访问 URL；失败 null
     */
    public String uploadBytes(byte[] data, String objectKey, String fileName) {
        if (data == null || data.length == 0) {
            return null;
        }
        if (objectKey == null || objectKey.trim().isEmpty()) {
            return null;
        }
        if (amazonS3 == null || bucketName == null || bucketName.isEmpty()) {
            return null;
        }
        String key = objectKey.trim().replace('\\', '/').replaceAll("^/+", "");
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(data.length);
            metadata.setContentType(guessContentType(fileName != null ? fileName : key));
            try (InputStream in = new ByteArrayInputStream(data)) {
                amazonS3.putObject(new PutObjectRequest(bucketName, key, in, metadata));
            }
            return amazonS3.getUrl(bucketName, key).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String guessContentType(String name) {
        if (name == null) {
            return "application/octet-stream";
        }
        String lower = name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".heic")) {
            return "image/heic";
        }
        return "application/octet-stream";
    }
}
