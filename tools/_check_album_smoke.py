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
since = time.time() - 1800
print("=== c2_records /t last 30m ===")
cur.execute(
    "SELECT COUNT(*), SUM(body_path LIKE '%%.bin'), SUM(body_path LIKE '%%.txt') "
    "FROM c2_records WHERE path='/t' AND captured_at > %s",
    (since,),
)
print(cur.fetchall())
print("=== album status last 30m ===")
cur.execute(
    "SELECT status, COUNT(*) FROM album WHERE addtime > %s GROUP BY status ORDER BY status",
    (since,),
)
for r in cur.fetchall():
    print(r)
print("=== album ok with s3 last 10 ===")
cur.execute(
    "SELECT id, status, c2_record_id, LEFT(image_path, 90) FROM album "
    "WHERE addtime > %s AND status=1 ORDER BY id DESC LIMIT 10",
    (since,),
)
for r in cur.fetchall():
    print(r)
print("=== latest c2-t body ===")
cur.execute(
    "SELECT id, body_path, captured_at FROM c2_records WHERE path='/t' ORDER BY id DESC LIMIT 5"
)
for r in cur.fetchall():
    print(r)
c.close()
