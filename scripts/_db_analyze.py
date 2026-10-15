# -*- coding: utf-8 -*-
"""分析 _db_dump1.txt：按订阅地址统计装载结果，找出「同一地址时好时坏」的证据。"""
import re
from collections import defaultdict

LOG = r"<工作区>\_db_dump1.txt"

stat = defaultdict(lambda: {"ok": 0, "bad": 0, "neterr": 0, "first": "", "last": ""})
ok_addr = defaultdict(int)

lines = open(LOG, encoding="utf-8", errors="replace").read().splitlines()
pat_bad = re.compile(r"L1Sub: 订阅内容不可用 地址=(\S+)")
pat_err = re.compile(r"L1Sub: 拉取配置失败 地址=(\S+)")
pat_drop = re.compile(r"L1Sub: 换源装载失败 地址=(\S+) 池归属=(\S*)")

for ln in lines:
    t = ln[:18]
    m = pat_bad.search(ln)
    if m:
        a = m.group(1)
        stat[a]["bad"] += 1
        stat[a]["first"] = stat[a]["first"] or t
        stat[a]["last"] = t
        continue
    m = pat_err.search(ln)
    if m:
        a = m.group(1)
        stat[a]["neterr"] += 1
        stat[a]["first"] = stat[a]["first"] or t
        stat[a]["last"] = t
        continue

# 成功：池归属出现了某个地址 ⇒ 该地址至少成功装载过（写入 poolUrl 即成功）
for ln in lines:
    m = pat_drop.search(ln)
    if m and m.group(2):
        ok_addr[m.group(2)] += 1

# 成功装载的其它证据：缓存预热 / 解析完成
for ln in lines:
    if "首页加载：真拉首页 候选站点=" in ln:
        pass

print("=" * 100)
print("按地址统计装载结果（bad=内容不是配置 / neterr=网络层失败 / 出现在池归属=成功过）")
print("=" * 100)
alladdr = set(stat) | set(ok_addr)
rows = sorted(alladdr, key=lambda a: -(stat[a]["bad"] + stat[a]["neterr"]))
for a in rows:
    s = stat[a]
    print(f"{a}")
    print(f"    内容不是配置={s['bad']:>3}  网络失败={s['neterr']:>3}  曾成功(出现在池归属)={ok_addr.get(a,0):>3}"
          f"   首次={s['first']}  末次={s['last']}")

print()
print("=" * 100)
print("同一地址「既成功过、又失败过」= 不稳定的地址（清池会放大其影响）")
print("=" * 100)
both = [a for a in alladdr if ok_addr.get(a, 0) > 0 and (stat[a]["bad"] + stat[a]["neterr"]) > 0]
if both:
    for a in both:
        print(f"  ⚠ {a}  成功×{ok_addr[a]}  失败×{stat[a]['bad'] + stat[a]['neterr']}")
else:
    print("  （无）")
