package com.admin.util;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;

public final class LoginIpUtil {

    public static final int MAX_ITEMS = 32;
    public static final int ITEM_MAX = 64;
    private static final Pattern SPLIT = Pattern.compile("[\\s,;]+");

    private LoginIpUtil() {
    }

    public static List<String> normalize(Object raw) {
        List<String> parts = new ArrayList<>();
        if (raw == null) {
            return parts;
        }
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                parts.addAll(normalize(item == null ? "" : String.valueOf(item)));
            }
            return dedupe(parts);
        }
        String s = (raw instanceof String) ? ((String) raw).trim() : String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return parts;
        }
        if (s.startsWith("[")) {
            try {
                JSONArray arr = JSON.parseArray(s);
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        parts.addAll(normalize(arr.getString(i)));
                    }
                    return dedupe(parts);
                }
            } catch (Exception ignored) {
                // fall through to split
            }
        }
        for (String p : SPLIT.split(s)) {
            if (!p.isBlank()) {
                parts.add(p.trim());
            }
        }
        return dedupe(parts);
    }

    private static List<String> dedupe(List<String> parts) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String token : parts) {
            if (token == null) {
                continue;
            }
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (seen.add(t.toLowerCase(Locale.ROOT))) {
                out.add(t);
            }
        }
        return out;
    }

    public static String validate(Object raw) {
        List<String> items = normalize(raw);
        if (items.size() > MAX_ITEMS) {
            return "最多 " + MAX_ITEMS + " 条 IP/CIDR";
        }
        for (String token : items) {
            if (token.length() > ITEM_MAX) {
                return "IP/CIDR 条目过长";
            }
            if (!isValidEntry(token)) {
                return "无效 IP 或 CIDR：" + token;
            }
        }
        return null;
    }

    public static boolean allowed(String ip, List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return true;
        }
        InetAddress addr = parseIp(ip);
        if (addr == null) {
            return false;
        }
        for (String token : entries) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String t = token.trim();
            try {
                if (t.contains("/")) {
                    if (inCidr(addr, t)) {
                        return true;
                    }
                } else {
                    InetAddress allowed = parseIp(t);
                    if (allowed != null && addr.equals(allowed)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public static boolean isValidEntry(String token) {
        String s = token == null ? "" : token.trim();
        if (s.isEmpty()) {
            return false;
        }
        try {
            if (s.contains("/")) {
                String[] p = s.split("/", 2);
                if (parseIp(p[0]) == null) {
                    return false;
                }
                int prefix = Integer.parseInt(p[1]);
                boolean v4 = p[0].contains(".");
                return prefix >= 0 && prefix <= (v4 ? 32 : 128);
            }
            return parseIp(s) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static InetAddress parseIp(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.contains("%")) {
            s = s.split("%", 2)[0];
        }
        if (s.startsWith("::ffff:") && s.indexOf('.') > 0) {
            s = s.substring(7);
        }
        try {
            return InetAddress.getByName(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean inCidr(InetAddress addr, String cidr) throws Exception {
        String[] parts = cidr.split("/", 2);
        InetAddress network = parseIp(parts[0]);
        if (network == null) {
            return false;
        }
        int prefix = Integer.parseInt(parts[1]);
        byte[] a = addr.getAddress();
        byte[] n = network.getAddress();
        if (a.length != n.length) {
            return false;
        }
        int full = prefix / 8;
        int rem = prefix % 8;
        for (int i = 0; i < full; i++) {
            if (a[i] != n[i]) {
                return false;
            }
        }
        if (rem == 0) {
            return true;
        }
        int mask = 0xFF << (8 - rem);
        return (a[full] & mask) == (n[full] & mask);
    }
}
