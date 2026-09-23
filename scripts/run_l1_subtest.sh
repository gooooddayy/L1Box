#!/bin/bash
# 订阅解析容错用例表：桌面 JVM 直接跑，不依赖真机、不依赖 Android SDK。
# 用法：bash run_l1_subtest.sh
set -e

ROOT="/c/Users/Administrator/WorkBuddy/2026-08-19-14-43-05"
JDK="$ROOT/dl/jdk11/jdk-11.0.32+9"
SRC="$ROOT/FreeBox-src/app/src/main/java/com/github/tvbox/osc/util"
T="$ROOT/_l1_subtest"
PKG="$T/com/github/tvbox/osc/util"

mkdir -p "$PKG"
cp "$SRC/L1SubUrl.java" "$SRC/L1SubContent.java" "$SRC/L1SubHeaders.java" "$SRC/L1DnsPolicy.java" "$PKG/"
rm -rf "$T/out"
mkdir -p "$T/out"

cd "$T"
"$JDK/bin/javac" -encoding UTF-8 -nowarn -d out com/github/tvbox/osc/util/*.java
"$JDK/bin/java" -Dfile.encoding=UTF-8 -cp out com.github.tvbox.osc.util.L1SubTest
