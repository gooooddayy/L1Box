#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""流式扫描存档 logcat，统计 L1Box 关键埋点命中情况。
不把 677MB 读进内存，按行流式处理。"""
import io
import re
import sys
from collections import Counter, defaultdict

PATH = sys.argv[1] if len(sys.argv) > 1 else "_br_pre_test_logcat_archive.txt"

# 埋点/事件关键词 -> 归类
PATTERNS = [
    ("订阅线 L1Sub",        re.compile(r"L1Sub")),
    ("播放起播/成功",        re.compile(r"起播")),
    ("播放错误:取链超时",    re.compile(r"获取播放信息错误")),
    ("播放错误:播放出错",    re.compile(r"视频播放出?错")),
    ("兜底链 errReplay",     re.compile(r"errReplay|tryRefetchOnce|重新取链|REFETCH")),
    ("搜索轮次",            re.compile(r"搜索轮次")),
    ("站点池定论",           re.compile(r"站点池定论|池定论|sitePoolSettled")),
    ("可搜站点数",           re.compile(r"可搜站点")),
    ("等待次数/重试",        re.compile(r"等待次数|readyRetry")),
    ("jar 装载序号",         re.compile(r"jar装载序号|loadSeq")),
    ("SQLite 打不开(既有)",  re.compile(r"SQLiteCantOpenDatabaseException")),
    ("新代码 l1play(不该有)", re.compile(r"l1play")),
    ("新代码 reviewZeroResult", re.compile(r"reviewZeroResult")),
    ("崩溃 FATAL",           re.compile(r"FATAL EXCEPTION")),
    ("TVBox 进程被杀",       re.compile(r"killProcess|Scheduling restart")),
]

TAG = re.compile(r"\bI/(\S+?)\s*:")

counts = Counter()
samples = defaultdict(list)
line_no = 0

with io.open(PATH, "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        line_no += 1
        for name, rx in PATTERNS:
            if rx.search(line):
                counts[name] += 1
                if len(samples[name]) < 6:
                    samples[name].append(line.rstrip()[:220])

print(f"文件: {PATH}")
print(f"总行数: {line_no:,}\n")
print("===== 命中统计 =====")
for name, _ in PATTERNS:
    c = counts.get(name, 0)
    flag = "" if c == 0 else "  <<<"
    print(f"  {c:>9,}  {name}{flag}")

print("\n===== 样本（每条最多 6 行） =====")
for name, _ in PATTERNS:
    if counts.get(name, 0):
        print(f"\n--- {name} ---")
        for s in samples[name]:
            print("   ", s)
