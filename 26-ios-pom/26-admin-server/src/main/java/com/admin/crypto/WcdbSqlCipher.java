package com.admin.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.sqlite.mc.SQLiteMCConfig;
import org.sqlite.mc.SQLiteMCSqlCipherConfig;

import com.admin.util.Bip39Util;

/**
 * 打开 WCDB/SQLCipher（对齐 global_wallet.py 的 libsqlcipher 路径）。
 * 使用 sqlite-jdbc-crypt，不依赖本机 libsqlcipher。
 */
public final class WcdbSqlCipher {

    private static final Pattern TABLE_RE = Pattern.compile("[A-Za-z0-9_]+");

    private WcdbSqlCipher() {
    }

    public static String tryPhrase(Path db, List<Object> materials, Bip39Util bip39) {
        if (db == null || materials == null || bip39 == null) {
            return null;
        }
        int tried = 0;
        for (Object material : materials) {
            if (tried >= 12) {
                break;
            }
            tried++;
            for (String key : keyVariants(material)) {
                for (SQLiteMCConfig.Builder cfg : profiles(key)) {
                    String dumped = openDump(db, cfg);
                    if (dumped == null) {
                        continue;
                    }
                    String phrase = bip39.searchPhrase(dumped);
                    if (phrase != null) {
                        return phrase;
                    }
                    if (bip39.validateMnemonic(dumped.trim())) {
                        return dumped.trim();
                    }
                }
            }
        }
        return null;
    }

    private static List<SQLiteMCConfig.Builder> profiles(String key) {
        List<SQLiteMCConfig.Builder> out = new ArrayList<>();
        out.add(SQLiteMCSqlCipherConfig.getV3Defaults()
                .setLegacyPageSize(4096)
                .setKdfIter(64000)
                .withKey(key));
        out.add(new SQLiteMCSqlCipherConfig().setLegacy(4).withKey(key));
        out.add(SQLiteMCSqlCipherConfig.getV3Defaults().withKey(key));
        out.add(SQLiteMCSqlCipherConfig.getV4Defaults().withKey(key));
        out.add(SQLiteMCSqlCipherConfig.getV3Defaults()
                .setLegacyPageSize(4096)
                .withKey(key));
        return out;
    }

    private static String openDump(Path db, SQLiteMCConfig.Builder cfg) {
        String uri = "jdbc:sqlite:file:" + db.toAbsolutePath().toString().replace('\\', '/');
        try (Connection con = cfg.build().createConnection(uri);
                Statement st = con.createStatement()) {
            try (ResultSet probe = st.executeQuery("SELECT count(*) FROM sqlite_master")) {
                if (!probe.next()) {
                    return null;
                }
            }
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name != null && TABLE_RE.matcher(name).matches()) {
                        tables.add(name);
                    }
                }
            }
            if (tables.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (String tbl : tables) {
                if (n++ >= 25) {
                    break;
                }
                try (ResultSet rs = st.executeQuery("SELECT * FROM \"" + tbl + "\" LIMIT 80")) {
                    int cols = rs.getMetaData().getColumnCount();
                    int rows = 0;
                    while (rs.next() && rows < 80) {
                        rows++;
                        for (int i = 1; i <= cols; i++) {
                            String v = rs.getString(i);
                            if (v != null && !v.isEmpty()) {
                                sb.append(v).append('\t');
                            }
                        }
                        sb.append('\n');
                    }
                } catch (Exception ignored) {
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> keyVariants(Object material) {
        List<String> keys = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (material instanceof String) {
            String s = ((String) material).trim();
            add(keys, seen, s);
            if (s.matches("(?i)[0-9a-f]{64}")) {
                add(keys, seen, "x'" + s.toLowerCase(Locale.ROOT) + "'");
            }
            return keys;
        }
        if (material instanceof byte[]) {
            byte[] raw = (byte[]) material;
            if (raw.length == 0) {
                return keys;
            }
            add(keys, seen, "x'" + KeychainXml.toHex(raw) + "'");
            if (raw.length >= 32) {
                byte[] chunk = new byte[32];
                System.arraycopy(raw, 0, chunk, 0, 32);
                add(keys, seen, "x'" + KeychainXml.toHex(chunk) + "'");
            }
            if (raw.length >= 64) {
                byte[] chunk = new byte[64];
                System.arraycopy(raw, 0, chunk, 0, 64);
                add(keys, seen, "x'" + KeychainXml.toHex(chunk) + "'");
            }
            String utf = new String(raw, StandardCharsets.UTF_8);
            if (utf.indexOf('\0') < 0 && utf.length() >= 8 && utf.length() <= 128) {
                add(keys, seen, utf);
            }
        }
        return keys;
    }

    private static void add(List<String> keys, Set<String> seen, String k) {
        if (k != null && !k.isEmpty() && seen.add(k)) {
            keys.add(k);
        }
    }
}
