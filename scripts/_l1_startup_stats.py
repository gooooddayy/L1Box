# -*- coding: utf-8 -*-
"""统计 L1Play 起播链路耗时构成（不改任何工程文件，纯只读分析）"""
import io, re, sys, statistics

PATH = sys.argv[1] if len(sys.argv) > 1 else "_be_test1.log"

TS = re.compile(r"^(\d\d-\d\d)\s+(\d\d:\d\d:\d\d\.\d\d\d)\s+\d+\s+\d+\s+[A-Z]\s+L1Play\s*:\s?(.*)$")

def ts2ms(hhmmss):
    h, m, s = hhmmss.split(":")
    sec, ms = s.split(".")
    return ((int(h) * 60 + int(m)) * 60 + int(sec)) * 1000 + int(ms)

ev = []          # (abs_ms, day, msg)
with io.open(PATH, encoding="utf-8", errors="ignore") as f:
    for ln in f:
        m = TS.match(ln)
        if not m:
            continue
        day, hhmmss, msg = m.group(1), m.group(2), m.group(3).strip()
        if msg.startswith("播放心跳："):
            msg = msg[len("播放心跳："):].strip()
        ev.append((day, ts2ms(hhmmss), msg))

print("L1Play 行数:", len(ev))

ready  = []   # playUrl->prepared 耗时（埋点自带）
purify = []
fetch  = []   # 取链开始 -> 拿到直链
total  = []   # 取链开始 -> 起播已就绪（用户点开到出画面）

cur_fetch_start = None
cur_fetch_ms = None

for day, t, msg in ev:
    if msg.startswith("取链") and "开始 ep=" in msg:
        cur_fetch_start = (day, t)
        cur_fetch_ms = None
    elif msg.startswith("取链") and "拿到直链" in msg:
        if cur_fetch_start and cur_fetch_start[0] == day:
            cur_fetch_ms = t - cur_fetch_start[1]
            fetch.append((day, cur_fetch_ms))
    elif msg.startswith("起播") and "已就绪，耗时" in msg:
        mm = re.search(r"耗时\s+(\d+)\s*ms", msg)
        if mm:
            ready.append((day, int(mm.group(1))))
            if cur_fetch_start and cur_fetch_start[0] == day:
                total.append((day, t - cur_fetch_start[1]))
    elif msg.startswith("净化"):
        mm = re.search(r"ms=(\d+)", msg)
        kind = "命中" if "命中" in msg else ("直连" if "直连" in msg else "其它")
        if mm:
            purify.append((day, kind, int(mm.group(1))))

def stat(name, rows, idx=1):
    if not rows:
        print("\n%s: 无样本" % name)
        return
    v = sorted(r[idx] for r in rows)
    n = len(v)
    print("\n%s  n=%d  中位=%dms  均值=%.0fms  P90=%dms  最小=%d  最大=%d" % (
        name, n, v[n // 2], sum(v) / n, v[min(n - 1, int(n * 0.9))], v[0], v[-1]))
    # 直方图
    buckets = [(0, 300), (300, 600), (600, 1000), (1000, 1500), (1500, 2000),
               (2000, 3000), (3000, 5000), (5000, 10000), (10000, 10 ** 9)]
    for lo, hi in buckets:
        c = sum(1 for x in v if lo <= x < hi)
        if c:
            print("    %5d~%-5dms : %-3d  %s" % (lo, hi if hi < 10 ** 9 else 999999, c, "#" * c))

stat("① 取链（取链开始→拿到直链）", fetch)
stat("② 净化（额外网络请求）", purify, 2)
stat("③ 起播就绪（playUrl→onPrepared）", ready)
stat("④ 点开到出画面（取链开始→起播就绪）", total)

# 净化按类型
if purify:
    print("\n净化类型分布:")
    for k in ("命中", "直连", "其它"):
        v = [x[2] for x in purify if x[1] == k]
        if v:
            print("    %s: %d 次, 中位 %dms, 最大 %dms" % (k, len(v), sorted(v)[len(v) // 2], max(v)))
