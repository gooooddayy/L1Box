#!/usr/bin/env bash
# check_l1box_compat.sh — APK 兼容性强制校验（构建闸门，不达标 exit 1）
#
# 校验目标（对应历次踩坑，防回退）：
#   1. uses-feature 必须为空 —— screen.portrait 曾被依 Activity 方向自动注入且 required=true，
#      导致部分设备商店/安装器判定需要硬件特性，安装面变窄；
#   2. sdkVersion=24 / targetSdkVersion=28 —— 版本口径锁定（24=Android 7.0 起，28 同官方 TVBox）；
#   3. supports-screens 覆盖 small~xlarge、densities 覆盖五档密度 —— 多设备多分辨率适配底线；
#   4. application-label 存在 —— 安装器显示名。
#
# 用法: bash check_l1box_compat.sh <apk路径>

set -euo pipefail

APK="${1:?用法: bash check_l1box_compat.sh <apk路径>}"
[ -f "$APK" ] || { echo "!! APK 不存在: $APK" >&2; exit 1; }

AAPT="<本机>/AppData/Local/Android/Sdk/build-tools/33.0.2/aapt2.exe"
[ -f "$AAPT" ] || { echo "!! 未找到 aapt2: $AAPT" >&2; exit 1; }

BADGING="$("$AAPT" dump badging "$APK")"
FAIL=0

check() { # check <说明> <grep模式> <期望存在:1/0>
  local desc="$1" pat="$2" want="$3"
  if echo "$BADGING" | grep -qE "$pat"; then
    [ "$want" = "1" ] && { echo "    OK  $desc"; return; }
    echo "    !!  $desc（不应存在却存在）"; FAIL=1; return
  fi
  [ "$want" = "0" ] && { echo "    OK  $desc"; return; }
  echo "    !!  $desc（缺失）"; FAIL=1
}

echo "===== 兼容性校验: $(basename "$APK") ====="

# 1. uses-feature 必须为空
if echo "$BADGING" | grep -q "^uses-feature"; then
  echo "    !!  uses-feature 应为空，实际:" >&2
  echo "$BADGING" | grep "^uses-feature" | sed 's/^/        /' >&2
  FAIL=1
else
  echo "    OK  uses-feature 为空（无硬件特性强制要求）"
fi

# 2. 版本口径
check "minSdk=24"       "^sdkVersion:'24'"        1
check "targetSdk=28"    "^targetSdkVersion:'28'"  1

# 3. 屏幕尺寸覆盖（TV 盒子多为 large/xlarge，手机 normal）
for s in small normal large xlarge; do
  check "supports-screens 含 $s" "supports-screens:.*'$s'" 1
done

# 4. 密度五档（mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi → 160/240/320/480/640）
for d in 160 240 320 480 640; do
  check "densities 含 $d" "densities:.*'$d'" 1
done

# 5. 应用名
check "application-label 存在" "^application-label:" 1

if [ $FAIL -ne 0 ]; then
  echo "!! 兼容性校验未通过，判构建失败" >&2
  exit 1
fi
echo "===== 兼容性校验全部通过 ====="
