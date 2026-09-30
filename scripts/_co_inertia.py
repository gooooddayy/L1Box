# -*- coding: utf-8 -*-
"""co 轮：惯性时长（状态迁移权威值）+ UP 时边界状态统计。只读日志。"""
import io
import re
import collections

RE_ST = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+).*右栏状态→(\S+?) 待落=(\d+) offset=(\d+)/(\d+)")
RE_UP = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+).*★右栏UP 手速=(-?\d+)\(\S+?\)"
                   r" 手横速=(-?\d+) 手指位移=(-?\d+) 接触=(\d+)ms ｜可下滚=(\w+) 可上滚=(\w+)"
                   r" offset=(\d+)/(\d+)")


def ms(g):
    return ((int(g[2]) * 3600 + int(g[3]) * 60 + int(g[4])) * 1000
            + int(g[5].ljust(3, "0")[:3]))


states = []   # (ms, name, offset, range)
ups = []      # (ms, vy, dy, dur, canDown, canUp, off, rng)

for line in io.open("_co_round1.log", encoding="utf-8", errors="ignore"):
    m = RE_ST.search(line)
    if m:
        g = m.groups()
        states.append((ms(g), g[6], int(g[8]), int(g[9])))
        continue
    m = RE_UP.search(line)
    if m:
        g = m.groups()
        ups.append((ms(g), int(g[6]), int(g[8]), int(g[9]),
                    g[10] == "true", g[11] == "true", int(g[12]), int(g[13])))

print("状态迁移=%d  UP=%d" % (len(states), len(ups)))

# ---- 惯性时长：惯性 → 下一个状态 ----
print()
print("=" * 78)
print("  惯性时长（权威值：'惯性' 状态到下一个状态的时间）")
print("=" * 78)
seg = []
for i in range(len(states) - 1):
    if states[i][1] == "惯性":
        nxt = states[i + 1]
        seg.append((states[i][0], nxt[0] - states[i][0], nxt[1],
                    states[i][2], states[i][3]))
print("  共 %d 段" % len(seg))
c = collections.Counter()
for _, d, nxt, _, _ in seg:
    key = "→" + nxt
    c[(key, "<50ms" if d < 50 else "50-200" if d < 200 else "200-600" if d < 600 else ">=600")] += 1
for k in sorted(c):
    print("   %-8s %-8s %d" % (k[0], k[1], c[k]))

short = [s for s in seg if s[1] < 150 and s[2] == "静止"]
long_ = [s for s in seg if s[1] >= 700 and s[2] == "静止"]
print()
print("  极短惯性(<150ms) %d 段   正常惯性(>=700ms) %d 段" % (len(short), len(long_)))

# ---- UP 时边界 ----
print()
print("=" * 78)
print("  UP 时刻（fling 发起前）能否继续滚")
print("=" * 78)
tot = len(ups)
down_ok = sum(1 for u in ups if u[4])
up_ok = sum(1 for u in ups if u[5])
both = sum(1 for u in ups if u[4] and u[5])
neither = sum(1 for u in ups if not u[4] and not u[5])
print("  总 UP=%d" % tot)
print("  可下滚=true : %d" % down_ok)
print("  可上滚=true : %d" % up_ok)
print("  两者都 true : %d" % both)
print("  两者都 false: %d" % neither)

# ---- 把「UP 距边界多远」与「惯性时长」配对 ----
print()
print("=" * 78)
print("  配对：UP 时 offset 相对边界的位置  vs  随后的惯性时长")
print("=" * 78)
rows = []
for u in ups:
    t, vy, dy, dur, cd, cu, off, rng = u
    nxt = [s for s in seg if s[0] >= t - 5]
    if not nxt:
        continue
    s = nxt[0]
    if s[2] != "静止":
        continue
    rows.append((t, vy, dur, off, rng, s[1]))
print("  %-9s %7s %6s %8s %8s %10s  %s" % ("vy", "接触", "位移", "offset", "range", "惯性时长", "距边界"))
print("  " + "-" * 74)
for t, vy, dur, off, rng, sd in sorted(rows, key=lambda x: x[5]):
    margin = min(off, rng - off)
    print("  %7d %5dms %5dpx %8d %8d %8dms  %s"
          % (vy, dur, 0, off, rng, sd,
             "近顶部(<2000)" if off < 2000 else ("近底部(<2000)" if rng - off < 2000 else "中间")))
