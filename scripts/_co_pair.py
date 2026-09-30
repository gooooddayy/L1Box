# -*- coding: utf-8 -*-
"""
co 轮数据：把「fling 请求速度」与「实际滑行结果」配对，找规律。
只读日志。
"""
import io
import re

LOG = "_co_round1.log"

RE_FL = re.compile(
    r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+).*★右栏fling请求 vx=(-?\d+) vy=(-?\d+)"
    r" ｜手指手速=(-?\d+) 手指位移=(-?\d+) 接触=(\d+)ms")
RE_UP = re.compile(
    r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+).*★右栏UP 手速=(-?\d+)\((\S+?)\)"
    r" 手横速=(-?\d+) 手指位移=(-?\d+) 接触=(\d+)ms ｜可下滚=(\w+) 可上滚=(\w+)"
    r" offset=(\d+)/(\d+)")
RE_RUN = re.compile(
    r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+).*右栏滑行\[停稳\]：帧=(\d+) 总位移=(\d+)"
    r" 最大单帧=(\d+) 平均=(\d+) 突变=(\d+) 距UP=(-?\d+)ms 尾6帧=\[([-\d,]+)\]")


def ms(g):
    return ((int(g[2]) * 3600 + int(g[3]) * 60 + int(g[4])) * 1000
            + int(g[5].ljust(3, "0")[:3]))


ups, fls, runs = [], [], []
for line in io.open(LOG, encoding="utf-8", errors="ignore"):
    m = RE_UP.search(line)
    if m:
        g = m.groups()
        ups.append(dict(t=ms(g), vy=int(g[6]), dirn=g[7], vx=int(g[8]),
                        dy=int(g[9]), dur=int(g[10]),
                        cdn=g[11] == "true", cup=g[12] == "true",
                        off=int(g[13]), rng=int(g[14])))
        continue
    m = RE_FL.search(line)
    if m:
        g = m.groups()
        fls.append(dict(t=ms(g), vx=int(g[6]), vy=int(g[7]), hand=int(g[8])))
        continue
    m = RE_RUN.search(line)
    if m:
        g = m.groups()
        runs.append(dict(t=ms(g), frames=int(g[6]), total=int(g[7]),
                         mx=int(g[8]), avg=int(g[9]), jumps=int(g[10]),
                         sinceUp=int(g[11]), tail=g[12]))
        continue

print("UP=%d  fling请求=%d  滑行结算=%d" % (len(ups), len(fls), len(runs)))

# 每个 fling 请求 → 之后最近的滑行结算
rows = []
for f in fls:
    nxt = [r for r in runs if r["t"] >= f["t"] - 5]
    if not nxt:
        continue
    r = nxt[0]
    u = None
    for cand in ups:
        if abs(cand["t"] - f["t"]) <= 30:
            u = cand
            break
    rows.append((f, r, u))

print()
print("=" * 118)
print("  #  时刻        vy      手位移  接触   距UP    帧数  总位移   末帧  offset/range        判定")
print("=" * 118)
for i, (f, r, u) in enumerate(rows, 1):
    hhmmss = "%02d:%02d.%03d" % (0, 0, 0)
    tail_last = r["tail"].split(",")[-1]
    if r["sinceUp"] < 150:
        verdict = "★极短(被掐)"
    elif r["sinceUp"] < 400:
        verdict = "偏短"
    else:
        verdict = "正常"
    offs = ("%6d/%-7d" % (u["off"], u["rng"])) if u else "-"
    print("  %-3d %s %7d  %6s  %4sms  %5dms  %5d  %6d  %5s  %-17s %s"
          % (i, "%02d:%02d:%02d.%03d" % (0, 0, 0, 0), f["vy"],
             ("%d" % u["dy"]) if u else "-", ("%d" % u["dur"]) if u else "-",
             r["sinceUp"], r["frames"], r["total"], tail_last, offs, verdict))
print()
print("⚠️ 上表时刻列渲染有误，改用下面按 y 排序的紧凑表：")
print()
print("=" * 112)
print("   vy    手位移  接触   距UP   帧数   总位移   末帧   横/纵比   offset/range")
print("=" * 112)
for f, r, u in sorted(rows, key=lambda x: x[1]["sinceUp"]):
    ratio = ("%.2f" % (abs(u["vx"]) / abs(u["vy"]))) if u and u["vy"] else "-"
    offs = ("%6d/%-7d" % (u["off"], u["rng"])) if u else "-"
    print("  %6d  %5s  %4sms  %6dms  %5d  %6d  %5s   %s    %s"
          % (f["vy"], ("%d" % u["dy"]) if u else "-", ("%d" % u["dur"]) if u else "-",
             r["sinceUp"], r["frames"], r["total"],
             r["tail"].split(",")[-1], ratio, offs))

# 分组对比
print()
print("=" * 112)
print("  分组对比：极短惯性 vs 正常惯性")
print("=" * 112)
short = [(f, r, u) for f, r, u in rows if r["sinceUp"] < 150]
norm = [(f, r, u) for f, r, u in rows if r["sinceUp"] >= 700]
for name, sel in (("极短(<150ms)", short), ("正常(>=700ms)", norm)):
    if not sel:
        continue
    vys = [f["vy"] for f, _, _ in sel]
    rngs = [u["rng"] for _, _, u in sel if u]
    offs = [u["off"] for _, _, u in sel if u]
    ratios = [(abs(u["vx"]) / abs(u["vy"])) for _, _, u in sel if u and u["vy"]]
    print("  %-14s 样本=%2d  vy 中位=%6d  range 中位=%7s  offset 中位=%7s  横纵比中位=%.2f"
          % (name, len(sel), sorted(vys)[len(vys) // 2],
             sorted(rngs)[len(rngs) // 2] if rngs else "-",
             sorted(offs)[len(offs) // 2] if offs else "-",
             sorted(ratios)[len(ratios) // 2] if ratios else -1))
