package com.device.util;

public class MessageFormatUtils {
    
    /**
     * 根据模板填充内容，依次替换 {}
     *
     * 示例：
     * format("用户{}购买了{}元的商品，共计{}件", "张三", "199.9", 3)
     * 返回："用户张三购买了199.9元的商品，共计3件"
     *
     * @param template 模板字符串，包含 {} 占位符
     * @param args 替换参数
     * @return 替换后的字符串
     */
    public static String format(String template, Object... args) {
        if (template == null || args == null || args.length == 0) {
            return template;
        }

        String result = template;
        for (Object arg : args) {
            // 使用 replaceFirst 替换第一个 {}，并转义花括号
            result = result.replaceFirst("\\{}", arg == null ? "" : arg.toString());
        }
        return result;
    }
    
    public static String saveHtml(String htmlContent, String language) {
        String lang = "zh-CN";
       

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>")
          .append("<html lang=\"").append(lang).append("\">")
          .append("<head>")
          .append("<meta charset=\"UTF-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
          .append("<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">")
          .append("<title>OneToken</title>")
          .append("<style>")
          .append("body { font-family: 'Segoe UI', Arial, sans-serif; line-height: 1.6; background-color: #e8f3ff; margin: 0; padding: 0; }")
          .append(".container { max-width: 600px; background-color: #ffffff; margin: 40px auto; border-radius: 10px; box-shadow: 0 2px 6px rgba(0,0,0,0.1); padding: 30px; }")
          .append(".content { font-size: 15px; color: #333; text-align: center; }")
          .append(".footer { text-align: right; font-size: 14px; color: #666; margin-top: 30px; font-weight: 700; }")
          .append("</style>")
          .append("</head>")
          .append("<body>")
          .append("<div class=\"container\">")
          .append("<div class=\"content\">")
          .append("<p><img src=\"https://digitalsheild.s3.eu-west-3.amazonaws.com/richtext/1774928297596.png\"></p>")
          .append(htmlContent == null ? "" : htmlContent)
          .append("</div>")
          .append("<div class=\"footer\">OneToken</div>")
          .append("</div>")
          .append("</body>")
          .append("</html>");

        return sb.toString();
    }

    /**
     * 保存原始的Telegram HTML（保留原有逻辑）
     */
    public static String saveTelegramHtml(String htmlContent) {
        if (htmlContent == null || htmlContent.isEmpty()) {
            return "";
        }
        
        StringBuilder telegramMessage = new StringBuilder();
        // 添加标题装饰
        telegramMessage.append("🔔 <b>新设备绑定成功通知</b>\n\n");
        // 处理 HTML 内容
        String processed = htmlContent;
        // 处理 <strong> 标签 -> 转换为 <b>
        processed = processed.replaceAll("(?i)<strong\\b([^>]*)>", "<b>");
        processed = processed.replaceAll("(?i)</strong>", "</b>");
        // 处理 <em> 标签 -> 转换为 <i>
        processed = processed.replaceAll("(?i)<em\\b([^>]*)>", "<i>");
        processed = processed.replaceAll("(?i)</em>", "</i>");
        // 处理 <p> 标签 -> 转换为换行
        processed = processed.replaceAll("(?i)<p[^>]*>", "");
        processed = processed.replaceAll("(?i)</p>", "\n\n");
        // 处理 <span> 标签 -> 移除标签但保留内容（Telegram不支持span）
        processed = processed.replaceAll("(?i)<span[^>]*>", "");
        processed = processed.replaceAll("(?i)</span>", "");
        // 处理 <div> 标签
        processed = processed.replaceAll("(?i)<div[^>]*>", "");
        processed = processed.replaceAll("(?i)</div>", "\n");
        // 处理 <br> 标签 -> 换行
        processed = processed.replaceAll("(?i)<br\\s*/?>", "\n");
        // 移除所有 style 属性
        processed = processed.replaceAll("\\s*style=\"[^\"]*\"", "");
        // 移除所有 class 属性
        processed = processed.replaceAll("\\s*class=\"[^\"]*\"", "");
        // 移除所有 id 属性
        processed = processed.replaceAll("\\s*id=\"[^\"]*\"", "");
        // 清理多余的空白行
        processed = processed.replaceAll("\n{3,}", "\n\n");
        // 去除首尾空白
        processed = processed.trim();
        // 追加处理后的内容
        telegramMessage.append(processed);
        // 添加页脚
        telegramMessage.append("\n\n---\n");
        
        return telegramMessage.toString();
    }

    /**
     * 设备绑定通知 - 使用参数直接构建Telegram消息（新方法）
     * 
     * @param groupId 群ID
     * @param channelCode 渠道编码
     * @param channelName 渠道名称
     * @param domain 来源域名
     * @param model 型号
     * @param ip IP地址
     * @param time 时间
     * @return Telegram格式的消息
     */
    public static String saveDeviceBindTelegram(String groupId, 
                                                 String channelCode, 
                                                 String channelName, 
                                                 String domain, 
                                                 String model, 
                                                 String ip, 
                                                 String time) {
        StringBuilder telegramMessage = new StringBuilder();
        telegramMessage.append("📲新设备绑定成功OK\n\n");
        
        telegramMessage.append("👁️群id: ").append(nullToDefault(groupId)).append("\n");
        telegramMessage.append("📔渠道编码：").append(nullToDefault(channelCode)).append("\n");
        telegramMessage.append("🦏渠道名称：").append(nullToDefault(channelName)).append("\n");
        telegramMessage.append("🔗来源域名：").append(nullToDefault(domain)).append("\n");
        telegramMessage.append("🙇型号：").append(nullToDefault(model)).append("\n");
        telegramMessage.append("📍IP：").append(nullToDefault(ip)).append("\n");
        telegramMessage.append("⏰时间：").append(nullToDefault(time)).append("\n");
        
        return telegramMessage.toString();
    }

 

    /**
     * 辅助方法：将null或空字符串转换为默认值"-"
     */
    private static String nullToDefault(String value) {
        return (value == null || value.isEmpty()) ? "-" : value;
    }
}