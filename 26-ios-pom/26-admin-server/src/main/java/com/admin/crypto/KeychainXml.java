package com.admin.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 keychain.xml 条目解析（对齐 Python keychain_parse.iter_keychain_items）。
 */
public final class KeychainXml {

    private static final Pattern ITEM_SPLIT = Pattern.compile("(?i)<item>");
    private static final Pattern AGRP = Pattern.compile("(?i)<agrp>([^<]*)</agrp>");
    private static final Pattern ACCT = Pattern.compile("(?i)<acct([^>]*)>([^<]*)</acct>");
    private static final Pattern SVCE = Pattern.compile("(?i)<svce([^>]*)>([^<]*)</svce>");
    private static final Pattern VDATA = Pattern.compile("(?i)<v_Data([^>]*)>([^<]*)</v_Data>");

    private KeychainXml() {
    }

    public static final class Item {
        public final String agrp;
        public final String acct;
        public final String svce;
        public final byte[] raw;
        public final String text;

        public Item(String agrp, String acct, String svce, byte[] raw, String text) {
            this.agrp = agrp == null ? "" : agrp;
            this.acct = acct == null ? "" : acct;
            this.svce = svce == null ? "" : svce;
            this.raw = raw == null ? new byte[0] : raw;
            this.text = text == null ? "" : text;
        }
    }

    public static List<Item> parse(Path keychainPath) {
        List<Item> out = new ArrayList<>();
        if (keychainPath == null || !Files.isRegularFile(keychainPath)) {
            return out;
        }
        String text;
        try {
            text = Files.readString(keychainPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return out;
        }
        String[] blocks = ITEM_SPLIT.split(text);
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            String agrp = group(AGRP, block, 1);
            Matcher acctM = ACCT.matcher(block);
            String acct = "";
            if (acctM.find()) {
                acct = decodeField(acctM.group(2), isBin(acctM.group(1)));
            }
            Matcher svceM = SVCE.matcher(block);
            String svce = "";
            if (svceM.find()) {
                svce = decodeField(svceM.group(2), isBin(svceM.group(1)));
            }
            Matcher dataM = VDATA.matcher(block);
            if (!dataM.find()) {
                continue;
            }
            boolean bin = isBin(dataM.group(1));
            byte[] raw = decodePayload(dataM.group(2), bin);
            String decoded;
            try {
                decoded = new String(raw, StandardCharsets.UTF_8).replace("\0", "").trim();
            } catch (Exception e) {
                decoded = "";
            }
            out.add(new Item(agrp, acct, svce, raw, decoded));
        }
        return out;
    }

    public static String readString(Path keychainPath) {
        if (keychainPath == null || !Files.isRegularFile(keychainPath)) {
            return "";
        }
        try {
            return Files.readString(keychainPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isBin(String attrs) {
        String a = attrs == null ? "" : attrs.toLowerCase(Locale.ROOT);
        return a.contains("bin=\"1\"") || a.contains("bin='1'");
    }

    private static String decodeField(String raw, boolean bin) {
        String val = raw == null ? "" : raw.trim();
        if (val.isEmpty() || !bin) {
            return val;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(val);
            try {
                return new String(decoded, StandardCharsets.UTF_8).replace("\0", "").trim();
            } catch (Exception e) {
                return toHex(decoded);
            }
        } catch (Exception e) {
            return val;
        }
    }

    private static byte[] decodePayload(String raw, boolean bin) {
        String val = raw == null ? "" : raw.trim();
        if (val.isEmpty()) {
            return new byte[0];
        }
        if (!bin) {
            return val.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return Base64.getDecoder().decode(val);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static String group(Pattern p, String block, int g) {
        Matcher m = p.matcher(block);
        return m.find() ? m.group(g).trim() : "";
    }

    public static String toHex(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    public static byte[] fromHex(String hex) {
        String h = hex == null ? "" : hex.trim();
        if (h.length() % 2 != 0) {
            return new byte[0];
        }
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static byte[] b64(String s) {
        String raw = (s == null ? "" : s.trim()).replace('-', '+').replace('_', '/');
        int pad = (4 - (raw.length() % 4)) % 4;
        return Base64.getDecoder().decode(raw + "====".substring(0, pad));
    }
}
