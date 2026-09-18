package com.consumer.parse;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.lang3.StringUtils;

/**
 * 对齐 Python archive_utils：列出 / 读取 tar、tar.gz、zip 成员。
 */
public final class ArchiveIO {

    private ArchiveIO() {
    }

    public static List<String> listMembers(Path archive) {
        return listMembers(archive, null);
    }

    public static List<String> listMembers(Path archive, Collection<String> prefixes) {
        List<String> names = new ArrayList<>();
        walk(archive, prefixes, (name, in, size) -> names.add(name), 0);
        return names;
    }

    /**
     * 单次遍历归档。maxBytes&lt;=0 时不读内容（仅列出）。
     * visitor 收到的 InputStream 已定位到成员起点，读完后由 walk 继续下一项。
     */
    public static void walk(Path archive, Collection<String> prefixes,
            MemberVisitor visitor, int maxBytes) {
        if (archive == null || !Files.isRegularFile(archive) || visitor == null) {
            return;
        }
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".zip")) {
                walkZip(archive, prefixes, visitor, maxBytes);
            } else {
                walkTar(archive, prefixes, visitor, maxBytes);
            }
        } catch (Exception ignored) {
        }
    }

    public static byte[] readMember(Path archive, String member, int maxBytes) {
        if (StringUtils.isBlank(member)) {
            return null;
        }
        byte[][] holder = new byte[1][];
        walk(archive, null, (name, in, size) -> {
            if (holder[0] == null && memberEquals(name, member)) {
                holder[0] = readLimited(in, maxBytes);
            }
        }, maxBytes);
        return holder[0];
    }

    public static boolean memberEquals(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    public static boolean matchesPrefix(String member, Collection<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return true;
        }
        String n = normalize(member);
        for (String p : prefixes) {
            String prefix = normalize(p);
            if (prefix.isEmpty()) {
                return true;
            }
            if (n.equals(prefix) || n.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    public static String normalize(String member) {
        return StringUtils.defaultString(member).replace('\\', '/').replaceFirst("^\\./", "");
    }

    /**
     * 只拦截真正的 {@code ..} 路径穿越。客户端 tar 会把超长路径截成 {@code .../UTC--*}，
     * {@code startsWith("..")} 会把这种合法成员误丢。
     */
    public static boolean isUnsafeMember(String member) {
        String n = normalize(member);
        if (n.isEmpty()) {
            return true;
        }
        for (String part : n.split("/")) {
            if ("..".equals(part)) {
                return true;
            }
        }
        return false;
    }

    public static String baseName(String member) {
        String n = normalize(member);
        int i = n.lastIndexOf('/');
        return i < 0 ? n : n.substring(i + 1);
    }

    public static byte[] readLimited(InputStream in, int maxBytes) {
        if (in == null) {
            return new byte[0];
        }
        int cap = maxBytes > 0 ? maxBytes : 2_000_000;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(cap, 64 * 1024));
            byte[] buf = new byte[8192];
            int got = 0;
            int n;
            while (got < cap && (n = in.read(buf, 0, Math.min(buf.length, cap - got))) > 0) {
                bos.write(buf, 0, n);
                got += n;
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    public static void skipFully(InputStream in, long size) {
        if (in == null || size <= 0) {
            return;
        }
        try {
            long left = size;
            byte[] buf = new byte[8192];
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) {
                    break;
                }
                left -= n;
            }
        } catch (Exception ignored) {
        }
    }

    @FunctionalInterface
    public interface MemberVisitor {
        void visit(String name, InputStream in, long size) throws Exception;
    }

    private static void walkZip(Path archive, Collection<String> prefixes,
            MemberVisitor visitor, int maxBytes) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                String member = normalize(e.getName());
                if (member.isEmpty() || isUnsafeMember(member) || !matchesPrefix(member, prefixes)) {
                    continue;
                }
                visitor.visit(member, zis, e.getSize());
            }
        }
    }

    private static void walkTar(Path archive, Collection<String> prefixes,
            MemberVisitor visitor, int maxBytes) throws Exception {
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        InputStream in = new BufferedInputStream(Files.newInputStream(archive));
        if (name.endsWith(".gz") || name.endsWith(".tgz")) {
            in = new GZIPInputStream(in);
        }
        try (TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry e;
            while ((e = tar.getNextTarEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                String member = normalize(e.getName());
                if (member.isEmpty() || isUnsafeMember(member) || !matchesPrefix(member, prefixes)) {
                    continue;
                }
                visitor.visit(member, tar, e.getSize());
            }
        }
    }

    /** 便于一次性收集指定成员。 */
    public static void forEach(Path archive, Collection<String> prefixes, BiConsumer<String, byte[]> consumer,
            int maxBytes) {
        walk(archive, prefixes, (name, in, size) -> consumer.accept(name, readLimited(in, maxBytes)), maxBytes);
    }
}
