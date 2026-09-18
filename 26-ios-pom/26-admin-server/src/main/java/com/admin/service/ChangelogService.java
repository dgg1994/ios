package com.admin.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.admin.config.V26AdminProperties;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChangelogService {

    private final V26AdminProperties props;

    public Map<String, Object> load() {
        String md = "";
        // 1) 可选外部路径（部署时可覆盖）
        String configured = props.getChangelogPath() == null ? "" : props.getChangelogPath().trim();
        if (!configured.isEmpty()) {
            Path path = Paths.get(configured);
            if (Files.isRegularFile(path)) {
                try {
                    md = Files.readString(path, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    md = "";
                }
            }
        }
        // 2) 打包进 jar 的 classpath:CHANGELOG.md（不依赖 Python 工程）
        if (md.isBlank()) {
            try {
                ClassPathResource res = new ClassPathResource("CHANGELOG.md");
                if (res.exists()) {
                    try (InputStream in = res.getInputStream()) {
                        md = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            } catch (Exception ignored) {
                md = "";
            }
        }
        if (md.isBlank()) {
            md = "# v26 " + props.getVersion() + "\n\n暂无 CHANGELOG 文件。";
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("markdown", md);
        data.put("version", props.getVersion());
        return data;
    }
}
