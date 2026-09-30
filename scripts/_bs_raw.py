#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按 pid（可多选）+ 时间范围，打印某份 logcat 里的全部 System.out 原始行。
用法: python _bs_raw.py <logcat> <pid逗号分隔|*|空> [起始HH:MM] [结束HH:MM] [最多行数]
时间按行首 'MM-DD HH:MM:SS.mmm' 的第 7~16 字符比较（与既有脚本口径一致）。
"""
import io
import re
import sys

PATH = sys.argv[1] if len(sys.argv) > 1 else "_bs_a345_logcat.txt"
PIDS = sys.argv[2] if len(sys.argv) > 2 else ""
T0 = sys.argv[3] if len(sys.argv) > 3 else ""
T1 = sys.argv[4] if len(sys.argv) > 4 else ""
LIMIT = int(sys.argv[5]) if len(sys.argv) > 5 else 200

pids = set(p for p in PIDS.split(",") if p.strip()) if PIDS not in ("", "*") else None
rows = []
with io.open(PATH, encoding="utf-8", errors="replace") as f:
    for line in f:
        if "System.out" not in line:
            continue
        t = line[6:20]           # HH:MM:SS.mmm
        if T0 and t < T0:
            continue
        if T1 and t > T1:
            continue
        if pids is not None:
            m = re.match(r"^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s", line)
            if not m or m.group(1) not in pids:
                continue
        rows.append(line.rstrip())

print("命中行数:", len(rows))
for l in rows[:LIMIT]:
    print(l[:190])
