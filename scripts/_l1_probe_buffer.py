# -*- coding: utf-8 -*-
# 只读探测：确认 Exo 缓冲控制器是否真的接管、起播门槛是否真的生效
import io, sys, re

path = sys.argv[1] if len(sys.argv) > 1 else "_be_test1.log"

cnt = {"L1Buffer": 0, "L1Play": 0, "L1Diag": 0, "tier": 0, "升档": 0, "可用堆不足": 0}
samples = {"L1Buffer": [], "tier": [], "L1Play": [], "usePlayer": []}
stages = {}

def take(k, s, n=8):
    if len(samples[k]) < n:
        samples[k].append(s)

with io.open(path, "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        if "L1Buffer" in line:
            cnt["L1Buffer"] += 1
            if "升档 @" in line:
                cnt["升档"] += 1
            if "可用堆不足" in line:
                cnt["可用堆不足"] += 1
            take("L1Buffer", line.rstrip())
        if "buffer=" in line and ("tier" in line or "档" in line or "Device" in line):
            cnt["tier"] += 1
            take("tier", line.rstrip())
        if "L1Play" in line:
            cnt["L1Play"] += 1
            # 阶段名：日志格式 L1Play: 阶段 ...  用切分而非正则
            try:
                msg = line[line.find("L1Play"):]
                msg = msg.split(":", 1)[1].strip() if ":" in msg else msg
                st = msg.split(" ", 1)[0]
                stages[st] = stages.get(st, 0) + 1
            except Exception:
                pass
            take("L1Play", line.rstrip())
        if "usePlayer" in line or "switchedPlayer" in line or "播放器" in line and "L1Diag" in line:
            take("usePlayer", line.rstrip())
        if "L1Diag" in line:
            cnt["L1Diag"] += 1

print("== 文件:", path)
for k, v in cnt.items():
    print("  %-12s %d" % (k, v))
print("== L1Play 阶段分布:")
for k, v in sorted(stages.items(), key=lambda x: -x[1]):
    print("  %-14s %d" % (k, v))
for k in ("tier", "L1Buffer", "L1Play", "usePlayer"):
    if samples[k]:
        print("== 样例 [%s]:" % k)
        for s in samples[k]:
            print("   ", s[:200])
