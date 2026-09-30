#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""bs 版埋点提取：只保留自有 System.out 埋点，按时间排序输出。

用法:
    python _bs_applog.py <logcat 文件> [PID过滤]
PID过滤: 逗号分隔，留空=全部
"""
import io
import re
import sys

LOG = sys.argv[1]
PIDF = sys.argv[2] if len(sys.argv) > 2 else ""
ONLY = set(x.strip() for x in PIDF.split(",") if x.strip())

# 自有埋点关键词
MARK = [
    "缓存预热", "搜索补全", "搜索轮次", "搜索复检", "搜索等待",
    "搜索首条结果", "搜索耗时", "搜索开始", "搜索完成", "搜索看门狗",
    "从本地缓存加载", "L1Sub", "L1Play",
]
# 明确排除的第三方噪声
NOISE = ["okhttp", "javax.", "com.google", "retrofit", "-->", "<--", "ExoPlayer"]

# logcat 行: 09-24 13:51:17.982 17072 17072 I System.out: <msg>
LINE = re.compile(r"^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+(\w)\s+System\.out:\s?(.*)$")

rows = []
skipped = 0
with io.open(LOG, encoding="utf-8", errors="replace") as f:
    for raw in f:
        raw = raw.rstrip("\n")
        if "System.out" not in raw:
            continue
        m = LINE.match(raw)
        if not m:
            skipped += 1
            continue
        t, pid, tid, lvl, msg = m.groups()
        if ONLY and pid not in ONLY:
            continue
        if not any(k in msg for k in MARK):
            continue
        if any(n in msg for n in NOISE):
            continue
        rows.append((t, pid, msg.strip()))

print("命中行数: %d   (格式未识别行: %d)" % (len(rows), skipped))
if not rows:
    sys.exit(0)
print("时间范围: %s → %s" % (rows[0][0], rows[-1][0]))
pids = []
for t, p, m in rows:
    if p not in pids:
        pids.append(p)
print("涉及进程: %s" % pids)
print("-" * 100)

last_pid = None
for t, p, m in rows:
    if last_pid is not None and p != last_pid:
        print("  ---------------- 进程切换 %s → %s ----------------" % (last_pid, p))
    last_pid = p
    print("%s [pid %s] %s" % (t, p, m))
