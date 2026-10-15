#!/usr/bin/env bash
# _ab_wake.sh — A/B 实测：后台 60 秒内**主线程被调度运行的次数**（schedstat 第 3 字段 = timeslices ≈ 唤醒次数）
# 用法: bash _ab_wake.sh <apk路径> <标签>
export MSYS_NO_PATHCONV=1
ADB=<本机>/AppData/Local/Android/Sdk/platform-tools/adb.exe
PG=com.github.tvbox.osc
APK="$1"; TAG="$2"

echo "================ [$TAG] ================"
"$ADB" install -r -d "$APK" 2>&1 | tail -1
"$ADB" shell am force-stop $PG; sleep 2
"$ADB" logcat -c
"$ADB" shell "am start -n $PG/.ui.activity.SplashActivity" >/dev/null 2>&1
sleep 15   # 给足时间让冷启动的下载/解析全部收尾，避免把后台收尾算进观察窗
PID=$("$ADB" shell pidof $PG | tr -d '\r')
TOP=$("$ADB" shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | sed 's/.*tvbox.osc\///; s/ .*//')
echo "  pid=$PID  前台=$TOP"

SLICES_CMD="cat /proc/$PID/task/$PID/schedstat"
STAT_CMD="cat /proc/$PID/stat"
echo "  探测 schedstat: $("$ADB" shell $SLICES_CMD 2>&1 | head -1)"
F1=$("$ADB" shell $SLICES_CMD 2>/dev/null | tr -d '\r')
T1=$("$ADB" shell $STAT_CMD 2>/dev/null | awk '{print $14+$15}')
echo "  前台稳态: schedstat=[$F1]  utime+stime=$T1"

"$ADB" shell input keyevent KEYCODE_HOME
sleep 3
echo "  已按 HOME（应用退到后台），观察 60 秒 …"
sleep 60
F2=$("$ADB" shell $SLICES_CMD 2>/dev/null | tr -d '\r')
T2=$("$ADB" shell $STAT_CMD 2>/dev/null | awk '{print $14+$15}')
echo "  后台 60s: schedstat=[$F2]  utime+stime=$T2"
echo "$F1|$F2" | awk -F'|' '{
  split($1,a," "); split($2,b," ");
  printf "  ⇒ 后台 60s 增量: 主线程被调度 %d 次 | 已运行 %d ms\n", b[3]-a[3], (b[1]-a[1])/1000000;
}'
echo "$T1|$T2" | awk -F'|' '{ printf "  ⇒ 后台 60s 增量: utime+stime = %d jiffies\n", $2-$1 }'
echo "  后台结束 pid=$("$ADB" shell pidof $PG | tr -d '\r')"
echo "  —— 本版 ToastSweeper 埋点 ——"
"$ADB" logcat -d -v brief 2>/dev/null | grep -E "ToastSweeper" | tail -5 | sed 's/\(.\{130\}\).*/    \1/'
"$ADB" shell am force-stop $PG
echo
