#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import time
import pymysql

c = pymysql.connect(
    host="127.0.0.1",
    user="root",
    password="Root@123",
    database="ioslianjie",
    charset="utf8mb4",
)
cur = c.cursor()
print("now", time.time())
cur.execute("SELECT MAX(id), MAX(captured_at) FROM c2_records")
print("c2 max", cur.fetchone())
cur.execute(
    "SELECT id, path, kind, body_path, captured_at FROM c2_records ORDER BY id DESC LIMIT 10"
)
print("=== latest c2 ===")
for r in cur.fetchall():
    print(r)
cur.execute("SELECT COUNT(*) FROM device")
print("device count", cur.fetchone())
cur.execute(
    "SELECT id, status, c2_record_id, file_size, LEFT(IFNULL(image_path,''), 100), "
    "LEFT(IFNULL(error_msg,''), 60) FROM album ORDER BY id DESC LIMIT 10"
)
print("=== latest album ===")
for r in cur.fetchall():
    print(r)
cur.execute("SELECT status, COUNT(*) FROM album GROUP BY status ORDER BY status")
print("=== album by status ===")
for r in cur.fetchall():
    print(r)
c.close()
