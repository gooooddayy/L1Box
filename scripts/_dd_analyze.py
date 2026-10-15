# -*- coding: utf-8 -*-
"""查「曾经能用、忽然不能用」的源：同一地址既出现在池归属(成功)，又出现在失败日志。"""
import re
from collections import defaultdict

LOG = r"<工作区>\_dc_dump.txt"
lines = open(LOG, encoding="utf-8", errors="replace").read().splitlines()

ok = defaultdict(list)      # 地址 -> [时刻]（成功：成为池归属）
bad = defaultdict(list)     # 地址 -> [(时刻, 原因)]
drop = []                   # (时刻, 失败地址, 池归属)

p_bad = re.compile(r"L1Sub: 订阅内容不可用 地址=(\S+)")
p_err = re.compile(r"L1Sub: 拉取配置失败 地址=(\S+)")
p_drop = re.compile(r"L1Sub: 换源装载失败 地址=(\S+) 池归属=(\S*)")
p_pool = re.compile(r"池归属=(\S+) ⇒ 清空前源站点池")

for ln in lines:
    t = ln[:18] if ln[:2] == "10" else ""
    m = p_bad.search(ln)
    if m:
        bad[m.group(1)].append((t, "内容不是配置")); continue
    m = p_err.search(ln)
    if m:
        bad[m.group(1)].append((t, "网络失败")); continue
    m = p_drop.search(ln)
    if m:
        drop.append((t, m.group(1), m.group(2) or "(空)"))
        if m.group(2):
            ok[m.group(2)].append(t)

print("=" * 100)
print("① 每个地址：成功过几次（成为过池归属）/ 失败过几次")
print("=" * 100)
for a in sorted(set(ok) | set(bad), key=lambda x: -(len(bad[x]) + len(ok[x]))):
    print(f"{a}")
    print(f"    成为池归属={len(ok[a]):>2} 次   失败={len(bad[a]):>2} 次 "
          f"{[r for _t, r in bad[a]][:6]}")

print()
print("=" * 100)
print("② ★「曾经能用、忽然不能用了」的地址（既成功过、又失败过）")
print("=" * 100)
both = [a for a in set(ok) & set(bad)]
if both:
    for a in both:
        print(f"  ⚠ {a}")
        print(f"      成功时刻: {ok[a]}")
        print(f"      失败时刻: {bad[a]}")
else:
    print("  （无——没有任何地址既成功过又失败过）")

print()
print("=" * 100)
print("③ 池归属变迁（谁清了谁）—— 时间序，只看最后 16 条")
print("=" * 100)
for t, a, owner in drop[-16:]:
    print(f"  {t}  失败地址={a}\n              清掉的池归属={owner}")
