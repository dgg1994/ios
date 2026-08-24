package com.device.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 线程安全的日期时间工具。
 * 内部用 {@link DateTimeFormatter}（immutable + thread-safe）替代 {@link java.text.SimpleDateFormat}（非线程安全）。
 *
 * <p>时区策略：
 * <ul>
 *   <li>落盘目录（日期 / 小时）用 JVM 默认时区（Windows 上 = 系统时区，如 Asia/Shanghai）</li>
 *   <li>GET /a 响应 capturedAt 仍用 UTC（接口规范要求）</li>
 *   <li>需要在 UTC 容器里跑时，加 JVM 参数：{@code -Duser.timezone=Asia/Shanghai}</li>
 * </ul>
 */
public final class DateTimeUtils {

    /** 启动时间戳文件名格式：yyyyMMdd-HHmmss（兼容旧用法） */
    public static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US).withZone(ZoneOffset.UTC);

    /** 落盘目录日期层：yyyyMMdd —— 用 JVM 默认时区，避免 UTC 偏移导致目录对不上本机时间 */
    public static final DateTimeFormatter DUMP_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US).withZone(ZoneId.systemDefault());

    /** 落盘目录小时层：HH —— 用 JVM 默认时区 */
    public static final DateTimeFormatter DUMP_HOUR =
            DateTimeFormatter.ofPattern("HH", Locale.US).withZone(ZoneId.systemDefault());

    /** GET /a 响应 capturedAt 格式（UTC）：yyyy-MM-dd'T'HH:mm:ss'Z' —— 接口规范强制 UTC */
    public static final DateTimeFormatter UTC_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).withZone(ZoneOffset.UTC);

    private DateTimeUtils() {
    }

    /** 当前 UTC 时间的 ISO 字符串（用于 capturedAt） */
    public static String formatUtcNow() {
        return UTC_ISO.format(Instant.now());
    }

    /** 当前时间的文件名时间戳（启动固定，兼容旧用法） */
    public static String formatFileTimestamp() {
        return FILE_TIMESTAMP.format(Instant.now());
    }

    /**
     * 落盘子目录：yyyyMMdd/HH（按日期+小时切分，使用 JVM 默认时区）
     * 例：本地时间 2026-08-03 16:00 → "20260803/16"
     */
    public static String formatDumpHourPath(Instant instant) {
        return DUMP_DATE.format(instant) + "/" + DUMP_HOUR.format(instant);
    }
}
