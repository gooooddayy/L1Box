#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 logcat dump 里提取指定 PID 的应用日志时间线（剔系统噪声）。
格式: 09-24 12:28:39.403 18156 18156 I System.out: 消息
"""
import io
import sys

PATH = sys.argv[1]
PIDS = set(sys.argv[2].split(",")) if len(sys.argv) > 2 and sys.argv[2] else None
LIMIT = int(sys.argv[3]) if len(sys.argv) > 3 else 400

out = []
with io.open(PATH, "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        parts = line.split(None, 6)
        if len(parts) < 7:
            continue
        date, tm, pid, tid, lvl, tagpart, msg = parts
        if not (len(tm) > 8 and tm[2] == ":" and tm[5] == ":"):
            continue
        if PIDS and pid not in PIDS:
            continue
        tag = tagpart.rstrip(":")
        low = msg.lower()
        if tag in ("System.out", "AndroidRuntime") or "tvbox" in low or "l1box" in low or "catvod" in low:
            out.append((date, tm, pid, tag, msg.strip()))

for date, tm, pid, tag, msg in out[:LIMIT]:
    print(f"{tm} [{pid}] {tag}: {msg[:190]}")
print(f"\n(命中 {len(out)} 行，显示前 {min(LIMIT, len(out))})")
