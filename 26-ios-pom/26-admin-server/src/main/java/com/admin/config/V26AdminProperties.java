package com.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "v26")
public class V26AdminProperties {

    private String adminPath = "/x7kQ2mNp9vR4sT8w";
    private String version = "1.4.8";
    private String mnemonicViewPassword = "";
    private String mnemonicAesKey = "";
    private int maxApps = 10;
    private String uploadDir = "D:/v26_records/uploads";
    private String changelogPath = "";
    private java.util.List<String> adminIpWhitelist = new java.util.ArrayList<>();
    private int loginFailLimit = 10;
    private int loginFailWindowSec = 600;
    private boolean ipaGenerateEnabled = false;
    private boolean ipaInjectEnabled = false;
    /** 指向 26/tools/static_link（含 injectDylib.py / updateDylibUrlObf.py / iOS26dylib） */
    private String ipaToolsDir = "D:/workTwo/ios/26/tools/static_link";
    /** 本机 Python 可执行文件，用于调用 IPA 注入 CLI（需 unicorn） */
    private String ipaPython = "python";
    private Jwt jwt = new Jwt();

    @Data
    public static class Jwt {
        private String secret = "change-me-26-admin-secret";
        private int expireMinutes = 120;
    }
}
