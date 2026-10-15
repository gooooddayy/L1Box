#!/usr/bin/env bash
# _ab_poll2.sh — 严格 A/B：只测「退后台后、被系统冻结之前」的窗口，且应用处于空闲态
# 用法: bash _ab_poll2.sh <apk路径> <标签>
export MSYS_NO_PATHCONV=1
ADB=<本机>/AppData/Local/Android/Sdk/platform-tools/adb.exe
PG=com.github.tvbox.osc
APK="$1"; TAG="$2"

echo "================ [$TAG] ================"
"$ADB" install -r -d "$APK" 2>&1 | tail -1
"$ADB" shell am force-stop $PG; sleep 2
"$ADB" shell "am start -n $PG/.ui.activity.SplashActivity" >/dev/null 2>&1
echo "  等 25 秒让首页完全加载并空闲（避免把收尾工作算进来）…"
sleep 25
PID=$("$ADB" shell pidof $PG | tr -d '\r')
TOP=$("$ADB" shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | sed 's/.*osc\///; s/ .*//')
echo "  pid=$PID  前台=$TOP（应为 MainActivity）"
sc(){ "$ADB" shell "cat /proc/$PID/task/$PID/schedstat" 2>/dev/null | awk '{print $3}' | tr -d '\r'; }
echo "  前台空闲 5 秒基线测量中…"
P0=$(sc); sleep 5; P1=$(sc); echo "  前台空闲 5s 调度增量 = $((P1-P0))（前台有帧循环，本应是大数）"

echo "  → 按 HOME 退后台，逐 5 秒窗口测（前 4 窗＝冻结前，这正是旧版轮询会跑的窗口）"
"$ADB" shell input keyevent KEYCODE_HOME
prev=$P1
for i in 1 2 3 4 5 6; do
  sleep 5
  V=$(sc)
  printf "  后台窗%d(5s): %5d 次调度\n" "$i" "$((V-prev))"
  prev=$V
done
echo "  —— 本版 ToastSweeper 埋点 ——"
"$ADB" logcat -d -v brief 2>/dev/null | grep -E "ToastSweeper" | tail -4 | sed 's/\(.\{120\}\).*/    \1/'
"$ADB" shell am force-stop $PG
echo
