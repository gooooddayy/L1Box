# -*- coding: utf-8 -*-
"""
cq 轮：理论滑行距离（独立 OverScroller 复算） vs 实测惯性位移，并按动画缩放分组。
只读日志。
"""
import io
import re
import collections

LOG = "_cq_round1.log"

RE_FL = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+).*★右栏fling请求 vx=(-?\d+) vy=(-?\d+)"
                   r" 理论距离=(-?\d+) ｜动画缩放=([-0-9.]+) 密度=([-0-9.]+)"
                   r" 手速=(-?\d+) 手指位移=(-?\d+) 接触=(\d+)ms 距UP=(-?\d+)ms")
RE_IN = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+).*★右栏惯性\[停稳\]：惯性帧=(\d+)"
                   r" 惯性位移=(\d+)(?: 首帧=(-?\d+) 末帧=(-?\d+) ｜拖动帧=(\d+) 总帧=(\d+) 距UP=(-?\d+)ms)?")
RE_RUN = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+).*右栏滑行\[停稳\]：帧=\d+ 总位移=(\d+)")


def ms(g):
    return ((int(g[2]) * 3600 + int(g[3]) * 60 + int(g[4])) * 1000
            + int(g[5].ljust(3, "0")[:3]))


fls, ins = [], []
for line in io.open(LOG, encoding="utf-8", errors="ignore"):
    m = RE_FL.search(line)
    if m:
        g = m.groups()
        fls.append(dict(t=ms(g), vx=int(g[6]), vy=int(g[7]), theory=int(g[8]),
                        scale=float(g[9]), dens=float(g[10]),
                        hand=int(g[11]), dy=int(g[12]), contact=int(g[13])))
        continue
    m = RE_IN.search(line)
    if m:
        g = m.groups()
        ins.append(dict(t=ms(g), frames=int(g[6]), disp=int(g[7]),
                        first=int(g[8]) if g[8] else 0,
                        last=int(g[9]) if g[9] else 0,
                        dragF=int(g[10]) if g[10] else 0,
                        allF=int(g[11]) if g[11] else 0,
                        sinceUp=int(g[12]) if g[12] else -1))
        continue

print("fling请求=%d  惯性结算=%d" % (len(fls), len(ins)))

# 配对：每个 fling 请求 → 之后最近的惯性结算
rows = []
for f in fls:
    nxt = [r for r in ins if r["t"] >= f["t"] - 5]
    if nxt:
        rows.append((f, nxt[0]))

print()
print("=" * 118)
print("  缩放  vy     理论距离   实测惯性位移  理论/实测   惯性帧  末帧    接触  距UP")
print("=" * 118)
for f, r in rows:
    ratio = (f["theory"] / r["disp"]) if r["disp"] else 0
    print("  %.1f  %6d  %9d  %11d  %9.1f  %7d  %6d  %5dms %6dms"
          % (f["scale"], f["vy"], f["theory"], r["disp"], ratio,
             r["frames"], r["last"], f["contact"], r["sinceUp"]))

print()
print("=" * 118)
print("  按动画缩放分组")
print("=" * 118)
for sc in sorted(set(f["scale"] for f, _ in rows)):
    sel = [(f, r) for f, r in rows if f["scale"] == sc]
    if not sel:
        continue
    th = sorted(f["theory"] for f, _ in sel)
    dp = sorted(r["disp"] for _, r in sel)
    fr = sorted(r["frames"] for _, r in sel)
    su = sorted(r["sinceUp"] for _, r in sel if r["sinceUp"] >= 0)
    print("  缩放=%.1f  样本=%2d" % (sc, len(sel)))
    print("     理论距离  中位=%8d  最小=%8d  最大=%8d" % (th[len(th)//2], th[0], th[-1]))
    print("     实测位移  中位=%8d  最小=%8d  最大=%8d" % (dp[len(dp)//2], dp[0], dp[-1]))
    print("     惯性帧数  中位=%8d  最小=%8d  最大=%8d" % (fr[len(fr)//2], fr[0], fr[-1]))
    if su:
        print("     惯性时长  中位=%8dms 最小=%d 最大=%d" % (su[len(su)//2], su[0], su[-1]))
    print()

# 理论 vs 实测 的整体关系
allth = [f["theory"] for f, _ in rows]
alldp = [r["disp"] for _, r in rows]
print("=" * 118)
print("  总体：理论距离 vs 实测惯性位移")
print("=" * 118)
print("  理论距离 中位 = %d px" % sorted(allth)[len(allth)//2])
print("  实测位移 中位 = %d px" % sorted(alldp)[len(alldp)//2])
print("  比值（理论/实测）中位 = %.1f 倍" %
      sorted([(f["theory"] / r["disp"]) for f, r in rows if r["disp"]])[len(rows)//2])
print()
big = [(f, r) for f, r in rows if f["theory"] > 3000]
small = [(f, r) for f, r in rows if f["theory"] <= 3000]
print("  理论距离 > 3000px 的样本: %d" % len(big))
print("  理论距离 <= 3000px 的样本: %d  ← 若存在，说明 scroller 本身就算出短距离" % len(small))
for f, r in small[:10]:
    print("      vy=%6d 理论=%6d 实测=%6d 缩放=%.1f" % (f["vy"], f["theory"], r["disp"], f["scale"]))
