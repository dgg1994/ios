package com.ctwo.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * 入站请求格式门禁：非 JSON 对象 / 空 body 不落库（仍由接口层返回 ACK）。
 */
public final class RequestGuardUtil {

    private RequestGuardUtil() {
    }

    public static JSONObject parseJsonObject(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            Object obj = JSON.parse(body);
            if (obj instanceof JSONObject) {
                return (JSONObject) obj;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    public static boolean isJsonObjectBody(String body) {
        return parseJsonObject(body) != null;
    }
}
