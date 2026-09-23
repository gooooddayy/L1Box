# -*- coding: utf-8 -*-
"""按时间窗口导出 L1Play 埋点时间线（含相对秒），用于看"失败那一刻到底试了什么"。"""
import io
import re
import sys

LINE_RE = re.compile(r"^(\d\d-\d\d)\s+(\d\d:\d\d:\d\d\.\d+)\s+\w/(\S+)\s*\(\s*(\d+)\):\s*(.*)$")


def secs(t):
    h, m, s = t.split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)


def load(path):
    ev = []
    for ln in io.open(path, encoding="utf-8", errors="replace"):
        if "L1Play" not in ln:
            continue
        m = LINE_RE.match(ln.strip())
        if not m:
            continue
        _d, t, tag, _pid, msg = m.groups()
        if tag != "L1Play":
            continue
        ev.append((secs(t), t, msg.strip()))
    return ev


def dump(ev, a, b, label):
    print("\n########## %s  (窗口 %s ~ %s) ##########" % (label, a, b))
    sub = [e for e in ev if secs(a) <= e[0] <= secs(b)]
    if not sub:
        print("  (该窗口无埋点)")
        return
    t0 = sub[0][0]
    for ts, t, msg in sub:
        print("  +%7.3fs  %s  %s" % (ts - t0, t, msg))


if __name__ == "__main__":
    path = sys.argv[1]
    ev = load(path)
    print("埋点总数 = %d" % len(ev))
    if not ev:
        sys.exit(0)
    print("区间 = %s ~ %s" % (ev[0][1], ev[-1][1]))
    for win in sys.argv[2:]:
        a, b, label = win.split(",")
        dump(ev, a, b, label)
