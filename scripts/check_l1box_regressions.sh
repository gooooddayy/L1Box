#!/usr/bin/env bash
# check_l1box_regressions.sh — 源码级回归闸门（防历次已修 bug 复现）
#
# 全部为静态检查：不联网、不依赖设备，纯 grep 断言源文件是否还带着"修复痕迹"。
# 每一条都对应一次真实事故，改动代码时只要把修复改没了，构建立刻失败。
# 用法: bash check_l1box_regressions.sh
#
# 新增规则时请写清「日期 + 症状 + 一旦回退会发生什么」，否则后人不知道能不能删。

set -uo pipefail

WS="<工作区>"
ROOT="$WS/FreeBox-src"
FAIL=0

must() { # must <相对路径> <正则> <说明>
  local f="$ROOT/$1"
  if [ ! -f "$f" ]; then echo "    !!  $3（文件缺失: $1）"; FAIL=1; return; fi
  if grep -qE "$2" "$f"; then echo "    OK  $3"; else echo "    !!  $3（未命中 /$2/ 于 $1）"; FAIL=1; fi
}

mustnot() { # mustnot <相对路径> <正则> <说明>
  local f="$ROOT/$1"
  if [ ! -f "$f" ]; then echo "    !!  $3（文件缺失: $1）"; FAIL=1; return; fi
  if grep -qE "$2" "$f"; then
    echo "    !!  $3（出现了不该有的写法）:"
    grep -nE "$2" "$f" | head -3 | sed 's/^/        /'
    FAIL=1
  else echo "    OK  $3"; fi
}

count() { # count <相对路径> <正则> <最少次数> <说明>
  local f="$ROOT/$1" n
  if [ ! -f "$f" ]; then echo "    !!  $4（文件缺失: $1）"; FAIL=1; return; fi
  n=$(grep -cE "$2" "$f" || true)
  if [ "$n" -ge "$3" ]; then echo "    OK  $4（$n 处）"; else echo "    !!  $4（只找到 $n 处，需 ≥$3）"; FAIL=1; fi
}

SW=app/src/main/java/com/github/tvbox/osc/util/ToastSweeper.java
BC=app/src/main/java/com/github/tvbox/osc/player/controller/BaseController.java
VC=app/src/main/java/com/github/tvbox/osc/player/controller/VodController.java
PH=app/src/main/java/com/github/tvbox/osc/util/PlayerHelper.java
EX=player/src/main/java/xyz/doikki/videoplayer/exo/ExoMediaSourceHelper.java
PS=app/src/main/java/com/github/tvbox/osc/util/ProgressStore.java
PF=app/src/main/java/com/github/tvbox/osc/ui/fragment/PlayFragment.java
OG=app/src/main/java/com/github/tvbox/osc/util/OkGoHelper.java
IJ=app/src/main/java/com/github/tvbox/osc/player/IjkMediaPlayer.java
RS=app/src/main/java/com/github/tvbox/osc/server/RemoteServer.java
PT=app/src/main/java/com/github/tvbox/osc/util/PlayTrace.java
ECC=player/src/main/java/xyz/doikki/videoplayer/exo/ExoCacheConfig.java
EPL=player/src/main/java/xyz/doikki/videoplayer/exo/ExoMediaPlayer.java

echo "===== A. 横幅清扫（2026-09-11：手势提示「亮度50%」被当成横幅 → 整个播放器被打成 0 尺寸+INVISIBLE 永久保持 = 黑屏/按钮全没/点不动） ====="
mustnot "$SW" 'Pattern\.compile\([^)]*%\$' "横幅文本特征不得再含「以 % 结尾即横幅」的宽泛规则"
must    "$SW" 'containsHostView' "宿主自有布局识别函数 containsHostView 存在"
must    "$SW" 'if \(containsHostView\(c, 0\)\)' "树内扫描：宿主布局只下探、绝不整体隐藏"
must    "$SW" 'if \(containsHostView\(g, 0\)\) return false;' "add 瞬间判定同样放过宿主布局"
must    "$SW" 'if \(containsHostView\(c, 0\)\) return;' "decor 子 View 路径同样放过宿主布局"
must    "$SW" 'com\.github\.tvbox\.osc\.' "宿主类前缀白名单在列"
must    "$SW" 'sweepWindows\(\);' "窗口级清扫仍在帧循环里（每帧，防弹窗闪现）"

echo "===== B. 手势亮度/音量提示（2026-09-11：需求＝上下滑正常显示「亮度xx%」「音量xx%」，但不得有额外告警） ====="
must    "$BC" 'msg\.obj = "亮度" \+ percent \+ "%"' "上下滑正常显示「亮度xx%」提示"
must    "$BC" 'msg\.obj = "音量" \+ percent \+ "%"' "上下滑正常显示「音量xx%」提示"
must    "$BC" 'mSlideInfo\.setVisibility\(VISIBLE\)' "提示浮层仍会显示（不得整段删掉）"
mustnot "$BC" 'slideBaseHeight|MIN_GESTURE_BRIGHTNESS|isValidSlide' "不得重新引入手势增益/亮度下限的改写（2026-09-11 已判定为误判：真因是横幅清扫误隐藏播放器）"
mustnot "$BC" 'ToastUtils|Toast\.makeText' "手势路径不得新增任何额外提示（用户明确：只要正常数值提示）"

echo "===== C. 播放器兼容（2026-09-10 s 版；2026-09-15 随 X 节更新口径） ====="
mustnot "$EX" 'setExtractorsFactory' "Exo 2.18 无 setExtractorsFactory（须走构造函数第 2 参）"
must    "$VC" 'switchToNextInternalPlayer' "内置内核切换工具存在（只在内置内核间切，绝不唤起 MX/VLC）"
must    "$VC" 'int nextType = \(playerType == 1\) \? 2 : 1' "该工具的切换范围写死在 1(IJK)/2(Exo) 两个内置内核，不受候选列表影响"
must    "$PH" 'buildCompatFallbackPlan' "兼容兜底阶梯存在"
must    "$PH" 'Hawk\.get\(HawkConfig\.PLAY_TYPE, 2\)' "默认内核=Exo(2)，失败自动切 IJK 软解"

echo "===== D. 首页源池与缓存门槛（2026-09-01 首页卡死 / jar 永不更新） ====="
must    "app/src/main/java/com/github/tvbox/osc/api/ApiConfig.java" 'private final LinkedHashMap<String, SourceBean> homeSiteList' "首页源池 homeSiteList 与订阅池 sourceBeanList 仍是两套独立容器"
must    "app/src/main/java/com/github/tvbox/osc/api/ApiConfig.java" 'synchronized \(homeSiteList\)' "首页源池读写有同步保护"
must    "app/src/main/java/com/github/tvbox/osc/api/ApiConfig.java" 'useCache \|\| !md5\.isEmpty\(\)' "jar 缓存门槛与上游逐字一致（写反则下拉刷新不更新 jar）"
must    "app/src/main/java/com/github/tvbox/osc/ui/fragment/HomeFragment.kt" 'private fun cancelHomeWatchdog' "看门狗可取消（onPause 只取消看门狗、不掐断加载链路）"
must    "app/src/main/java/com/github/tvbox/osc/ui/fragment/HomeFragment.kt" 'postDelayed\(mHomeWatchdog, 30000\)' "看门狗 30s 覆盖 initData+reloadSitePool"
must    "app/src/main/java/com/github/tvbox/osc/base/App.java" 'StartupGuard' "启动保护 StartupGuard 已挂载"

echo "===== E. 体积 / 权限 / 多设备基线 ====="
mustnot "app/build.gradle" '^[[:space:]]*(implementation|api|compileOnly|runtimeOnly)[^/]*conscrypt' "不得重新引入 conscrypt 依赖（-3.2MB，Android 上为死引用）"
mustnot "app/build.gradle" 'minifyEnabled true' "minify 保持关闭（资源名混淆，开 minify 会出运行期找不到资源）"
must    "app/src/main/java/com/github/tvbox/osc/base/BaseActivity.java" 'AutoSize\.autoConvertDensity' "旋转/分屏重算 AutoSize density"
must    "app/src/main/java/com/github/tvbox/osc/server/RemoteServer.java" 'ALLOW_LAN_FILE_MANAGE' "9978 局域网文件管理门禁保留（默认仅本机）"

echo "===== F. 投屏面板（2026-09-11 用户明确要求精简） ====="
mustnot "app/src/main/res/layout/dialog_cast.xml" 'btn_prev|btn_pause|btn_next|@\+id/seekbar' "投屏面板只留「停止/关闭」，不得再出现切集/暂停/进度条"
must    "app/src/main/res/layout/dialog_cast.xml" '两台设备要在同一个WiFi下投屏' "无设备时的同网提示文案在列"

echo "===== G. ShadowLayout ripple 必配背景（否则 NPE 闪退） ====="
_viol=0
while IFS= read -r f; do
  if ! grep -q 'hl_layoutBackground' "$f"; then
    echo "    !!  $(basename "$f") 用了 hl_shapeMode=\"ripple\" 却缺 hl_layoutBackground（会 NPE）"
    _viol=1; FAIL=1
  fi
done < <(grep -rl 'hl_shapeMode="ripple"' "$ROOT/app/src/main/res/layout" 2>/dev/null)
[ $_viol -eq 0 ] && echo "    OK  所有 ripple 型 ShadowLayout 都配了 hl_layoutBackground"

echo "===== H. 播放体验：断点续播 / 连播倒计时 / 缓冲色 / 片头提示（2026-09-11） ====="
must    "$PS" 'MAX_RECORDS = 300' "进度记录有上限并会淘汰最旧（避免无限增长）"
must    "$PF" 'ProgressStore\.save' "播放进度写入独立存储"
must    "$PF" 'String progressKey = mVodInfo\.sourceKey' "进度键仍由 源+剧ID+线路+集数 构成（含易变播放地址则永远对不上）"
must    "$PF" 'progress >= dur \* 95 / 100' "进度到 95% 视为看完：清记录，下次从头播（否则一进去就跳片尾）"
must    "$PF" 'pos < 10000' "续播位置不足 10 秒不提示（刚开头弹提示是打扰）"
must    "$PF" '已从 ' "断点续播有「已从 XX:XX 继续播放」提示"
must    "$PF" '已跳过片头 ' "片头跳过有「已跳过片头 XX 秒」提示"
mustnot "$PF" 'CacheManager\.save\(MD5\.string2MD5\(url\)' "播放进度不得写回 cache 表（与历史/收藏同库，加字段要换库文件＝历史全丢）"
must    "$SW" '已跳过片头 |已从 ' "续播/片头提示已进 Toast 白名单（否则被清扫器当 jar 弹窗移除，用户看不到）"
must    "$VC" 'boolean hasNext\(\);' "控制器可判断后面还有没有剧集（最后一集要停在最后一帧）"
must    "$VC" 'onWindowVisibilityChanged' "退后台必须停表（View 不会 detach，否则在后台自己跳集）"
must    "$VC" 'cancelNextEpisodeTip\(\);' "倒计时可取消"
mustnot "app/src/main/res/layout/player_vod_control_view.xml" 'android:text="正在' "新浮层文案不得以「正在」开头（命中横幅清扫特征 → 整块被隐藏）"
must    "app/src/main/res/values/colors.xml" 'light_gray_color">#8C8C8C' "缓冲二级色为中间灰（原 #e6e6e6 与白色进度段同色，3dp 细条上看不出缓冲）"
# 播完分支跨行校验：STATE_PLAYBACK_COMPLETED 之后 3 行内必须出现 showNextEpisodeTip
if grep -A3 'STATE_PLAYBACK_COMPLETED:' "$ROOT/$VC" | grep -q 'showNextEpisodeTip'; then
  echo "    OK  播完不再直接跳集，改走倒计时浮层"
else
  echo "    !!  播完分支未走 showNextEpisodeTip（直接跳集会让倒计时失效）"; FAIL=1
fi
must    "$VC" 'mNextTipCancelled = true;' "「取消」须落成本集标记：片尾跳过点取消后视频仍会播到真结尾并再次触发播放完成，无标记会再弹一次且静默跳集＝取消无效"
must    "$VC" 'if \(mNextTipCancelled\) return;' "弹浮层前必须先看「本集已取消」标记"
must    "$VC" 'mNextTipCancelled = false;' "标记须每集起播复位（否则取消一次后整季都不再提示连播）"
mustnot "$VC" 'next_episode_cancel\)\.setOnClickListener\(v -> cancelNextEpisodeTip\(\)\)' "取消按钮不得直接调 cancelNextEpisodeTip（那样取消仅收起浮层、到期仍会自动跳集）"
# 旋转按钮必须与操作栏同显同隐（2026-09-12：全屏闲置播放时上中下栏都淡出了，旋转按钮仍独自留在画面左侧）
# 锚点留足余量（-A14）：块内插行不算回归，别让它误报（2026-09-12 在 1002 里加一行 clearRotateKeepAlive 就误报过一次）
if grep -A14 'case 1002:' "$ROOT/$VC" | grep -q 'applyRotateBtnVisibility(true)'; then
  echo "    OK  显示操作栏分支已联动旋转按钮"
else
  echo "    !!  显示操作栏分支(1002)未联动旋转按钮（点屏幕唤出操作栏时旋转按钮不出现）"; FAIL=1
fi
if grep -A14 'case 1003:' "$ROOT/$VC" | grep -q 'applyRotateBtnVisibility(false)'; then
  echo "    OK  隐藏操作栏分支已联动旋转按钮"
else
  echo "    !!  隐藏操作栏分支(1003)未联动旋转按钮（闲置播放时按钮会残留在画面上）"; FAIL=1
fi
mustnot "$VC" 'mRotateBtn\.setVisibility\(mInPip' "旋转按钮显隐不得绕过统一方法（裸 setVisibility(VISIBLE) 清不掉淡出残留的 alpha=0，会变成看不见但能点的隐形控件）"
if grep -A25 'public void changedLandscape' "$ROOT/$VC" | grep -q 'mRotateBtn\.setVisibility'; then
  echo "    !!  changedLandscape 里仍有裸 setVisibility（残留 alpha=0 会让按钮隐形却可点）"; FAIL=1
else
  echo "    OK  changedLandscape 已走统一显隐方法"
fi
if grep -A10 'public void onPipModeChanged' "$ROOT/$VC" | grep -q 'mRotateBtn\.setVisibility'; then
  echo "    !!  onPipModeChanged 里仍有裸 setVisibility"; FAIL=1
else
  echo "    OK  onPipModeChanged 已走统一显隐方法"
fi
must    "$VC" 'show && mIsFullScreen && !mInPip' "旋转按钮仅在「全屏且非画中画」且操作栏可见时显示"
must    "$VC" 'alpha\(mRotateLocked \? 0\.5f : 1\.0f\)' "旋转按钮淡入须按锁定态取 alpha（锁定＝半透明提示，不能被动画覆盖成 1.0）"

echo "===== I. 缓冲水位（2026-09-12：按档位加深，但必须「不动低端机、可回落、参数不越界」） ====="
LC=player/src/main/java/xyz/doikki/videoplayer/exo/L1LoadControl.java
EM=player/src/main/java/xyz/doikki/videoplayer/exo/ExoMediaPlayer.java
EP=app/src/main/java/com/github/tvbox/osc/player/EXOmPlayer.java
DP=app/src/main/java/com/github/tvbox/osc/util/DeviceProfile.java
must    "$LC" 'class L1LoadControl extends DefaultLoadControl' "缓冲控制器在（继承默认控制器，只额外加一道闸门）"
must    "$LC" 'STAGE1_US = 50_000_000L' "阶段一水位仍是 50 秒（= Exo 默认；改大等于前 10 秒也激进，误点即走会多下流量）"
must    "$LC" 'ESCALATE_AT_US = 10_000_000L' "升档点仍是观看满 10 秒"
must    "$LC" 'playbackPositionUs < ESCALATE_AT_US' "升档判断读播放位置（不依赖 Activity/定时器，切集后自动归零重走保守）"
count   "$LC" 'escalated = false;' 2 "复位升档状态须有两条路径（位置回到 10 秒内 + onStopped）——只靠 onStopped 会让第二集起播直接吃深水位"
must    "$LC" 'deepMs, deepMs, START_PLAYBACK_MS, AFTER_REBUFFER_MS' "构造参数顺序：min=max=深水位，门槛取常量（Exo 构造期断言 min>=start，写反即抛 IllegalArgumentException）"
must    "$LC" 'START_PLAYBACK_MS = 1500' "起播门槛 1500ms（退回 2500 会让首帧多等约 1 秒）"
must    "$LC" 'AFTER_REBUFFER_MS = 5000' "重缓冲门槛保持 Exo 默认 5000ms（调小会「恢复快但反复卡」）"
must    "$LC" 'onMemoryPressure' "内存压力回落入口存在"
must    "$LC" 'setTargetBufferSize' "压力时调小分配器目标尺寸（触发 trim 归还空闲块）"
# 2026-09-18：原断言写死 'availBytes() >= (long) deepBytes * AVAIL_FACTOR' —— 把那条自相矛盾的公式
# 锁死了（公式等价于要求「运行时可用堆 ≥ 创建时可用堆」，播满 10 秒后堆必然更低 ⇒ 结构上不成立；
# 实测 15 次会话够条件 6 次只升档 4 次且失败无日志）。改写为断语义，不再锁字面公式。
must    "$LC" 'deepBytes \+ RESERVE_BYTES, MIN_AVAIL_BYTES' "升档守门＝可用堆 ≥ 水位字节＋余量（不再与创建时的可用堆自比）"
# 标准档深水位 128MB（用户 09-18 拍板，目标＝「拖到没加载过的位置也不卡」）。
# 依据是 Exo 自己的默认常量：DEFAULT_MUXED_BUFFER_SIZE=137.6MB —— 改造前的 96MB 反而**低于官方默认**，
# 128MB 仍在官方默认之下，所以这不是"激进的怪招"，而是常规量级。
# 注意它只是"申请值"，create() 里还会被 maxMemory()/4 夹取（低堆设备自动收敛，不会硬顶）。
must    "$LC" '128 << 20' "标准档深水位 128MB（低于 Exo 官方默认 137.6MB）"
must    "$LC" 'maxMemory\(\) / 4' "字节闸门按堆上限比例夹取（低堆设备自动收敛，不硬顶）"
mustnot "$LC" 'static final long AVAIL_FACTOR' "不许把自相矛盾的 AVAIL_FACTOR 加回来"
must    "$LC" '未升档 @ ' "升档失败必须留观测（否则「加深有没有生效」只能靠猜）"
must    "$LC" '接管：档位=' "必须记录是否接管（含档位/门槛/水位/堆大小）"
must    "$LC" 'shouldStartPlayback' "起播放行时的缓冲量必须有观测（判断 HLS 分片粒度下门槛是否真起作用）"
must    "$PF" '内核=' "起播埋点必须带内核名（水位与门槛只对 Exo 生效）"
mustnot "$LC" 'Process\.|System\.exit' "缓冲控制器不得含任何进程级操作"
must    "$EM" 'mLoadControl = createLoadControl\(\);' "控制器经工厂创建（策略可替换）"
mustnot "$EM" 'mLoadControl = new DefaultLoadControl\(\)' "不得改回硬编码 new（会让按档位的缓冲策略整体失效）"
must    "$EP" 'L1LoadControl\.create\(DeviceProfile\.bufferTier\(\)\)' "播放器按设备档位取缓冲控制器"
must    "$DP" 'totalMemMb' "设备分档采集了物理内存（getMemoryClass 识别不出 4GB 低端机）"
must    "$DP" 'computeBufferTier' "缓冲档位计算函数存在"
count   "app/src/main/java/com/github/tvbox/osc/base/App.java" 'L1LoadControl\.onMemoryPressure\(\);' 2 "内存压力两处回调都要回落缓冲水位（onTrimMemory 前台等级 + onLowMemory）"

echo "===== J. 缓冲二级色暂停补刷（2026-09-12：Exo 暂停只停渲染不停下载，界面停在暂停那一刻，恢复播放时灰条突然跳） ====="
must    "$VC" 'case 1006:' "暂停补刷消息存在"
must    "$VC" 'videoPlayState != VideoView.STATE_PAUSED \|\| getWindowVisibility\(\) != VISIBLE' "补刷须同时满足「暂停态 + 窗口可见」——只靠状态回调会踩「窗口先不可见、暂停回调后到」的顺序在后台空转"
must    "$VC" 'if \(mSeekBar == null \|\| mIsDragging\) return;' "拖动进度条时不写二级进度（与进度表同一避让条件）"
# 唯一写入点：缓冲百分比只读一次，读数留在 updateBufferProgress 里；复制回进度表就会出现在两处
_n=$(grep -cE 'getBufferedPercentage' "$ROOT/$VC")
if [ "$_n" -eq 1 ]; then
  echo "    OK  缓冲百分比只有一处读取（播放中与暂停期共用同一写入点，不会两处各写一次）"
else
  echo "    !!  缓冲百分比读取 ${_n} 处，应为 1 处（复制回 setProgress 会导致两条刷新路径各写一次）"; FAIL=1
fi
if grep -A3 'case VideoView.STATE_PAUSED:' "$ROOT/$VC" | grep -q 'startBufferTicker'; then
  echo "    OK  进入暂停态启动补刷"
else
  echo "    !!  暂停分支未启动补刷（灰条会停在暂停那一刻）"; FAIL=1
fi
if grep -A4 'case VideoView.STATE_PLAYING:' "$ROOT/$VC" | grep -q 'stopBufferTicker'; then
  echo "    OK  回到播放态停掉补刷（进度表已接管，避免双写）"
else
  echo "    !!  播放分支未停补刷（两条刷新路径会同时写 seekBar）"; FAIL=1
fi
count   "$VC" 'stopBufferTicker\(\);' 6 "六处停表点齐全：IDLE / PLAYING / ERROR / 播放完成 / onDetachedFromWindow / 窗口不可见"
if grep -A8 'protected void onWindowVisibilityChanged' "$ROOT/$VC" | grep -q 'stopBufferTicker'; then
  echo "    OK  退后台（View 不 detach）也会停补刷"
else
  echo "    !!  onWindowVisibilityChanged 未停补刷（后台每秒空转）"; FAIL=1
fi
if grep -A6 'protected void onDetachedFromWindow' "$ROOT/$VC" | grep -q 'stopBufferTicker'; then
  echo "    OK  退出播放页会停补刷"
else
  echo "    !!  onDetachedFromWindow 未停补刷（退出后 View 被持有时会空转）"; FAIL=1
fi
must    "$VC" 'sendEmptyMessageDelayed\(1006, 1000\)' "补刷间隔为 1000ms（与进度表同频，不额外加压）"
must    "$VC" 'startBufferTicker\(\); // 回前台且仍是暂停态' "回前台若仍是暂停态会恢复补刷"

echo "===== K. 旋转按钮连点延寿（2026-09-12：点旋转后操作栏立刻收起，旋转按钮接入操作栏显隐后被一起带走 → 想点第二档必须重新唤出操作栏。上游靠「全屏常驻」天然规避，此处补回这个例外） ====="
must    "$VC" '!show && mRotateKeepAlive && !mBottomVisible && mIsFullScreen && !mInPip' "延寿仅在全屏、非画中画、操作栏确实收起时生效（否则退出全屏/画中画会把按钮留下）"
must    "$VC" 'postDelayed\(mRotateKeepAliveEnd, dismissTimeOperationBar\)' "延寿时长与操作栏一致（dismissTimeOperationBar）"
# 续期：延寿分支内先摘旧回调再重挂，否则连点时第二下还没点就超时收起了
if grep -A2 'if (!show && mRotateKeepAlive' "$ROOT/$VC" | grep -q 'removeCallbacks(mRotateKeepAliveEnd)'; then
  echo "    OK  延寿可续期（每次点击重新计时）"
else
  echo "    !!  延寿未续期：连点第二下之前计时就到点了"; FAIL=1
fi
must    "$VC" 'if \(mBottomVisible\) return; // 操作栏已被唤回' "倒计时到点时若操作栏已回来，按钮交还操作栏管（避免操作栏可见而按钮被收走）"
count   "$VC" 'keepRotateBtnForNextTap\(\);' 3 "短按 / 锁定提示后 / 长按 三处都要延寿（漏一处就会点完立刻消失）"
# 点击监听必须改走延寿；仍用 hideBottom 就会把它自己一起带走（上游敢这么写是因为按钮当时常驻）
if grep -A16 'mRotateBtn\.setOnClickListener' "$ROOT/$VC" | grep -q 'hideBottom();'; then
  echo "    !!  旋转按钮监听仍在直接 hideBottom（会把它自己一起带走，第二档点不到）"; FAIL=1
else
  echo "    OK  旋转按钮监听不再直接收起操作栏，改走延寿"
fi
count   "$VC" 'clearRotateKeepAlive\(\);' 5 "五处作废点齐全：1002 唤回 / changedLandscape / onPipModeChanged / onDetachedFromWindow / 窗口不可见"
if grep -A8 'protected void onDetachedFromWindow' "$ROOT/$VC" | grep -q 'clearRotateKeepAlive'; then
  echo "    OK  退出播放页会清掉延寿计时器"
else
  echo "    !!  onDetachedFromWindow 未清延寿计时器（View 被持有时会在后台空转）"; FAIL=1
fi
if grep -A12 'protected void onWindowVisibilityChanged' "$ROOT/$VC" | grep -q 'clearRotateKeepAlive'; then
  echo "    OK  退后台会清掉延寿计时器并收起按钮"
else
  echo "    !!  onWindowVisibilityChanged 未清延寿计时器（后台空转）"; FAIL=1
fi

echo "===== L. 搜索回退 ab 口径（2026-09-13 ak 定稿：减少崩溃是必须的，加速是锦上添花。16 并发+quick 把加固家族 jar 初始化密度顶上去，兑现 killall 互杀链） ====="
FS=app/src/main/java/com/github/tvbox/osc/ui/activity/FastSearchActivity.kt
DA=app/src/main/java/com/github/tvbox/osc/ui/activity/DetailActivity.java
CA=app/src/main/java/com/github/tvbox/osc/ui/activity/CollectActivity.kt
HA=app/src/main/java/com/github/tvbox/osc/ui/activity/HistoryActivity.kt
SV=app/src/main/java/com/github/tvbox/osc/viewmodel/SourceViewModel.java
must    "$FS" 'getSearch\(key, searchTitle\)$' "搜索走两参调用（quick=false，与 ab 逐字一致；quick=true 是 ae 遗留的崩溃密度推手）"
count   "$FS" 'getSearch\(key, searchTitle, ' 0 "三参 getSearch 调用不得复现（true 是 ae 遗留，false 也无需显式传）"
must    "$SV" 'sp\.searchContent\(wd, quick\)' "搜索请求透传 quick 开关（写死 false 会把提速整体退化）"
must    "$FS" 'bucket\.any \{ it\.id == video\.id \}' "去重仅限同站点+影片ID（跨源同名片/不同版本/不同站点一律保留，绝不合并）"
must    "$FS" 'setNewData\(ArrayList\(list\)\)' "过滤视图必须复制桶（不与 adapter 共享引用）"
must    "$FS" 'if \(firstWaveFinished\) return' "收尾幂等（正常归零与看门狗超时两条路径只能生效一次）"
must    "$FS" 'else if \(pendingResults\.isNotEmpty\(\)\)' "收尾后的迟到结果仍要落地（只落地，不再判空态）"
must    "$FS" 'isFilterMode = true' "进入过滤视图要置位（上游从未赋值的老 bug）"
must    "$DP" 'Math\.min\(cores, 6\) : 10' "搜索并发回退 ab 口径：低端 min(核数,6)、其余 10（ak 定稿：崩溃开关是初始化密度，慢速摊开时序；机制封堵在 O/N 节防线，两者叠加）"
mustnot "$FS" 'refill|Refill' "补全轮代码不得复现（jar 请求量翻倍会放大加固 jar 原生崩溃）"
mustnot "$DP" 'refillConcurrency' "补全轮并发配置不得复现"

# M 节：2026-09-12 详情页片名兜底
# 症状：部分源（如「天堂」等 jar 源）detailContent 不回 vod_name → 详情页标题显示"暂无信息"，
#       且空名会随 VodInfo 存进历史/收藏。若回退，此类源永远显示占位文案。
must    "$DA" 'bundle\.getString\("title"\)' "详情页要读取来源页带来的片名兜底"
must    "$DA" 'TextUtils\.isEmpty\(mVideo\.name\) && !TextUtils\.isEmpty\(fallbackTitle\)' "仅源详情缺名时才补（绝不覆盖源返回的真名）"
must    "$DA" 'fallbackTitle = video\.name;' "快捷换线重载详情要同步更新兜底名（防新源缺名时串显上一个源的名字）"
count   "$DA" 'bundle\.putString\("title", ' 0 "详情页自身不再向外跳，不应有 putString(title" # 详情页无此调用为正常
must    "$FS" 'bundle\.putString\("title", video\.name\)' "搜索主列表+过滤列表两个入口都要传片名"
count   "$FS" 'bundle\.putString\("title", video\.name\)' 2 "搜索页两处入口都传片名"
must    "$CA" 'bundle\.putString\("title", vodInfo\.name\)' "收藏入口传片名"
must    "$HA" 'bundle\.putString\("title", vodInfo\.name\)' "历史入口传片名"

echo "===== N. 加固 jar 原生初始化串行化 + 代理就绪门禁（2026-09-13 真机 SIGABRT 杀进程） ====="
# 症状：搜索时整个进程被系统杀掉（Fatal signal 6 / JNI DETECTED ERROR: obj == null in call to
#       CallObjectMethod from DexNative.proxyInvoke，线程名 NanoHttpd Reque）。
# logcat 实证：pool-19/29/40/48 多个线程池线程同时进入 jar 内部的 GoProxyManager.startBlockingForInit，
#       各自 exec 一份 new_go_proxy_wex 监听不同随机端口（38781、21132…），jar 记录的端口被互相覆盖
#       → waitHealth 连续 12 次去查 127.0.0.1:31830（从未被监听的端口）→ 代理对象为 null
#       → 此后任意一个 /proxy 请求进来即 abort。
# 关键约束：abort 由 native 发起，是进程级死亡而非异常，Java 层 catch(Throwable) 拿不到控制权。
#       所以防线只能是"事前串行 + 事前门禁"，绝不能改成 try-catch 兜。
# 回退后果：并发一上来就随机杀进程，且没有任何 Java 层手段能兜住。
PI=app/src/main/java/com/github/catvod/crawler/ProtectedInitJar.java
JL=app/src/main/java/com/github/catvod/crawler/JarLoader.java
must    "$PI" 'synchronized \(NATIVE_INIT_LOCK\)' "原生初始化必须持进程级锁（按 jarKey 分锁管不住 jar 之间的并发）"
if grep -A6 'synchronized (NATIVE_INIT_LOCK)' "$ROOT/$PI" | grep -q 'bindDexLoader'; then
  echo "    OK  DexNative.getLoader 也在锁内（本次有 loader 与端口两处竞态）"
else
  echo "    !!  bindDexLoader 被挪到锁外：getLoader 并发会互相覆盖当前 loader"; FAIL=1
fi
must    "$PI" 'private static volatile boolean GO_PROXY_STARTED' "单飞闸门必须进程级（旧版按 jar Class 记账，每 jar 一份，挡不住 killall 互杀链）"
must    "$PI" 'GO_PROXY_STARTED && bindDexLoader\(clz, init\)' "有代理可承接才手绑：本 jar 只绑定不重启（再启动=killall 杀掉先启动的）"
must    "$PI" 'PROXY_HOLDER = clz' "启动成功必须记录代理持有者（/proxy 唯一准调用的加固 jar）"
must    "$PI" 'static boolean isHolderClassLoader' "身份终审要能按 ClassLoader 判持有者（key 账本会被作废，身份不会）"
must    "$PI" 'static void occupyProxy' "换届要有唯一入口（身份与 key 同锁更新），且必须与 init 同锁"
if grep -A4 'static void occupyProxy' "$ROOT/$PI" | grep -q 'PROXY_HOLDER_KEY = key;'; then
  echo "    OK  occupyProxy 在一处同时更新身份与 key（不再有第二份会各自过期的账本）"
else
  echo "    !!  occupyProxy 未同时更新身份与 key：双份状态必然制造账本空洞"; FAIL=1
fi
must    "$PI" '加固 jar 初始化失败（手绑与 jar 自身入口都不可用），已降级其站点' "两条路都失败才降级（旧版吞异常=看着能用、一请求就 abort；ap 版直接拒载=站点成片消失）"
mustnot "$PI" 'private void invokeStartGoProxy' "旧的「吞异常版」启动方法不得复现"
must    "$JL" 'initializingJarKeys\.add\(key\)' "加固 jar 初始化窗口要登记"
must    "$JL" 'initializingJarKeys\.remove\(key\)' "初始化窗口结束要摘除（与 add 成对，防残留永久拒服务）"
must    "$JL" 'proxyQuietUntil\.put\(key, SystemClock\.elapsedRealtime\(\) \+ PROXY_QUIET_MS\)' "init 返回后要记静默期（代理在 init 之后才真正监听端口）"
must    "$JL" 'PROXY_QUIET_MS = 800L' "静默期取实测值 800ms（勿回退 2000ms：那是按错误的 1.4~1.6 秒估算，过度保护会白丢首轮结果）"
must    "$JL" 'if \(isNativeWindow\(key\)\) return proxyNotReady\(\)' "未就绪窗口内不碰 native，直接返回 503"
must    "$JL" 'private boolean isNativeWindow' "门禁判据集中在 isNativeWindow"
must    "$JL" 'protectedJarKeys\.contains\(key\) && !key\.equals\(ProtectedInitJar\.proxyHolderKey\(\)\)' "非持有者的加固 jar 一律事前 503；持有者身份与 key 同源（不再有第二份会各自过期的账本）"
# 门禁必须排在真正下发 native 之前，否则等于没加
_gate_line=$(grep -n 'isNativeWindow(key)' "$ROOT/$JL" | head -1 | cut -d: -f1)
_call_line=$(grep -n 'proxyMethods\.get(key)' "$ROOT/$JL" | head -1 | cut -d: -f1)
if [ -n "$_gate_line" ] && [ -n "$_call_line" ] && [ "$_gate_line" -lt "$_call_line" ]; then
  echo "    OK  门禁排在 proxyMethods.get 之前（先拦后调）"
else
  echo "    !!  门禁排在实际调用之后，起不到拦截作用"; FAIL=1
fi

echo "===== O. 中和器替身名合法性（2026-09-13 旧版改指类描述符，dex verifier 加载即拒整个 dex = jar 永久不可用） ====="
# 症状：Invalid method name: 'Landroid/os/Process;' → Failed to open dex files → ClassNotFoundException: Init
#       （ai 版真机日志 13 次；坏 jar 就地写回磁盘，每个新进程都再失败一次）
KN=app/src/main/java/com/github/catvod/crawler/JarKillNeutralizer.java
must    "$KN" 'legalMethodNameIdx' "替身名必须是字符串表里已存在的合法方法名（verifier 放行，运行时按自杀方法签名解析仍必失败）"
mustnot "$KN" 'putU32\(d, off \+ 4, descIdx\)' "旧版「name_idx 改指类描述符」写法不得复现"
must    "$KN" 'isLegacyBrokenAlias' "要能识别旧版改坏的存量 jar 并就地修复（否则每个新进程都 ClassNotFoundException 一次）"
must    "$KN" 'jarFile\.setWritable\(true\)' "只读缓存 jar 要先恢复可写才能修复重写"

echo "===== P. 家族判据＝jar 级汇总（2026-09-14 an 批次一：修掉「壳 + payload 多 dex」盲区） ====="
# 症状：旧判据逐 dex 独立判定、且要求 Init 与 DexNative 落在**同一个 dex**。订阅里存在
#       「壳 + payload 多 dex」结构的加固 jar（真机 files/0b9565d6a6b5ecd989a7de9e1d50677e.jar），
#       两边都不满足 → 恒判普通 → 被直接调用 Init.init → 内部先 killall 共享 Go 代理再启动自己的
#       → native abort（am 真机 3 次 SIGABRT；崩溃栈的行号正是普通分支那次 invoke）。
# 修法：① 逐 dex 只采集特征，全部采完再统一判定 ② 家族入口类表（跨 dex）
# 09-14 ap 版追加：曾有的启发式兜底「同时引用 System.load* 与 Runtime.exec」**必须不复现** ——
# 它把大量普通 jar 误判进受控路径（ap 真机 458 次误拒载、站点成片不可用），而它想兜住的
# "未知变体"本就由 probeFamily 零盲区覆盖，属于纯粹的误伤源。
must    "$PI" 'private static final byte\[\]\[\] FAMILY_TYPES' "家族入口类表要覆盖同族全部入口（DexNative/ProxyOrigin/InitOrigin/GoProxyManager）"
must    "$PI" 'private boolean hitsFamilyType' "家族类名识别要按 typeIds 字符串表项做字节比对（不上万条 new String）"
must    "$PI" 'Signals collect\(\)' "每个 dex 只采集特征，不再逐 dex 独立判定"
must    "$PI" 'signals\.or\(collectDex' "多个 dex 的特征必须汇总后再判（壳+payload 结构的正解）"
must    "$PI" 'boolean family\(\)' "最终判定要集中在 jar 级的 family()"
must    "$PI" 'if \(familyType\) return true;' "家族入口类命中即家族（跨 dex 汇总）"
mustnot "$PI" 'nativeRef' "启发式信号 nativeRef 已撤除（误伤源：普通 jar 被拖进受控路径＝站点不可用）"
mustnot "$PI" 'processRef' "启发式信号 processRef 已撤除（同上）"
mustnot "$PI" 'new Dex\(data, packageName\)\.isProtected' "旧的逐 dex 独立判定不得复现（壳+payload 必然漏判）"
# 家族判据必须排在白名单之前：白名单只保证不 killProcess，防不了 ProxyOrigin 的 killall
_fam_line=$(grep -n 'if (familyType) return true;' "$ROOT/$PI" | head -1 | cut -d: -f1)
_wl_line=$(grep -n 'if (whitelisted) return false;' "$ROOT/$PI" | head -1 | cut -d: -f1)
if [ -n "$_fam_line" ] && [ -n "$_wl_line" ] && [ "$_fam_line" -lt "$_wl_line" ]; then
  echo "    OK  家族判据排在白名单之前（白名单防不了 killall 互杀）"
else
  echo "    !!  白名单先于家族判据命中：白名单变体 jar 会漏进 Init.init 的 killall 链"; FAIL=1
fi

echo "===== Q. 运行时权威探测 + /proxy fail-safe + 撤除密度放大器（2026-09-14 an 批次一） ====="
# 依据：静态扫描要自己解析 dex 结构，总有结构变体骗过它（P 节就是一次实录）。
#       loadClass 是 ART 自己的解析结果——跨 dex、抗壳、抗混淆，且只链接不初始化（零副作用）：
#       命中即按家族走受控路径；/proxy 端用同一判据做 fail-safe，即使装载判据漏了也打不到 native。
# 同时撤除两个密度放大器：分源并发档与后台预热（用户 09-14 拍板：提速只走"不重复加载"）。
AC=app/src/main/java/com/github/tvbox/osc/api/ApiConfig.java
HF=app/src/main/java/com/github/tvbox/osc/ui/fragment/HomeFragment.kt
SA=app/src/main/java/com/github/tvbox/osc/ui/activity/SettingActivity.kt
# LVC / LP（本地播放页与其控制器）已于 2026-09-22 随"本地视频链整体下线"删除，
#   原本锚在这两个文件上的两条断言（本地页旧播放器列表、本地页 pl 焊内置）随之撤除——
#   文件都不存在了，"本地页不得……" 已由删除本身完全覆盖，保留只会制造假红。
CT=app/src/main/res/layout/player_vod_control_view.xml
DL=app/src/main/res/layout/activity_detail.xml
MA=app/src/main/java/com/github/tvbox/osc/ui/activity/MainActivity.kt
MN=app/src/main/AndroidManifest.xml
UT=app/src/main/java/com/github/tvbox/osc/util/Utils.java
SUA=app/src/main/java/com/github/tvbox/osc/ui/adapter/SubscriptionAdapter.java
SDD=app/src/main/java/com/github/tvbox/osc/ui/dialog/ShareSubscriptionDialog.java
DR=app/src/main/res/drawable/ic_open_external.xml
VI=app/src/main/java/com/github/tvbox/osc/bean/VodInfo.java
SL=app/src/main/res/layout/dialog_playing_control.xml
PD1=app/src/main/java/com/github/tvbox/osc/ui/dialog/PlayingControlDialog.java
PD2=app/src/main/java/com/github/tvbox/osc/ui/dialog/PlayingControlRightDialog.java
must    "$PI" 'static boolean probeFamily' "要有运行时家族探测（唯一不受 dex 结构影响的判据）"
must    "$PI" 'loadClass\("com\.github\.catvod\.spider\.DexNative"\)' "运行时探测要查 DexNative（ART 全 dex 解析）"
must    "$JL" 'private boolean isFamily\(DexClassLoader' "装载时的家族判定＝静态判据 ‖ 运行时探测"
must    "$JL" 'ProtectedInitJar\.probeFamily\(classLoader\)' "运行时探测必须接入装载路径（否则形态变体仍走普通分支调 Init.init）"
must    "$JL" 'ProtectedInitJar\.probeFamily\(mcl\)' "/proxy 必须用同一探测做 fail-safe（这一层不依赖静态判据正确）"
mustnot "$DP" 'searchConcurrencyNonFamily' "分源并发档已撤除，不得复现"
mustnot "$JL" 'warmUp' "后台预热已撤除，不得复现（预热抬高初始化密度＝al/am 崩溃推手）"
mustnot "$AC" 'warmUpSiteJars' "预热调度入口已撤除"
mustnot "$HF" 'warmUpSiteJars' "HomeFragment 不得再挂预热"
must    "$FS" 'DeviceProfile\.searchConcurrency\(\)\)' "搜索池必须回到 ab 口径（全员同档）"
mustnot "$FS" 'Semaphore' "家族 jar 信号量限流已随分源并发一起撤除"

echo "===== R. /proxy 身份终审 + 进程级入站门禁（2026-09-14，am + aq） ====="
# 身份终审：key 型账本可被清空/过期，"init 成功过的 loader 身份"不会 —— 终审落在身份上。
# 入站门禁（aq 新增，进程级）：加固 jar 初始化时会把共享 Go 代理 killall 掉再启动自己的，
#   而 /proxy 按 recentJarKey 路由 —— 此刻属于**另一个** jar 的请求，按 key 的门禁完全挡不住，
#   它会打到正被 killall 的原生对象上 → obj==null → SIGABRT。门禁只能是"全进程任一 jar 在初始化
#   → 所有 /proxy 一律拒绝"。
must    "$PI" 'FAMILY_LOADERS\.add\(clz\.getClassLoader\(\)\)' "家族 loader 身份账本要在 init 成功时登记（第二真相源，不随配置重载清空）"
must    "$PI" 'static boolean isFamilyClassLoader' "/proxy 终审要能按 ClassLoader 判家族身份"
must    "$PI" 'static boolean isHolderClassLoader' "/proxy 终审要能按 ClassLoader 判持有者身份"
must    "$JL" 'ProtectedInitJar\.isFamilyClassLoader\(mcl\)' "proxyInvoke必须在invoke前按loader身份终审（key账本可被清空，身份不会）"
must    "$JL" 'ProtectedInitJar\.isHolderClassLoader\(mcl\)' "非持有者家族jar的事前503必须落在身份判定上"
must    "$PI" 'private static volatile boolean NATIVE_INIT_IN_PROGRESS' "要有进程级初始化标志（按 key 的门禁管不住别的 jar）"
must    "$PI" 'NATIVE_INIT_IN_PROGRESS = true;' "init 全过程必须置位（否则 killall 窗口内仍会放进 /proxy）"
must    "$PI" 'NATIVE_INIT_IN_PROGRESS = false;' "init 结束必须复位（否则 /proxy 永久 503）"
must    "$JL" 'if \(ProtectedInitJar\.nativeInitInProgress\(\)\) return proxyNotReady\(\)' "/proxy 入站必须先查进程级初始化标志（早于按 key 的判断）"
must    "$PI" 'FAMILY_LOADERS' "" # 身份账本本体
must    "$PI" 'PROBE_CACHE\.remove\(cl\)' "loader 被丢弃时精确清探测缓存（批次二起整体 clear 会让服役中的 loader 白探测一遍）"
mustnot "$JL" 'private final boolean mainLoader' "主实例标记已撤除：换届只由 init 成功驱动，不再有第二个裁判"
mustnot "$JL" 'ProtectedInitJar\.markHolderUsed\(\)' "旧的 per-generation 接力窗口已撤除（换届改由 occupyProxy 承担）"

echo "===== S. 装载复用：一次装载整进程服役（2026-09-14 批次二：治 mid==null + 提速） ====="
# 根因：load() 每次都 clear + new DexClassLoader → 类换代，而进程内已 dlopen 的原生库不卸载
#       → native 侧记着的 jmethodID 指向上一代 → mid==null → SIGABRT
#       （真机「退出→重进→立刻搜索」大概率崩溃的机制）。
# 修法：按 jar 路径复用同一个 DexClassLoader，跨配置重载存活；类不换代，native 与 Java 侧身份一致。
# 铁律：复用必须校验内容指纹 —— 服务端换 jar（md5 变）必须丢弃重建，否则「下拉刷新能让 jar 更新」失效。
must    "$JL" 'private static final java\.util\.LinkedHashMap<String, LoadedJar> REUSED' "复用表必须 static：主/home 两实例共享一份账本（分别记账必然对不上）"
must    "$JL" 'REUSE_CAPACITY = 20' "复用表要有容量上限（首轮搜索实测会创建 26 个 loader 量级）"
must    "$JL" 'private static String fileFingerprint' "复用指纹取文件内容 md5（不用订阅声明的 md5：中和器改写过缓存，它天然对不上＝永不命中）"
must    "$JL" 'fingerprint\.equals\(entry\.fingerprint\)' "指纹匹配才复用；不匹配即作废重建（jar 换版必须生效）"
must    "$JL" 'JarKillNeutralizer\.patch\(cache\)' "复用查询前先中和（幂等）：指纹必须等于「真正会被加载的那个文件」"
must    "$JL" 'LoadedJar reused = reuse\(path, fileFingerprint\(cache\)\)' "loadJarInternal 必须先查复用，再考虑 md5 缓存校验与下载"
must    "$JL" 'adopt\(key, reused\)' "复用命中要把 loader 与 proxyMethod 一起挂回本实例路由表"
must    "$JL" 'remember\(jar, new LoadedJar' "装载成功要记入复用表（下一次直接沿用）"
must    "$JL" 'if \(candidate\.loader == holder\) continue;' "LRU 淘汰必须放过持有者的 loader（它的代理还在跑，丢了就没人能接管）"
must    "$KN" 'private static String patchedKey' "中和器的已处理记账要带大小/时间：只按路径记账会让换版后的新 jar 跳过中和"
mustnot "$JL" 'ProtectedInitJar\.resetProxyFlight\(\)' "JarLoader 不得再在 load() 里重置进程级单飞（复用时代，配置重载不再等于换届）"

echo "===== T. 加固 jar 初始化两条路径 + 代理换届归位（2026-09-14 aq） ====="
# 行号级实证（真机 logcat）：
#   ① ak/am 里这批「壳 + payload 多 dex」加固 jar 全部由普通分支调 Init.init 活着
#      （DexNative.getLoader 的入口帧＝com.github.catvod.spider.Init.init，13843 / 14678 次，0 崩溃）。
#   ② ap 判据修好后它们首次进入手写受控路径，而手写路径**跳过了"起代理"**：
#      getLoader → InitOrigin.init → ProxyOrigin.init 去连 127.0.0.1:8964~9031 全部 ECONNREFUSED
#      （7802 次），拿不到 loader → 458 次拒载 → 站点成片消失（用户实测"重进后搜索为空"）。
# 定稿：手绑优先（有代理可承接时省一次 killall+重启＝提速）；失败或没有代理可承接 → 交还 jar 自身入口；
#       谁真正启动了代理谁就是持有者（走 jar 自身入口＝jar 内部重启了共享代理＝必须换届）。
must    "$PI" 'boolean bound = GO_PROXY_STARTED && bindDexLoader\(clz, init\)' "有代理可承接时才手绑（无代理时手绑必失败：getLoader 找不到要连的端口）"
must    "$PI" 'bound = invokeSelfInit\(clz\)' "手绑失败必须交还 jar 自身入口，不得直接拒载（ap 版 458 次误拒载的教训）"
must    "$PI" 'private boolean invokeSelfInit' "jar 自身入口要独立成方法（可读、可断言）"
must    "$PI" 'static void occupyProxy' "代理持有者身份与 key 同锁更新，两者永远同一份真相"
must    "$PI" 'occupyProxy\(clz, key\)' "只有真正启动了代理的 jar 才能成为持有者（承接已有的那个不能）"
mustnot "$PI" 'startGoProxyOnce' "依赖 Init.startGoProxy 入口的旧单飞已撤除（该入口在真 jar 上不存在，日志里 0 次）"
mustnot "$PI" 'takeOverIfStale' "旧的「旧持有者本代未被请求」接力判据已撤除（换届改由 occupyProxy 承担）"
mustnot "$PI" 'HOLDER_USED' "旧的 per-generation 接力窗口标记已撤除（与 occupyProxy 并存会让持有者被莫名清空）"

echo "===== U. 搜索池不得在进详情页时拆除（2026-09-14 aq：反复进出详情页后补全停止） ====="
# 症状：搜索结果页 → 详情页 → 返回 → 再进详情页，反复几次后结果补全停在半路。
# 根因：点击结果时对搜索池 shutdownNow()，并把返回的"尚未开跑"任务缓存、回页时重投。
#       但 shutdownNow 只交出排队任务，**已经在跑的被打断且永不回来**；回页时队列往往已空
#       → 在飞的站点被永久丢弃，且完成计数被重新基准化 → 页面"正常收尾"却少一块结果。
# 修法：进详情页不拆搜索。搜索是有界任务（看门狗 30 秒封顶），后台跑完，返回即完整列表。
mustnot "$FS" 'pauseRunnable' "进详情页不再缓存并重投排队任务（被中断的在跑任务永远回不来）"
mustnot "$FS" 'override fun onResume' "随 pauseRunnable 一起撤除的重投入口不得复现"
count   "$FS" 'searchExecutorService!!\.shutdownNow\(\)' 4 "shutdownNow 只允许留在四处：断网、新搜索、超时收尾、页面销毁"

echo "===== V. 冷启动首搜不得把「装载中」判成「空源」（2026-09-14 ar：冷启动首搜空源） ====="
# 症状：清后台后立刻打开 → 搜一个词 → 偶发"空源"，回首页再搜就正常。
# 根因：订阅站点池与内置首页源都是异步装载的，冷启动和用户抢速度时两者同时为空 →
#       getSourceBeanList() 返回空表 → FastSearchActivity 的 siteKey 为空 → 直接 showEmpty()。
#       用户看到的"空源"其实只是"还没加载好"，只能退回首页等装载完再搜才正常。
#       加剧因素：SearchHelper.getSourcesForSearch() 在站点池为空时回退出空表，
#       而空表被当成过滤条件（containsKey 全 false）→ 全部站点被滤掉，同样得到空源。
# 修法：ApiConfig 增加 sitePoolSettled 定论标志；搜索侧未定论时按节奏重试而非给空态；
#       空勾选集合一律视为"没有过滤意图"。
must    "$AC" 'private volatile boolean sitePoolSettled = false' "站点池装载定论标志存在且 volatile（跨线程可见）"
count   "$AC" 'sitePoolSettled = true' 4 "四处装载出口都要置位：订阅解析完、无订阅、首页源就绪、重试用尽"
must    "$AC" 'public boolean isSitePoolSettled\(\)' "搜索入口要能查询装载是否已有定论"
must    "$FS" 'isSitePoolSettled\(\) && readyRetry < READY_RETRY_MAX' "搜索侧先区分「未就绪」与「真空态」，未就绪时重试而不是给空态"
must    "$FS" 'postDelayed\(readyRetryRunnable, READY_RETRY_DELAY_MS\)' "未就绪重试必须真的排上定时任务"
must    "$FS" 'readyRetry = 0' "重试计数必须在新一轮搜索入口复位"
must    "$FS" 'isNotEmpty\(\) && !mCheckSources!!\.containsKey' "空勾选集合不等于全都不选（否则冷启动会被滤成空源）"
must    "$DA" '!mCheckSources\.isEmpty\(\) && !mCheckSources\.containsKey' "详情页换源搜索同一处判据必须一致"

echo "===== W. 冷启动首搜空源·第二根因：定论标志不得被提前置位，0 结果必须经裁决（2026-09-14 as） ====="
# 症状（ar 实测仍复现）：清后台 → 打开 → 立刻搜 → 偶发"暂无数据"；**手点第二下就正常**。
# 真根因一（可静态证）：ar 把 sitePoolSettled 在 loadLocalHomeConfig 出口无条件置 true。
#       而 parseHomeJson 只填 homeSiteList / mHomeSource，**不填 sourceBeanList**（搜索取的正是后者）：
#       内置首页配置来自 assets、冷启动瞬间就绪；订阅则是网络请求，要几百毫秒到数秒
#       （日志实测"从本地缓存加载"0 次 ⇒ 每次冷启动都重新拉订阅）。
#       于是首搜时 sourceBeanList 还空着，可搜站点为 0，而"未定论就等一等"的守卫已被自己废掉
#       → 立刻判"暂无数据"；第二下时订阅已落地 → 结果正常。
# 真根因二（可观测证）：搜索与 jar 初始化撞车时，jar 自己的 searchContent 在配置/代理未就绪时
#       抛 NPE 或返空串，站点被静默记成 0 条。ar 日志实测 63 次
#       `App99.searchContent → NullPointerException: Map.size() on null`，栈底是 searchResult$lambda-16。
# 修法：①内置首页配置出口只在"本来就没有订阅"时才算定论；
#       ②JarLoader 记装载序号，搜索侧快照/比对 → 撞车则自动重搜（用户手点第二下的自动化）；
#       ③等待预算 4×500ms → 10×500ms（5 秒），等待期间停在 loading 而不是先闪空态。
must    "$AC" 'if \(Hawk\.get\(HawkConfig\.API_URL, ""\)\.isEmpty\(\)\) sitePoolSettled = true' "内置首页配置出口必须条件化：它不填 sourceBeanList，只有「本来就没有订阅」时才算定论"
must    "$AC" 'return mHomeSource == null \? emptyHome : mHomeSource' "首页源必须有非空存根兜底：内置首页配置 sites 为空时（真实情况）mHomeSource 为 null，若直接把 null 塞进搜索列表会 NPE"
must    "$JL" 'public static long loadSeq\(\)' "jar 装载序号必须可查询（搜索侧判断本轮是否与初始化重叠）"
must    "$JL" 'LOAD_SEQ\.incrementAndGet\(\)' "装载序号必须在每次装载有结论时自增（含失败，用 finally）"
must    "$FS" 'roundLoadSeq = JarLoader\.loadSeq\(\)' "发起搜索时必须快照装载序号，否则收尾无从比对"
must    "$FS" 'private fun verdictEmpty\(\)' "0 结果必须统一走裁决，不得各出口自行判空"
count   "$FS" 'verdictEmpty\(\)' 3 "裁决函数本体 + 两处收尾调用（首波归零、看门狗超时）"
must    "$FS" '!roundPoolSettled \|\| \(seqNow != roundLoadSeq\)' "不可信判据＝站点池尚未定论 ‖ 本轮有 jar 装载落定"
must    "$FS" '正在初始化播放源，自动重试…' "自动重搜必须有明确提示（不能让用户以为卡住）"
must    "$FS" 'removeCallbacks\(autoResearchRunnable\)' "页面销毁要清掉自动重搜定时任务"
must    "$SW" '正在初始化播放源' "新 Toast 文案必须进宿主白名单，否则会被当 jar 弹窗移除（白名单是允许列表）"
must    "$FS" 'AUTO_RESEARCH_MAX = 2' "自动重搜必须有上限（源真的没结果时要能收敛到空态）"
must    "$FS" 'READY_RETRY_MAX = 40' "站点池等待预算 20 秒（2026-09-22 拍板：A8 实测就绪可晚到 ≈+16.5s，5s 天生踩空；计数全程共享 ⇒ 最长等 20s）"
must    "$FS" 'INIT_HINT_AT_RETRY' "等待 ≈2s 就要给「正在初始化」提示（09-22 拍板 Q1：不能等预算用尽才说，观感是提示→马上失败）"
must    "$FS" 'fromWaitingRetry: Boolean = false' "搜索诊断基线必须区分自旋重试入口，否则收尾总耗时是假计时（实测 总耗时=7ms）"

echo "===== X. 播放成功率·第一批：速度 + 兜底正确性 + 埋点（2026-09-15） ====="
# 背景：搜索结果来自不同站点，地址格式千奇百怪，播放失败分三类 ——
#   甲 取链失败（卡在「正在获取播放信息」不动）／乙 拿到链但播不出／丙 能播但慢。
# 本轮口径（用户 2026-09-15 拍板）：**不做任何自动换线路/换站点**，换源由用户手动决定；
#   只在「同一个地址」上把能试的都试完，并且让失败能被看见（埋点）。
# 崩溃面：全部改动落在宿主 View 层，不碰 jar 装载节奏、不新增线程、不新增用户可见入口。
# 下面每条被改回去都会退回一个真实症状，注释写在各自说明里。
# —— D2 埋点：没有埋点，收益就只能靠感觉
must    "$PT" 'PlayTrace' "埋点工具类存在"
must    "$PT" 'catch \(Throwable ignored\)' "埋点必须吞掉自身的任何异常（绝不许影响播放）"
must    "$PF" 'PlayTrace\.stage\("取链"' "取链阶段有埋点"
must    "$PF" 'PlayTrace\.stage\("净化"' "净化阶段有埋点（起播慢的最大来源，必须可量化）"
must    "$PF" 'PlayTrace\.stage\("起播"' "起播阶段有埋点"
must    "$PF" 'PlayTrace\.stage\("兜底"' "兜底阶段有埋点"
# —— A1 净化预取 2.5 秒超时（默认 client 10 秒 × 最多两次跳转＝最坏 20 秒纯等待压在起播前）
must    "$OG" 'PURIFY_TIMEOUT_MS = 2500' "净化预取超时 2.5 秒"
must    "$PF" 'withPurifyClient\(OkGo\.<String>get' "净化两处 GET 都必须走带超时的 client"
must    "$PF" 'if \(gen != mPurifyGen\) return' "净化回调必须比对请求代次（旧回调不得用旧地址覆盖新一集）"
# —— A2 净化内容生命周期：起播真实地址时作废上一份净化内容
must    "$PF" 'RemoteServer\.m3u8Content = null' "起播非本地净化地址时必须作废上一份净化内容"
# —— C4 把「这是 HLS」明确告诉播放器（ExoMediaSourceHelper.inferContentType 是按字符串 contains 判的）
must    "$RS" 'fileName\.equals\("/l1\.m3u8"\)' "本地 HLS 端点存在（带 .m3u8 后缀＝播放器一次判对容器）"
must    "$PF" 'return "http://127\.0\.0\.1:" \+ RemoteServer\.serverPort \+ "/l1\.m3u8"' "净化命中后播放的是带后缀的本地端点"
must    "$PF" '!isLoopbackUrl\(url\)\)' "去 BOM 兜底必须排除本机地址（否则会把本地清单整套送去第三方）"
# —— B1 兜底阶梯合一：自动路径只留一条链，且不得重复试同一个配置
must    "$PF" 'if \(fallbackToRawUrl\(\)\) return;' "失败后先回退原始直连（代理这层坏了，拿代理地址怎么试都没用）"
must    "$PF" 'if \(compatFallback\(\)\) return;' "失败后进内置降级阶梯"
mustnot "$PF" 'retriedSwitchPlayer' "那个「只置位、从不清零」的一次性开关必须彻底移除（否则第二集起这层保护就是空的）"
must    "$PF" 'String epKey = curEpisodeKey\(\)' "换集必须把兜底额度/重试计数/看门狗状态归零"
must    "$PF" 'mLastPlayKey = epKey' "换集判定必须落在集标识上（不能靠「进 play 就清零」——autoRetry 内部也走 play(false)，那样会无限重试）"
# —— B2 回退直连只给一次
count   "$PF" 'mFallbackRawUrl = null;' 3 "回退地址用掉即作废（兜底路径 + 换集 + 起播真实地址三处都要清）"
# —— B3 起播看门狗（黑屏转圈在改动前不算失败，也就永远等不到兜底）
must    "$PF" 'START_WATCHDOG_MS = 20000' "起播看门狗 20 秒"
# 2026-09-15 修正（起播看门狗判据漏洞）：原判据把「网速 > 0」也算进展，于是
# 「持续下数据、却始终不出画面」的源被判成有进展、永久解除保护（实测 st=准备中 /
# pos=0 / 网速 46KB 每秒，此后界面再无任何反馈，只能干等）。现在：
#   真进展 = 离开准备中 或 位置真的推进；
#   「有数据无画面」不属于进展，但也不立刻判死（会误杀真慢的源）—— 只延长观察一次再终裁。
must    "$PF" 'st != VideoView\.STATE_PREPARING \|\| pos > 0\) \{' "真进展判据：离开准备中或位置推进（不得拿网速当进展）"
mustnot "$PF" 'pos > 0 \|\| speed > 0' "不得再把「网速 > 0」单独当进展解除看门狗（空转源会被永久放行）"
must    "$PF" 'START_WATCHDOG_STALL_MS' "「有数据无画面」必须延长观察一次再终裁"
must    "$PF" 'mWatchdogRound' "看门狗要有「延长一轮」的轮次（防首次判死即误杀）"
must    "$PF" 'mWatchdogFired = false;' "每次起播都要重置判死标记（降级重播后必须重新有人接管）"
# 断言"延长窗口"这个语义，不再锁死具体常量名/数值 —— 2026-09-17 简化时把三档轮次收敛成一轮延长，
# 说明仍然是同一条：判死之前必须先多给一段时间。改动阈值不该让这条断言挂掉，改掉"延长"本身才该挂。
must    "$PF" 'postWatchdog\(START_WATCHDOG_STALL_MS\)' "判死前必须延长一轮（压低误杀）"
must    "$PF" 'cancelStartWatchdog\(\);' "就绪 / 出错 / 换集 / 销毁都必须停看门狗"
# —— 取链看门狗（本轮新增：取链回调里的 catch 是静默的，界面会永远停在「正在获取播放信息」）
must    "$PF" 'FETCH_WATCHDOG_MS = 15000' "取链看门狗 15 秒"
must    "$PF" 'armFetchWatchdog\(\)' "取链开始必须武装看门狗"
must    "$PF" 'finishFetchWatchdog\(\)' "取链有结论必须收工（含空结论与销毁）"
# —— B4 IJK 补网络超时（默认无超时＝连上之后对端不吐数据就无限挂，不进兜底链）
must    "$IJ" 'DEFAULT_RW_TIMEOUT_US' "IJK 补默认网络超时"
must    "$IJ" 'hasCodecOption\("rw_timeout"\)' "只补「订阅未指定」的情况，不覆盖站点设置"
must    "$IJ" 'path\.startsWith\("rtsp"\)' "直播路径（rtsp/udp/rtp）不得套读超时（那里的停顿是常态）"

echo "===== Y. 播放成功率·第二批：兼容增强 + 手动菜单 + 失败出口（2026-09-15） ====="
# —— B5 默认播放器口径统一（站点未指定时取全局默认；外部吊起失败另有内置回退目标）
must    "$PF" 'PlayerHelper\.defaultPlayerType\(sourceBean\)' "站点未指定播放器时照用全局默认（取值收敛到 PlayerHelper.defaultPlayerType，不再写死 2=Exo）"
must    "$PH" 'fallbackBuiltinType\(\)' "外部吊起失败要有内置回退目标"
# —— B6 手动播放器菜单：短按只在内置间循环、长按给完整列表、IJK 拆硬/软两项
must    "$PH" 'getBuiltinItems\(\)' "内置候选（含硬/软解码档）存在"
must    "$PH" 'findHardDecodeCodecName\(\)' "硬解档按 4|mediacodec==1 判定（不依赖订阅给分组起的名字）"
must    "$PH" 'BUILTIN_IJK_HARD' "IJK 拆成硬解/软解两项（一次点选同时决定播放器+解码）"
must    "$PH" 'nextBuiltinItem\(' "短按的下一档写死在内置候选上"
must    "$PH" 'applyPlayerItem\(' "候选项落地：内置项同时写解码档，外部播放器写编号"
must    "$VC" 'PlayerHelper\.nextBuiltinItem\(mPlayerConfig\)' "手动短按只走内置循环"
mustnot "$VC" 'getExistPlayerTypes\(\)' "短按不得再用含外部播放器且顺序不确定的列表（否则会唤起 MX/VLC）"
must    "$VC" 'PlayerHelper\.getPlayerPickItems\(\)' "「播放器」按钮给出与详情页同一份清单（系统 + 内置三档 + 已安装外部）"
# —— B7 硬解失败回退（音频 AC3/DTS 能落到工程自带的 ffmpeg 软解）
must    "$EM" 'setEnableDecoderFallback\(true\)' "启用同类型渲染器回退（硬解失败不再直接报错）"
# —— B8 每源独立数据源工厂（原先反射改单例工厂的 UA → 跨站点残留）
must    "$EX" 'createHttpDataSourceFactory\(' "按当次请求头新建数据源工厂"
must    "$EX" 'getDataSourceFactory\(headers\)' "取工厂的两条路径都必须带上当次请求头"
mustnot "$EX" 'java\.lang\.reflect' "不得再用反射改单例工厂的 userAgent（A 站点的 UA 会残留到 B 站点）"
mustnot "$EX" 'mHttpDataSourceFactory' "单例工厂字段必须移除"
# —— B9 隧道模式只埋点观察，本轮不改行为
must    "$EM" 'tunneling=on' "隧道模式先埋点（部分机型上它会导致「有声音没画面」，先把证据留出来）"
# —— C1 请求头兜底补全：只补缺失的同源 Referer，绝不覆盖站点给的值
must    "$PF" 'fillMissingHeaders\(' "站点没给 Referer 时补同源 Referer"
must    "$PF" 'Referer' "补的是 Referer"
mustnot "$PF" 'UA\.randomOne\(\)' "不得用随机 UA 补全（同一源每次 UA 都变＝把不确定性带进播放链路）"
must    "$PF" 'HashMap<String, String> rawHeaders' "补全在汇聚点做：参数换名，保证净化回调仍能引用（effectively final）"
# —— C1 修正（au 回归修复）：补出来的 Referer 只能给「净化预取」那一次请求用，绝不能进播放器。
# 真机实测：清单里的分片常托管在第三方 CDN 上，CDN 拒绝外站 Referer（小红书 CDN：不带 Referer 返回
# 200、带源站 Referer 返回 403）→ 分片集体 403，症状＝「清单取到了、却始终播不出来」。
must    "$PF" 'startPlayUrl\(url, raw\);' "净化各出口必须用站点原始请求头"
must    "$PF" 'startPurified\([^)]*content, raw, t0\);' "净化收尾必须用站点原始请求头"
mustnot "$PF" 'startPurified\([^)]*content, headers, t0\);' "净化收尾不得把补全后的请求头交给播放器"
must    "$PF" 'final HashMap<String, String> headers = rawHeaders;' "播放汇聚点不得再补 Referer（沿用调用方给的头）"
# —— C2 同址换协议重试（零网络成本、不换内核）
must    "$PF" 'retryVariantUrl\(\)' "同址 http↔https 变形重试"
must    "$PF" '正在尝试备用协议重连' "变形重试要有可见提示（否则用户以为卡住）"
# —— C3 去 BOM 改本地做，不再依赖第三方
mustnot "$PF" 'unBom\.php' "不得再把地址转发给第三方去 BOM 服务（第三方挂了＝凭空多一次失败）"
must    "$PF" 'retryWithoutBom\(' "去 BOM 改本地：自拉清单、剥 BOM 后经内置代理播"
must    "$PF" 'stripBom\(' "BOM 剥离要同时处理 \\uFEFF 与 latin1 误读两种形态"
# —— D1 失败出口：只给「重试」，不引导换源、不引导换播放器
must    "$PF" 'append\("重试"\)' "失败出口只给「重试」"
must    "$PF" 'retryFromScratch\(\)' "重试必须重置本集额度（否则点下去只会立刻再看到同一句提示）"
mustnot "$PF" 'append\("切换播放器"\)' "失败提示不再引导换播放器（自动链已逐档试过；手动切换在操作栏「播放器」按钮）"
# —— 换集重置：两个新标记都要归零（否则第二集起这两个兜底直接失效）
must    "$PF" 'mBomRetried = false;' "换集必须重置去 BOM 标记"
must    "$PF" 'mVariantTried = false;' "换集必须重置变形标记"

echo "===== Z. 播放成功率·第三批：看门狗判据修正 + 降级不再用坏地址 + 失败去重（2026-09-15） ====="
# 背景：这三条都不是某个站点特有的，而是「失败链走错路」的通用缺陷：
#   1) 看门狗把「有网速」当进展（见 X 节 B3 修正说明）；
#   2) 变形重试把地址改成了对方不支持的协议（例如把 http 端口硬写成 https），
#      而紧随其后的降级用的正是这个死地址；
#   3) 同一次起播的失败被处理两次（播放器换源/释放后仍会滞后报一次 error），
#      实测同一集出现过间隔 0.25 秒的两轮降级。
# —— 降级必须回到「变形前」的地址
must    "$PF" 'mVariantBaseUrl = mPlayingUrl;' "变形重试前必须记下原地址"
must    "$PF" 'mVariantTried && !TextUtils\.isEmpty\(mVariantBaseUrl\)' "降级时若变形试过，必须回到变形前的原地址"
mustnot "$PF" 'startPlayUrl\(mPlayingUrl, mPlayingHeaders\);' "降级不得直接拿 mPlayingUrl（可能是被改坏的协议地址）"
must    "$PF" 'mVariantBaseUrl = null;' "换集/手动重试必须清掉变形原地址"
# —— 同一次起播只处理一次失败
must    "$PF" 'mPlayStartedAt == mFailHandledAt' "同一次起播的重复 error 回调必须被忽略（否则白白吃掉降级额度）"
must    "$PF" 'mFailHandledAt = mPlayStartedAt;' "处理失败时必须记下这次起播"
# —— 埋点补全（原来真失败一条都没记，等于没有数据）
must    "$PF" 'PlayTrace\.fail\("出链"' "兜底试尽这个真失败出口必须记埋点（原来只记 stage）"
must    "$PF" 'mFetchStartedAt' "取链看门狗必须记真实耗时（原来写死 -1）"
must    "$PF" 'purifyFailKind' "净化预取失败要粗归因（区分本机代理地址与远端，用于统计而不是猜）"
# —— A2 遗留：净化内容的跨线程可见性
must    "$RS" 'public static volatile String m3u8Content;' "净化内容必须是 volatile（写方是 OkGo 回调线程、读方是 NanoHTTPD 工作线程）"
# —— 本集重播总预算：每一档都空转时，用户不该盯着转圈两分半
must    "$PF" 'EPISODE_RETRY_BUDGET_MS = [0-9]+' "本集自动重播要有总预算（防最坏空转干等）"
# 预算判据必须是"再跑一个完整看门狗窗口会不会超"，不能是"当前是否已经超"。
# 原因（2026-09-17 审查发现）：判死点在 32 / 64 / 96 秒，只比大小的写法填 65 还是 90
# 都卡在 64 与 96 之间 ⇒ 一次降级都拦不下，最坏等待始终是 96 秒，预算等于没有。
must    "$PF" 'elapsed \+ START_WATCHDOG_MS \+ START_WATCHDOG_STALL_MS > EPISODE_RETRY_BUDGET_MS' "预算判据必须含下一个看门狗窗口（否则 65/90 这类取值一次降级都挡不住）"
# 终态出口：预算用尽、兜底试尽都必须"直接给提示"，不能再被 autoRetry 排一次重新取链 ——
# 那等于又开一个 20+12 秒的窗口，把刚刚锁住的最坏等待重新拖长。
# （2026-09-22 更新：P1 拍板后，兜底试尽与 errorFinal 之间允许**一次带护栏的**重取 ——
#   见下方 P1 小节；errorFinal 仍是终态出口，once 护栏保证它最多被绕过一次。）
must    "$PF" 'void errorFinal\(String err\)' "必须有「不再自动重取」的终态失败出口"
must    "$PF" 'errorFinal\("视频播放出错"\)' "预算用尽与兜底试尽必须走终态出口（不得回退成 errorWithRetry）"
# 排队中的退避重取必须能被作废：只比对集标识挡不住"同一集上用户手动点重试"，
# 那种情况下队列里那次重取会照跑，和新发起的那次撞成两个并发取链。
must    "$PF" 'if \(genAtSchedule != mRetryGen\) return;' "排队中的重取必须按重取代次作废（照跑会与手动重试并发取链）"
must    "$PF" 'mEpisodeStartAt = System.currentTimeMillis\(\);' "换集与手动重试必须把预算重置"

echo "===== AA. 播放成功率·第四批：失败态可见 + 本机代理不变形 + 非 URL 地址早判（2026-09-15 全场景实测） ====="
# 背景：这三条全是 av 全场景真机实测（43 次点播 / 10 站点 / 7 次失败）暴露出来的，与具体站点无关：
#   1) 取链空返回走的是 errorWithRetry(finish=true) —— 只弹 2 秒 Toast、**不关** loading 浮层，
#      实测界面继续转了 65 秒。用户看到的是"卡死"，不是"出错"，也不会想到还能重试；
#   2) 变形重试的守卫只认 /l1.m3u8，把网盘源的 127.0.0.1:9978/proxy 也变形了一遍 ——
#      本机同一端口不存在另一套协议，必然失败，白耗一次尝试；
#   3) 站点会下发不是 URL 的"地址"（实测 Ksvideo-<hex>，全工程无此协议）。
#      它交给内核必失败，还会走完整条兜底链，实测每次白耗约 12 秒。
# —— 失败出口必须只剩一个形态：关转圈、留错误态、带「重试」
must    "$PF" 'void errorWithRetry\(String err\)' "失败出口只剩一个形态（不得再按 finish 分支）"
mustnot "$PF" 'errorWithRetry\(String err, boolean finish\)' "finish 参数必须已废除（它唯一的区别就是「只弹 Toast」）"
mustnot "$PF" 'Toast\.makeText\(mContext, err' "失败出口不得只弹 Toast（浮层会留在转圈状态）"
must    "$PF" 'setTip\(err, false, true\);' "失败出口必须落到错误态（关转圈 + 显示错误）"
must    "$PF" 'needRetryLink' "错误态必须带可点的「重试」——只给这一个出口，不引导换源/换播放器"
# —— 本机代理地址不得做协议变形
must    "$PF" 'isLoopbackUrl\(mPlayingUrl\)' "本机代理地址不得做 http↔https 变形"
mustnot "$PF" 'isLocalHlsUrl\(mPlayingUrl\)\) return false' "变形守卫不得只挡 /l1.m3u8（漏掉网盘源的 /proxy）"
# —— 不是 URL 的地址必须在起播前判掉
must    "$PF" 'isPlayableUrl' "非 URL 地址必须在起播前判掉"
must    "$PF" 'PlayTrace\.fail\("地址"' "非 URL 地址必须留痕（否则这类失败统计不到）"
must    "$PF" 'Thunder\.isSupportUrl' "判据必须放行迅雷专用通路（magnet/ed2k/torrent 不带 ://）"

echo "===== AB. 播放成功率·第五批：失败先退避重取 + 判据简化（2026-09-17 aw 真机实测） ====="
# 背景：aw 真机复测（40 次点播 / 4 个站点 / 16 次失败）把「一进去就是重试」钉死了：
#   errorWithRetry → autoRetry → play(false) 全程同步调用，实测两次取链请求只隔 **2~8 毫秒**，
#   等于把同一个请求连打两遍；而且只重试 1 次 ⇒ 从点播到出提示全程 271ms（12:46:22.189→22.460）。
#   用户原话：「不要一下不试就报错」，但口径又要求**不碰线路**
#   ⇒ 唯一可用的维度只剩时间：必须退避到秒级（实测源侧抖动恢复窗口正是秒级），给到 2 次。
# —— 09-17 ax 轮实测又纠正了两处：
#   (a) 曾让重取期间显示「正在重新获取播放地址（n/2）」，用户明确反馈**多余**
#       （「我的本意是重试时长太短会误判可播放的源，不是说特地把时间拉长」）
#       ⇒ 删掉计数文案，重取期间沿用屏幕上原有的 loading 状态；
#   (b) 8 秒预算原来只检查"现在是否超预算"，没把这次要等的退避算进去 ⇒ 不是真上限：
#       实测 14:12:07 点播、14:12:17.7 才给结论（10.7 秒 > 承诺的 8 秒）。
# —— 另修一处失效守卫：去 BOM 的 loopback 判断写的是带斜杠的 `://127.0.0.1/`，
#    而真实本地地址是 `http://127.0.0.1:9978/proxy?...`（端口插在中间）⇒ 匹配不上，守卫等于没有。
must    "$PF" 'AUTO_RETRY_DELAY_MS = \{1200, 2500\}' "自动重取必须退避到秒级（不得回退成零间隔连打）"
mustnot "$PF" 'if \(autoRetryCount < 1\)' "自动重取不得回退成「只重试 1 次」"
must    "$PF" 'FETCH_RETRY_BUDGET_MS = 8000' "取链重取必须有 8 秒总预算（对应给用户的提示时长承诺）"
must    "$PF" 'System\.currentTimeMillis\(\) \+ delay - mEpisodeStartAt > fetchBudgetMs' "重取预算必须是「点播到出结论」的真上限（连本次退避一起算，否则会超时；预算参数化以服务 P1 两条护栏路径）"
mustnot "$PF" '正在重新获取播放地址' "退避重取不得引入「重试 N 次」计数文案（09-17 用户反馈：看着多余）"
must    "$PF" 'keyAtSchedule' "退避期间换集必须作废那次重取（否则会打断新一集的取链）"
mustnot "$PF" '!url\.contains\("://127\.0\.0\.1/"\) && !url\.contains\("://localhost/"\)' "去 BOM 的 loopback 守卫不得回退成带斜杠写法（带端口地址匹配不上）"
echo "----- AB2. 判据简化：宁可不作为，也不误杀能播的源（2026-09-17 用户口径「千万不要把正常能播放的源判错」） -----"
# 背景：本轮全量审查"哪些机制可能误伤正常源"后的三条结论，都已用实测数据支撑：
# 1) 看门狗不能再拿"网速"当进展判据 —— 不同内核 getTcpSpeed 语义不一致（取不到恒为 0），
#    会把"慢但能播"的源提前判死；实测正常源就绪最大 2.8 秒，20+12 秒窗口已极宽松；
# 2) 变形重试必须限定标准端口 —— 历史 4 次触发 0 次成功，其中 3 次是 :9090 这类私有端口硬试 https；
# 3) 预算收紧到 65 秒（首次 32 秒 + 一次降级 32 秒），避免最坏等满 96 秒。
mustnot "$PF" 'if \(speed <= 0\)' "看门狗不得再用网速当进展判据（会误杀慢源）"
mustnot "$PF" 'START_WATCHDOG_RECHECK_MS' "看门狗不得回退成「三档轮次」（判据已被收敛成一次延长）"
must    "$PF" 'hasStandardPort' "http↔https 变形必须限定标准端口（私有端口上换协议必然连不上）"

echo "===== AC. 播放器清单口径：只列本机真能用的 + 外部吊起失败回退（2026-09-17 用户拍板） ====="
# 用户口径（09-17 二次澄清后）：① 列表只列**本机确实可用**的播放器 —— 装了外部就显示、没装就不显示，
#    目的是不让用户点到"点了吊不起来"的项；**不是把外部播放器封掉**；
#   ② 手动切了外部但吊起失败要退回内置接着播；③ 系统播放器不该被写死成"不存在"；④ 短按循环只在内置里走。
mustnot "$PH" 'playersExist\.put\(0, false\)' "系统播放器不得再被写死成不存在"
must    "$PH" 'getAvailablePlayerTypes\(\)' "设置页候选＝本机可用的播放器（已安装的外部必须在列）"
must    "$PH" 'getBuiltinPlayerTypes\(\)' "本地播放页候选＝内置内核（本地页没有吊起外部的通路）"
mustnot "$PH" 'PlayerHelper\.getExistPlayerTypes' "顺序来自 HashMap 的旧列表必须整体移除"
mustnot "$SA" 'PlayerHelper\.getExistPlayerTypes' "设置页不得再用旧列表"
must    "$PH" 'normalizeAvailablePlayerType\(' "配置指向本机没有的播放器时才落回可用项（判据是存在性，与是否外部无关）"
mustnot "$PH" 'mPlayersExistInfo' "播放器存在性不得再静态缓存（装完外部播放器必须立刻可见，不能等重启）"
must    "$SA" 'PlayerHelper\.getAvailablePlayerTypes\(\)' "设置页按可用性列播放器"
mustnot "$SA" 'normalizeBuiltinPlayerType' "设置页不得再把外部播放器一律踢回内置（那等于封掉外部）"
# 原断言写死了 `(int) Hawk.get(HawkConfig.PLAY_TYPE, 2)` 这个具体写法 —— 属于"断言字面写法"，
# 09-18 把默认值判据收口进 PlayerHelper.defaultPlayerType 之后它必然误报。改为断语义：
must    "$PF" 'PlayerHelper\.defaultPlayerType\(sourceBean\)' "缺省播放器由 PlayerHelper 统一回答（站点优先、其次全局默认）"
must    "$PH" 'sourceBean\.getPlayerType\(\) != -1' "站点指定了默认播放器就用站点的"
must    "$PH" 'HawkConfig\.PLAY_TYPE' "站点没指定时取设置页的全局默认（设了外部就真的用它，吊不起来才对）"
must    "$PF" 'PlayerHelper\.copyWithBuiltinFallback\(playCfg\)' "外部吊起失败必须回退内置（不得就此让这一集播不了）"
must    "$PF" 'if \(callResult\)' "只有吊起成功才结束本次起播（失败要继续往内置路径走）"
mustnot "$PF" 'Hawk\.put\(HawkConfig\.PLAY_TYPE' "回退内置不得改写用户设置（下次手动选外部仍要先尝试吊起）"
must    "$PH" 'playersExist\.put\(0, true\)' "系统播放器在清单里恒可用（原先被写死成不存在）"

echo "===== AD. 播放器入口改造：外部按钮 / 详情页外部播放 / 气泡清理（2026-09-17 用户拍板） ====="
# 用户口径：① 播放器按钮的长按取消，改由控制条「外部」按钮点击展开清单；
#   ② 清单＝系统播放器 + 已安装的外部播放器（没装外部时只剩系统播放器，按钮仍常显）；
#   ③ 详情页投屏左侧加一颗同语义按钮；④ 悬浮气泡全清。
# 动机：按钮上显示的是当前播放器名而不是"播放器"三字，长按在触屏手机上基本发现不了。

# —— 长按取消：播放器选择只从「外部」按钮进
mustnot "$VC" 'mPlayerBtn\.setOnLongClickListener' "播放器按钮的长按已取消（改为点击展开清单）"
must    "$VC" 'mPlayerExtBtn\.setOnClickListener' "「外部」按钮点击展开播放器清单"
must    "$CT" 'id="@\+id/play_ext"' "控制条里有「外部」按钮"
must    "$VC" 'findViewById\(R\.id\.play_ext\)' "「外部」按钮已接入控制器"
mustnot "$PH" 'getAllPlayerItems' "旧「内置三项 + 外部」完整列表已移除（长按取消后无调用点，留着只会误导）"

# —— 位置：必须紧贴解析档（硬解码/软解码那一颗）右侧
IJK_LINE=$(grep -n 'id="@+id/play_ijk"' "$ROOT/$CT" | head -1 | cut -d: -f1)
EXT_LINE=$(grep -n 'id="@+id/play_ext"' "$ROOT/$CT" | head -1 | cut -d: -f1)
if [ -n "${IJK_LINE:-}" ] && [ -n "${EXT_LINE:-}" ] && [ "$EXT_LINE" -gt "$IJK_LINE" ]; then
  echo "    OK  「外部」按钮在解析档右侧（play_ext 在 play_ijk 之后）"
else
  echo "    !!  「外部」按钮必须排在解析档 play_ijk 之后"; FAIL=1
fi
# 详情页：外部播放按钮要在投屏按钮左侧
CAST_LINE=$(grep -n 'id="@+id/tvCast"' "$ROOT/$DL" | head -1 | cut -d: -f1)
DEXT_LINE=$(grep -n 'id="@+id/tvExtPlayer"' "$ROOT/$DL" | head -1 | cut -d: -f1)
if [ -n "${CAST_LINE:-}" ] && [ -n "${DEXT_LINE:-}" ] && [ "$DEXT_LINE" -lt "$CAST_LINE" ]; then
  echo "    OK  详情页外部播放按钮在投屏左侧"
else
  echo "    !!  详情页外部播放按钮必须在投屏 tvCast 之前"; FAIL=1
fi

# —— 详情页：同一份清单、同一套吊起与切内核路径
must    "$DA" 'R\.id\.tvExtPlayer' "详情页有外部播放按钮"
must    "$DA" 'PlayerHelper\.getPlayerPickItems\(\)' "详情页用同一份清单（系统 + 内置三档 + 已安装外部）"
must    "$DA" 'PlayerHelper\.runExternalPlayer\(' "详情页选外部＝吊起第三方 App"
must    "$DA" 'switchPlayerItem\(' "详情页选内置内核＝在预览里切内核重播"
must    "$DL" 'ic_open_external' "详情页外部播放图标已接入"
must    "$DR" 'viewportWidth="24"' "图标资源存在"

# —— PlayFragment 对外接口（详情页要用，缺一个就编不过）
must    "$PF" 'public boolean switchPlayerItem\(' "对外提供切内核入口"
must    "$PF" 'public HashMap<String, String> getPlayingHeaders\(' "请求头可读（复用播放中那一份，不新造机制）"
must    "$PF" 'public long getSavedProgressOfCurrent\(' "当前集进度可读（吊起时续播）"
must    "$PF" 'public void pausePlayback\(' "吊起成功后暂停内置播放（避免两个播放器同时出声）"
must    "$VC" 'public void updatePlayerCfgView\(' "控制条刷新对外可见（详情页切内核后要同步按钮文案）"

# —— 气泡全清（只清 tooltip，不动 contentDescription 与长按原本的行为）
must    "$UT" 'public static void disableTooltip\(' "清气泡工具存在"
must    "$UT" 'setTooltipText\(null\)' "确实把 tooltip 置空（而不是删无障碍描述）"
count   "$VC" 'Utils\.disableTooltip\(' 2 "控制条：旋转 + 投屏两处都要清"
count   "$DA" 'Utils\.disableTooltip\(' 3 "详情页：投屏 / 下载 / 外部播放三处都要清"
# 底部导航必须用**递归**版：bottomNav 的直接子级只有一个菜单容器，
# 真正带 tooltip 的 item View 在它里面 —— 只遍历直接子级会清不到（上一版就是这么漏的）
must    "$MA" 'Utils\.disableTooltipDeep\(' "底部导航清气泡必须递归整棵树"
mustnot "$MA" 'bottomNav\.childCount' "底部导航不能只遍历直接子级（item View 藏在菜单容器里）"
must    "$UT" 'instanceof ViewGroup' "递归深入子 View（气泡可能长在内部层级上）"
must    "$UT" 'setOnLongClickListener' "长按短路兜底：Material 之后在别的时机重设 tooltip 也弹不出来"
must    "$SUA" 'Utils\.disableTooltip\(' "订阅项「更多操作」也要清（列表项会复用，每次绑定清一遍）"
must    "$SDD" 'Utils\.disableTooltip\(' "分享弹窗的二维码也要清"

# —— 包可见性：Android 11+ 未声明的包查不到（装了也检测不到），按代码里的识别清单补齐
must    "$MN" 'com\.mxtech\.videoplayer\.pro' "manifest 声明 MX(Pro) 包名"
must    "$MN" 'com\.mxtech\.videoplayer\.ad' "manifest 声明 MX(免费版) 包名"
must    "$MN" 'xyz\.re\.player\.ex' "manifest 声明 Reex 包名"
must    "$MN" 'org\.xbmc\.kodi' "manifest 声明 Kodi 包名"
must    "$MN" 'org\.videolan\.vlc' "manifest 声明 VLC 包名"

echo "===== AE. 播放器作用域：默认 / 线路级 / 外部仅本次（2026-09-18 用户拍板） ====="
# 用户口径：① 设置页默认播放器对「没手动选过播放器」的片子生效（含看过的）；
#   ② 手动切内置内核只作用于**同线路**本片的所有集（换线路互不影响）；
#   ③ 外部播放器仅本次，不写配置，下一集回到本线路配置；
#   ④ 详情页与播放页共用同一份清单、同一套落盘规则。
# 一旦回退：改设置页默认对老片子全部失效，或者换线路、用一次外部就把播放器选择串味。

# —— 判据必须是显式标记，不能再靠"配置里有没有 pl"（调比例/速度也会写配置）
must    "$PH" 'isUserPicked\(' "「有没有手动选过播放器」用显式标记判定"
mustnot "$PF" 'mVodPlayerCfg\.has\("pl"\)' "旧的「配置里有 pl 就永不吃默认」判据已移除"

# —— 线路级：配置按线路存档，换线路不串味
must    "$VI" 'flagPlayerCfgMap' "VodInfo 有按线路存档的播放器配置表"
must    "$PF" 'getLinePlayerCfg\(' "取配置时按当前线路取存档"
must    "$PF" 'setLinePlayerCfg\(' "写配置时按当前线路存档"
must    "$PF" 'isLegacyOnlyCfg\(' "旧数据认领的前提是整张线路表为空（否则换新线路会把上一条线路的选择搬过去）"
must    "$DA" 'flagPlayerCfgMap = vodInfo' "详情页与播放页共享同一份存档表（否则播放页写的详情页看不见）"

# —— 手动入口打标记：播放页 1 处 + 控制条 3 处（短按循环 / 弹窗内置项 / 解析档按钮）。
#    解析档按钮切的也是 IJK 硬/软解，与弹窗里「IJK播放器(硬解码/软解码)」是同一件事；
#    漏了它就会出现"弹窗选了能记住、解析档切了记不住"的口径不一致（09-18 复查发现并已修）。
#    这里必须用 count 而不是 must：must 只验"有调用"，别处有调用它就绿了 —— 当初就是这么漏的。
must    "$PF" 'PlayerHelper\.markUserPicked\(' "播放页切播放器要打「用户手动选过」标记"
count   "$VC" 'PlayerHelper\.markUserPicked\(' 3 "控制条三处手动入口都要打标记（短按循环/弹窗内置项/解析档）"

# —— 自动兜底换内核不得打标记（否则一次失败就把自动切换固化成用户选择）
SW_LINE=$(grep -n 'public void switchToNextInternalPlayer' "$ROOT/$VC" | head -1 | cut -d: -f1)
if [ -n "${SW_LINE:-}" ]; then
  SW_BODY=$(sed -n "${SW_LINE},$((SW_LINE+16))p" "$ROOT/$VC")
  if echo "$SW_BODY" | grep -qE 'markUserPicked'; then
    echo "    !!  播放失败自动兜底换内核不得打手动标记（会把自动切换固化成用户选择）"; FAIL=1
  else
    echo "    OK  自动兜底换内核不打手动标记"
  fi
else
  echo "    !!  找不到 switchToNextInternalPlayer（播放失败自动兜底入口）"; FAIL=1
fi

# —— 真正在跑的那条兜底路径同样不许写回用户配置。
#    上面那条锁的是 switchToNextInternalPlayer，而 09-18 复查发现它**全工程已无调用点**
#    （只盯死代码＝没有保护）。实际降级走 compatFallback → startPlayUrl(临时副本 fbOverride)，
#    这里锁住它不许调 updatePlayerCfg / savePlayerCfgToLine / markUserPicked：
#    一旦写回，用户手动选过的播放器（plt=1）下次不再刷新，等于自动切换把用户选择顶掉。
FB_LINE=$(grep -n 'private boolean compatFallback' "$ROOT/$PF" | head -1 | cut -d: -f1)
if [ -n "${FB_LINE:-}" ]; then
  FB_BODY=$(sed -n "${FB_LINE},$((FB_LINE+26))p" "$ROOT/$PF")
  if echo "$FB_BODY" | grep -qE 'updatePlayerCfg\(|savePlayerCfgToLine\(|markUserPicked'; then
    echo "    !!  自动兜底降级不得写回用户配置（会顶掉用户手动选的播放器）"; FAIL=1
  else
    echo "    OK  自动兜底降级只改临时副本，不写回用户配置"
  fi
else
  echo "    !!  找不到 compatFallback（播放失败降级入口）"; FAIL=1
fi

# —— 外部播放器：仅本次吊起，不得写配置（写了就变成整部片子都走外部）
must    "$PF" 'public boolean playWithExternalPlayer\(' "有「仅本次吊起外部」的实现入口"
must    "$VC" 'listener\.playExternal\(' "控制条选外部＝只吊起（不写配置、不重播）"
mustnot "$PH" 'getExternalPlayerItems' "旧的外部专用清单已并入「播放器」清单（留着只会与新清单撞语义）"
PW_LINE=$(grep -n 'public boolean playWithExternalPlayer' "$ROOT/$PF" | head -1 | cut -d: -f1)
if [ -n "${PW_LINE:-}" ]; then
  PW_BODY=$(sed -n "${PW_LINE},$((PW_LINE+22))p" "$ROOT/$PF")
  if echo "$PW_BODY" | grep -qE 'savePlayerCfgToLine\(|applyPlayerItem\('; then
    echo "    !!  仅本次吊起外部播放器不得写配置（写了就变成整部片子都走外部）"; FAIL=1
  else
    echo "    OK  仅本次吊起外部播放器不写配置"
  fi
else
  echo "    !!  找不到 playWithExternalPlayer"; FAIL=1
fi

# —— 弹窗回显当前播放器（名称可见，用户才知道现在用的是哪一个）
must    "$VC" '请选择播放器（当前：' "控制条弹窗标题回显当前播放器"
must    "$DA" '请选择播放器（当前：' "详情页弹窗标题回显当前播放器"

# ======================= AE 节：09-18 第二轮 =======================
# 一、候选编号的"是不是外部"判据
#    内置的 IJK 硬/软解编码是 100/101，也在 10 以上；用 item>=10 粗判会把它们送去吊起外部播放器
must    "$PH" 'public static boolean isExternalPlayer\(' "有明确的「是不是外部播放器」判据（10~14）"
# 判据写成"比较到右括号"的实际语句形态：注释里会出现 `item >= 10` 这类字面写法（用来说明为什么不能用它），
# 直接拿 'item >= 10' 当判据会命中自己的注释而误报 —— 这是本项目闸门的老坑，别再踩。
mustnot "$VC" '>= *10\)' "控制条不再用「>=10」粗判外部播放器"
mustnot "$DA" '>= *10\)' "详情页不再用「>=10」粗判外部播放器"
mustnot "$PF" '>= *10\)' "播放页不再用「>=10」粗判外部播放器（内置 IJK 硬/软解 100/101 会被一起挡掉）"
must    "$PF" 'PlayerHelper\.isExternalPlayer\(' "播放页改用统一的「是不是外部播放器」判据"

# 二、详情页弹窗的勾选要落在当前播放器上（原先写死第 0 项，永远勾"系统播放器"）
must    "$PF" 'public int getCurrentPlayerItem\(' "播放页暴露「当前播放器项」编码"
must    "$DA" 'playFragment\.getCurrentPlayerItem\(\)' "详情页按当前编码算勾选位置"
must    "$DA" 'items, defaultPos\)' "详情页弹窗传入算出来的勾选位置（不再写死 0）"

# 三、全屏设置面板的「播放器」一行也要有切换入口
must    "$SL" 'android:id="@\+id/player_pick"' "设置面板「播放器」一行有切换入口"
must    "$PD1" 'mBinding\.playerPick' "底部设置弹窗绑定该入口"
must    "$PD2" 'mBinding\.playerPick' "右侧设置弹窗绑定该入口"
must    "$PD1" 'mPlayerExtBtn\.performClick\(\)' "底部设置弹窗复用控制条那颗按钮的逻辑（不另造一份清单）"
must    "$PD2" 'mPlayerExtBtn\.performClick\(\)' "右侧设置弹窗同样复用（不另造一份清单）"

# 四、本线路没有存档时不得继承别的线路的播放器选择（串味）
IPC_LINE=$(grep -n 'void initPlayerCfg()' "$ROOT/$PF" | head -1 | cut -d: -f1)
if [ -n "${IPC_LINE:-}" ]; then
  IPC_BODY=$(sed -n "${IPC_LINE},$((IPC_LINE+45))p" "$ROOT/$PF")
  if echo "$IPC_BODY" | grep -q 'isLegacyOnlyCfg()' && echo "$IPC_BODY" | grep -q 'mVodPlayerCfg = new JSONObject();'; then
    echo "    OK  本线路无存档时重建配置（不继承上一条线路的播放器选择）"
  else
    echo "    !!  initPlayerCfg 缺「本线路无存档就重建」分支，切线路会把上一条线路的选择带过去"; FAIL=1
  fi
else
  echo "    !!  找不到 initPlayerCfg"; FAIL=1
fi

# 五、源详情缺 id 时用本次请求的 vodId 补齐
#    历史去重键是 (sourceKey, vodId)：写入用 vodInfo.id、读取用本次请求的 vodId，id 一空两边就对不上
must    "$DA" 'mVideo\.id = vodId' "源详情缺 id 时用请求 id 补齐（否则历史堆积、点进去转圈）"

echo "===== 磁盘缓存（2026-09-18）＝「播放器本来就要发的请求，顺便在本地留一份副本」 ====="
# 本批唯一的高危项：本机端点（/l1.m3u8、/proxy）**每一集都是同一个固定地址**，
# 而 Exo 缓存 key 默认取 URI ⇒ 不做排除就必然「换集之后还在播上一集」。这条必须留断言防回退。
must    "$ECC" 'isCacheableUrl' "缓存前必过「地址可不可缓存」判据"
must    "$ECC" 'setEnabled' "开关状态要能被 App 层（设置页）写入"
must    "$ECC" 'sEnabled = true' "默认开（磁盘缓存不增加任何网络请求，属零风控项）"
ECC_LINE=$(grep -n 'static boolean isCacheableUrl' "$ROOT/$ECC" | head -1 | cut -d: -f1)
if [ -n "${ECC_LINE:-}" ]; then
  ECC_BODY=$(sed -n "${ECC_LINE},$((ECC_LINE+30))p" "$ROOT/$ECC")
  if echo "$ECC_BODY" | grep -q '127\.0\.0\.1' && echo "$ECC_BODY" | grep -q 'return false'; then
    echo "    OK  本机端点被排除在缓存之外（换集不会命中上一集残留）"
  else
    echo "    !!  isCacheableUrl 缺「本机端点 → 不缓存」判据，换集会播上一集"; FAIL=1
  fi
else
  echo "    !!  找不到 isCacheableUrl"; FAIL=1
fi

# 起播接管：开关 + 可缓存性两道闸，任一道不过就走改造前的直连路径
must    "$EPL" 'ExoCacheConfig\.isEnabled\(\)' "起播是否缓存受设置页开关控制"
must    "$EPL" 'ExoCacheConfig\.isCacheableUrl\(path\)' "起播地址要过可缓存性判据"
mustnot "$EPL" 'getMediaSource\(path, headers, false, errorCode\)' "不得再硬编码不缓存（这正是磁盘缓存代码齐备却从未启用的原因）"

# 缓存库：上限统一取配置（不再写死 512MB）、建不起来必须退回直连
must    "$EX" 'ExoCacheConfig\.getMaxBytes\(\)' "缓存上限统一取配置（不再写死 512MB）"
mustnot "$EX" '512 \* 1024 \* 1024' "缓存上限不得再写死 512MB"
must    "$EX" 'setEventListener\(mCacheListener\)' "命中率观测要挂上（否则无法判断缓存有没有真用上）"
must    "$EX" 'onCachedBytesRead' "命中字节数必须落日志"
NEWC_LINE=$(grep -n 'private Cache newCache()' "$ROOT/$EX" | head -1 | cut -d: -f1)
if [ -n "${NEWC_LINE:-}" ]; then
  NEWC_BODY=$(sed -n "${NEWC_LINE},$((NEWC_LINE+24))p" "$ROOT/$EX")
  if echo "$NEWC_BODY" | grep -q 'catch (Throwable' && echo "$NEWC_BODY" | grep -q 'return null'; then
    echo "    OK  缓存库建不起来时返回 null（调用方退回直连，不会把播放一起拖垮）"
  else
    echo "    !!  newCache 缺异常兜底：缓存失败会连播放一起拖垮"; FAIL=1
  fi
else
  echo "    !!  找不到 newCache"; FAIL=1
fi

# 开关的两个同步点：改了就推（不必重启）＋ 启动/初始化拉一次（否则关掉后重启会被静默丢弃）
must    "$SA" 'HawkConfig\.EXO_DISK_CACHE' "设置页要有「磁盘缓存」开关"
must    "$SA" 'ExoCacheConfig\.setEnabled' "改开关要立刻推给播放器（否则要重启才生效）"
must    "$PH" 'ExoCacheConfig\.setEnabled' "启动/初始化时把开关同步给 player 模块（否则关掉后重启会被静默丢弃）"

# 进程启动时清理上次会话的缓存（用户 09-18 口径：**软件内退出保留、完全退出软件才清**，
# 目的是不让它长期占用用户存储）。关键从来不是"清"，而是"怎么清"：
#   · 同步递归删 200~300MB 要 0.5~2 秒 ⇒ 拖慢启动，且与播放争同一个目录；
#   · 改名只动一个目录项，与目录大小无关（毫秒级），改完播放器自动新建同名新目录
#     ⇒ 写的是新目录、删的是旧目录，零竞态；删一半被杀也安全（下次启动扫到残留继续删）。
must    "$ECC" 'purgeStaleOnProcessStart' "缓存清理入口在（缺了则上次会话的缓存永远留在用户机器上）"
must    "$ECC" 'renameTo' "清理用改名而非同步递归删（否则拖慢启动，且与播放争同一目录）"
must    "$ECC" 'STALE_PREFIX' "待删目录要有独立前缀（否则分不清「正在用」与「待删」）"
must    "$EX" 'ExoCacheConfig\.CACHE_DIR_NAME' "创建点引用统一常量（两处各写一份字面串迟早漂移：清了但没清到）"
mustnot "$EX" '"exo-video-cache"' "创建点不得再写死目录名（必须与清理点共用同一常量）"
count   "app/src/main/java/com/github/tvbox/osc/base/App.java" 'ExoCacheConfig\.purgeStaleOnProcessStart' 1 "只在进程启动时清一次（软件内退出走不到 onCreate，缓存才能保留）"

# ===== 订阅源解析容错（2026-09-19）=====
# 症状（用户实报）：①改了订阅地址，界面显示新地址、实际请求的还是**上一个源的线路**
#   （多仓的 getEffectiveUrl() 取 activeLineUrl，而编辑时这三个字段没跟着作废）；
#   ②漏写 http:// 的地址被拼成 "http://" 请求必败（某条线路"怎么都拉不出来"）；
#   ③地址以 ";pk;" 结尾时 split 丢尾部空串 ⇒ 数组越界；
#   ④裸 base64 的订阅必然"解析配置失败"（原实现只认 [A-Za-z0-9]{8}** 前缀）；
#   ⑤上一次刷新还在跑时到来的切源请求被静默丢弃 ⇒ 界面停在上一个源。
# 回退上面任一项，对应症状立刻复发。
SU=app/src/main/java/com/github/tvbox/osc/util/L1SubUrl.java
SC=app/src/main/java/com/github/tvbox/osc/util/L1SubContent.java
SBA=app/src/main/java/com/github/tvbox/osc/ui/activity/SubscriptionActivity.kt
SUBB=app/src/main/java/com/github/tvbox/osc/bean/Subscription.java

must    "$SU" 'public static String normalize' "订阅地址要统一规范化（BOM/零宽字符/首尾引号/漏写 http://）"
must    "$SU" 'BARE_HOST' "只有裸域名/裸 IP 才补 http（别把「点此复制接口」这类说明文字拼成地址）"
must    "$SC" 'decodeBase64' "裸 base64 的订阅必须解得开（原实现只认 [A-Za-z0-9]{8}** 前缀）"
must    "$SC" 'public static String toJson' "非 JSON 内容（HTML 外壳/前后夹文字/BOM/双层编码）统一收敛出口"
mustnot "$SC" 'Base64\.decode\(|Base64\.encode' "解码不得调用 Android 的 Base64（否则用例表没法在桌面 JVM 上跑）"
must    "$SC" 'if \(json != null\) return json' "解出来的东西必须过 JSON 校验才采信（宁可漏认，绝不误认）"
must    "$AC" 'configUrl = L1SubUrl\.normalize\(apiUrl\)' "漏写协议头不得再被拼成空地址 http://"
mustnot "$AC" 'configUrl = "http://" \+ configUrl' "旧写法必须消失（那一刻 configUrl 还是空串）"
must    "$AC" 'a\.length > 1' "split 丢尾部空串导致的数组越界要挡住"
must    "$AC" 'L1SubContent\.toJson\(jsonStr\)' "本地缓存回读/外部推入路径同样要净化"
must    "$AC" 'L1SubContent\.decodeText' "GBK 源站不得再烂成 U+FFFD（结构是 ASCII 照样解析得过，更难发现）"
must    "$SUBB" 'L1SubUrl\.normalize' "订阅地址写入口（构造函数/setUrl）统一收敛"
must    "$SUBB" 'ls\.isEmpty\(\) \? "" : ls\.get\(0\)\.getSourceUrl\(\)' "多仓但当前线路为空时回落第一条（否则把空串写进 API_URL＝切源后变空源）"
must    "$SBA" 'reloadSubscriptionLines' "编辑地址后必须重新识别形态，不得沿用旧仓线路"
must    "$SBA" 'item\.isMultiRepo = false' "地址变更时多仓身份与已选线路要一起作废"
must    "$SBA" 'parseLines' "添加与编辑共用同一套多仓判定（两条路径不一致就会一边残留旧仓、一边丢仓）"
must    "$SBA" 'headers\(OkGoHelper\.subHeaders\(fixed\)\)' "添加阶段请求头要与启用阶段共用同一套（OkGoHelper.subHeaders；原实现两段各写各的）"
must    "$HF" 'mPendingReload' "刷新在跑时到来的切源请求要记下来补刷（丢弃＝切了源还显示旧线路数据）"
must    "$HF" 'schedulePendingReload' "待补刷要有真实出口，不能只置位不消费"

# ===== 订阅源解析第二批：内容层 / 请求头 / 结果层 + 安全DNS 回退（2026-09-19）=====
# 症状：①源站把配置改坏（返回网页/半截 JSON）时，本地明明有好的缓存却直接报错，首页一个源都没有；
#   ②顶层是数组 [{...}]、base64 里包 gzip、带块注释或尾逗号的订阅一律"解析失败"；
#   ③开防盗链的源站缺 Referer 直接 403；
#   ④失败只提示"解析配置失败"，用户不知道该改地址还是换源；
#   ⑤订阅解析出 0 站点却算"启用成功"，用户看到的是"全都成功、就是搜不出东西"；
#   ⑥安全DNS 默认关闭 ⇒ DNS 被污染的那类源默认状态下完全不可用；
#   而直接改成默认开启会踩内嵌 DoH 的两个坑（private host 直接抛异常且不回退 + 需等满一次超时）。
# 回退任一项，对应症状立刻复发。
SN=app/src/main/java/com/github/tvbox/osc/util/L1SubContent.java
SH=app/src/main/java/com/github/tvbox/osc/util/L1SubHeaders.java
SD=app/src/main/java/com/github/tvbox/osc/util/L1DnsPolicy.java
SF=app/src/main/java/com/github/tvbox/osc/util/L1FallbackDns.java
SO=app/src/main/java/com/github/tvbox/osc/util/OkGoHelper.java
SA=app/src/main/java/com/github/tvbox/osc/ui/activity/SettingActivity.kt

# —— A2 顶层数组 ——
must    "$SN" 'startsWithArray' "顶层数组要单独走一条路（否则用首个 { 到末个 } 切出 {a},{b} 这种半截合法内容，一路带到 Gson 才炸）"
must    "$SN" 'pickFromArray' "数组元素要按括号配对挑，不能 indexOf（元素内部有嵌套括号、字符串里也可能出现花括号）"
# —— A3 宽松清洗 ——
must    "$SN" 'cleanJson' "块注释与尾逗号的清洗出口"
mustnot "$SN" 'while \(i < s\.length\(\) && s\.charAt\(i\) !=' "行注释不能在这里删：换行已被上游 stripInvisible 删掉，删到行尾＝删到字符串末尾（曾把整份配置吃光、只留一个 { ）"
must    "$SN" 'looksParsable' "返回结果要过守门（括号平衡 + 字符串闭合 + 无注释残留），否则半截产物会被当结果返回"
# —— A4 压缩编码 ——
must    "$SN" 'maybeInflate' "base64 解出来是 gzip/zlib 的要解压（「压缩后再编码」是很常见的发布形态）"
must    "$SN" 'MAX_INFLATE' "解压必须设输出上限（解出多少由入参决定，不设限会被构造过的包吃光内存）"
must    "$SN" 'GZIPInputStream' "gzip 有独立头尾，必须用 GZIPInputStream（deflate 的 Inflater 解不了）"
# —— A5 请求头 ——
must    "$SH" 'public static String referer' "防盗链用的 Referer 取源站根地址（路径里常带 token，不该透传）"
must    "$SO" 'public static HttpHeaders subHeaders' "订阅请求头要有单一出处（两段共用，避免再次各写各的）"
must    "$AC" 'OkGoHelper\.subHeaders\(configUrl\)' "启用阶段必须用统一请求头"
# 注：这里**故意不加**「ApiConfig 里不得再出现 headers("User-Agent", userAgent)」这类断言。
# downloadJar() 的 jar 下载保留了那两行，是**有意**的：jar 来自任意 CDN，给它带源站
# Referer / Accept-Language 没有收益，语义上也与"拉订阅配置"不是一回事。
# 订阅拉取是否用了统一头，由上面那条正向断言守住（删掉/改回老写法就会失败）。
# —— A1 缓存回退 ——
count   "$AC" 'useCacheFallback' 3 "缓存回退＝一个实现 + 两条调用路径（内容坏了 / 网络重试用尽）"
must    "$AC" 'if \(useCacheFallback\(apiUrl, cache, activity, "订阅内容异常' "内容坏了要有缓存回退（一次坏响应不能让首页一个源都没有）"
# —— A6 准确结论 ——
must    "$SN" 'public static String diagTip' "要能给出面向用户的**具体**结论（空响应 / 返回的是网页 / 内容不是订阅配置）"
must    "$AC" 'L1SubContent\.diagTip' "启用阶段失败提示要用具体结论"
must    "$SBA" 'L1SubContent\.diagTip' "添加阶段失败提示要用具体结论"
mustnot "$AC" 'callback\.error\("解析配置失败"\)' "不得退回笼统的「解析配置失败」"
must    "app/src/main/java/com/github/tvbox/osc/util/ToastSweeper.java" '"订阅内容异常", "订阅拉取失败", "订阅里没有可用站点"' "新文案必须进 Toast 白名单（isHostToast 是允许列表，不在里面的会被当 jar 弹窗移除）"
# —— A7 0 站点不再算「成功」 ——
must    "$AC" 'getLastSiteCount' "要把实际站点数带出来（站点池为空在各层都是「正常返回」，只有数量能区分「装完了就是空」）"
must    "$HF" 'warnIfNoSite' "首页要提示「订阅里没有可用站点」（否则用户只看到「全都成功却搜不出东西」）"
# —— B1 安全DNS 回退 ——
must    "$SD" 'public static boolean directHost' "私有地址/IP 字面量判定要独立成纯逻辑（有桌面用例表）"
must    "$SF" 'implements Dns' "回退层要实现 OkHttp 的 Dns"
must    "$SF" 'doh\.url\(\) == null \|\| L1DnsPolicy\.directHost\(hostname\)' "三重前置判断必须合在同一行、在**任何一次 DoH 调用之前**生效（内嵌 DoH 对 private host 直接抛异常且不回退 ⇒ 127.0.0.1 的净化/网盘/首页路由会全挂）"
must    "$SF" 'policy\.cooling' "DoH 失联要熔断（否则每个未命中域名都白等一次超时，整体变慢）"
must    "$SO" 'builder\.dns\(fallbackDns\)' "注入点必须挂回退层"
mustnot "$SO" 'dns\(dnsOverHttps\)' "不许再裸挂 DoH（裸挂＝私有地址抛异常 + 失败对外不可见、无法熔断）"
must    "$SO" 'DOH_TIMEOUT_MS' "DoH 客户端要有短超时（默认 10 秒会让整个 App 的网络一起变慢）"
must    "app/src/main/java/okhttp3/dnsoverhttps/DnsOverHttps.java" 'throw new UnknownHostException\("doh failed' "DoH 失败要如实抛出，回退职责交给外层（否则外层无从感知失败、无法熔断）"
# —— B2 默认开启 + 一次性迁移 ——
must    "$SO" 'DEFAULT_DOH = 1' "安全DNS 出厂默认开启（前提是上面三条回退/熔断就位）"
must    "app/src/main/java/com/github/tvbox/osc/base/App.java" 'putDefault\(HawkConfig\.DOH_URL, OkGoHelper\.DEFAULT_DOH\)' "App 的默认值要与 OkGoHelper 同源，不允许两处各写一个数字"
must    "app/src/main/java/com/github/tvbox/osc/base/App.java" 'upgradeDohDefault' "老用户 Hawk 里存的旧默认值(0)要一次性抬起来（改 putDefault 对他们无效）"
must    "app/src/main/java/com/github/tvbox/osc/base/App.java" 'DOH_UPGRADED' "迁移只能执行一次（否则用户主动关掉后每次启动又被改回去）"
must    "$SA" 'Hawk\.put\(HawkConfig\.DOH_USER_SET, true\)' "用户在设置页选过安全DNS 要留痕（以后默认值调整不得再干涉他的选择）"

# ================= bn（2026-09-22）：搜索线观测 + DoH 注入点收口 =================
# 症状：用户报「这几轮改了 DNS 之后搜索变慢」。排查确认 DoH 会从**共享注入点**漏进 spider 侧
# （不只 FastSearchActivity 有没有被改这一件事），而上一次改动恰好把 DoH 的
# 「无结果 → 静默回退系统 DNS」改成了「无结果 → 如实抛出」⇒ 只要还有裸注入点，
# 那些调用方就从「慢一点但能通」变成「直接不通用」。以下三条守住"不许再有裸注入点"。
SPIDER="app/src/main/java/com/github/catvod/crawler/Spider.java"
RSRV="app/src/main/java/com/github/tvbox/osc/server/RemoteServer.java"
FSRCH="app/src/main/java/com/github/tvbox/osc/ui/activity/FastSearchActivity.kt"
must    "$SPIDER" 'return OkGoHelper\.fallbackDns' "jar 侧 safeDns() 必须返回回退包装层（裸 DoH：私网/IP 字面量直接抛 + DoH 失败直接抛到 jar 里）"
mustnot "$SPIDER" 'return OkGoHelper\.dnsOverHttps' "不许返回裸 dnsOverHttps（这是 bm 注释里写明的前置条件，此前闸门只查了 builder.dns(...) 这一种写法）"
must    "$RSRV" '本机 /dns-query 上游 DoH 失败' "本机 DNS 端点上游失败要留痕（原先静默返回空应答，日志里看不出是 DoH 挂了）"
must    "$FSRCH" 'L1FallbackDns\.delta' "搜索收尾要带 DNS 活动差值（「安全DNS 有没有参与、有没有在拖」的唯一判据）"
must    "$FSRCH" '搜索耗时：' "要有整轮耗时埋点（不许再靠体感判断快慢）"
must    "$FSRCH" '搜索首条结果：' "要有首条结果耗时埋点（体感来自「多久看到第一条」而不是整轮结束）"
must    "$FSRCH" '本轮装载增量' "要打本轮 jar 装载增量（用来区分「冷轮装载贵」与「站点网络慢」）"

echo "===== AE. P1 重新取链档 + 搜索等待 20s + 初始化提示时机（2026-09-22 拍板，A8+A组实测） ====="
# 背景：09-22 两例播放失败（播放中上游 404、起播兜底试尽——净化命中后 7.4s 无起播，10 秒处报错）
# 都在同一条死链上打转，唯一有效的动作「重新取链」只能靠用户手动点重试（实测立刻救回 403/627ms）。
# 拍板：把该动作自动化，但必须带护栏 —— once（本集一次）+ 起播阶段总时长上限；
# errorFinal 仍是终态出口。同时搜索等待预算 5s→20s（就绪实测最长 ≈+16.5s）。
must    "$PF" 'tryRefetchOnce' "兜底链必须先尝试一次自动重新取链再进终态出口（09-22 两例实证手动重取立刻救回）"
must    "$PF" 'if \(mRefetchTried\) return false;' "重新取链必须有 once 护栏（本集一次，否则回到 errorFinal 注释警告的无界重取）"
must    "$PF" 'REFETCH_TOTAL_BUDGET_MS = 20000' "起播阶段的重新取链必须有总时长上限（防「每一步都空转」病理复活）"
must    "$PF" 'mPlaybackHadStarted && tryRefetchOnce\("播放中断", false\)' "播放中断（就绪过）的重取不得受取链预算约束（地址已被证明有效，中途死亡可在任意时刻）"
must    "$PF" 'tryRefetchOnce\("起播兜底试尽", true\)' "起播兜底试尽的重取必须受总时长上限约束"
must    "$PF" 'mPlaybackHadStarted = true' "「播放中断」必须以 prepared 为前提（不得拿起播尝试当就绪）"
mustnot "$PF" 'if \(mPlaybackHadStarted && tryRefetchOnce\("播放中断", true\)\)' "播放中断的重取不得套总时长上限（会永远轮不到重取）"
must    "$PF" 'mRefetchTried = false; // P1：换集' "换集必须复位 once 护栏（每集独立一次）"
must    "$PF" 'mRefetchTried = false; // P1：用户手动重试' "手动重试必须同时复位 once 护栏（用户重试后自动重取额度要重新给满）"
must    "$FSRCH" 'READY_RETRY_MAX = 40' "搜索等待预算必须是 20s（09-22 拍板，见 X 节）"

if [ $FAIL -ne 0 ]; then
  echo "!! 回归闸门未通过：上面每一项都是修过的老问题，请先修好再出包" >&2
  exit 1
fi
echo "===== 回归闸门全部通过 ====="

