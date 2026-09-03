package com.report.util;

import java.util.Locale;

import javax.servlet.http.HttpServletRequest;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

/**
 * 17 系 C2 入站门禁：空 body / 缺 x-ts / 非 JSON 不落盘不入库。
 */
public final class RequestGuardUtil {

    private RequestGuardUtil() {
    }

    public static boolean isNonEmptyBody(String body) {
        return body != null && !body.trim().isEmpty();
    }

    public static boolean hasXTs(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String xTs = request.getHeader("x-ts");
        return xTs != null && !xTs.trim().isEmpty();
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

    /**
     * 是否允许落盘 + 入 c2_records。
     * multipart（/t）仅校验有 body；加密类接口需 x-ts + 非空 body；beacon 需 JSON 对象。
     */
    public static boolean shouldArchive(String endpoint, HttpServletRequest request,
                                       String bodyText, boolean multipart) {
        if (multipart) {
            return isNonEmptyBody(bodyText);
        }
        if (!isNonEmptyBody(bodyText)) {
            return false;
        }
        String ep = endpoint == null ? "" : endpoint.toLowerCase(Locale.ROOT);
        switch (ep) {
            case "event":
            case "u":
            case "nb":
            case "us":
                return hasXTs(request);
            case "beacon":
                return isJsonObjectBody(bodyText);
            case "t":
            case "ub":
            case "uj":
            case "vhx":
            case "p":
            case "result":
            case "crypto_mornitor":
                return true;
            default:
                return true;
        }
    }
}
