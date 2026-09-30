# -*- coding: utf-8 -*-
"""
cn 轮数据：右栏「接触时长 vs 惯性时长」定量分析（只读日志，不改任何代码）

要回答：用户说「接触时间短地快速滑 → 很快就停；接触时间长地滑 → 能滑比较长」。
本脚本把触摸事件流与滚动状态迁移对齐，给出量化关系。
"""
import io
import re
import collections

LOG = "_cn_round1.log"

RE_TP = re.compile(
    r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+).*\[tp\] (DOWN|UP|★CANCEL)\s+"
    r"落左栏=(\w+) x=(-?\d+) y=(-?\d+)")
RE_ST = re.compile(
    r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+).*右栏状态→(拖动|惯性|静止)")
RE_FL = re.compile(
    r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d+).*右栏滑行\[停稳\]：帧=(\d+) 总位移=(\d+)")


def ms(g):
    return ((int(g[2]) * 3600 + int(g[3]) * 60 + int(g[4])) * 1000
            + int(g[5].ljust(3, "0")[:3]))


touches = []   # (ms, kind, inLeft, x, y)
states = []    # (ms, name)
flings = []    # (ms, frames, total)

for line in io.open(LOG, encoding="utf-8", errors="ignore"):
    m = RE_TP.search(line)
    if m:
        touches.append((ms(m.groups()), m.group(7), m.group(8) == "true",
                        int(m.group(9)), int(m.group(10))))
        continue
    m = RE_ST.search(line)
    if m:
        states.append((ms(m.groups()), m.group(7)))
        continue
    m = RE_FL.search(line)
    if m:
        flings.append((ms(m.groups()), int(m.group(7)), int(m.group(8))))

print("触摸事件:", len(touches), " 状态迁移:", len(states), " 滑行结算:", len(flings))

# ---- 配对右栏手势（x > 400 ⇒ 不在左栏）----
gestures = []
pend = None
for t, k, inl, x, y in touches:
    if k == "DOWN":
        pend = (t, x, y)
    elif k in ("UP", "★CANCEL") and pend:
        t0, x0, y0 = pend
        if x0 > 400 and x > 400:          # 起点与终点都在右栏
            gestures.append((t0, t, abs(y - y0), y0, y))
        pend = None

print("右栏手势:", len(gestures))

# ---- 每个手势之后：UP → 第一次「静止」的时长（＝用户感知的惯性时长）----
rows = []
for t0, t1, dy, y0, y1 in gestures:
    dur = t1 - t0
    # UP 之后的第一个状态序列
    after = [(s, n) for s, n in states if s >= t1]
    settle = -1
    saw_settling = False
    for s, n in after[:8]:
        if n == "惯性":
            saw_settling = True
        if n == "静止":
            settle = s - t1
            break
    # 该手势对应的滑行结算（静止时刻之后最近的）
    fl = None
    for s, fr, tot in flings:
        if s >= t1:
            fl = (fr, tot)
            break
    rows.append((dur, dy, settle, saw_settling, fl))

print()
print("=" * 92)
print("  接触时长   手指位移   惯性时长(UP→静止)   见到惯性态   滑行帧数   滑行总位移")
print("=" * 92)
for dur, dy, settle, saw, fl in sorted(rows):
    f = ("%d/%d" % fl) if fl else "-"
    print("  %6dms  %7dpx  %14s   %6s   %9s   %9s"
          % (dur, dy, ("%dms" % settle) if settle >= 0 else "无", "是" if saw else "否",
             f.split("/")[0], f.split("/")[1] if fl else "-"))

print()
print("=" * 92)
print("  分组统计（按接触时长）")
print("=" * 92)
buckets = collections.OrderedDict([
    ("<80ms(极短快甩)", lambda d: d < 80),
    ("80~150ms", lambda d: 80 <= d < 150),
    ("150~300ms", lambda d: 150 <= d < 300),
    (">=300ms(长接触)", lambda d: d >= 300),
])
for name, f in buckets.items():
    sel = [r for r in rows if f(r[0])]
    if not sel:
        print("  %-16s 无样本" % name)
        continue
    dv = [r[1] for r in sel]
    sv = [r[2] for r in sel if r[2] >= 0]
    fv = [r[4][1] for r in sel if r[4]]
    print("  %-16s 样本=%2d  手指位移 中位=%5dpx  惯性时长 中位=%5sms  滑行总位移 中位=%6s"
          % (name, len(sel), sorted(dv)[len(dv) // 2],
             sorted(sv)[len(sv) // 2] if sv else -1,
             sorted(fv)[len(fv) // 2] if fv else "-"))

print()
print("=" * 92)
print("  相关性：接触时长 vs 惯性时长（皮尔逊粗算）")
print("=" * 92)
xs = [r[0] for r in rows if r[2] >= 0]
ys = [r[2] for r in rows if r[2] >= 0]
if len(xs) >= 3:
    n = len(xs)
    mx = sum(xs) / n
    my = sum(ys) / n
    cov = sum((a - mx) * (b - my) for a, b in zip(xs, ys)) / n
    sx = (sum((a - mx) ** 2 for a in xs) / n) ** 0.5
    sy = (sum((b - my) ** 2 for b in ys) / n) ** 0.5
    print("  n=%d  相关系数 r = %.3f" % (n, cov / (sx * sy) if sx * sy else 0))
    print("  （r 越接近 1 ⇒ 接触越久惯性越久 ⇒ 符合『速度按接触时长被算小』或『长接触累出更大速度』）")
