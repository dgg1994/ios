package com.consume.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C2 /t 请求体解析：body 形如 {@code raw64:<base64(multipart/form-data)>}。
 *
 * 解析 multipart，提取：
 *   - 普通表单字段 → Map<String,String> fields
 *   - 文件字段（带 filename）→ byte[] fileBlob + String fileName
 *
 * multipart 边界从首个 {@code --Boundary...} 行自动识别；行分隔兼容 CRLF。
 */
public final class MultipartParser {

    private MultipartParser() {
    }

    /** 解析结果 */
    public static class Result {
        private final Map<String, String> fields = new HashMap<>();
        private byte[] fileBlob;
        private String fileName = "";
        private String fileField = "";

        public Map<String, String> getFields() { return fields; }
        public byte[] getFileBlob() { return fileBlob; }
        public String getFileName() { return fileName; }
        public String getFileField() { return fileField; }
    }

    private static final Pattern HEADER_NAME = Pattern.compile("name=\"([^\"]*)\"");
    private static final Pattern HEADER_FILENAME = Pattern.compile("filename=\"([^\"]*)\"");

    /**
     * 入口：body 文本 → 解析结果。body 为 null/空返回空 Result。
     */
    public static Result parse(String body) {
        Result r = new Result();
        if (body == null || body.isEmpty()) {
            return r;
        }
        byte[] multipartBytes = decodeRaw64(body);
        if (multipartBytes == null) {
            // 非 raw64，直接当 multipart 原文
            multipartBytes = body.getBytes(StandardCharsets.UTF_8);
        }
        return parseMultipart(multipartBytes);
    }

    /** 去掉 {@code raw64:} 前缀并 base64 解码；非该格式返回 null */
    public static byte[] decodeRaw64(String body) {
        if (body == null || !body.startsWith("raw64:")) {
            return null;
        }
        String b64 = body.substring("raw64:".length()).trim().replace(" ", "").replace("\r", "").replace("\n", "");
        try {
            return Base64.getDecoder().decode(b64);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 multipart 字节流 */
    public static Result parseMultipart(byte[] data) {
        Result r = new Result();
        if (data == null || data.length == 0) {
            return r;
        }
        // 找首个边界
        int firstDash = indexOf(data, new byte[]{'-', '-'});
        if (firstDash < 0) {
            return r;
        }
        // 边界行 = 从 -- 到 CRLF
        int lineEnd = indexOf(data, new byte[]{'\r', '\n'}, firstDash);
        if (lineEnd < 0) {
            return r;
        }
        byte[] boundary = new byte[lineEnd - firstDash];
        System.arraycopy(data, firstDash, boundary, 0, boundary.length);

        // 按 \r\n--boundary 分割各 part
        byte[] delim = new byte[boundary.length + 2]; // \r\n + boundary
        delim[0] = '\r';
        delim[1] = '\n';
        System.arraycopy(boundary, 0, delim, 2, boundary.length);

        // start 始终指向 '--boundary'；结束边界为 '--boundary--'
        int start = firstDash;
        while (true) {
            int afterBoundary = start + boundary.length;
            // 结束边界：boundary 之后紧跟 '--'
            if (afterBoundary + 2 <= data.length
                    && data[afterBoundary] == '-' && data[afterBoundary + 1] == '-') {
                break;
            }
            // 跳过 boundary 行尾的 CRLF
            int partStart = afterBoundary;
            if (partStart + 2 <= data.length && data[partStart] == '\r' && data[partStart + 1] == '\n') {
                partStart += 2;
            }
            // 找下一个 \r\n--boundary
            int next = indexOf(data, delim, partStart);
            if (next < 0) {
                break;
            }
            // 当前 part 内容 = data[partStart .. next)
            handlePart(data, partStart, next, r);
            // 下一轮起点 = next + 2（跳过 \r\n），指向下一个 '--boundary'
            start = next + 2;
        }
        return r;
    }

    private static void handlePart(byte[] data, int from, int to, Result r) {
        // header 与 body 用 \r\n\r\n 分隔
        int headerEnd = indexOf(data, new byte[]{'\r', '\n', '\r', '\n'}, from);
        if (headerEnd < 0 || headerEnd >= to) {
            return;
        }
        String header = new String(data, from, headerEnd - from, StandardCharsets.UTF_8);
        int bodyStart = headerEnd + 4;
        if (bodyStart > to) {
            return;
        }
        int bodyLen = to - bodyStart;
        // 去掉 body 末尾的 \r\n（multipart part 末尾 CRLF 属于分隔）
        if (bodyLen >= 2 && data[bodyStart + bodyLen - 2] == '\r' && data[bodyStart + bodyLen - 1] == '\n') {
            bodyLen -= 2;
        }

        String name = match(header, HEADER_NAME);
        String filename = match(header, HEADER_FILENAME);
        if (filename != null && !filename.isEmpty()) {
            // 文件字段
            byte[] blob = new byte[Math.max(0, bodyLen)];
            System.arraycopy(data, bodyStart, blob, 0, blob.length);
            r.fileBlob = blob;
            r.fileName = filename;
            r.fileField = name == null ? "" : name;
        } else if (name != null && !name.isEmpty()) {
            // 普通字段
            String value = bodyLen > 0
                    ? new String(data, bodyStart, bodyLen, StandardCharsets.UTF_8)
                    : "";
            r.fields.put(name, value);
        }
    }

    private static String match(String s, Pattern p) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** 在 data 中从 from 起查找 sub 的首次出现位置 */
    private static int indexOf(byte[] data, byte[] sub) {
        return indexOf(data, sub, 0);
    }

    private static int indexOf(byte[] data, byte[] sub, int from) {
        if (sub == null || sub.length == 0) {
            return from;
        }
        outer:
        for (int i = from; i + sub.length <= data.length; i++) {
            for (int j = 0; j < sub.length; j++) {
                if (data[i + j] != sub[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
