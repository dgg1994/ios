package com.send.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ApiResponse {

    private ApiResponse() {
    }

    public static Map<String, Object> ok(Object data) {
        return ok("ok", data);
    }

    public static Map<String, Object> ok(String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("message", message);
        body.put("data", data);
        return body;
    }

    public static Map<String, Object> fail(String message, int code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", null);
        return body;
    }

    public static Map<String, Object> v2OkUpload(Map<String, Object> result) {
        Map<String, Object> data = new LinkedHashMap<>();
        Object uploadId = result.get("uploadId");
        Object chunkSize = result.get("chunkSize");
        Object numberOfChunks = result.get("numberOfChunks");
        Object expectedChunks = result.get("expectedChunks");
        if (numberOfChunks == null) {
            numberOfChunks = expectedChunks;
        }
        if (numberOfChunks == null) {
            numberOfChunks = 0;
        }
        if (expectedChunks == null) {
            expectedChunks = numberOfChunks;
        }
        Object status = result.get("status");
        if (status == null) {
            status = "PENDING";
        }
        data.put("uploadId", uploadId);
        data.put("chunkSize", chunkSize);
        data.put("numberOfChunks", numberOfChunks);
        data.put("expectedChunks", expectedChunks);
        data.put("status", status);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("ok", true);
        body.put("uploadId", uploadId);
        body.put("chunkSize", chunkSize);
        body.put("numberOfChunks", numberOfChunks);
        body.put("expectedChunks", expectedChunks);
        body.put("data", data);
        return body;
    }
}
