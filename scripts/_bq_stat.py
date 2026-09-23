# -*- coding: utf-8 -*-
"""bq 段1 日志统计：红线 + 关键埋点"""
import io, sys, os, re

LOGS = sys.argv[1:] or ["_bq_seg1.log"]
KEYS = [
    "FATAL EXCEPTION", "ANR in", "Process: com.github.tvbox.osc",
    "搜索轮次", "站点池定论", "等待次数", "自动重搜", "搜索收尾",
    "搜索首条结果", "搜索耗时", "本轮装载增量", "首条结果",
    "取链", "出链", "播放失败", "404", "正在初始化", "替代",
    "L1Dns", "DoH", "替身层", "SortData", "loadConfig",
    "播放中断", "起播兜底", "refetch", "重取",
]

for lg in LOGS:
    if not os.path.exists(lg):
        print("MISS", lg); continue
    print("=" * 70)
    print("FILE", lg, os.path.getsize(lg), "bytes")
    txt = io.open(lg, "r", encoding="utf-8", errors="replace").read()
    lines = txt.splitlines()
    print("LINES", len(lines))
    if lines:
        for l in lines[:1]:
            print("HEAD", l[:60])
        print("TAIL", lines[-1][:60])
    print("-" * 70)
    for k in KEYS:
        n = txt.count(k)
        if n:
            print("%-28s %d" % (k, n))
    print("-" * 70)
    # 起点分界标记
    marks = [l for l in lines if "L1Mark" in l]
    print("MARKS", len(marks))
    for m in marks[:10]:
        print("   ", m[:100])
