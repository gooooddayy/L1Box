#!/bin/bash
# bn 搜索线抓日志工具
# 用法：
#   bash _bn_capture.sh start           清缓冲并开始抓（后台写 _bn_host.log）
#   bash _bn_capture.sh mark "冷启第一次搜 站点=40"   在日志里打一条分界线
#   bash _bn_capture.sh stop            停抓并汇报体积
#   bash _bn_capture.sh check           只查设备与已装版本
#
# 为什么在宿主机抓：设备端 `logcat -f` 带缓冲常写出 0 字节，`-u` 又不支持。
# 为什么不用 taskkill 停：`taskkill /IM adb.exe` 会连 adb server 一起杀掉，影响装机。
#
# ⚠ 两条 09-22 实测到的硬约束：
#   ① 宿主机 `nohup ... &` 起的抓取进程**活不过工具调用结束**（调用一返回就被回收，实测增量 0）；
#      只有工具层的 background 能活，但它**约 10 分钟会被掐死**（实测跑满 9m15s 后 failed）。
#      ⇒ 长测试必须**分段**：每段做完用 `_bn_checkpoint.sh <段名>` 存一份。
#   ② 设备环形缓冲默认只有 **256 KiB**（一分钟就被 sensors 噪声刷满）⇒ 先 `logcat -G 64M` 扩到 64 MiB，
#      这样即使宿主机抓取被掐死，整段日志仍留在设备内存里、`logcat -d` 能完整 dump 回来（灾备）。

export PATH="/c/Users/Administrator/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:/usr/bin:/bin:$PATH"
ADB="/c/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe"
cd "$(dirname "$0")" || exit 1
LOG="_bn_host.log"
TAG="L1Mark"

devices() {
    "$ADB" devices | grep -w "device" | grep -v "List of devices"
}

case "$1" in
check)
    echo "--- 设备 ---"; "$ADB" devices -l
    if [ -n "$(devices)" ]; then
        echo "--- 已装版本 ---"
        "$ADB" shell dumpsys package com.github.tvbox.osc | grep -E "versionName|versionCode|lastUpdateTime"
    else
        echo "!! 没有处于 device 状态的设备（unauthorized / offline / 空 都算没连上）"
    fi
    ;;

start)
    if [ -z "$(devices)" ]; then echo "!! 设备没连上，先解决 USB 再 start"; exit 2; fi
    if [ -f "$LOG" ]; then mv "$LOG" "${LOG%.log}_$(date +%H%M%S).log"; fi
    "$ADB" logcat -G 64M >/dev/null 2>&1   # 扩环形缓冲＝灾备（默认 256K 一分钟就满）
    "$ADB" logcat -c                      # 清掉缓冲，日志从零开始
    mkdir -p _bn_seg
    nohup "$ADB" logcat -v time > "$LOG" 2>&1 &
    echo "started pid=$! -> $LOG"
    sleep 1
    "$ADB" shell log -t "$TAG" "抓日志开始 $(date +%H:%M:%S)"
    echo "现在去 App 里操作。关键埋点会出现在 $LOG："
    echo "  搜索轮次 / 搜索首条结果 / 搜索耗时 / DNS(窗口内) / 本轮装载增量 / 加固 jar 手绑成功 / JarKillNeutralizer"
    ;;

mark)
    if [ -z "$(devices)" ]; then echo "!! 设备没连上"; exit 2; fi
    "$ADB" shell log -t "$TAG" "===== ${2:-MARK} ====="
    echo "已打标记：${2:-MARK}"
    ;;

stop)
    n=$(ps -W 2>/dev/null | grep -c "[a]db.exe.*logcat")
    if [ "$n" -gt 0 ]; then
        powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='adb.exe'\" | Where-Object { \$_.CommandLine -like '*logcat*' } | Stop-Process -Force" >/dev/null 2>&1
        echo "已停 $n 个抓取进程"
    else
        echo "没有在跑的抓取进程"
    fi
    [ -f "$LOG" ] && wc -c "$LOG" && grep -c "" "$LOG"
    ;;

*)
    sed -n '2,10p' "$0"
    ;;
esac
