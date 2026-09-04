#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成可被 TPhotoDecryptor 解密的 /t plzma .dat（7z AES + StartHeader XOR 混淆）。"""

from __future__ import annotations

import base64
import io
import struct
import zlib
from typing import Optional

try:
    import py7zr
except ImportError:
    py7zr = None  # type: ignore

EVENT_KEY_PREFIX = "Ek8pl31K2yeHgQwy"
OBFUSCATION_PAT = struct.pack("<Q", 0x1234567800ABCDEF) * 2
SEVEN_Z_MAGIC = bytes([0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C])

# 1x1 最小合法 JPEG（base64）
MINIMAL_JPEG = base64.b64decode(
    "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRof"
    "Hh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAARCAABAAEDASIAAhEBAxEB"
    "/8QAFQABAQAAAAAAAAAAAAAAAAAAAAv/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAA"
    "AAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCwABmX/9k="
)


def seven_z_password(ts: str) -> str:
    return EVENT_KEY_PREFIX + (ts or "").strip()


def obfuscate_plzma_header(archive: bytes) -> bytes:
    """标准 7z → 客户端 .dat（仅混淆前 16 字节，与 TPhotoDecryptor.deobfuscatePlzmaHeader 对齐）。"""
    if len(archive) < 32:
        raise ValueError("7z 过短")
    if archive[:6] != SEVEN_Z_MAGIC:
        raise ValueError("不是 7z 魔数")
    mid = archive[12:28]  # NextHeaderOffset + NextHeaderSize
    out = bytearray(archive)
    for i in range(16):
        out[i] = mid[i] ^ OBFUSCATION_PAT[i]
    return bytes(out)


def deobfuscate_plzma_header(blob: bytes) -> bytes:
    """自测用：与 Java TPhotoDecryptor.deobfuscatePlzmaHeader 一致。"""
    if len(blob) < 32:
        raise ValueError("file 过短")
    mid = bytes(blob[i] ^ OBFUSCATION_PAT[i] for i in range(16))
    offset = struct.unpack("<Q", mid[0:8])[0]
    size = struct.unpack("<Q", mid[8:16])[0]
    need = 32 + offset + size
    if need > len(blob):
        raise ValueError(f"截断 need={need} have={len(blob)}")
    next_header = blob[32 + offset : 32 + offset + size]
    next_crc = zlib.crc32(next_header) & 0xFFFFFFFF
    body = mid + struct.pack("<I", next_crc)
    start_crc = zlib.crc32(body) & 0xFFFFFFFF
    hdr = bytearray(32)
    hdr[0:6] = SEVEN_Z_MAGIC
    hdr[6] = 0x00
    hdr[7] = 0x04
    struct.pack_into("<I", hdr, 8, start_crc)
    hdr[12:32] = body
    out = bytearray(blob)
    out[0:32] = hdr
    return bytes(out)


def build_7z_archive(image_bytes: bytes, inner_name: str, password: str) -> bytes:
    if py7zr is None:
        raise RuntimeError("缺少 py7zr：pip install py7zr")
    buf = io.BytesIO()
    with py7zr.SevenZipFile(buf, "w", password=password) as zf:
        zf.writestr(image_bytes, inner_name)
    return buf.getvalue()


def create_encrypted_dat(
    image_bytes: bytes,
    form_ts: str,
    inner_name: str = "bench.jpg",
) -> bytes:
    """JPEG/PNG 等 → 7z 加密 → XOR 混淆 → .dat"""
    archive = build_7z_archive(image_bytes, inner_name, seven_z_password(form_ts))
    return obfuscate_plzma_header(archive)


def load_image_bytes(path: Optional[str]) -> bytes:
    if path:
        with open(path, "rb") as f:
            return f.read()
    return MINIMAL_JPEG


def self_test() -> None:
    ts = str(1735689600000)
    dat = create_encrypted_dat(MINIMAL_JPEG, ts)
    archive = deobfuscate_plzma_header(dat)
    assert archive[:6] == SEVEN_Z_MAGIC
    with py7zr.SevenZipFile(io.BytesIO(archive), "r", password=seven_z_password(ts)) as zf:
        assert zf.testzip() is None, "7z 校验失败"
        names = zf.getnames()
        assert names, "7z 为空"
    print("t_dat_builder self_test OK")


if __name__ == "__main__":
    self_test()
