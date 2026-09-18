package com.consumer.parse;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.consumer.util.NoteStoreBodyExtractor;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class NotesParse {

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
            if (!extractNoteStore(archive, dir, prefixes)) {
                return empty;
            }
            Path db = dir.resolve("NoteStore.sqlite");
            if (!Files.isRegularFile(db)) {
                db = findNoteStore(dir);
            }
            if (db == null) {
                return empty;
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
            log.warn("notes parse fail path={} err={}", archive, e.toString());
            return empty;
        } finally {
            deleteDir(dir);
        }
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
