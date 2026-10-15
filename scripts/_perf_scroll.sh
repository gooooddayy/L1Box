#!/usr/bin/env bash
# _perf_scroll.sh — 用 dumpsys gfxinfo 量真实掉帧率
# 用法: bash _perf_scroll.sh <次数>
export MSYS_NO_PATHCONV=1
ADB=<本机>/AppData/Local/Android/Sdk/platform-tools/adb.exe
N=${1:-8}

"$ADB" shell dumpsys gfxinfo com.github.tvbox.osc reset >/dev/null 2>&1
# 首页网格：从下方 1400 滑到 900（滑动列表）
for i in $(seq 1 "$N"); do
  "$ADB" shell input swipe 632 1500 632 900 220
  sleep 0.35
done
for i in $(seq 1 "$N"); do
  "$ADB" shell input swipe 632 900 632 1500 220
  sleep 0.35
done
sleep 1
"$ADB" shell dumpsys gfxinfo com.github.tvbox.osc > <本机>/WorkBuddy/2026-08-19-14-43-05/_gfx.txt 2>&1
python - <<'PY'
import re, io
s = io.open(r"<工作区>/_gfx.txt", encoding="utf-8", errors="replace").read()
tot = re.search(r"Total frames rendered:\s*(\d+)", s)
jank = re.search(r"Janky frames:\s*(\d+)", s)
jank_pct = re.search(r"Janky frames \(legacy\):\s*(\d+)\s*\(([\d.]+)%\)", s)
p50 = re.search(r"50th percentile:\s*(\d+)ms", s)
p90 = re.search(r"90th percentile:\s*(\d+)ms", s)
p95 = re.search(r"95th percentile:\s*(\d+)ms", s)
p99 = re.search(r"99th percentile:\s*(\d+)ms", s)
missed = re.search(r"Number Missed Vsync:\s*(\d+)", s)
slow = re.search(r"Number Slow UI thread:\s*(\d+)", s)
slowbmp = re.search(r"Number Slow bitmap uploads:\s*(\d+)", s)
slowdraw = re.search(r"Number Slow issue draw commands:\s*(\d+)", s)
def g(m, d="-"):
    return m.group(1) if m else d
print("=== 帧统计 ===")
print("  总帧数            : %s" % g(tot))
print("  Janky frames      : %s" % g(jank))
print("  掉帧率(legacy)    : %s" % (jank_pct.group(2)+"%" if jank_pct else "-"))
print("  50th / 90th / 95th / 99th : %s / %s / %s / %s" % (g(p50), g(p90), g(p95), g(p99)))
print("  Missed Vsync      : %s" % g(missed))
print("  Slow UI thread    : %s" % g(slow))
print("  Slow bitmap upload: %s" % g(slowbmp))
print("  Slow draw commands: %s" % g(slowdraw))
for line in s.splitlines():
    if re.search(r"Janky frames:.*\(", line):
        print("  口径明细: " + line.strip())
PY
