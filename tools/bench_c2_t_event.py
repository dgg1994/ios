#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
C2 压测：POST /a + /event + /t → http://127.0.0.1:8100（forward-server）

特性：
  - /a：AES-ECB 加密绑机（走 17-binding-server）
  - /event：AES-ECB 加密（密钥 sha256(Ek8pl31K2yeHgQwy + x-ts)）
  - /t：multipart + 真实 plzma .dat（7z AES，可被 consume 解图上云）
  - 设备池：先 warmup /event（或 /a）建机，再压混合流量

依赖：pip install requests pycryptodome py7zr

示例：
  python tools/bench_c2_t_event.py --real-t --t-image tools/_test_photo.jpg \\
    --device-id 62DA9B830E04E100 --t-hex-d \\
    --a-ratio 0.1 --event-ratio 0.2 --t-ratio 0.7 \\
    --duration 60 --workers 32 --qps 40
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import random
import string
import struct
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from t_dat_builder import create_encrypted_dat, load_image_bytes

try:
    import requests
except ImportError:
    print("缺少 requests：pip install requests", file=sys.stderr)
    sys.exit(1)

try:
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import pad
except ImportError:
    print("缺少 pycryptodome：pip install pycryptodome", file=sys.stderr)
    sys.exit(1)

EVENT_KEY_PREFIX = "Ek8pl31K2yeHgQwy"
ACK_HINT = "vwD9XTDjRE/8O3PtJiV0ZQ=="


def _hex_ascii(n: int = 16) -> str:
    """模拟客户端 d/f 常见的 hex-ASCII（消费端会归一化）。"""
    raw = "".join(random.choice("0123456789ABCDEF") for _ in range(n))
    return "".join(f"{ord(c):02X}" for c in raw)


def _rand_serial() -> str:
    return "".join(random.choice(string.ascii_uppercase + string.digits) for _ in range(12))


@dataclass
class Device:
    d: str
    f: str
    u: str
    s: str
    lhu: str
    m: str = "iPhone14,5"
    pv: str = "17.0"
    d_t: Optional[str] = None  # /t multipart 专用 d/f；空则同 d


def _to_hex_ascii(plain: str) -> str:
    """明文 ECID → /t 常见的 hex-ASCII d/f（与 DeviceIdUtil 互逆）。"""
    return "".join(f"{ord(c):02X}" for c in plain.upper())


def make_device_from_id(device_id: str, t_hex_d: bool) -> Device:
    plain = device_id.strip().upper()
    d_t = _to_hex_ascii(plain) if t_hex_d else None
    return Device(
        d=plain,
        f=plain,
        u=plain + "-02080000" if len(plain) <= 48 else plain[:48],
        s=_rand_serial(),
        lhu=str(uuid.uuid4()).upper(),
        d_t=d_t,
    )


def make_device_pool(n: int, fixed_id: Optional[str] = None, t_hex_d: bool = False) -> List[Device]:
    if fixed_id:
        return [make_device_from_id(fixed_id, t_hex_d)]
    pool = []
    for _ in range(max(1, n)):
        d = _hex_ascii(16)
        pool.append(
            Device(
                d=d,
                f=d,
                u=uuid.uuid4().hex.upper(),
                s=_rand_serial(),
                lhu=str(uuid.uuid4()).upper(),
            )
        )
    return pool


def encrypt_event_body(plaintext: str, x_ts: str) -> str:
    key = hashlib.sha256((EVENT_KEY_PREFIX + x_ts).encode("utf-8")).digest()
    cipher = AES.new(key, AES.MODE_ECB)
    ct = cipher.encrypt(pad(plaintext.encode("utf-8"), AES.block_size))
    return base64.b64encode(ct).decode("ascii")


def build_event_payload(dev: Device, seq: int) -> Tuple[str, str]:
    x_ts = str(int(time.time() * 1000))
    plain = {
        "d": dev.d,
        "f": dev.f,
        "u": dev.u,
        "s": dev.s,
        "m": dev.m,
        "pv": dev.pv,
        "lhu": dev.lhu,
        "et": f"bench-{seq}",
        "ts": x_ts,
        "deviceInfo": {
            "productType": dev.m,
            "productVersion": dev.pv,
            "deviceName": "bench-iphone",
        },
    }
    body = encrypt_event_body(json.dumps(plain, separators=(",", ":")), x_ts)
    return x_ts, body


def build_a_payload(dev: Device, seq: int) -> Tuple[str, str]:
    """/a 与 /event 同一套 AES outer；明文多带 deviceInfo 便于 binding 建机。"""
    return build_event_payload(dev, seq)


def build_t_multipart(
    dev: Device,
    seq: int,
    file_size: int,
    real_t: bool,
    image_bytes: bytes,
    cached_dat: Optional[Tuple[str, bytes]] = None,
    dat_pool: Optional[List[Tuple[str, bytes]]] = None,
) -> Tuple[str, bytes, str]:
    boundary = "----BenchBoundary" + uuid.uuid4().hex
    if real_t and dat_pool:
        ts, blob = dat_pool[seq % len(dat_pool)]
    elif real_t and cached_dat is not None:
        ts, blob = cached_dat
    else:
        ts = str(int(time.time() * 1000))
        blob = None
    fields = {
        "idx": str(seq % 1000),
        "d": dev.d_t if dev.d_t else dev.d,
        "f": dev.d_t if dev.d_t else dev.f,
        "u": dev.u,
        "s": dev.s,
        "m": dev.m,
        "lhu": dev.lhu,
        "ts": ts,
        "rid": f"bench-{seq}",
        "ftu": "1",
        "sbu": "1",
        "b": "0",
        "c": "0",
        "sig": "bench",
    }
    chunks: List[bytes] = []
    for k, v in fields.items():
        chunks.append(
            (
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="{k}"\r\n\r\n'
                f"{v}\r\n"
            ).encode("utf-8")
        )
    if real_t:
        if blob is None:
            blob = create_encrypted_dat(image_bytes, ts, inner_name=f"bench_{seq}.jpg")
    else:
        blob = os.urandom(max(64, file_size))
    chunks.append(
        (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="bench_{seq}.dat"\r\n'
            f"Content-Type: application/octet-stream\r\n\r\n"
        ).encode("utf-8")
    )
    chunks.append(blob)
    chunks.append(b"\r\n")
    chunks.append(f"--{boundary}--\r\n".encode("utf-8"))
    body = b"".join(chunks)
    content_type = f"multipart/form-data; boundary={boundary}"
    return content_type, body, ts


def _unique_jpeg(base: bytes, idx: int) -> bytes:
    """在 JPEG EOI 前插入唯一 COM 段，保证 file_sha256 不同且仍可解码。"""
    tag = f"bench-{idx}-{uuid.uuid4().hex[:8]}".encode("ascii")
    com = b"\xff\xfe" + struct.pack(">H", 2 + len(tag)) + tag
    if len(base) >= 2 and base[-2:] == b"\xff\xd9":
        return base[:-2] + com + b"\xff\xd9"
    return base + com


def build_unique_dat_pool(image_bytes: bytes, size: int, workers: int = 8) -> List[Tuple[str, bytes]]:
    """
    预生成多份唯一 .dat：只加密 1 次，其后追加唯一尾字节。
    尾字节不破坏 7z 解密（TPhotoDecryptor / SevenZ 按头长度读），但 file_sha256 不同 → 每条都会新建 album 并上云。
    """
    size = max(1, size)
    t0 = time.perf_counter()
    ts = str(int(time.time() * 1000))
    base = create_encrypted_dat(image_bytes, ts, inner_name="bench.jpg")
    pool: List[Tuple[str, bytes]] = []
    for i in range(size):
        # 唯一尾缀 → 不同 sha；密码仍用同一 ts
        suffix = f"#{i:08d}-".encode("ascii") + os.urandom(8)
        pool.append((ts, base + suffix))
    print(
        f"[init] 唯一 /t .dat 池已就绪 count={len(pool)} "
        f"base={len(base)}B (+tail), cost={time.perf_counter()-t0:.1f}s"
    )
    return pool


@dataclass
class Stats:
    lock: threading.Lock = field(default_factory=threading.Lock)
    ok: int = 0
    fail: int = 0
    by_path: Dict[str, int] = field(default_factory=lambda: {"/a": 0, "/event": 0, "/t": 0})
    lat_ms: List[float] = field(default_factory=list)
    status: Dict[int, int] = field(default_factory=dict)
    errors: List[str] = field(default_factory=list)

    def add(self, path: str, code: int, ms: float, err: Optional[str] = None) -> None:
        with self.lock:
            if 200 <= code < 300 and err is None:
                self.ok += 1
            else:
                self.fail += 1
                if err and len(self.errors) < 20:
                    self.errors.append(err)
            self.by_path[path] = self.by_path.get(path, 0) + 1
            self.lat_ms.append(ms)
            self.status[code] = self.status.get(code, 0) + 1


def percentile(vals: List[float], p: float) -> float:
    if not vals:
        return 0.0
    s = sorted(vals)
    k = min(len(s) - 1, max(0, int(round((p / 100.0) * (len(s) - 1)))))
    return s[k]


class Bench:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.base = args.base.rstrip("/")
        self.pool = make_device_pool(args.devices, args.device_id, args.t_hex_d)
        self.t_image_bytes = load_image_bytes(args.t_image or None)
        self.cached_t_dat: Optional[Tuple[str, bytes]] = None
        self.dat_pool: List[Tuple[str, bytes]] = []
        if args.real_t:
            if args.t_unique_pool > 0:
                self.dat_pool = build_unique_dat_pool(
                    self.t_image_bytes,
                    args.t_unique_pool,
                    workers=min(16, max(4, args.workers // 2)),
                )
            else:
                ts = str(int(time.time() * 1000))
                blob = create_encrypted_dat(self.t_image_bytes, ts, inner_name="bench.jpg")
                self.cached_t_dat = (ts, blob)
                print(f"[init] 真实 /t .dat 已预生成(单份去重), ts={ts}, size={len(blob)}B")
        self.stats = Stats()
        self.seq = 0
        self.seq_lock = threading.Lock()
        self.session = requests.Session()
        adapter = requests.adapters.HTTPAdapter(
            pool_connections=max(args.workers, 8),
            pool_maxsize=max(args.workers * 2, 16),
            max_retries=0,
        )
        self.session.mount("http://", adapter)
        self.session.mount("https://", adapter)

    def next_seq(self) -> int:
        with self.seq_lock:
            self.seq += 1
            return self.seq

    def pick_device(self) -> Device:
        return random.choice(self.pool)

    def post_a(self, dev: Optional[Device] = None) -> None:
        dev = dev or self.pick_device()
        seq = self.next_seq()
        x_ts, body = build_a_payload(dev, seq)
        headers = {
            "Content-Type": "text/plain; charset=utf-8",
            "x-ts": x_ts,
            "User-Agent": "bench-c2/1.0",
        }
        t0 = time.perf_counter()
        try:
            r = self.session.post(
                f"{self.base}/a",
                data=body.encode("utf-8"),
                headers=headers,
                timeout=self.args.timeout,
            )
            ms = (time.perf_counter() - t0) * 1000
            self.stats.add("/a", r.status_code, ms)
        except Exception as e:
            ms = (time.perf_counter() - t0) * 1000
            self.stats.add("/a", 0, ms, str(e))

    def post_event(self, dev: Optional[Device] = None) -> None:
        dev = dev or self.pick_device()
        seq = self.next_seq()
        x_ts, body = build_event_payload(dev, seq)
        headers = {
            "Content-Type": "text/plain; charset=utf-8",
            "x-ts": x_ts,
            "User-Agent": "bench-c2/1.0",
        }
        t0 = time.perf_counter()
        try:
            r = self.session.post(
                f"{self.base}/event",
                data=body.encode("utf-8"),
                headers=headers,
                timeout=self.args.timeout,
            )
            ms = (time.perf_counter() - t0) * 1000
            self.stats.add("/event", r.status_code, ms)
        except Exception as e:
            ms = (time.perf_counter() - t0) * 1000
            self.stats.add("/event", 0, ms, str(e))

    def post_t(self, dev: Optional[Device] = None) -> None:
        dev = dev or self.pick_device()
        seq = self.next_seq()
        ctype, body, x_ts = build_t_multipart(
            dev,
            seq,
            self.args.t_file_size,
            self.args.real_t,
            self.t_image_bytes,
            self.cached_t_dat,
            self.dat_pool if self.dat_pool else None,
        )
        headers = {
            "Content-Type": ctype,
            "x-ts": x_ts,
            "User-Agent": "bench-c2/1.0",
            "Content-Length": str(len(body)),
        }
        t0 = time.perf_counter()
        try:
            r = self.session.post(
                f"{self.base}/t",
                data=body,
                headers=headers,
                timeout=self.args.timeout,
            )
            ms = (time.perf_counter() - t0) * 1000
            self.stats.add("/t", r.status_code, ms)
        except Exception as e:
            ms = (time.perf_counter() - t0) * 1000
            self.stats.add("/t", 0, ms, str(e))

    def warmup(self) -> None:
        n = self.args.warmup
        if n <= 0:
            return
        print(f"[warmup] 对 {len(self.pool)} 个设备各发 /event ×{n} …")
        with ThreadPoolExecutor(max_workers=min(16, self.args.workers)) as ex:
            futs = []
            for _ in range(n):
                for dev in self.pool:
                    futs.append(ex.submit(self.post_event, dev))
            for f in as_completed(futs):
                f.result()
        # 给 binding/入库一点时间，便于后续 /t 命中
        time.sleep(self.args.warmup_sleep)
        print(f"[warmup] 完成 ok={self.stats.ok} fail={self.stats.fail}")

    def one_shot(self) -> None:
        r = random.random()
        a = max(0.0, float(self.args.a_ratio))
        e = max(0.0, float(self.args.event_ratio))
        t = max(0.0, float(self.args.t_ratio))
        total = a + e + t
        if total <= 0:
            self.post_t()
            return
        a, e, t = a / total, e / total, t / total
        if r < a:
            self.post_a()
        elif r < a + e:
            self.post_event()
        else:
            self.post_t()

    def run_qps(self) -> None:
        interval = 1.0 / self.args.qps if self.args.qps > 0 else 0
        end = time.time() + self.args.duration
        with ThreadPoolExecutor(max_workers=self.args.workers) as ex:
            futs = []
            next_t = time.time()
            while time.time() < end:
                now = time.time()
                if interval > 0 and now < next_t:
                    time.sleep(min(0.002, next_t - now))
                    continue
                futs.append(ex.submit(self.one_shot))
                if interval > 0:
                    next_t += interval
                # 防止 future 堆积过多
                if len(futs) > self.args.workers * 20:
                    done = [f for f in futs if f.done()]
                    for f in done:
                        f.result()
                    futs = [f for f in futs if not f.done()]
            for f in as_completed(futs):
                f.result()

    def run_total(self) -> None:
        with ThreadPoolExecutor(max_workers=self.args.workers) as ex:
            futs = [ex.submit(self.one_shot) for _ in range(self.args.total)]
            for f in as_completed(futs):
                f.result()

    def print_report(self, elapsed: float) -> None:
        s = self.stats
        total = s.ok + s.fail
        lats = list(s.lat_ms)
        print("\n======== bench result ========")
        print(f"base      : {self.base}")
        print(f"elapsed   : {elapsed:.2f}s")
        print(f"total     : {total}  ok={s.ok}  fail={s.fail}")
        print(f"by path   : {dict(s.by_path)}")
        print(f"status    : {dict(s.status)}")
        if elapsed > 0:
            print(f"throughput: {total / elapsed:.1f} req/s")
        if lats:
            print(
                f"latency   : avg={sum(lats)/len(lats):.1f}ms  "
                f"p50={percentile(lats,50):.1f}ms  "
                f"p95={percentile(lats,95):.1f}ms  "
                f"p99={percentile(lats,99):.1f}ms  "
                f"max={max(lats):.1f}ms"
            )
        if s.errors:
            print("errors    :")
            for e in s.errors:
                print(f"  - {e}")
        print("提示: 网关 ACK 快不代表消费追平；请另看 Kafka LAG / consume 日志")
        print("==============================\n")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="压测 C2 /a + /event + /t（经 8100 网关）")
    p.add_argument("--base", default="http://127.0.0.1:8100", help="网关地址")
    p.add_argument("--workers", type=int, default=32, help="并发线程数")
    p.add_argument("--qps", type=float, default=0, help="目标 QPS；0 表示打满 workers")
    p.add_argument("--duration", type=float, default=30, help="按时长压测秒数（与 --total 二选一）")
    p.add_argument("--total", type=int, default=0, help="按总请求数压测；>0 时忽略 duration")
    p.add_argument("--a-ratio", type=float, default=0.1, help="/a 占比")
    p.add_argument("--event-ratio", type=float, default=0.2, help="/event 占比")
    p.add_argument("--t-ratio", type=float, default=0.7, help="/t 占比")
    p.add_argument("--real-t", action="store_true", help="/t 使用真实 plzma .dat（可解图上云）")
    p.add_argument("--t-image", default="", help="/t 内嵌图片路径（默认内置 1x1 JPEG）")
    p.add_argument(
        "--t-unique-pool",
        type=int,
        default=0,
        help="预生成 N 份唯一 .dat 轮询（>0 时每条 /t sha 不同，都会新建 album 上云；建议 ≥ 预计 /t 数）",
    )
    p.add_argument("--device-id", default="", help="固定 deviceid（d/f 归一化后应等于此值）")
    p.add_argument("--t-hex-d", action="store_true", help="/t 的 d/f 用 hex-ASCII 发送（更接近真实客户端）")
    p.add_argument("--devices", type=int, default=40, help="设备池大小（未指定 device-id 时）")
    p.add_argument("--warmup", type=int, default=-1, help="每设备 warmup /event 次数；-1=有 device-id 则 0 否则 1")
    p.add_argument("--warmup-sleep", type=float, default=1.0, help="warmup 后等待秒数")
    p.add_argument("--t-file-size", type=int, default=2048, help="/t 附件字节数（非 --real-t 时）")
    p.add_argument("--timeout", type=float, default=15.0, help="单请求超时秒")
    p.add_argument("--smoke", action="store_true", help="各发 1 条 /a+/event+/t 后退出")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    for name in ("a_ratio", "event_ratio", "t_ratio"):
        v = getattr(args, name)
        if v < 0:
            print(f"--{name.replace('_','-')} 不能为负", file=sys.stderr)
            sys.exit(2)
    if args.a_ratio + args.event_ratio + args.t_ratio <= 0:
        print("a/event/t 占比之和须 > 0", file=sys.stderr)
        sys.exit(2)
    if args.warmup < 0:
        args.warmup = 0 if args.device_id else 1

    bench = Bench(args)

    # smoke
    if args.smoke:
        print(f"[smoke] GET {args.base}/health …")
        try:
            hr = bench.session.get(f"{args.base}/health", timeout=5)
            print(f"[smoke] health={hr.status_code} body={hr.text[:80]!r}")
        except Exception as e:
            print(f"[smoke] health 失败: {e}", file=sys.stderr)
            sys.exit(1)
        bench.post_a(bench.pool[0])
        bench.post_event(bench.pool[0])
        bench.post_t(bench.pool[0])
        bench.print_report(0.001)
        return

    mix = f"a={args.a_ratio:.0%}/event={args.event_ratio:.0%}/t={args.t_ratio:.0%}"
    print(
        f"[start] base={args.base} workers={args.workers} mix={mix} "
        f"device_id={args.device_id or '(random pool)'} "
        f"real_t={args.real_t} t_image={args.t_image or '(builtin 1x1 jpg)'} "
        f"mode={'total='+str(args.total) if args.total>0 else f'duration={args.duration}s qps={args.qps}'}"
    )
    t0 = time.perf_counter()
    # warmup 统计单独算进总报告也可；这里重置更清晰
    bench.warmup()
    bench.stats = Stats()

    if args.total > 0:
        bench.run_total()
    else:
        if args.qps <= 0:
            # 打满：持续提交直到 duration
            end = time.time() + args.duration
            with ThreadPoolExecutor(max_workers=args.workers) as ex:
                futs = set()
                while time.time() < end:
                    futs.add(ex.submit(bench.one_shot))
                    # 回收完成的
                    done = {f for f in futs if f.done()}
                    for f in done:
                        f.result()
                    futs -= done
                    if len(futs) >= args.workers * 4:
                        # 背压
                        finished = next(as_completed(futs))
                        finished.result()
                        futs.discard(finished)
                for f in as_completed(futs):
                    f.result()
        else:
            bench.run_qps()

    elapsed = time.perf_counter() - t0
    bench.print_report(elapsed)


if __name__ == "__main__":
    main()
