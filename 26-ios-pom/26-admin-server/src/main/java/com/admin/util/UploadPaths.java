package com.admin.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;

/**
 * 对齐 Python upload_paths：UPLOAD_DIR/{date}/{deviceId}/{fileName}。
 */
public final class UploadPaths {

    private static final Pattern UUID_RE = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern DATE_RE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private UploadPaths() {
    }

    public static Path resolveDiskPath(String uploadDir, String diskPath) {
        String raw = StringUtils.trimToEmpty(diskPath).replace('\\', '/');
        if (raw.isEmpty() || raw.contains("..")) {
            return null;
        }
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        if (looksAbsolute(raw)) {
            Path abs = Paths.get(raw).toAbsolutePath().normalize();
            if (abs.startsWith(root) && isNonEmptyFile(abs)) {
                return abs;
            }
        }
        if (raw.startsWith("/")) {
            return null;
        }
        String[] parts = raw.split("/");
        List<String> segs = new ArrayList<>();
        for (String p : parts) {
            if (p == null || p.isEmpty() || ".".equals(p)) {
                continue;
            }
            if ("..".equals(p) || p.contains("\\") || p.indexOf('\0') >= 0) {
                return null;
            }
            segs.add(p);
        }
        if (segs.size() < 3) {
            return null;
        }
        if (!DATE_RE.matcher(segs.get(0)).matches() || !UUID_RE.matcher(segs.get(1)).matches()) {
            return null;
        }
        Path out = root;
        for (String seg : segs) {
            out = out.resolve(seg);
        }
        out = out.normalize();
        if (!out.startsWith(root)) {
            return null;
        }
        return isNonEmptyFile(out) ? out : null;
    }

    /** 对齐 Python resolve_upload_zip：按文件名扫全部日期目录。 */
    public static Path findDeviceFile(String uploadDir, String deviceId, String fileName) {
        String did = StringUtils.trimToEmpty(deviceId).toLowerCase(Locale.ROOT);
        String fname = StringUtils.trimToEmpty(fileName);
        if (!UUID_RE.matcher(did).matches() || fname.isEmpty() || fname.contains("/") || fname.contains("\\")
                || fname.contains("..")) {
            return null;
        }
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> days = Files.list(root)) {
            List<Path> dateDirs = new ArrayList<>();
            days.filter(Files::isDirectory)
                    .filter(p -> DATE_RE.matcher(p.getFileName().toString()).matches())
                    .forEach(dateDirs::add);
            dateDirs.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
            for (Path day : dateDirs) {
                Path cand = day.resolve(did).resolve(fname).normalize();
                if (cand.startsWith(root) && isNonEmptyFile(cand)) {
                    return cand;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static Path findDeviceKeychain(String uploadDir, String deviceId) {
        String did = StringUtils.trimToEmpty(deviceId).toLowerCase(Locale.ROOT);
        if (!UUID_RE.matcher(did).matches()) {
            return null;
        }
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> days = Files.list(root)) {
            List<Path> dateDirs = new ArrayList<>();
            days.filter(Files::isDirectory)
                    .filter(p -> DATE_RE.matcher(p.getFileName().toString()).matches())
                    .forEach(dateDirs::add);
            dateDirs.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
            for (Path day : dateDirs) {
                Path kc = day.resolve(did).resolve("keychain.xml").normalize();
                if (kc.startsWith(root) && Files.isRegularFile(kc) && Files.size(kc) > 0) {
                    return kc;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 设备上报目录：优先较新的日期。 */
    public static Path findDeviceDir(String uploadDir, String deviceId) {
        String did = StringUtils.trimToEmpty(deviceId).toLowerCase(Locale.ROOT);
        if (!UUID_RE.matcher(did).matches()) {
            return null;
        }
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> days = Files.list(root)) {
            List<Path> dateDirs = new ArrayList<>();
            days.filter(Files::isDirectory)
                    .filter(p -> DATE_RE.matcher(p.getFileName().toString()).matches())
                    .forEach(dateDirs::add);
            dateDirs.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
            for (Path day : dateDirs) {
                Path folder = day.resolve(did).normalize();
                if (folder.startsWith(root) && Files.isDirectory(folder)) {
                    return folder;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static List<Path> listDeviceArchives(String uploadDir, String deviceId) {
        List<Path> out = new ArrayList<>();
        String did = StringUtils.trimToEmpty(deviceId).toLowerCase(Locale.ROOT);
        if (!UUID_RE.matcher(did).matches()) {
            return out;
        }
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> days = Files.list(root)) {
            List<Path> dateDirs = new ArrayList<>();
            days.filter(Files::isDirectory)
                    .filter(p -> DATE_RE.matcher(p.getFileName().toString()).matches())
                    .forEach(dateDirs::add);
            dateDirs.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
            for (Path day : dateDirs) {
                Path folder = day.resolve(did).normalize();
                if (!folder.startsWith(root) || !Files.isDirectory(folder)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(folder)) {
                    files.filter(UploadPaths::isNonEmptyFile)
                            .filter(p -> isArchiveName(p.getFileName().toString()))
                            .forEach(out::add);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 最新一份 send-server 落盘的 devices_*.json（含 appId）。 */
    public static Path findLatestDeviceDump(String uploadDir, String deviceId) {
        String did = StringUtils.trimToEmpty(deviceId).toLowerCase(Locale.ROOT);
        if (!UUID_RE.matcher(did).matches()) {
            return null;
        }
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return null;
        }
        Path best = null;
        long bestMtime = Long.MIN_VALUE;
        try (Stream<Path> days = Files.list(root)) {
            List<Path> dateDirs = new ArrayList<>();
            days.filter(Files::isDirectory)
                    .filter(p -> DATE_RE.matcher(p.getFileName().toString()).matches())
                    .forEach(dateDirs::add);
            dateDirs.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
            for (Path day : dateDirs) {
                Path folder = day.resolve(did).normalize();
                if (!folder.startsWith(root) || !Files.isDirectory(folder)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(folder)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!name.startsWith("devices_") || !name.endsWith(".json") || !Files.isRegularFile(f)) {
                            continue;
                        }
                        long mtime = Files.getLastModifiedTime(f).toMillis();
                        if (best == null || mtime > bestMtime) {
                            best = f;
                            bestMtime = mtime;
                        }
                    }
                }
                if (best != null) {
                    return best;
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    public static boolean isArchiveName(String fileName) {
        String lower = StringUtils.defaultString(fileName).toLowerCase(Locale.ROOT);
        return lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".zip");
    }

    private static boolean looksAbsolute(String raw) {
        if (raw.startsWith("/") || raw.startsWith("\\\\")) {
            return true;
        }
        return raw.length() >= 3 && Character.isLetter(raw.charAt(0)) && raw.charAt(1) == ':' && raw.charAt(2) == '/';
    }

    private static boolean isNonEmptyFile(Path path) {
        try {
            return path != null && Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
