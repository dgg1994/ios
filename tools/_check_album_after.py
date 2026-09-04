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
since = time.time() - 600
print("=== c2 /t last 10min ===")
cur.execute(
    "SELECT COUNT(*), SUM(body_path LIKE '%%.bin') FROM c2_records "
    "WHERE path='/t' AND captured_at > %s",
    (since,),
)
print(cur.fetchone())
print("=== album by status (last 10min addtime) ===")
cur.execute(
    "SELECT status, COUNT(*), SUM(image_path IS NOT NULL AND image_path<>'') "
    "FROM album WHERE addtime > %s GROUP BY status",
    (since,),
)
for r in cur.fetchall():
    print(r)
print("=== album latest 8 ===")
cur.execute(
    "SELECT id, status, c2_record_id, LEFT(IFNULL(image_path,''), 90), "
    "LEFT(IFNULL(file_sha256,''), 16) FROM album ORDER BY id DESC LIMIT 8"
)
for r in cur.fetchall():
    print(r)
print("=== latest c2-t ===")
cur.execute(
    "SELECT id, body_path FROM c2_records WHERE path='/t' ORDER BY id DESC LIMIT 3"
)
for r in cur.fetchall():
    print(r)
c.close()
