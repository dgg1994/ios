package com.consumer.parse;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.consumer.util.NoteStoreBodyExtractor;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class NotesParse {

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
        List<ParsedNote> fromStore = parseNoteStore(archive, prefixes);
        if (!fromStore.isEmpty()) {
            return fromStore;
        }
        // com.apple.mobilenotes 等沙盒包通常无 NoteStore，扫 AirDrop Inbox / 明文片段
        return parseSandboxNotes(archive, prefixes);
    }

    private List<ParsedNote> parseNoteStore(Path archive, Collection<String> prefixes) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("notes_parse_");
            if (!extractNoteStore(archive, dir, prefixes)) {
                return List.of();
            }
            Path db = dir.resolve("NoteStore.sqlite");
            if (!Files.isRegularFile(db)) {
                db = findNoteStore(dir);
            }
            if (db == null) {
                return List.of();
            }
            List<NoteStoreBodyExtractor.NoteBody> bodies = NoteStoreBodyExtractor.extractFromSqliteFile(db);
            List<ParsedNote> notes = new ArrayList<>();
            int i = 0;
            for (NoteStoreBodyExtractor.NoteBody b : bodies) {
                ParsedNote n = new ParsedNote();
                n.setIdentifier(String.valueOf(i++));
                n.setTitle(b.title);
                n.setSnippet(b.snippet);
                n.setBody(b.body);
                notes.add(n);
            }
            return notes;
        } catch (Exception e) {
            log.warn("notes parse NoteStore fail path={} err={}", archive, e.toString());
            return List.of();
        } finally {
            deleteDir(dir);
        }
    }

    private List<ParsedNote> parseSandboxNotes(Path archive, Collection<String> prefixes) {
        Map<String, ParsedNote> byKey = new LinkedHashMap<>();
        try {
            ArchiveIO.walk(archive, prefixes, (member, in, size) -> {
                String norm = member.replace('\\', '/');
                String base = Path.of(norm).getFileName().toString();
                String low = base.toLowerCase(Locale.ROOT);
                boolean airdrop = low.endsWith(".notesairdropdocument") || low.contains("notesairdrop");
                boolean inboxText = norm.toLowerCase(Locale.ROOT).contains("/documents/inbox/")
                        || norm.toLowerCase(Locale.ROOT).contains("documents/inbox/");
                if (!airdrop && !inboxText) {
                    return;
                }
                String fromName = cleanTitle(base);
                byte[] raw = readLimited(in, SANDBOX_MEMBER_MAX);
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
                n.setIdentifier("sandbox:" + key.substring(0, 12));
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

    private boolean extractNoteStore(Path archive, Path dest, Collection<String> prefixes) throws Exception {
        boolean[] any = {false};
        ArchiveIO.walk(archive, prefixes, (member, in, size) -> {
            if (writeNoteStoreMember(dest, member, in)) {
                any[0] = true;
            }
        }, 64 * 1024 * 1024);
        return any[0];
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

    private static String cleanTitle(String base) {
        String name = base == null ? "" : base.trim();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = TRAILING_COPY.matcher(name).replaceAll("").trim();
        return name;
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
        // 文件名往往就是助记词全文；正文可能夹杂二进制噪声，优先更像词表的长空白分隔串
        if (wordish(x) && wordish(y)) {
            // AirDrop：文件名通常就是干净明文，优先于二进制抽串
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

    private static byte[] readLimited(InputStream in, int max) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n == 0) {
                continue;
            }
            int take = Math.min(n, Math.max(0, max - total));
            if (take > 0) {
                bos.write(buf, 0, take);
                total += take;
            }
            if (total >= max) {
                break;
            }
        }
        return bos.toByteArray();
    }

    /** 从二进制中抽出可打印 UTF-8 片段（AirDrop protobuf 内嵌明文）。 */
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
            // 尝试 UTF-8 多字节
            int len = utf8Len(raw, i);
            if (len > 1) {
                try {
                    CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT);
                    String ch = dec.decode(java.nio.ByteBuffer.wrap(raw, i, len)).toString();
                    if (!ch.isEmpty() && !Character.isISOControl(ch.charAt(0))) {
                        cur.append(ch);
                        i += len;
                        continue;
                    }
                } catch (Exception ignored) {
                }
            }
            if (cur.length() >= 8) {
                if (cur.length() > best.length()) {
                    best = new StringBuilder(cur);
                }
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
}
