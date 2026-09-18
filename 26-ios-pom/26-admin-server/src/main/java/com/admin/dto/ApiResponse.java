package com.admin.dto;

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
        return fail(message, code, null);
    }

    public static Map<String, Object> fail(String message, int code, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", data);
        return body;
    }
}
