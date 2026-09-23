#!/bin/bash
# bn 真机测试 · 分段存档（每个测试段做完跑一次）
# 用法：bash _bn_checkpoint.sh 段1冷启首搜
#
# 为什么需要它：宿主机侧的后台 logcat 有 ~10 分钟时限，到点会被掐死（实测 9m15s）。
# 设备侧环形缓冲已扩到 64 MiB（`adb logcat -G 64M`，默认只有 256 KiB＝一分钟就被噪声刷满），
# 所以 `logcat -d` 一次性 dump 能把**整段**拿回来——不依赖 grep 实时流。

export PATH="/c/Users/Administrator/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:/usr/bin:/bin:$PATH"
ADB="/c/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe"
cd "$(dirname "$0")" || exit 1

NAME="${1:-seg_$(date +%H%M%S)}"
mkdir -p _bn_seg
OUT="_bn_seg/${NAME}.log"

"$ADB" logcat -d -v time > "$OUT" 2>&1
SZ=$(wc -c < "$OUT")
echo "存档 -> $OUT  ($SZ 字节)"

echo "--- 我方埋点命中 ---"
for k in "搜索轮次" "搜索首条结果" "搜索耗时" "DNS(窗口内)" "本轮装载增量" "可搜站点" \
         "加固 jar 手绑成功" "JarKillNeutralizer" "L1Dns" "UnknownHostException"; do
    printf '%-24s %s\n' "$k" "$(grep -c "$k" "$OUT")"
done

echo "--- 硬故障 ---"
printf '%-24s %s\n' "FATAL" "$(grep -c 'FATAL EXCEPTION' "$OUT")"
printf '%-24s %s\n' "AndroidRuntime E" "$(grep -c 'E/AndroidRuntime' "$OUT")"
printf '%-24s %s\n' "ANR" "$(grep -c 'ANR in' "$OUT")"
