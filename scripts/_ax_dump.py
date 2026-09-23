# -*- coding: utf-8 -*-
"""把某轮日志里的 L1Play 埋点导成紧凑时间线（含相对秒），用于逐场复盘。

注意：不要用正则解析 logcat 前缀 —— 这台机器的 bash heredoc 会把反斜杠转成斜杠，
正则一旦走 heredoc 就静默失效。这里按固定位置切时间戳、按 '):' 切消息，稳。
"""
import io
import sys


def secs(t):
    h, m, s = t.split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)


def main():
    log = sys.argv[1]
    out_path = sys.argv[2] if len(sys.argv) > 2 else "_ax_lines.txt"
    ev = []
    for ln in io.open(log, encoding="utf-8", errors="replace"):
        if "L1Play" not in ln:
            continue
        s = ln.strip()
        # logcat -v time 前缀固定：'MM-DD HH:MM:SS.mmm L/Tag  (pid): msg'
        if len(s) < 32 or s[2] != "-" or s[5] != " ":
            continue
        t = s[6:18]
        cut = s.find("):")
        if cut < 0:
            continue
        msg = s[cut + 2:].strip()
        if not msg:
            continue
        ev.append((secs(t), t, msg))
    if not ev:
        print("no L1Play lines")
        return
    t0 = ev[0][0]
    io.open(out_path, "w", encoding="utf-8").write(
        "\n".join(f"+{ts - t0:8.3f}s  {t}  {msg}" for ts, t, msg in ev)
    )
    print(f"lines={len(ev)}  span={ev[-1][0] - t0:.1f}s  {ev[0][1]} ~ {ev[-1][1]}  -> {out_path}")


main()
