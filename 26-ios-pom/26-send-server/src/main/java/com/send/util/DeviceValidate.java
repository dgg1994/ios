package com.send.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * 对齐 Python {@code app.services.device_validate}。
 */
public final class DeviceValidate {

    private static final Pattern UUID_RE = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern UUID_FIND_RE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DEVICE_ID_LOOSE_RE =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern HARDWARE_RE =
            Pattern.compile("^(iPhone|iPad|iPod)\\d{1,2},\\d{1,2}$");
    private static final Pattern IOS_VERSION_RE =
            Pattern.compile("^\\d{1,2}(\\.\\d{1,3}){1,3}$");
    private static final Pattern MODEL_RE = Pattern.compile("^(iPhone|iPad|iPod)$");
    private static final Pattern DEVICE_NAME_RE =
            Pattern.compile("^[\\w .\\-'+（）()·\\u4e00-\\u9fff]{1,128}$");
    private static final Pattern APP_NAME_RE =
            Pattern.compile("^[\\w .\\-'+（）()·&/\\u4e00-\\u9fff]{1,64}$");
    private static final Pattern BUNDLE_ID_RE =
            Pattern.compile("^[A-Za-z][A-Za-z0-9-]*(\\.[A-Za-z0-9][A-Za-z0-9-]*){1,8}$");

    private DeviceValidate() {
    }

    public static String validateDeviceId(String value) {
        return validateDeviceId(value, "deviceId", false);
    }

    public static String validateDeviceId(String value, String field, boolean loose) {
        String text = StringUtils.trimToEmpty(value);
        if (text.isEmpty()) {
            throw new BizException(400, field + " 不能为空");
        }
        if (UUID_RE.matcher(text).matches()) {
            return text.toLowerCase();
        }
        if (loose && DEVICE_ID_LOOSE_RE.matcher(text).matches() && !text.contains("..")) {
            return text;
        }
        throw new BizException(400, field + " 必须是 UUID" + (loose ? " 或合法设备标识" : ""));
    }

    public static String validateXDeviceIdHeader(String raw) {
        if (StringUtils.isBlank(raw)) {
            throw new BizException(400, "缺少 X-Device-Id");
        }
        return validateDeviceId(raw, "X-Device-Id", false);
    }

    public static String validateXDeviceIdHeaderLoose(String raw) {
        if (StringUtils.isBlank(raw)) {
            throw new BizException(400, "缺少 X-Device-Id");
        }
        return validateDeviceId(raw, "X-Device-Id", true);
    }

    public static String extractUuidFromName(String name) {
        if (name == null) {
            return null;
        }
        Matcher m = UUID_FIND_RE.matcher(name);
        return m.find() ? m.group(0).toLowerCase() : null;
    }

    public static String validateOptionalUuid(String value, String field) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (!UUID_RE.matcher(text).matches()) {
            throw new BizException(400, field + " 必须是 UUID");
        }
        return text.toLowerCase();
    }

    public static String validateHardwareModel(String value) {
        return matchOrNull(value, HARDWARE_RE, "hardwareModel 格式非法，期望如 iPhone14,5");
    }

    public static String validateIosVersion(String value) {
        return matchOrNull(value, IOS_VERSION_RE, "iosVersion 格式非法，期望如 18.6.1");
    }

    public static String validateDeviceName(String value) {
        String text = StringUtils.trimToNull(value);
        if (text == null) {
            return null;
        }
        if (!DEVICE_NAME_RE.matcher(text).matches() || hasControl(text)) {
            throw new BizException(400, "deviceName 格式非法");
        }
        return text;
    }

    public static String validateModel(String value) {
        return matchOrNull(value, MODEL_RE, "model 格式非法，期望 iPhone / iPad / iPod");
    }

    public static String validateAppName(String value) {
        String text = StringUtils.trimToNull(value);
        if (text == null) {
            return null;
        }
        if (!APP_NAME_RE.matcher(text).matches() || hasControl(text)) {
            throw new BizException(400, "appName 格式非法");
        }
        return text;
    }

    public static String validateBundleId(String value) {
        String text = StringUtils.trimToNull(value);
        if (text == null) {
            return null;
        }
        if (text.length() > 128 || !BUNDLE_ID_RE.matcher(text).matches()) {
            throw new BizException(400, "bundleId 格式非法，期望反向域名");
        }
        return text;
    }

    public static String validateUploadId(String raw) {
        String text = StringUtils.trimToEmpty(raw);
        if (text.isEmpty() || text.length() > 64 || !UUID_RE.matcher(text).matches()) {
            throw new BizException(400, "非法 uploadId");
        }
        return text.toLowerCase();
    }

    public static String validateFilenameSegment(String name) {
        String text = StringUtils.trimToEmpty(name);
        if (text.isEmpty() || text.length() > 255) {
            throw new BizException(400, "非法 fileName");
        }
        if (text.contains("..") || text.contains("/") || text.contains("\\") || text.contains("\0")) {
            throw new BizException(400, "非法 fileName");
        }
        return text;
    }

    private static String matchOrNull(String value, Pattern pattern, String err) {
        String text = StringUtils.trimToNull(value);
        if (text == null) {
            return null;
        }
        if (!pattern.matcher(text).matches()) {
            throw new BizException(400, err);
        }
        return text;
    }

    private static boolean hasControl(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 32) {
                return true;
            }
        }
        return false;
    }
}
