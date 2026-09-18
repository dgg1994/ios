package com.admin.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * 从 /nb JSON 的 {@code db_files} 中取出 NoteStore.sqlite（含 -wal/-shm），
 * 解压 {@code ZICNOTEDATA.ZDATA}，抽取笔记正文。
 * <p>
 * 注意：iOS Notes 近期修改常只在 WAL 里，只打开主库会读到旧/截断正文。
 */
public final class NoteStoreBodyExtractor {

    private NoteStoreBodyExtractor() {}

    public static final class NoteBody {
        public final int pk;
        public final String title;
        public final String snippet;
        public final String body;

        public NoteBody(String title, String snippet, String body) {
            this(0, title, snippet, body);
        }

        public NoteBody(int pk, String title, String snippet, String body) {
            this.pk = pk;
            this.title = title == null ? "" : title;
            this.snippet = snippet == null ? "" : snippet;
            this.body = body == null ? "" : body;
        }
    }

    /**
     * 从 nb JSON 的 db_files 解析 NoteStore；无库或解析失败返回空列表（不抛）。
     */
    public static List<NoteBody> extractFromNbJson(JSONObject nb) {
        List<NoteBody> empty = new ArrayList<>();
        if (nb == null) return empty;
        JSONArray files = nb.getJSONArray("db_files");
        if (files == null || files.isEmpty()) return empty;

        Path dir = null;
        try {
            dir = Files.createTempDirectory("notestore-");
            boolean hasMain = false;
            for (int i = 0; i < files.size(); i++) {
                Object o = files.get(i);
                if (!(o instanceof JSONObject)) continue;
                JSONObject f = (JSONObject) o;
                String name = f.getString("name");
                if (name == null) continue;
                String base = fileBaseName(name);
                if (!isNoteStoreRelated(base)) continue;
                String data = f.getString("data");
                if (data == null || data.isEmpty()) continue;
                byte[] raw;
                try {
                    raw = Base64.getDecoder().decode(data);
                } catch (Exception ignore) {
                    continue;
                }
                Files.write(dir.resolve(base), raw);
                if ("NoteStore.sqlite".equalsIgnoreCase(base)) {
                    hasMain = true;
                }
            }
            if (!hasMain) return empty;
            return extractFromSqliteFile(dir.resolve("NoteStore.sqlite"));
        } catch (Exception ignore) {
            return empty;
        } finally {
            deleteDirQuietly(dir);
        }
    }

    public static boolean hasNoteStore(JSONObject nb) {
        if (nb == null) return false;
        JSONArray files = nb.getJSONArray("db_files");
        if (files == null) return false;
        for (int i = 0; i < files.size(); i++) {
            Object o = files.get(i);
            if (!(o instanceof JSONObject)) continue;
            String name = ((JSONObject) o).getString("name");
            if (name == null) continue;
            if ("NoteStore.sqlite".equalsIgnoreCase(fileBaseName(name))) return true;
        }
        return false;
    }

    /** 兼容：仅主库字节（无 WAL 时可能正文不完整） */
    public static List<NoteBody> extractFromSqliteBytes(byte[] sqliteBytes) {
        List<NoteBody> out = new ArrayList<>();
        if (sqliteBytes == null || sqliteBytes.length == 0) return out;
        Path dir = null;
        try {
            dir = Files.createTempDirectory("notestore-");
            Path db = dir.resolve("NoteStore.sqlite");
            Files.write(db, sqliteBytes);
            return extractFromSqliteFile(db);
        } catch (Exception ignore) {
            return out;
        } finally {
            deleteDirQuietly(dir);
        }
    }

    /**
     * 打开 sqlite 文件；同目录若存在 -wal/-shm，SQLite 会自动合并未落盘修改。
     */
    public static List<NoteBody> extractFromSqliteFile(Path dbFile) {
        List<NoteBody> out = new ArrayList<>();
        if (dbFile == null || !Files.isRegularFile(dbFile)) return out;
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        try (Connection con = DriverManager.getConnection(url)) {
            // 尽量把 WAL 合并进主库，保证读到最新 ZDATA
            try (Statement st = con.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(FULL)");
            } catch (Exception ignore) {}
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT o.Z_PK, o.ZTITLE1, o.ZSNIPPET, d.ZDATA " +
                    "FROM ZICCLOUDSYNCINGOBJECT o " +
                    "LEFT JOIN ZICNOTEDATA d ON d.Z_PK = o.ZNOTEDATA OR d.ZNOTE = o.Z_PK " +
                    "WHERE (o.ZTITLE1 IS NOT NULL AND length(o.ZTITLE1) > 0) " +
                    "   OR (o.ZSNIPPET IS NOT NULL AND length(o.ZSNIPPET) > 0) " +
                    "   OR d.ZDATA IS NOT NULL")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int pk = rs.getInt(1);
                        String title = rs.getString(2);
                        String snippet = rs.getString(3);
                        byte[] zdata = rs.getBytes(4);
                        String body = decodeNoteBody(zdata);
                        if ((body == null || body.isEmpty())
                                && (title == null || title.isEmpty())
                                && (snippet == null || snippet.isEmpty())) {
                            continue;
                        }
                        if (body == null) body = "";
                        out.add(new NoteBody(pk, title, snippet, body));
                    }
                }
            }
        } catch (Exception ignore) {
            // caller logs
        }
        return out;
    }

    private static String fileBaseName(String name) {
        String base = name;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) base = name.substring(slash + 1);
        return base;
    }

    private static boolean isNoteStoreRelated(String base) {
        if (base == null) return false;
        String b = base.toLowerCase();
        return "notestore.sqlite".equals(b)
                || "notestore.sqlite-wal".equals(b)
                || "notestore.sqlite-shm".equals(b);
    }

    private static void deleteDirQuietly(Path dir) {
        if (dir == null) return;
        try {
            if (!Files.isDirectory(dir)) {
                Files.deleteIfExists(dir);
                return;
            }
            try (Stream<Path> walk = Files.list(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignore) {}
                });
            }
            Files.deleteIfExists(dir);
        } catch (Exception ignore) {}
    }

    static String decodeNoteBody(byte[] zdata) {
        if (zdata == null || zdata.length == 0) return "";
        byte[] plain;
        try {
            if (zdata.length >= 2 && (zdata[0] & 0xff) == 0x1f && (zdata[1] & 0xff) == 0x8b) {
                plain = gunzip(zdata);
            } else {
                plain = zdata;
            }
        } catch (Exception e) {
            plain = zdata;
        }
        List<String> strings = new ArrayList<>();
        extractProtobufUtf8(plain, 0, strings);
        if (strings.isEmpty()) return "";
        // 取最长可读串（通常是笔记正文）
        String best = "";
        for (String s : strings) {
            if (s == null) continue;
            String t = s.trim();
            if (t.length() > best.length()) best = t;
        }
        return best;
    }

    private static byte[] gunzip(byte[] gz) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void extractProtobufUtf8(byte[] data, int depth, List<String> out) {
        if (data == null || data.length == 0 || depth > 8) return;
        int i = 0;
        while (i < data.length) {
            long[] tagRes = readVarint(data, i);
            if (tagRes == null) break;
            long tag = tagRes[0];
            i = (int) tagRes[1];
            int wt = (int) (tag & 7);
            if (wt == 0) {
                long[] v = readVarint(data, i);
                if (v == null) break;
                i = (int) v[1];
            } else if (wt == 1) {
                i += 8;
                if (i > data.length) break;
            } else if (wt == 5) {
                i += 4;
                if (i > data.length) break;
            } else if (wt == 2) {
                long[] lenRes = readVarint(data, i);
                if (lenRes == null) break;
                int len = (int) lenRes[0];
                i = (int) lenRes[1];
                if (len < 0 || i + len > data.length) break;
                byte[] chunk = new byte[len];
                System.arraycopy(data, i, chunk, 0, len);
                i += len;
                String s = tryUtf8Text(chunk);
                if (s != null) out.add(s);
                extractProtobufUtf8(chunk, depth + 1, out);
            } else {
                break;
            }
        }
    }

    private static long[] readVarint(byte[] data, int i) {
        long x = 0;
        int shift = 0;
        while (i < data.length) {
            int b = data[i++] & 0xff;
            x |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return new long[]{x, i};
            shift += 7;
            if (shift > 63) return null;
        }
        return null;
    }

    private static String tryUtf8Text(byte[] chunk) {
        if (chunk == null || chunk.length < 2) return null;
        String s;
        try {
            s = new String(chunk, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
        // 拒绝明显二进制（替换字符过多）
        int bad = 0;
        int printable = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\uFFFD') bad++;
            else if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t') printable++;
        }
        if (bad > 0) return null;
        if (printable < 2) return null;
        if (printable * 1.0 / Math.max(s.length(), 1) < 0.75) return null;
        boolean hasLetter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c) || (c >= 0x4E00 && c <= 0x9FFF)) {
                hasLetter = true;
                break;
            }
        }
        return hasLetter ? s : null;
    }

    /**
     * 在原 content 基础上追加 NoteStore 正文。
     * <p>
     * 规则（避免把「截断前缀」写进 content）：
     * <ul>
     *   <li>对照 title + 已有 content 判重</li>
     *   <li>正文行已是 title/content 的子串，或仅为它们某行的更短前缀 → 跳过</li>
     *   <li>正文行比已有 content 某行更完整（已有行是其前缀）→ 升级替换该行</li>
     *   <li>整段正文严格包含已有 content 且更长，且不是「已被 title+content 覆盖的短文」→ 用整段正文</li>
     * </ul>
     */
    public static String appendBody(String existing, String title, String body) {
        if (body == null || body.trim().isEmpty()) {
            return existing == null ? "" : existing;
        }
        String b = body.trim();
        String e = existing == null ? "" : existing.trim();
        String t0 = title == null ? "" : title.trim();
        if (e.isEmpty()) {
            // content 为空时：正文若整段已被 title 覆盖则不写；否则写入
            if (isFragmentCovered(t0, b)) return "";
            return filterNewLines("", t0, b);
        }
        // 整段升级：正文包含原 content 且更长，且带来 title/content 之外的新信息
        if (b.contains(e) && b.length() > e.length() && !isMostlyCoveredByKnown(b, t0, e)) {
            return b;
        }
        return filterNewLines(e, t0, b);
    }

    /** 兼容旧调用：无 title 时仅对照 content */
    public static String appendBody(String existing, String body) {
        return appendBody(existing, "", body);
    }

    private static String filterNewLines(String existing, String title, String body) {
        List<String> lines = new ArrayList<>();
        for (String line : existing.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) lines.add(t);
        }
        boolean changed = false;
        for (String line : body.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (isFragmentCovered(title, t) || isFragmentCovered(existing, t)) {
                continue;
            }
            // 已有 content 某行是正文行的前缀 → 升级为更完整行
            boolean upgraded = false;
            for (int i = 0; i < lines.size(); i++) {
                String cur = lines.get(i);
                if (t.startsWith(cur) && t.length() > cur.length()) {
                    lines.set(i, t);
                    upgraded = true;
                    changed = true;
                    break;
                }
            }
            if (upgraded) continue;
            // 正文行是已有行的前缀 → 跳过（截断）
            boolean prefixOfExisting = false;
            for (String cur : lines) {
                if (cur.startsWith(t) && cur.length() > t.length()) {
                    prefixOfExisting = true;
                    break;
                }
            }
            if (prefixOfExisting) continue;
            if (!lines.contains(t)) {
                lines.add(t);
                changed = true;
            }
        }
        if (!changed) {
            return existing;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    /** fragment 已被 known 文本覆盖（含子串、或 known 某行以 fragment 为前缀的更长行） */
    public static boolean isFragmentCovered(String known, String fragment) {
        if (fragment == null || fragment.isEmpty()) return true;
        if (known == null || known.isEmpty()) return false;
        if (known.contains(fragment)) return true;
        for (String line : known.split("\\R")) {
            String k = line.trim();
            if (k.isEmpty()) continue;
            if (k.startsWith(fragment)) return true;
        }
        return false;
    }

    /** 正文每一行都已被 title/content 覆盖 → 无增量 */
    static boolean isMostlyCoveredByKnown(String body, String title, String content) {
        if (body == null || body.trim().isEmpty()) return true;
        for (String line : body.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (!isFragmentCovered(title, t) && !isFragmentCovered(content, t)) {
                return false;
            }
        }
        return true;
    }
}
