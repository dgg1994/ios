package com.admin.parse;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.admin.util.NoteStoreBodyExtractor;

import lombok.extern.slf4j.Slf4j;

/**
 * 解析 notes 压缩包：正文 + 附件照片（对齐 Python notes_parse）。
 */
@Slf4j
@Component
public class NotesParse {

    private static final Set<String> IMAGE_EXTS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".tif", ".tiff", ".bmp");
    private static final int SANDBOX_MEMBER_MAX = 256 * 1024;
    private static final Pattern TRAILING_COPY = Pattern.compile("(-\\d+)+$");

    public List<ParsedNote> parseArchive(Path archive) {
        return parseArchive(archive, null);
    }

    public List<ParsedNote> parseArchive(Path archive, Collection<String> prefixes) {
        List<ParsedNote> empty = new ArrayList<>();
        if (archive == null || !Files.isRegularFile(archive)) {
            return empty;
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("notes_parse_");
            List<MediaMember> media = new ArrayList<>();
            if (!extractNoteStoreAndIndexMedia(archive, dir, prefixes, media)) {
                deleteDir(dir);
                dir = null;
                return parseSandboxNotes(archive, prefixes);
            }
            Path db = dir.resolve("NoteStore.sqlite");
            if (!Files.isRegularFile(db)) {
                db = findNoteStore(dir);
            }
            if (db == null) {
                deleteDir(dir);
                dir = null;
                return parseSandboxNotes(archive, prefixes);
            }
            List<NoteStoreBodyExtractor.NoteBody> bodies = NoteStoreBodyExtractor.extractFromSqliteFile(db);
            Map<Integer, List<ParsedNote.NotePhoto>> photosByNote = photosByNotePk(db, media);
            List<ParsedNote> notes = new ArrayList<>();
            int i = 0;
            for (NoteStoreBodyExtractor.NoteBody b : bodies) {
                ParsedNote n = new ParsedNote();
                n.setIdentifier(b.pk > 0 ? String.valueOf(b.pk) : String.valueOf(i++));
                n.setTitle(b.title);
                n.setSnippet(b.snippet);
                n.setBody(b.body);
                if (b.pk > 0 && photosByNote.containsKey(b.pk)) {
                    n.setPhotos(photosByNote.get(b.pk));
                }
                notes.add(n);
            }
            return notes;
        } catch (Exception e) {
            log.warn("notes parse fail path={} err={}", archive, e.toString());
            return empty;
        } finally {
            deleteDir(dir);
        }
    }

    private boolean extractNoteStoreAndIndexMedia(Path archive, Path dest, Collection<String> prefixes,
            List<MediaMember> mediaOut) throws Exception {
        boolean[] any = {false};
        ArchiveIO.walk(archive, prefixes, (member, in, size) -> {
            String norm = member.replace('\\', '/').replaceFirst("^/+", "");
            MediaMember mm = classifyMedia(norm, size);
            if (mm != null) {
                mediaOut.add(mm);
            }
            String base = Path.of(member.replace('\\', '/')).getFileName().toString().toLowerCase(Locale.ROOT);
            boolean noteStore = base.equals("notestore.sqlite")
                    || base.equals("notestore.sqlite-wal")
                    || base.equals("notestore.sqlite-shm");
            if (noteStore) {
                if (writeNoteStoreMember(dest, member, in)) {
                    any[0] = true;
                }
            } else {
                ArchiveIO.skipFully(in, size);
            }
        }, 64 * 1024 * 1024);
        return any[0];
    }

    private MediaMember classifyMedia(String norm, long size) {
        if (norm == null || norm.isBlank()) {
            return null;
        }
        // 兼容 Accounts/... 与 group.com.apple.notes/Accounts/...
        String accounts = notesAccountsPath(norm);
        if (accounts == null) {
            return null;
        }
        String low = accounts.toLowerCase(Locale.ROOT);
        String kind;
        if (low.contains("/media/")) {
            kind = "media";
        } else if (low.contains("/fallbackimages/")) {
            kind = "fallback";
        } else if (low.contains("/previews/")) {
            kind = "preview";
        } else {
            return null;
        }
        if (low.contains("/thumbnails/default/")) {
            return null;
        }
        String ext = "";
        int dot = accounts.lastIndexOf('.');
        if (dot >= 0) {
            ext = accounts.substring(dot).toLowerCase(Locale.ROOT);
        }
        if (!"media".equals(kind) && !IMAGE_EXTS.contains(ext)) {
            return null;
        }
        if ("media".equals(kind) && !ext.isEmpty() && !IMAGE_EXTS.contains(ext)) {
            return null;
        }
        return new MediaMember(accounts, size, kind);
    }

    private static String notesAccountsPath(String member) {
        String raw = member.replace('\\', '/').replaceFirst("^/+", "");
        int idx = raw.toLowerCase(Locale.ROOT).indexOf("accounts/");
        if (idx < 0) {
            return null;
        }
        return raw.substring(idx);
    }

    private boolean writeNoteStoreMember(Path dest, String member, InputStream in) throws Exception {
        String base = Path.of(member.replace('\\', '/')).getFileName().toString();
        String low = base.toLowerCase(Locale.ROOT);
        if (!low.equals("notestore.sqlite") && !low.equals("notestore.sqlite-wal") && !low.equals("notestore.sqlite-shm")) {
            return false;
        }
        Path out = dest.resolve(base);
        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        return low.equals("notestore.sqlite");
    }

    private Path findNoteStore(Path dir) throws Exception {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.getFileName().toString().equalsIgnoreCase("NoteStore.sqlite"))
                    .findFirst().orElse(null);
        }
    }

    private Map<Integer, List<ParsedNote.NotePhoto>> photosByNotePk(Path db, List<MediaMember> media) {
        Map<Integer, List<ParsedNote.NotePhoto>> out = new HashMap<>();
        if (media == null || media.isEmpty()) {
            return out;
        }
        String url = "jdbc:sqlite:" + db.toAbsolutePath();
        try (Connection con = DriverManager.getConnection(url)) {
            Integer attEnt = entityId(con, "ICAttachment");
            Integer medEnt = entityId(con, "ICMedia");
            if (attEnt == null) {
                return out;
            }
            Set<String> cols = tableCols(con, "ZICCLOUDSYNCINGOBJECT");
            Map<Integer, MediaRow> medias = new HashMap<>();
            if (medEnt != null) {
                StringBuilder sql = new StringBuilder("SELECT Z_PK, ZIDENTIFIER, ZFILENAME");
                if (cols.contains("ZGENERATION1")) {
                    sql.append(", ZGENERATION1");
                }
                sql.append(" FROM ZICCLOUDSYNCINGOBJECT WHERE Z_ENT = ?");
                try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
                    ps.setInt(1, medEnt);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            MediaRow mr = new MediaRow();
                            mr.pk = rs.getInt("Z_PK");
                            mr.identifier = nz(rs.getString("ZIDENTIFIER"));
                            mr.filename = nz(rs.getString("ZFILENAME"));
                            medias.put(mr.pk, mr);
                        }
                    }
                }
            }
            StringBuilder asql = new StringBuilder(
                    "SELECT Z_PK, ZIDENTIFIER, ZNOTE, ZTYPEUTI, ZFILESIZE, ZTITLE");
            if (cols.contains("ZMEDIA")) {
                asql.append(", ZMEDIA");
            }
            asql.append(" FROM ZICCLOUDSYNCINGOBJECT WHERE Z_ENT = ?");
            try (PreparedStatement ps = con.prepareStatement(asql.toString())) {
                ps.setInt(1, attEnt);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int notePk = rs.getInt("ZNOTE");
                        if (rs.wasNull()) {
                            continue;
                        }
                        String attId = nz(rs.getString("ZIDENTIFIER"));
                        String uti = nz(rs.getString("ZTYPEUTI"));
                        long declared = rs.getLong("ZFILESIZE");
                        String mediaId = "";
                        String filename = "";
                        if (cols.contains("ZMEDIA")) {
                            int mid = rs.getInt("ZMEDIA");
                            if (!rs.wasNull()) {
                                MediaRow mr = medias.get(mid);
                                if (mr != null) {
                                    mediaId = mr.identifier;
                                    filename = mr.filename;
                                }
                            }
                        }
                        ParsedNote.NotePhoto photo = resolvePhoto(media, attId, mediaId, filename, uti, declared);
                        if (photo != null) {
                            out.computeIfAbsent(notePk, k -> new ArrayList<>()).add(photo);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("notes photos query fail: {}", e.toString());
        }
        return out;
    }

    private ParsedNote.NotePhoto resolvePhoto(List<MediaMember> media, String attachmentId,
            String mediaId, String filename, String uti, long declaredSize) {
        List<MediaMember> candidates = new ArrayList<>();
        String mid = mediaId == null ? "" : mediaId.trim();
        String aid = attachmentId == null ? "" : attachmentId.trim();
        for (MediaMember m : media) {
            String low = m.path.toLowerCase(Locale.ROOT);
            if (!mid.isEmpty() && low.contains("/media/" + mid.toLowerCase(Locale.ROOT) + "/")) {
                candidates.add(m);
            } else if (!aid.isEmpty() && "fallback".equals(m.kind) && low.contains(aid.toLowerCase(Locale.ROOT))) {
                candidates.add(m);
            } else if (!aid.isEmpty() && "preview".equals(m.kind) && low.contains(aid.toLowerCase(Locale.ROOT))) {
                candidates.add(m);
            }
        }
        if (candidates.isEmpty() && !mid.isEmpty()) {
            for (MediaMember m : media) {
                if (m.path.toLowerCase(Locale.ROOT).contains(mid.toLowerCase(Locale.ROOT))) {
                    candidates.add(m);
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort((a, b) -> {
            int ka = kindRank(a.kind);
            int kb = kindRank(b.kind);
            if (ka != kb) {
                return Integer.compare(ka, kb);
            }
            return Long.compare(b.size, a.size);
        });
        MediaMember best = candidates.get(0);
        String name = (filename == null || filename.isBlank()) ? Path.of(best.path).getFileName().toString() : filename.trim();
        ParsedNote.NotePhoto photo = new ParsedNote.NotePhoto();
        photo.setMember(best.path);
        photo.setFilename(name);
        photo.setMime(mimeFrom(uti, name));
        photo.setSize(best.size > 0 ? best.size : declaredSize);
        photo.setKind(best.kind);
        return photo;
    }

    private static int kindRank(String kind) {
        if ("media".equals(kind)) {
            return 0;
        }
        if ("fallback".equals(kind)) {
            return 1;
        }
        if ("preview".equals(kind)) {
            return 2;
        }
        return 9;
    }

    private static String mimeFrom(String uti, String filename) {
        String u = uti == null ? "" : uti.trim().toLowerCase(Locale.ROOT);
        if (u.contains("jpeg") || u.contains("jpg")) {
            return "image/jpeg";
        }
        if (u.contains("png")) {
            return "image/png";
        }
        if (u.contains("gif")) {
            return "image/gif";
        }
        if (u.contains("heic")) {
            return "image/heic";
        }
        if (u.contains("webp")) {
            return "image/webp";
        }
        String ext = "";
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        if (dot >= 0) {
            ext = filename.substring(dot).toLowerCase(Locale.ROOT);
        }
        if (".jpg".equals(ext) || ".jpeg".equals(ext)) {
            return "image/jpeg";
        }
        if (".png".equals(ext)) {
            return "image/png";
        }
        if (".gif".equals(ext)) {
            return "image/gif";
        }
        if (".webp".equals(ext)) {
            return "image/webp";
        }
        if (".heic".equals(ext) || ".heif".equals(ext)) {
            return "image/heic";
        }
        return "application/octet-stream";
    }

    private static Integer entityId(Connection con, String name) {
        try (Statement st = con.createStatement();
                ResultSet tables = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            Set<String> names = new HashSet<>();
            while (tables.next()) {
                names.add(tables.getString(1));
            }
            if (!names.contains("Z_PRIMARYKEY")) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT Z_ENT FROM Z_PRIMARYKEY WHERE Z_NAME = ? LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static Set<String> tableCols(Connection con, String table) {
        Set<String> cols = new HashSet<>();
        try (Statement st = con.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
        } catch (Exception ignored) {
        }
        return cols;
    }

    private List<ParsedNote> parseSandboxNotes(Path archive, Collection<String> prefixes) {
        Map<String, ParsedNote> byKey = new LinkedHashMap<>();
        try {
            ArchiveIO.walk(archive, prefixes, (member, in, size) -> {
                String norm = member.replace('\\', '/');
                String base = Path.of(norm).getFileName().toString();
                String low = base.toLowerCase(Locale.ROOT);
                boolean airdrop = low.endsWith(".notesairdropdocument") || low.contains("notesairdrop");
                String pathLow = norm.toLowerCase(Locale.ROOT);
                boolean inboxText = pathLow.contains("/documents/inbox/") || pathLow.contains("documents/inbox/");
                if (!airdrop && !inboxText) {
                    ArchiveIO.skipFully(in, size);
                    return;
                }
                String fromName = cleanSandboxTitle(base);
                byte[] raw = ArchiveIO.readLimited(in, SANDBOX_MEMBER_MAX);
                String fromBytes = extractPrintable(raw);
                String body = pickLonger(fromName, fromBytes);
                if (body == null || body.isBlank() || body.length() < 8) {
                    return;
                }
                String key = sha1(body.toLowerCase(Locale.ROOT));
                if (byKey.containsKey(key)) {
                    return;
                }
                ParsedNote n = new ParsedNote();
                n.setIdentifier("sandbox:" + key.substring(0, Math.min(12, key.length())));
                n.setTitle(fromName.length() > 120 ? fromName.substring(0, 120) : fromName);
                n.setBody(body);
                n.setSnippet(body.length() > 160 ? body.substring(0, 160) : body);
                n.setFolder("MobileNotes");
                byKey.put(key, n);
            }, SANDBOX_MEMBER_MAX);
        } catch (Exception e) {
            log.warn("notes sandbox parse fail path={} err={}", archive, e.toString());
        }
        return new ArrayList<>(byKey.values());
    }

    private static String cleanSandboxTitle(String base) {
        String name = base == null ? "" : base.trim();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return TRAILING_COPY.matcher(name).replaceAll("").trim();
    }

    private static String pickLonger(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        if (x.isEmpty()) {
            return y;
        }
        if (y.isEmpty()) {
            return x;
        }
        if (wordish(x) && wordish(y)) {
            return x;
        }
        if (wordish(x) && !wordish(y)) {
            return x;
        }
        if (wordish(y) && !wordish(x)) {
            return y;
        }
        return x.length() >= y.length() ? x : y;
    }

    private static boolean wordish(String s) {
        String[] parts = s.trim().split("\\s+");
        return parts.length >= 8 && parts.length <= 30;
    }

    private static String extractPrintable(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        StringBuilder best = new StringBuilder();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < raw.length; ) {
            int c = raw[i] & 0xFF;
            if (c >= 0x20 && c < 0x7F) {
                cur.append((char) c);
                i++;
                continue;
            }
            int len = utf8Len(raw, i);
            if (len > 1) {
                try {
                    CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT);
                    String ch = dec.decode(ByteBuffer.wrap(raw, i, len)).toString();
                    if (!ch.isEmpty() && !Character.isISOControl(ch.charAt(0))) {
                        cur.append(ch);
                        i += len;
                        continue;
                    }
                } catch (Exception ignored) {
                }
            }
            if (cur.length() >= 8 && cur.length() > best.length()) {
                best = new StringBuilder(cur);
            }
            cur.setLength(0);
            i++;
        }
        if (cur.length() > best.length()) {
            best = cur;
        }
        return best.toString().trim().replaceAll("\\s+", " ");
    }

    private static int utf8Len(byte[] raw, int i) {
        int c = raw[i] & 0xFF;
        if ((c & 0x80) == 0) {
            return 1;
        }
        if ((c & 0xE0) == 0xC0 && i + 1 < raw.length) {
            return 2;
        }
        if ((c & 0xF0) == 0xE0 && i + 2 < raw.length) {
            return 3;
        }
        if ((c & 0xF8) == 0xF0 && i + 3 < raw.length) {
            return 4;
        }
        return 1;
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static void deleteDir(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static final class MediaMember {
        final String path;
        final long size;
        final String kind;

        MediaMember(String path, long size, String kind) {
            this.path = path;
            this.size = size;
            this.kind = kind;
        }
    }

    private static final class MediaRow {
        int pk;
        String identifier = "";
        String filename = "";
    }
}
