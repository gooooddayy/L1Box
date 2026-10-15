#!/usr/bin/env bash
# _ab_poll.sh — A/B 实测：后台 60 秒内主线程的唤醒次数（nvcsw/nivcsw 代理指标）
# 用法: bash _ab_poll.sh <apk路径> <标签>
export MSYS_NO_PATHCONV=1
ADB=<本机>/AppData/Local/Android/Sdk/platform-tools/adb.exe
PG=com.github.tvbox.osc
APK="$1"; TAG="$2"

echo "================ [$TAG] $APK ================"
"$ADB" install -r -d "$APK" 2>&1 | tail -1
"$ADB" shell am force-stop $PG; sleep 2
"$ADB" logcat -c
"$ADB" shell "am start -n $PG/.ui.activity.SplashActivity" >/dev/null 2>&1
sleep 12
PID=$("$ADB" shell pidof $PG | tr -d '\r')
echo "  pid=$PID"
TOP=$("$ADB" shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | sed 's/.*tvbox.osc\///; s/ .*//')
echo "  起始前台=$TOP（应为 MainActivity）"

read_stat(){
  "$ADB" shell "cat /proc/$PID/task/$PID/stat" 2>/dev/null | awk '{print $42, $43, $14, $15}'
}
S1=$(read_stat); echo "  前台稳态读数: nvcsw/nivcsw/utime/stime = $S1"
"$ADB" shell input keyevent KEYCODE_HOME
sleep 3
echo "  已按 HOME，后台观察 60 秒 …"
sleep 60
S2=$(read_stat); echo "  后台 60s 读数: nvcsw/nivcsw/utime/stime = $S2"
echo "$S1" | awk -v s2="$S2" '{
  split(s2,b," ");
  printf "  ⇒ 后台 60s 增量: nvcsw=%d  nivcsw=%d  utime+stime=%d jiffies\n", b[1]-$1, b[2]-$2, (b[3]-$3)+(b[4]-$4);
}'
# 顺带确认仍在后台（没被系统杀掉）
"$ADB" shell pidof $PG | tr -d '\r' | sed 's/^/  后台结束时 pid=/'
echo "  —— 日志（本版埋点）——"
"$ADB" logcat -d -v brief 2>/dev/null | grep -E "ToastSweeper" | tail -6 | sed 's/\(.\{130\}\).*/    \1/'
"$ADB" shell am force-stop $PG
echo
