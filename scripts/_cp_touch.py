# -*- coding: utf-8 -*-
"""
cp 轮：检验「抬手后二次碰触导致惯性被停止」这一假设。
核心判据：惯性时长（距UP）是否 ≈ 「本次 UP → 下一次 DOWN」的间隔。
若是，则停止是**被下一次触摸触发的**（RecyclerView 在 ACTION_DOWN 里会 stopScroll）。
只读日志。
"""
import io
import re
import collections

LOG = "_cp_round1.log"

RE_T = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+).*\[tp\] (DOWN|UP|★CANCEL)\s+落左栏=(\w+) x=(-?\d+) y=(-?\d+)")
RE_UP = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+).*★右栏UP 手速=(-?\d+)\(\S+?\)"
                   r" 手横速=(-?\d+) 手指位移=(-?\d+) 接触=(\d+)ms ｜可下滚=(\w+) 可上滚=(\w+)")
RE_IN = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+).*★右栏惯性\[停稳\]：惯性帧=(\d+)"
                   r" 惯性位移=(\d+) 首帧=(-?\d+) 末帧=(-?\d+) ｜拖动帧=(\d+) 总帧=(\d+) 距UP=(-?\d+)ms")
RE_ST = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+).*右栏状态→(\S+?) 待落=\d+")


def ms(g):
    return ((int(g[2]) * 3600 + int(g[3]) * 60 + int(g[4])) * 1000
            + int(g[5].ljust(3, "0")[:3]))


touches = []
ups = []
inertia = []
states = []

for line in io.open(LOG, encoding="utf-8", errors="ignore"):
    m = RE_T.search(line)
    if m:
        touches.append((ms(m.groups()), m.group(7), m.group(8) == "true",
                        int(m.group(9)), int(m.group(10))))
        continue
    m = RE_UP.search(line)
    if m:
        g = m.groups()
        ups.append(dict(t=ms(g), vy=int(g[6])))
        continue
    m = RE_IN.search(line)
    if m:
        g = m.groups()
        inertia.append(dict(t=ms(g), frames=int(g[6]), disp=int(g[7]),
                            first=int(g[8]), last=int(g[9]),
                            dragFrames=int(g[10]), allFrames=int(g[11]),
                            sinceUp=int(g[12])))
        continue
    m = RE_ST.search(line)
    if m:
        states.append((ms(m.groups()), m.group(7)))

print("触摸=%d  右栏UP=%d  惯性结算=%d" % (len(touches), len(ups), len(inertia)))

# ---- 每个 UP → 之后第一个 DOWN 的间隔 ----
downs = [t for t, k, inl, x, y in touches if k == "DOWN"]
down_after = {}
for u in ups:
    nxt = [d for d in downs if d > u["t"]]
    down_after[u["t"]] = (nxt[0] - u["t"]) if nxt else -1

print()
print("=" * 106)
print("  惯性结算  vs  「本次UP → 下一次DOWN」的间隔")
print("=" * 106)
print("  #  距UP(惯性时长)  惯性帧  惯性位移  首帧  末帧  拖动帧  总帧   下一个DOWN在  判定")
print("-" * 106)
pairs = []
for i, r in enumerate(inertia, 1):
    # 找与本次结算对应的 UP（结算前的最后一个 UP）
    cands = [u for u in ups if u["t"] <= r["t"] and u["t"] >= r["t"] - 3000]
    u = cands[-1] if cands else None
    gap = down_after.get(u["t"], -1) if u else -1
    # 判据：若「下一个DOWN」出现得比惯性结束**早或差不多**，说明是它掐断的
    same = (gap >= 0 and abs(gap - r["sinceUp"]) <= 60)
    verdict = "★被下一次触摸掐断" if same else ("延续较久" if gap > r["sinceUp"] + 60 else "?")
    pairs.append((r, gap, verdict))
    print("  %-3d %10dms  %6d  %8d  %5d  %5d  %6d  %5d  %10s  %s"
          % (i, r["sinceUp"], r["frames"], r["disp"], r["first"], r["last"],
             r["dragFrames"], r["allFrames"],
             ("%dms" % gap) if gap >= 0 else "无", verdict))

print()
print("=" * 106)
print("  汇总")
print("=" * 106)
hit = [p for p in pairs if p[2].startswith("★")]
print("  结算总数: %d" % len(pairs))
print("  「惯性结束 ≈ 下一次触摸时刻」的: %d  (%.0f%%)" % (hit, 100.0 * len(hit) / max(1, len(pairs))))
print("  惯性阶段 0 帧的: %d" % sum(1 for r in inertia if r["frames"] == 0))
print()
gaps = [g for _, g, _ in pairs if g >= 0]
if gaps:
    c = collections.Counter()
    for g in gaps:
        c["<100ms" if g < 100 else "100-300" if g < 300 else "300-800" if g < 800 else ">=800"] += 1
    print("  「UP→下一个DOWN」间隔分布:", dict(c))
    print("  中位 = %dms" % sorted(gaps)[len(gaps) // 2])

# ---- 末帧是否收敛 ----
print()
print("=" * 106)
print("  末帧收敛性（惯性最后一帧的位移绝对值）")
print("=" * 106)
conv = [r for r in inertia if abs(r["last"]) <= 30]
print("  末帧 <=30px（收敛/自然减速）: %d" % len(conv))
print("  末帧 >30px（仍在快跑时停住）: %d" % (len(inertia) - len(conv)))
print()
print("  末帧最大的 10 条：")
for r in sorted(inertia, key=lambda x: -abs(x["last"]))[:10]:
    print("     惯性帧=%4d 位移=%7d 末帧=%6d 距UP=%6dms" %
          (r["frames"], r["disp"], r["last"], r["sinceUp"]))
