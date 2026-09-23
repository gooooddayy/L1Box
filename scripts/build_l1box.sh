#!/usr/bin/env bash
# build_l1box.sh — 端到端构建 + 签名 + 内容校验，避免"重新签名陈旧 APK"的致命陷阱
#
# 设计要点（针对 2026-09-02 发现的 bug）：
#   1. 签名输入永远取「编译产物目录里刚编出来的 APK」，绝不引用根目录可能存在的旧 APK。
#   2. 签名后做三道闸门校验：apksigner 三方案 + 内嵌 home_config.json 无死链 + dex 含修复字符串。
#      任一不过即 exit 1，绝不产出"看起来成功其实还是旧的"包。
#
# 用法（在 Git Bash 里）:
#   bash build_l1box.sh                 # 默认输出名 L1Box_v1.1.1_release_20260902
#   bash build_l1box.sh MyName         # 自定义输出名
# 注意：本脚本需要写 .lock，请在该环境以"关闭沙箱"方式运行（本代理执行时加 dangerouslyDisableSandbox）。

set -euo pipefail

# ---------- 路径（项目专用，固定） ----------
WS="<工作区>"
ROOT="$WS/FreeBox-src"
JAVA_HOME="$WS/dl/jdk11/jdk-11.0.32+9"
ANDROID_HOME="$USERPROFILE/AppData/Local/Android/Sdk"
GRADLE_USER_HOME="$WS/.guserhome2"
GD="<本机>/.gradle/wrapper/dists/gradle-7.3.3-bin/6a41zxkdtcxs8rphpq6y0069z/gradle-7.3.3/bin/gradle.bat"
SDK="$ANDROID_HOME/build-tools/33.0.2"
JKS="$ROOT/TVBoxOSC.jks"
KS_PASS="TVBoxOSC"
ALIAS="tvboxosc"

NAME="${1:-L1Box_v1.1.1_release_20260902}"
REL_DIR="$ROOT/app/build/outputs/apk/release"

echo "===== 预检: home_config.json 配置校验（防首页死链/缺字段复发） ====="
if command -v python >/dev/null 2>&1; then
  python "$WS/validate_home_config.py" "$ROOT/app/src/main/assets/home_config.json" || {
    echo "!! 配置校验发现 ERROR（见上方），中止构建以避免带病出包" >&2
    exit 1
  }
else
  echo "（跳过：未找到 python，无法跑配置校验）"
fi

echo "===== [1/6] 准备环境 ====="
cd "$ROOT"
export JAVA_HOME ANDROID_HOME GRADLE_USER_HOME
taskkill //F //IM java.exe //T 2>/dev/null || true
taskkill //F //IM gradle.exe //T 2>/dev/null || true
# 仅清理陈旧的 native 锁文件（避免 .lock/.lck 死锁），不整树删除以免触发批量删除保护
find "$GRADLE_USER_HOME/native" \( -name "*.lock" -o -name "*.lck" \) -delete 2>/dev/null || true

echo "===== [2/6] 编译 release（含瞬时守护进程闪断自动重试） ====="
MAX_TRIES=3
n=0
compile_ok=0
while [ $n -lt $MAX_TRIES ]; do
  n=$((n + 1))
  echo "--- 编译尝试 $n/$MAX_TRIES ---"
  # 每次重试前清理残留的 java/gradle 进程与 native 锁，规避瞬时 daemon 通信错误
  taskkill //F //IM java.exe //T 2>/dev/null || true
  taskkill //F //IM gradle.exe //T 2>/dev/null || true
  find "$GRADLE_USER_HOME/native" \( -name "*.lock" -o -name "*.lck" \) -delete 2>/dev/null || true
  if "$GD" --no-daemon --console=plain assembleRelease; then
    compile_ok=1
    break
  fi
  echo "!! assembleRelease 失败（可能为瞬时守护进程错误），准备重试..."
done
if [ $compile_ok -ne 1 ]; then
  echo "!! 编译在 $MAX_TRIES 次尝试后仍失败，请检查网络/JDK/SDK 后手动重跑" >&2
  exit 1
fi

echo "===== [3/6] 定位刚编出的未签名 APK ====="
shopt -s nullglob
cands=("$REL_DIR"/*.apk)
shopt -u nullglob
# 排除已签名产物（带 _signed 或 aligned）
fresh=()
for f in "${cands[@]}"; do
  case "$(basename "$f")" in
    *_signed.apk|aligned.apk) ;;
    *) fresh+=("$f") ;;
  esac
done
if [ "${#fresh[@]}" -ne 1 ]; then
  echo "!! 期望编译产物目录里恰有 1 个未签名 APK，实际找到 ${#fresh[@]} 个:" >&2
  printf '   %s\n' "${cands[@]}" >&2
  exit 1
fi
IN="${fresh[0]}"
echo "    编译产物: $IN"

echo "===== [4/6] zipalign + apksigner (v1/v2/v3, min-sdk 17) ====="
OUT_BASE="$REL_DIR/${NAME}_signed.apk"
export OUT_BASE
rm -f "$OUT_BASE" aligned.tmp.apk
"$SDK/zipalign.exe" -p 4 "$IN" aligned.tmp.apk
"$SDK/apksigner.bat" sign \
  --min-sdk-version 17 \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --ks "$JKS" \
  --ks-key-alias "$ALIAS" \
  --ks-pass pass:"$KS_PASS" \
  --key-pass pass:"$KS_PASS" \
  aligned.tmp.apk
mv -f aligned.tmp.apk "$OUT_BASE"

echo "===== [5/6] 校验闸门 ====="
# 闸门 1: apksigner 三方案
"$SDK/apksigner.bat" verify --verbose --min-sdk-version 17 "$OUT_BASE" > /tmp/verify.txt 2>&1
for s in "v1 scheme (JAR signing): true" "v2 scheme (APK Signature Scheme v2): true" "v3 scheme (APK Signature Scheme v3): true"; do
  grep -qF "$s" /tmp/verify.txt || { echo "!! 签名校验失败: 缺少 [$s]" >&2; exit 1; }
done
echo "    签名 v1/v2/v3: OK"

# 闸门 2: 内嵌 home_config.json 不得含死链（顶层 spider 字段 / oss4liview 域名）
python - <<'PY' || { echo "!! home_config.json 仍含死链" >&2; exit 1; }
import zipfile, sys, os
z = zipfile.ZipFile(os.environ["OUT_BASE"])
d = z.read("assets/home_config.json").decode("utf-8","replace")
bad = ('"spider"' in d) or ("oss4liview" in d)
print("    home_config.json 含死链(spider/oss4liview):", bad)
sys.exit(1 if bad else 0)
PY
echo "    home_config.json 死链检查: OK"

# 闸门 3: dex 必须含修复字符串（首页源兜底 + spider 回退 + 横幅清扫宿主护栏 + 手势提示保留 + 播放体验四项）
python - <<'PY' || { echo "!! dex 缺少修复代码" >&2; exit 1; }
import zipfile, sys, os
z = zipfile.ZipFile(os.environ["OUT_BASE"])
blob = b"".join(z.read(n) for n in z.namelist() if n.startswith("classes") and n.endswith(".dex"))
for needle in [b"homeSiteList", b"SpiderNull", b"getCSP",
               b"containsHostView", "\u4eae\u5ea6".encode("utf-8"), "\u97f3\u91cf".encode("utf-8"),
               # 2026-09-11 播放体验：独立进度存储 / 连播倒计时浮层 / 续播与片头提示
               b"ProgressStore", b"showNextEpisodeTip", b"next_episode_tip",
               b"mNextTipCancelled", b"dismissNextEpisodeTip",
               "\u5df2\u4ece ".encode("utf-8"), "\u5df2\u8df3\u8fc7\u7247\u5934 ".encode("utf-8"),
               # 2026-09-12 旋转按钮随操作栏同显同隐
               b"applyRotateBtnVisibility", b"mBottomVisible",
               # 2026-09-12 播放缓冲水位：按设备档位的缓冲控制器 + 内存压力回落
               b"L1LoadControl", b"onMemoryPressure", "\u7f13\u51b2\u5347\u6863 @ ".encode("utf-8"),
               # 2026-09-18 批一：升档守门改「水位字节＋余量」（原公式与创建时的可用堆自比＝结构上不成立）
               #              + 三处观测：是否接管 / 未升档原因 / 起播放行时的缓冲量 / 起播内核心
               b"RESERVE_BYTES", b"shouldStartPlayback",
               "接管：档位=".encode("utf-8"), "未升档 @ ".encode("utf-8"),
               "起播放行 @ ".encode("utf-8"), "内核=".encode("utf-8"),
               # 2026-09-18 批二：磁盘缓存（默认开可关 + 本机端点强制排除 + 命中率观测）
               b"ExoCacheConfig", b"isCacheableUrl", b"exo_disk_cache",
               b"exo-video-cache", b"onCachedBytesRead", b"L1Cache",
               # 2026-09-18 批三：标准档深水位 96→128MB ＋ 进程冷启动清理上次会话缓存
               #              （改名待删 + 后台删除；创建点与清理点共用 CACHE_DIR_NAME 常量）
               b"purgeStaleOnProcessStart", b"exo-video-cache-old-", b"exo-cache-purge",
               b"CACHE_DIR_NAME", b"STALE_PREFIX",
               "\u7f13\u5b58\u6e05\u7406\u5b8c\u6210\uff1a".encode("utf-8"),
               # 2026-09-12 暂停期缓冲补刷：单一写入点 + 计时起停
               b"updateBufferProgress", b"startBufferTicker", b"stopBufferTicker",
               # 2026-09-12 旋转按钮连点延寿：点旋转后按钮自己多留 5 秒
               b"keepRotateBtnForNextTap", b"clearRotateKeepAlive", b"mRotateKeepAliveEnd",
               # 2026-09-12 详情页片名兜底（源缺 vod_name 时用来源页片名补）
               b"fallbackTitle",
               # 2026-09-13 搜索单轮化：quick 一波到底，线程池仍走统一入口
               b"l1box-search",
               # 2026-09-13 加固 jar 原生初始化串行化 + 代理就绪门禁（真机 SIGABRT 杀进程）
               b"NATIVE_INIT_LOCK", b"GO_PROXY_STARTED",
               b"initializingJarKeys", b"proxyQuietUntil", b"isNativeWindow",
               # 2026-09-14 进程级单飞代理（多加固 jar killall 互杀链）+ /proxy 只路由持有者
               b"PROXY_HOLDER", b"protectedJarKeys", b"proxyHolderKey",
               # 2026-09-14 批次二：装载复用（治 mid==null 跨代失配 + 免重复抽解原生库/重复初始化）
               b"LoadedJar", b"REUSED", b"fileFingerprint", b"adopt",
               # 2026-09-13 中和器替身名合法化（旧版类描述符别名被 dex verifier 整体拒载）
               b"legalMethodNameIdx", b"isLegacyBrokenAlias",
               # 2026-09-14 家族判据改 jar 级汇总（壳+payload 多 dex 盲区）+ 运行时权威探测
               b"FAMILY_TYPES", b"hitsFamilyType", b"probeFamily", b"PROBE_CACHE",
               # 2026-09-14 批次二/三收口：/proxy 按 loader 身份终审 + 持有者身份与 key 同源
               b"FAMILY_LOADERS", b"isFamilyClassLoader", b"isHolderClassLoader",
               b"Signals", b"PROXY_HOLDER_KEY", b"proxyHolderLoader",
               # 2026-09-14 aq：手绑失败交还 jar 自身入口（ap 版 458 次误拒载）+ 代理换届归位
               b"invokeSelfInit", b"occupyProxy", b"nativeInitInProgress",
               b"NATIVE_INIT_IN_PROGRESS",
               # 2026-09-15 播放成功率第一批：净化预取超时 / 净化请求代次 / 回退原始直连
               #   / 起播看门狗 / 取链看门狗 / 本地 HLS 端点（容器一次判对）/ IJK 网络超时 / 埋点
               b"PlayTrace", b"PURIFY_TIMEOUT_MS", b"mPurifyGen",
               b"fallbackToRawUrl", b"mFallbackRawUrl",
               b"mStartWatchdog", b"mFetchWatchdog",
               b"l1.m3u8", b"rw_timeout",
               # 2026-09-15 播放成功率第二批：请求头补全 / 同址换协议 / 本地去 BOM / 手动重试
               #   / 内置播放器候选（含硬软解档）/ Exo 硬解回退 / 每源独立数据源工厂 / 隧道埋点
               b"fillMissingHeaders", b"retryVariantUrl", b"retryWithoutBom", b"stripBom",
               b"retryFromScratch", b"mBomRetried", b"mVariantTried",
               b"getBuiltinItems", b"getPlayerPickItems", b"applyPlayerItem",
               # 2026-09-17 播放器入口改造：外部按钮清单 / 详情页外部播放 / 气泡清理
               #   （getAllPlayerItems 已随长按取消删除；getExternalPlayerItems 于 09-18
               #     并入 getPlayerPickItems —— 两页共用「系统+内置三档+已装外部」一份清单）
               b"switchPlayerItem", b"getPlayingHeaders", b"getSavedProgressOfCurrent",
               b"pausePlayback", b"disableTooltip", b"tvExtPlayer", b"showExternalPlayDialog",
               b"nextBuiltinItem", b"currentPlayerItem", b"findHardDecodeCodecName",
               # 2026-09-18 播放器作用域：默认跟随 / 线路级存档 / 外部仅本次
               b"playWithExternalPlayer", b"markUserPicked", b"isUserPicked",
               b"defaultPlayerType", b"flagPlayerCfgMap", b"getLinePlayerCfg",
               b"setLinePlayerCfg", b"savePlayerCfgToLine", b"adoptLegacyPlayerCfg",
               # 2026-09-18 第二轮（真机测试后）：外部播放器判据 / 详情页勾选当前位置
               #   / 设置面板切换入口 / 本线路无存档不串味 / 诊断埋点
               #   注意 cfgFrom 是局部变量名，不会出现在 dex 字符串池里，不能当校验符号用
               b"isExternalPlayer", b"getCurrentPlayerItem", b"player_pick",
               b"L1Diag",
               # 2026-09-18 第三轮：底部导航气泡（item View 藏在菜单容器里，必须递归清 + 长按短路）
               b"disableTooltipDeep",
               b"createHttpDataSourceFactory", b"setEnableDecoderFallback",
               b"tunneling=on"]:
    ok = needle in blob
    print("    dex 含", needle.decode("utf-8"), ":", ok)
    if not ok:
        sys.exit(1)
# 反向断言：补全轮已按实测结论移除（jar 请求量翻倍会放大加固 jar 原生崩溃）
# 以及：加固 jar 启动的"吞异常版"实现不得复现（启动失败必须能被上层感知并降级）
# 以及：撤除的密度放大器与旧判据不得复现（用户 09-14 拍板：提速只走"不重复加载"）
# 以及：ap 版被真机证否的两类实现不得复现 —— 启发式家族信号（nativeRef+processRef，
#       误把普通 jar 拖进受控路径＝458 次误拒载）、以及进详情页拆搜索池（补全停止）
for gone in [b"startRefill", b"finishRefillByTimeout", b"refillConcurrency", b"l1box-refill",
             b"invokeStartGoProxy", b"startGoProxyOnce",
             b"dexNativePresent", b"searchConcurrencyNonFamily", b"familyJarKeys",
             b"warmUpSiteJars", b"warmUp", b"loadGeneration",
             b"nativeRef", b"processRef",
             b"isProxyHolder", b"resetProxyFlight", b"takeOverIfStale", b"takeoverWanted",
             b"HOLDER_USED", b"markHolderUsed", b"resetHolderUsage", b"forgetStale",
             b"mainLoader",
             # 2026-09-18：旧的外部专用清单已并入 getPlayerPickItems（两页共用一份），不得复现
             b"getExternalPlayerItems",
             # 2026-09-18：按整部影视（不分线路）吃默认值的旧判据不得复现
             b"getAllPlayerItems",
             # 2026-09-15：那个「只置位、从不清零」的自动切播放器开关不得复现 ——
             # 它与兜底阶梯第一档是同一份配置（同一个内核被试两遍），又绕开阶梯自己的账本
             # 导致下一档切回刚失败的内核（来回切），而且第一集之后每一集都不再受它保护。
             # 撤销后由 PlayFragment 的单一兜底链（回退直连 → 内置降级 → 重新解析 → 提示）统一承担。
             b"retriedSwitchPlayer",
             # 2026-09-15 第二批：去 BOM 不再依赖第三方服务（第三方异常＝凭空多一次失败，
             #   且会把整份清单发到外部）
             # 注：「单例数据源工厂字段」刻意不加 dex 级反向断言 —— 那个字段名 Exo 官方
             #   okhttp 扩展内部也在用（实测命中在 mergeExtDexRelease/classes.dex＝外部库），
             #   dex 级比对会误报；它由第 5 道源码级闸门（Y 节）按文件精确拦。
             b"jundie", b"unBom"]:
    if gone in blob:
        print("    !! dex 仍含已移除的代码:", gone.decode("utf-8"))
        sys.exit(1)
print("    dex 补全轮代码已清除: OK")
PY
echo "    dex 修复代码检查: OK"

# 闸门 4: 兼容性强制校验（uses-feature 空 / 版本口径 / 屏幕与密度覆盖）
bash "$WS/check_l1box_compat.sh" "$OUT_BASE" || { echo "!! 兼容性校验未通过，中止出包" >&2; exit 1; }

# 闸门 5: 源码级回归闸门（把历次已修 bug 变成静态断言，防回退）
bash "$WS/check_l1box_regressions.sh" || { echo "!! 回归闸门未通过，中止出包" >&2; exit 1; }

echo "===== [6/6] 落地到根目录 ====="
cp -f "$OUT_BASE" "$ROOT/$NAME.apk"
echo ">>> 完成: $ROOT/$NAME.apk"
ls -la "$ROOT/$NAME.apk"

echo "===== [7/7] 真机安装（尽力而为，无设备则跳过） ====="
ADB="$ANDROID_HOME/platform-tools/adb.exe"
if [ -f "$ADB" ]; then
  if "$ADB" get-state >/dev/null 2>&1; then
    echo "    检测到设备，执行 adb install -r -d ..."
    # 先 force-stop 再装：避免旧版前台 Service 占用导致 INSTALL_FAILED (-99)
    "$ADB" shell am force-stop com.github.tvbox.osc >/dev/null 2>&1
    "$ADB" install -r -d "$ROOT/$NAME.apk" && echo "    安装成功" || echo "    !! 安装失败（见上方 adb 输出），可手动安装"
  else
    echo "    未连接设备，跳过 adb install（APK 已落地: $ROOT/$NAME.apk）"
  fi
else
  echo "    未找到 adb，跳过（APK 已落地: $ROOT/$NAME.apk）"
fi
echo "===== ALL DONE ====="
