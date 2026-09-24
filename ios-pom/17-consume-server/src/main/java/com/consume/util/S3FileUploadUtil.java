package com.consume.util;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * AWS S3 文件上传工具类
 */
@Component
public class S3FileUploadUtil {
    
    private static AmazonS3 amazonS3;
    private static String bucketName;
    
    /** 本地未配 s3.aws_region 时无 AmazonS3 bean，允许为空。 */
    @Autowired(required = false)
    public void setAmazonS3(AmazonS3 amazonS3) {
        S3FileUploadUtil.amazonS3 = amazonS3;
    }
    
    @Value("${s3.file_bucket}")
    public void setBucketName(String bucketName) {
        S3FileUploadUtil.bucketName = bucketName;
    }
    
    
    public static String fileUpload(MultipartFile file,String type) {
		try {
			// 检查文件是否为空或大小是否超过限制
			if (file.isEmpty() || file.getSize() > 200 * 1024 * 1024) { // 假设最大文件大小为 200MB
				return "文件为空或超过最大限制";
			}
			// 创建临时文件
			File tempFile = File.createTempFile("upload-", file.getOriginalFilename(),
					new File(System.getProperty("java.io.tmpdir")));
			file.transferTo(tempFile); // 将 MultipartFile 保存为 File
			// 构建 S3 中的完整路径（目录+文件名）
			String fileName = type +System.currentTimeMillis()+ file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf("."));
			// 创建上传请求
			PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, fileName, tempFile);
			// 执行上传
			amazonS3.putObject(putObjectRequest);
			String fileUrl = amazonS3.getUrl(bucketName, fileName).toString();
			return fileUrl;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}
    
    public static String fileUploadHtml(MultipartFile file, String type) {
        try {
            if (file.isEmpty() || file.getSize() > 200 * 1024 * 1024) {
                return "文件为空或超过最大限制";
            }
            
            String fileName = type + System.currentTimeMillis() + 
                file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf("."));
            
            // 不调用 transferTo，直接使用 InputStream
            try (InputStream inputStream = file.getInputStream()) {
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(file.getSize());
                metadata.setContentType(file.getContentType());
                
                PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, fileName, inputStream, metadata);
                amazonS3.putObject(putObjectRequest);
            }
            
            return amazonS3.getUrl(bucketName, fileName).toString();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
	


	@SuppressWarnings("unused")
	private static File createTempFile(MultipartFile file) throws IOException {
		// 创建临时文件
		File tempFile = File.createTempFile("upload-", file.getOriginalFilename(),
				new File(System.getProperty("java.io.tmpdir")));

		// 使用输入流将 MultipartFile 内容写入临时文件
		try (InputStream inputStream = file.getInputStream(); FileOutputStream fos = new FileOutputStream(tempFile)) {
			byte[] buffer = new byte[1024];
			int bytesRead;
			while ((bytesRead = inputStream.read(buffer)) != -1) {
				fos.write(buffer, 0, bytesRead);
			}
		}
		return tempFile;
	}

	@SuppressWarnings("unused")
	private static String uploadToS3(File file, String fileName) {
		try {
			ObjectMetadata metadata = new ObjectMetadata();
			metadata.setContentType("text/html; charset=UTF-8");

			PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, fileName, file);
			putObjectRequest.setMetadata(metadata);

			amazonS3.putObject(putObjectRequest);
			return amazonS3.getUrl(bucketName, fileName).toString();

		} catch (AmazonS3Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * 上传本地文件到 S3（相册解图后专用）。
	 *
	 * @param file       本地图片文件
	 * @param objectKey  S3 object key，如 photos/123/456.jpg
	 * @return 可访问 URL；失败返回 null
	 */
	public String uploadLocalFile(File file, String objectKey) {
		if (file == null || !file.isFile()) {
			return null;
		}
		if (objectKey == null || objectKey.trim().isEmpty()) {
			return null;
		}
		if (amazonS3 == null || bucketName == null || bucketName.isEmpty()) {
			return null;
		}
		String key = objectKey.trim().replace("\\", "/").replaceAll("^/+", "");
		try {
			ObjectMetadata metadata = new ObjectMetadata();
			metadata.setContentLength(file.length());
			metadata.setContentType(guessContentType(file.getName()));
			PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, key, file);
			putObjectRequest.setMetadata(metadata);
			amazonS3.putObject(putObjectRequest);
			return amazonS3.getUrl(bucketName, key).toString();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * 上传内存字节到 S3（相册云存储：不写本地业务盘）。
	 *
	 * @param data       图片字节
	 * @param objectKey  S3 object key，如 photos/123/456.jpg
	 * @param fileName   用于推断 Content-Type
	 * @return 可访问 URL；失败返回 null
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
		String key = objectKey.trim().replace("\\", "/").replaceAll("^/+", "");
		try {
			ObjectMetadata metadata = new ObjectMetadata();
			metadata.setContentLength(data.length);
			metadata.setContentType(guessContentType(fileName != null ? fileName : key));
			try (InputStream in = new java.io.ByteArrayInputStream(data)) {
				PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, key, in, metadata);
				amazonS3.putObject(putObjectRequest);
			}
			return amazonS3.getUrl(bucketName, key).toString();
		} catch (Exception e) {
			e.printStackTrace();
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