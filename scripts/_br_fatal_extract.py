#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从存档 logcat 里提取 FATAL EXCEPTION 的完整堆栈（崩溃行 + 后续 60 行）。"""
import io
import sys

PATH = sys.argv[1] if len(sys.argv) > 1 else "_br_pre_test_logcat_archive.txt"
AFTER = 60

hits = []
buf = []
with io.open(PATH, "r", encoding="utf-8", errors="replace") as f:
    for i, line in enumerate(f, 1):
        if "FATAL EXCEPTION" in line:
            hits.append((i, line.rstrip()))

print(f"共 {len(hits)} 处 FATAL EXCEPTION\n")

# 第二遍：按行号抓上下文（文件大，只顺序扫一遍更省事，这里用记录行号后重扫）
targets = {h[0]: h[1] for h in hits}
if not targets:
    sys.exit(0)

with io.open(PATH, "r", encoding="utf-8", errors="replace") as f:
    keep_until = -1
    printbuf = []
    for i, line in enumerate(f, 1):
        if i in targets:
            printbuf = [line.rstrip()]
            keep_until = i + AFTER
        elif i <= keep_until:
            printbuf.append(line.rstrip())
        if keep_until == i and printbuf:
            print(f"########## 行 {printbuf[0][:60]} ##########")
            # 只打与崩溃相关的行：E 级别或含异常类名/at 栈帧
            for L in printbuf:
                if ("AndroidRuntime" in L or "\tat " in L
                        or "Caused by" in L or "Exception" in L or "Error" in L):
                    print(L)
            print()
