#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""by 包复测日志判定：断触/图片变慢的证据挖掘（只读，不改代码）"""
import io, re, sys, collections

DUMP = r"<工作区>\_by_dump.txt"
START = "09-28 14:44"   # by 装机时刻之后
OUT = r"<工作区>\_by_judge_out.txt"

KEYS = ["搜索耗时：", "图片加载：", "搜索轮次：", "搜索停滞：", "搜索稳定：", "播放失败：",
        "FATAL", "ANR ", "Skipped", "跳过站点", "自动兜底", "同址同类"]

# 找 L1Box 的 pid（带包名的那行）
pids = set()
lines_keep = collections.defaultdict(list)
fatal = []
skipped = []
times = collections.Counter()

with io.open(DUMP, encoding="utf-8", errors="replace") as f:
    for line in f:
        if len(line) < 20:
            continue
        ts = line[:18]
        if ts[:11] < START:
            continue
        times[ts[:14]] += 1
        if "com.github.tvbox.osc" in line and "Start proc" in line:
            m = re.search(r"Start proc \d+:com\.github\.tvbox\.osc", line)
            if m:
                pids.add(line.split()[2])
        for k in KEYS:
            if k in line:
                lines_keep[k].append(line.rstrip()[:220])
                break

with io.open(OUT, "w", encoding="utf-8") as w:
    w.write("===== 时间分布（每 10 分钟行数） =====\n")
    for t in sorted(times):
        w.write(f"  {t}000  {times[t]}\n")
    for k in KEYS:
        rows = lines_keep.get(k, [])
        w.write(f"\n===== {k}（{len(rows)} 行） =====\n")
        for r in rows[:120]:
            w.write("  " + r + "\n")
print("done ->", OUT)
