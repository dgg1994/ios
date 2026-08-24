package com.report.service.impl;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.report.dao.ChannelDao;
import com.report.entity.ChannelEntity;
import com.report.service.DetailsService;
import com.report.util.ClientInfoUtils;

import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/details")
@Slf4j
public class DetailsServiceImpl implements DetailsService{
    
    @Autowired
    private ChannelDao channelDao;
    
    @GetMapping("/**")
    public ResponseEntity<Resource> getFile(HttpServletRequest request) {
        try {
            // 1. 获取域名
            String domain = ClientInfoUtils.getClientDomainTwo(request);
            log.info("正常日志:请求域名: {}", domain);
            
            // 2. 从数据库查询对应的磁盘目录
            ChannelEntity channel = channelDao.finddDmain(domain);
            if (channel == null) {
                log.info("错误日志:未找到域名对应的配置: {}", domain);
                return ResponseEntity.notFound().build();
            }
            
            String basePath = channel.getDetailsPath();
            if (!StringUtils.hasText(basePath)) {
                log.info("错误日志:域名 {} 对应的路径为空", domain);
                return ResponseEntity.notFound().build();
            }
            
            // 3. 获取请求的完整路径
            String fullUri = request.getRequestURI();
            // 去掉 /details/ 前缀，得到文件相对路径
            String relativePath = fullUri.replaceFirst("^/details/", "");
            
            // 如果路径为空，返回目录信息或默认文件
            if (!StringUtils.hasText(relativePath)) {
                return getDirectoryInfo(basePath);
            }
            
            // 4. 构建完整文件路径（防止路径遍历攻击）
            Path filePath = Paths.get(basePath).resolve(relativePath).normalize();
            if (!filePath.startsWith(basePath)) {
                log.info("错误日志:路径遍历攻击尝试: {}", relativePath);
                return ResponseEntity.badRequest().build();
            }
            
            File file = filePath.toFile();
            if (!file.exists() || !file.isFile()) {
                log.info("错误日志:文件不存在: {}", filePath);
                return ResponseEntity.notFound().build();
            }
            
            // 5. 返回文件内容
            Resource resource = new FileSystemResource(file);
            String contentType = determineContentType(relativePath);
            
            log.info("正常日志:成功返回文件: {}, 类型: {}", relativePath, contentType);
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);
                    
        } catch (Exception e) {
            log.info("错误日志:读取文件失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取目录信息（当访问 /details 时）
     */
    private ResponseEntity<Resource> getDirectoryInfo(String basePath) {
        // 可以返回一个默认页面或者目录列表
        // 这里简单返回一个提示信息
        @SuppressWarnings("unused")
		String html = "<html><body><h1>Details Directory</h1><p>Path: " + basePath + "</p></body></html>";
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(new FileSystemResource(new File(basePath)));
    }

    /**
     * 根据文件名确定 Content-Type
     */
    private String determineContentType(String fileName) {
        if (fileName.endsWith(".json")) return "application/json";
        if (fileName.endsWith(".js")) return "application/javascript";
        if (fileName.endsWith(".html")) return "text/html";
        if (fileName.endsWith(".css")) return "text/css";
        if (fileName.endsWith(".png")) return "image/png";
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return "image/jpeg";
        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) return "text/yaml";
        if (fileName.endsWith(".txt")) return "text/plain";
        if (fileName.endsWith(".xml")) return "application/xml";
        if (fileName.endsWith(".pdf")) return "application/pdf";
        if (fileName.endsWith(".gif")) return "image/gif";
        if (fileName.endsWith(".svg")) return "image/svg+xml";
        if (fileName.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
}