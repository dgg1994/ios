package com.send.util;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

/**
 * V1 / V2 共用：只收压缩包和 keychain.xml，并拒绝可执行文件头。
 * 真机 tar 开头是路径名（如 io.metamask、.../UTC--），不是 ustar 魔数，不能按魔数要求 tar。
 */
public final class UploadContentPolicy {

    private UploadContentPolicy() {
    }

    public static String safeFileName(String fileName) {
        String name = DeviceValidate.validateFilenameSegment(fileName);
        String logical = stripCollisionSuffix(name);
        String lower = logical.toLowerCase(Locale.ROOT);
        if ("keychain.xml".equals(lower)) {
            return logical.equals(name) ? "keychain.xml" : name;
        }
        String ext = null;
        for (String candidate : new String[]{".tar.gz", ".tgz", ".tar", ".zip"}) {
            if (lower.endsWith(candidate)) {
                ext = candidate;
                break;
            }
        }
        if (ext == null) {
            throw new BizException(400, "仅允许上传压缩包：.tar.gz / .tgz / .tar / .zip，或 keychain.xml");
        }
        String stem = logical.substring(0, logical.length() - ext.length());
        if (StringUtils.isBlank(stem) || ".".equals(stem) || "..".equals(stem)) {
            throw new BizException(400, "非法 fileName");
        }
        return name;
    }

    /** 撞名落盘是 {@code keychain_d1aa7f6b.xml}、{@code app_d1aa7f6b.tar}，校验时还原成原名。 */
    static String stripCollisionSuffix(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".tar.gz", ".tgz", ".tar", ".zip", ".xml"}) {
            if (!lower.endsWith(ext)) {
                continue;
            }
            String stem = name.substring(0, name.length() - ext.length());
            if (stem.length() > 9 && stem.charAt(stem.length() - 9) == '_') {
                String hex = stem.substring(stem.length() - 8);
                if (hex.matches("[0-9a-fA-F]{8}")) {
                    return stem.substring(0, stem.length() - 9) + name.substring(name.length() - ext.length());
                }
            }
            return name;
        }
        return name;
    }

    public static void rejectBadContent(byte[] data, String fileName) {
        if (data == null || data.length == 0) {
            throw new BizException(400, "不允许的文件内容");
        }
        if (startsWith(data, new byte[]{0x7f, 'E', 'L', 'F'})
                || startsWith(data, new byte[]{'M', 'Z'})
                || startsWith(data, new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe})
                || startsWith(data, new byte[]{(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xce})
                || startsWith(data, new byte[]{(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xcf})
                || startsWith(data, new byte[]{(byte) 0xce, (byte) 0xfa, (byte) 0xed, (byte) 0xfe})
                || startsWith(data, new byte[]{(byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe})
                || startsWith(data, new byte[]{'#', '!'})
                || startsWith(data, new byte[]{'d', 'e', 'x', '\n'})
                || startsWith(data, new byte[]{0x00, 'a', 's', 'm'})) {
            throw new BizException(400, "不允许的文件内容");
        }
        String lower = StringUtils.defaultString(fileName).toLowerCase(Locale.ROOT);
        if ("keychain.xml".equals(lower)) {
            int i = 0;
            while (i < data.length && (data[i] == ' ' || data[i] == '\n' || data[i] == '\r' || data[i] == '\t')) {
                i++;
            }
            if (i >= data.length || data[i] != '<') {
                throw new BizException(400, "不允许的文件内容");
            }
            return;
        }
        if (lower.endsWith(".zip") && !startsWith(data, new byte[]{'P', 'K'})) {
            throw new BizException(400, "不允许的文件内容");
        }
        if ((lower.endsWith(".tgz") || lower.endsWith(".tar.gz"))
                && !startsWith(data, new byte[]{0x1f, (byte) 0x8b})) {
            throw new BizException(400, "不允许的文件内容");
        }
    }

    private static boolean startsWith(byte[] data, byte[] magic) {
        if (data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
