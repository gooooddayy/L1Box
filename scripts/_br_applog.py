#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""只提取 L1Box 自己的埋点（tag=System.out 且排除 okhttp 等三方库噪声）。"""
import io
import sys

PATH = sys.argv[1]
PIDS = set(sys.argv[2].split(",")) if len(sys.argv) > 2 and sys.argv[2] else None
LIMIT = int(sys.argv[3]) if len(sys.argv) > 3 else 500

# 三方库噪声前缀
NOISE = ("[okhttp]", "[OkHttp]", "[kworker]", "android.", "java.", "javax.")

# 我们自己的埋点特征词
OWN = ("搜索", "站点池", "可搜站点", "池签名", "jar装载序号", "JarToast",
       "等待次数", "L1", "订阅", "起播", "播放", "净化", "代理", "源池", "首页")

out = []
with io.open(PATH, "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        parts = line.split(None, 6)
        if len(parts) < 7:
            continue
        date, tm, pid, tid, lvl, tagpart, msg = parts
        if tagpart.rstrip(":") != "System.out":
            continue
        if PIDS and pid not in PIDS:
            continue
        msg = msg.strip()
        if msg.startswith(NOISE):
            continue
        if not any(k in msg for k in OWN):
            continue
        out.append((tm, pid, msg))

for tm, pid, msg in out[:LIMIT]:
    print(f"{tm} [{pid}] {msg[:200]}")
print(f"\n(共 {len(out)} 条自有埋点，显示 {min(LIMIT, len(out))})")
