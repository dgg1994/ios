# orc-server

独立 OCR 服务：图片识别 + BIP39 助记词判定。  
可被 consume 及其它业务通过 HTTP / Nacos 调用。

## 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/ocr/health` | `{ ok, ocrReady }` |
| POST | `/ocr/mnemonic-check` | raw body = 图片字节 |
| POST | `/ocr/mnemonic-check` | `multipart/form-data`，字段名 `file` |

响应示例：

```json
{
  "pass": true,
  "reason": "window_12_hits_12",
  "text": "...",
  "bip39Hits": 12,
  "ocrLen": 80,
  "width": 800,
  "height": 600,
  "costMs": 120,
  "ocrReady": true
}
```

`pass=true` 表示像助记词截图（规则与 consume 内 `MnemonicImageAnalyzer` 对齐）。

## 运行

1. 安装 Tesseract，并配置 `ocr.tessdata-path`（或依赖自动探测）。
2. 构建：

```bash
cd orc-server
mvn -DskipTests package
java -jar target/orc-server-0.0.1-SNAPSHOT.jar
```

默认端口 **8210**。服务名 `orc-server`，注册到 Nacos（与 17 系列同一 discovery 配置）。

## 调用示例

```bash
curl -s http://127.0.0.1:8210/ocr/health

curl -s -X POST http://127.0.0.1:8210/ocr/mnemonic-check \
  -H "Content-Type: application/octet-stream" \
  --data-binary @photo.jpg

curl -s -X POST http://127.0.0.1:8210/ocr/mnemonic-check \
  -F "file=@photo.jpg"
```

## 配置要点

- `ocr.max-concurrent`：同时 OCR 路数，按 CPU 核数设（默认 4），避免打满机器。
- `ocr.max-long-side`：缩图长边（默认 1600），降内存、提速。
- 判定阈值与 consume 的 `mnemonic-filter.*` 同名语义一致。
