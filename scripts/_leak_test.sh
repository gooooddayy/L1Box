#!/usr/bin/env bash
# _leak_test.sh — 受控的 Activity/内存泄漏测试
# 每步都核对前台 Activity，确保导航确实到位（避免把"点开了弹窗"误判成泄漏）
export MSYS_NO_PATHCONV=1
ADB=<本机>/AppData/Local/Android/Sdk/platform-tools/adb.exe
PG=com.github.tvbox.osc

snap(){
  local tag="$1"
  local top
  top=$("$ADB" shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | sed 's/.*tvbox.osc\///; s/ .*//')
  local m
  m=$("$ADB" shell dumpsys meminfo $PG 2>/dev/null | grep -E "Views:|Activities:|AppContexts:|ViewRootImpl:|TOTAL PSS" | tr -s ' ' | sed 's/^ //' | tr '\n' '|')
  printf "%-22s 前台=%-22s %s\n" "$tag" "${top:-?}" "$m"
}

echo "=== 冷启到首页 ==="
"$ADB" shell am force-stop $PG; sleep 2
"$ADB" shell "am start -n $PG/.ui.activity.SplashActivity" >/dev/null 2>&1
sleep 8
snap "基线"

echo
echo "=== 进详情 → 返回，重复 5 次（每次核对前台）==="
for i in $(seq 1 5); do
  "$ADB" shell input tap 234 746; sleep 5
  local_t="$("$ADB" shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | sed 's/.*tvbox.osc\///; s/ .*//')"
  "$ADB" shell input keyevent KEYCODE_BACK; sleep 3
  printf "  第 %d 轮 进详情时前台=%-20s " "$i" "$local_t"; snap "  返回后"
  # 若返回后不是 MainActivity（说明落到了详情页内的弹层），再补一次返回
done

echo
echo "=== 触发系统一次性回收（模拟后台/内存压力）后再看 ==="
"$ADB" shell am send-trim-memory $PG RUNNING_CRITICAL >/dev/null 2>&1; sleep 3
snap "trim 后"
"$ADB" shell am send-trim-memory $PG BACKGROUND >/dev/null 2>&1; sleep 3
snap "trim(BG) 后"
