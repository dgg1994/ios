#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import pymysql

ids = [452743, 415176, 415384, 421358, 415180, 415406, 421368, 454736]
c = pymysql.connect(
    host="127.0.0.1",
    user="root",
    password="Root@123",
    database="ioslianjie",
    charset="utf8mb4",
)
cur = c.cursor()
print("=== ids from your log ===")
for i in ids:
    cur.execute(
        "SELECT id, path, kind, body_path, FROM_UNIXTIME(captured_at) AS t "
        "FROM c2_records WHERE id=%s",
        (i,),
    )
    r = cur.fetchone()
    print(i, "EXISTS" if r else "MISSING", r)

print("=== range around today's bench (~454660+) ===")
cur.execute(
    "SELECT MIN(id), MAX(id), COUNT(*) FROM c2_records "
    "WHERE id >= 454660 AND captured_at > UNIX_TIMESTAMP()-7200"
)
print(cur.fetchall())
cur.execute(
    "SELECT path, COUNT(*) FROM c2_records WHERE id >= 454660 GROUP BY path"
)
for r in cur.fetchall():
    print(r)
c.close()
