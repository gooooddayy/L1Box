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
must    "$PF" 'rec < 10000' "续播位置不足 10 秒不提示（刚开头弹提示是打扰）"
must    "$PF" 'showResumeJumpTip\(rec\)' "断点续播改走**画面中央可点的「点击跳转 xx:xx」**（09-23 #5：进场从头播，点它才跳）"
mustnot "$PF" '已从 ' "旧的「已从 XX:XX 继续播放」底部 Toast 不得回归（已被中央浮层取代）"
must    "$PF" '已跳过片头 ' "片头跳过有「已跳过片头 XX 秒」提示"
mustnot "$PF" 'CacheManager\.save\(MD5\.string2MD5\(url\)' "播放进度不得写回 cache 表（与历史/收藏同库，加字段要换库文件＝历史全丢）"
must    "$SW" '已跳过片头 ' "片头提示已进 Toast 白名单（否则被清扫器当 jar 弹窗移除，用户看不到）"
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
must    "$FS" 'searchAdapterFilter\.setNewData\(if \(list == null\) ArrayList\(\) else ArrayList\(list\)\)' "过滤视图必须复制桶（不与 adapter 共享引用）"
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
must    "$JL" 'if \(isNativeWindow\(key\)\) return proxyDeny\(' "未就绪窗口内不碰 native，直接返回 503（走统一拒绝出口，便于留诊断日志）"
must    "$JL" 'private boolean isNativeWindow' "门禁判据集中在 isNativeWindow"
# 💡 2026-09-28 修正：这条原来是 `!key.equals(proxyHolderKey())` —— 它把**手绑成功**的 jar 也一起挡了。
# 手绑成功（"承接已有的本地代理"，getLoader 已连上正在跑的代理）本身就证明原生引用有效，却因为
# occupyProxy() 只在"jar 自己重启代理"时被调用而永远进不了 holder 名单 ⇒ 它的代理型站点被**永久** 503。
# 实测：`WexAiYueYue` 下发的 `127.0.0.1:9978/proxy?do=…` 被瞬时拒绝（预取 7ms 失败、jar 的 localProxy
# 从未被调用），cb/cc/dd **三轮 /proxy 一次都没成功**。现在改由 proxyCapable() 判定：holder 照旧放行，
# 手绑 jar 只有在"绑定时那一任代理至今仍活着"（holder 与世代都没变）时才放行 —— 防线不降级。
mustnot "$JL" '!key\.equals\(ProtectedInitJar\.proxyHolderKey\(\)\)' "不得再用「必须是 holder」单判据（会把合法的代理持有者换掉）"
must    "$JL" 'protectedJarKeys\.contains\(key\) && !ProtectedInitJar\.proxyCapable\(key, null\)' "非代理持有者的加固 jar 才事前 503（手绑且世代有效者必须放行）"
must    "$JL" 'ProtectedInitJar\.proxyCapable\(key, mcl\)' "ClassLoader 终审同样要给「手绑且世代有效」留出口（否则第一道放行、第二道又挡回去）"
must    "$PI" 'static boolean proxyCapable\(String key, ClassLoader loader\)' "代理可用性必须由 ProtectedInitJar 单一裁决"
must    "$PI" 'PROXY_GEN\.incrementAndGet\(\)' "持有者每接管一次，代理世代必须 +1（否则旧的手绑引用会被误判成活着的）"
count   "$PI" 'PROXY_GEN' 3 "世代号必须齐备：声明/接管自增/诊断打印（已不参与放行判据，见 cf 组）"
must    "$PI" 'hb\.holderKey\.equals\(PROXY_HOLDER_KEY\)' "手绑放行必须校验「绑定时那一任持有者仍是当前持有者」"
# ⚠️ 原来这里断言的是 `hb.gen != PROXY_GEN.get()`（放行时比世代号）——
# ce 轮实测证明那是 bug：世代号每次 occupyProxy 都 +1、与「这个 jar 还能不能用当前代理」无关，
# 导致先手绑的 jar 必然被后续接管顶掉、站点 100% 503。该断言已改到 cf 组（mustnot hb.gen）。
must    "$PI" 'rememberHandBound\(clz, key\)' "手绑成功必须记账（否则 /proxy 永远进不来）"
must    "$JL" 'private void proxyLog\(' "/proxy 必须有诊断日志出口（原来全程静默，失败了查不到原因）"
must    "$JL" '本地代理：do=' "诊断日志必须打出 do / key / 持有者 / 判定 / 耗时"
must    "$JL" 'proxyLogAt' "诊断日志必须节流（HLS 分片会把日志冲爆）"
must    "$JL" 'private static String briefJarKey' "日志里 jar key 必须截断（可读性）"
must    "$RS" 'proxy unavailable' "/proxy 拿不到结果时必须明确回 503（原来是 NPE ⇒ 500 ⇒ 播放器只看到 Exo 2004）"
must    "$RS" '!\(rs\[0\] instanceof Integer\)' "/proxy 必须对返回结构做空安全，不得再直接 (int) rs[0]"
mustnot "$RS" 'int code = \(int\) rs\[0\];' "不得再无条件强转 rs[0]（proxyInvoke 返回 null 时就是 NPE）"
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
must    "$JL" 'if \(ProtectedInitJar\.nativeInitInProgress\(\)\) return proxyDeny\(' "/proxy 入站必须先查进程级初始化标志（早于按 key 的判断）"
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
must    "$FS" 'private fun handleNoSearchableSite\(\)' "无可搜站点必须分流：未就绪→等待／订阅未加载成功→单独成态／其余→诚实空态（09-23 #2 定稿）"
must    "$FS" 'if \(!ApiConfig\.get\(\)\.isSitePoolSettled\(\)\) \{' "池未定论时不得判空，必须走等待（S1 没有任何通往「暂无数据」的路径）"
mustnot "$FS" 'readyRetry < READY_RETRY_MAX' "等待不得再有时间上限（09-23 #2 定稿：等够时间＝没有结果，逻辑上不成立）"
must    "$FS" 'postDelayed\(waitTickRunnable, READY_RETRY_DELAY_MS\)' "等待必须真的排上探池定时任务（纯内存读，零网络请求）"
must    "$FS" 'readyRetry = 0' "探池节拍计数必须在新一轮搜索入口复位"
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
must    "$FS" 'private fun reviewZeroResult\(\)' "0 结果必须统一走复检，不得各出口自行判空（09-23 起由 verdictEmpty 改名为 reviewZeroResult，语义更好且新增四条完整性判据）"
count   "$FS" 'reviewZeroResult\(\)' 3 "复检函数本体 + 两处收尾调用（首波归零、看门狗超时）"
must    "$FS" 'sigNow == roundPoolSig' "不可信判据之一＝池签名（池大小+定论+装载序号）与发起时逐字相同"
must    "$FS" 'poolReady && askedAll && unchanged && zero' "四条完整性判据必须同时成立才允许给空态（判据由「时间」改成「完整性」是 09-23 #2 的核心）"
must    "$FS" 'removeCallbacks\(waitTickRunnable\)' "页面销毁要清掉探池定时任务"
must    "$SW" '正在初始化播放源' "新 Toast 文案必须进宿主白名单，否则会被当 jar 弹窗移除（白名单是允许列表）"
mustnot "$FS" 'AUTO_RESEARCH_MAX' "重搜不得再有写死次数上限（09-23 #2：改由「池每变一次放行一次」，天然收敛且不空转）"
must    "$FS" 'LOADING_SWITCH_AT_RETRY' "20 秒后只把文案换成「播放源仍在加载」，继续转圈继续等（不是失败）"
must    "$FS" 'INIT_HINT_AT_RETRY' "等待 ≈2s 就要给「正在初始化」提示（09-22 拍板 Q1：不能等到失败才说，观感是提示→马上失败）"
must    "$FS" 'fromWaitingRetry: Boolean = false' "搜索诊断基线必须区分「等池」入口，否则收尾总耗时是假计时（实测 总耗时=7ms）"

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
must    "$PF" 'purifyFailKind' "净化预取失败要归因（区分本机代理地址与远端，用于统计而不是猜）"
# 2026-09-28：原来只打异常**类名**，`HttpException` 把 403/404/502 混成一个字符串，而三者修法毫无
# 共通之处（403=防盗链补头、404=地址失效重取无意义、5xx=源站问题）⇒ 必须把状态码打出来。
must    "$PF" 'com\.lzy\.okgo\.exception\.HttpException' "净化失败必须取到 HTTP 异常对象"
must    "$PF" '"Http" \+ ' "净化失败必须把 HTTP 状态码打进埋点"
mustnot "$PF" 'String type = \(th == null\)' "不得再只打异常类名（状态码被丢掉就永远定不了刀）"
must    "$PF" '连接超时' "超时必须与连接失败分开"
must    "$PF" 'DNS解析失败' "DNS 解析失败必须单独成类"
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
must    "$PF" 'tryRefetchOnce' "兜底链必须先尝试自动重新取链再进终态出口（09-22 两例实证手动重取立刻救回）"
must    "$PF" 'REFETCH_MAX = 2' "重新取链上限 1→2（09-23 用户拍板；只在起播已判失败之后发生，不拉长正常起播）"
must    "$PF" 'if \(mRefetchTried >= REFETCH_MAX\) return false;' "重取护栏必须是**计数式**上限（退回布尔 once 就等于只重取一次）"
mustnot "$PF" 'if \(mRefetchTried\) return false;' "不得退回 once 布尔护栏（09-23 已放宽到 2 次）"
must    "$PF" 'REFETCH_TOTAL_BUDGET_MS = 20000' "起播阶段的重新取链必须有总时长上限（防「每一步都空转」病理复活）"
must    "$PF" 'mPlaybackHadStarted && tryRefetchOnce\("播放中断", false\)' "播放中断（就绪过）的重取不得受取链预算约束（地址已被证明有效，中途死亡可在任意时刻）"
must    "$PF" 'tryRefetchOnce\("起播兜底试尽", true\)' "起播兜底试尽的重取必须受总时长上限约束"
must    "$PF" 'mPlaybackHadStarted = true' "「播放中断」必须以 prepared 为前提（不得拿起播尝试当就绪）"
mustnot "$PF" 'if \(mPlaybackHadStarted && tryRefetchOnce\("播放中断", true\)\)' "播放中断的重取不得套总时长上限（会永远轮不到重取）"
must    "$PF" 'mRefetchTried = 0; // P1：换集' "换集必须复位重取额度（每集独立）"
must    "$PF" 'mRefetchTried = 0; // P1：用户手动重试' "手动重试必须同时复位重取额度（用户重试后自动重取额度要重新给满）"
must    "$FSRCH" 'LOADING_SWITCH_AT_RETRY = 40' "搜索等待 20s 的语义保留（09-23 起它只切文案、不再判失败，见 V/W 节）"

echo "===== AF. 2026-09-23 十项改动（#1~#10 定稿批次） ====="
# 背景：用户一次提十条体验问题，逐条定稿后实施（见 _l1_docs/00_总纲与索引/本轮10项需求改动方案_定稿_20260923.md）。
# 每条下面都写清「回退后会退到什么症状」。判据一律取**语义**（关键判据 / 调用形态），不锚某行原文。
SAD=app/src/main/java/com/github/tvbox/osc/ui/adapter/SourceAdapter.java
CSD=app/src/main/java/com/github/tvbox/osc/ui/dialog/ChooseSourceDialog.java
HH=app/src/main/java/com/github/tvbox/osc/util/HistoryHelper.java
RDM=app/src/main/java/com/github/tvbox/osc/cache/RoomDataManger.java
HKC=app/src/main/java/com/github/tvbox/osc/util/HawkConfig.java
MPC=player/src/main/java/xyz/doikki/videoplayer/controller/MediaPlayerControl.java
VV=player/src/main/java/xyz/doikki/videoplayer/player/VideoView.java
LST=app/src/main/res/layout/activity_setting.xml

# #1 搜索历史词长按直接删除（回退：长按无反应）
must    "$FS" 'private fun removeSearchHistory\(word: String\)' "长按删除单条历史要有实体实现"
must    "$FS" 'history\.remove\(word\)' "必须按**文本**删除：删一条后其余条目整体前移，按下标会删错"
must    "$FS" 'getChildAt\(i\)\.setOnLongClickListener' "长按必须挂在 TagFlowLayout 包出来的容器上（库对标签控件 setClickable(false) 并自己吃掉按钮事件，挂控件上会让单击搜索失效）"

# #2 搜索假空（结构性地消灭；单独成态的那部分在 V/W 节）
must    "$FS" 'private enum class Phase \{ S1, S2, S3, S4, S5, SUB_FAIL \}' "四态状态机要在：S1 等待可搜／S2 搜索中／S3 复检／S4 诚实空态（S1 无上限、且没有通往空态的路径）；S5＝A 的补全守卫（09-24 加入）"
must    "$FS" 'private fun poolSignature\(\)' "探池必须有统一的池签名（站点数＋定论＋主jar＋站点集合指纹＋池来源，纯内存读零请求；bv 已把装载序号撤出——它是装载活动计数器，不是池内容变化的信号）"
must    "$FS" 'hasSubscription\(\) && hasSubUrl\(\)' "订阅未加载成功＝池已定论＋池里空＋有订阅地址，必须单独成态而不是冒充「暂无数据」"
must    "$FS" 'private var subReloadTried = false' "同址重拉必须有「每启动最多 1 次」护栏（地址不对时重拉一百次也还是不对）"
must    "$FS" 'loadConfig\(false, object : LoadConfigCallback' "同址重拉必须复用现成的订阅装载入口：**地址一字不改**，是重试不是换源"
must    "$FS" 'TipDialog\(' "订阅失败的出口只留「重试」：复用现成弹窗与布局，不新增界面元素"
mustnot "$FS" '正在初始化播放源，自动重试' "「自动重试…」这种把内部机制外泄、且观感是「提示→马上失败」的文案不得回归"

# #3 播放错误提示：照常弹，起播成功就自动收掉（判死秒数一字不动）
must    "$VC" 'void playing\(\);' "播放器必须把「真的开始播放」回调出来（与 prepared 区分开）"
must    "$VC" 'if \(listener != null\) listener\.playing\(\);' "STATE_PLAYING 必须触发该回调，否则提示会一直压在正在播放的画面上"
must    "$PF" 'public void playing\(\)' "播放页必须实现它：起播成功＝错误提示的使命结束，收起提示层"

# #4 冷启动快速进历史记录显示空白（池未装载完不得丢弃记录）
must    "$RDM" 'boolean poolSettled = ApiConfig\.get\(\)\.isSitePoolSettled\(\)' "读历史时只有「池已定论」才允许丢弃取不到源的记录（池在途就丢弃＝所有记录被静默丢掉→空态）"
must    "$HA" 'showLoading\(\)' "历史页必须有加载态（注册状态框架后不显示加载态＝数据回来前是真空白）"

# #5 续播：进场从头播 + 画面中央「点击跳转 xx:xx」，点它才跳
must    "$VC" 'public void showResumeJumpTip\(long pos\)' "续播提示必须是画面中央可点的浮层（不再自动跳、不再用底部 Toast）"
must    "$CT" 'resume_jump_layer' "中央浮层必须有实体布局"
must    "$VC" 'RESUME_JUMP_SHOW_MS = 5000L' "续播提示停留 5 秒（09-24 用户反馈 2 秒偏短、手还没动就没了）"
must    "$VC" '点击跳转 ' "续播提示文案必须点明「可点击」：只写「跳转到」用户不知道能点"
must    "$CT" 'shape_player_resume_jump_bg' "续播提示必须用独立的半透明淡绿背景，不得与连播浮层共用深色面板（改一边不牵连另一边）"
must    "$CT" 'paddingHorizontal="14dp"' "续播提示内边距收到 14dp（09-24 用户反馈原 24/14dp 的面板「太大画幅」）"
mustnot "$VC" 'setText\("跳转到 ' "旧文案（未点明可点击）不得回归：文案必须与「点击跳转」一致"
must    "$PF" 'return getSkipIntroSec\(\) \* 1000L;' "存档进度不得再回给内核自动跳（VideoView.onPrepared 会立刻 seekTo）：跳不跳交给用户点"
mustnot "$PF" 'mResumeTipPos' "旧的「底部 Toast ＋ 自动跳」承载字段必须删干净"

# #6 线路列表高亮当前线路（旧写法恒 null，从未生效）
must    "$SAD" 'public void setSelectedUrl\(String url\)' "高亮必须由「当前线路地址」驱动"
must    "$CSD" 'setSelectedUrl\(pickCurrentUrl\(\)\)' "选中态必须在 setNewData **之前**设置"
mustnot "$CSD" '\.findViewHolderForAdapterPosition\(' "setNewData 之后立刻取 ViewHolder 恒为 null，这种**调用**不得回归（注释里提它是允许的：要留痕说明为什么不能这么写）"

# #7 播放器画质显示按旋转角对调宽高（回退：90°/270° 的片子显示成竖着的假分辨率）
must    "$MPC" 'int getVideoRotation\(\);' "内核必须能把旋转角给出来（内核加取值接口）"
must    "$VV" 'mVideoRotation = extra;' "旋转角必须真的被记下来（MEDIA_INFO_VIDEO_ROTATION_CHANGED）"
must    "$VC" 'getVideoRotation\(\) % 180\) != 0' "显示画质时必须按旋转角对调宽高"

# #8 外部播放器（MX）约 10 秒结束 → 改喂本机转发端点
must    "$PH" 'public static String wrapForExternalPlayer' "外部播放器入口必须统一收口到包装函数（唯一改法，避免各播放器各改一遍）"
# 2026-09-24 bt **撤回**旧口径「没有请求头就原样返回」：真机 D1 实测证明它正是根因 ——
# 站点直链普遍不下发 headers，这条判据把绝大多数外部播放都静默退回了（日志里连包装埋点都不出现），
# MX 拿到裸地址 → 上游 403 → 约 12 秒结束。零开销的边界因此收窄为下面两条，缺头一律先补同源 Referer。
must    "$PH" 'if \(url == null \|\| url\.isEmpty\(\)\) return url;' "空地址原样返回（零开销边界一）"
must    "$PH" 'if \(isLoopbackUrl\(url\)\) return url;' "已是本机地址原样返回（零开销边界二）"
mustnot "$PH" 'playerType == 13' "RemoteTVBox 是把地址 **POST 给局域网另一台设备**的：塞 127.0.0.1 会指到那台设备自己，必须排除"
must    "$RS" 'private Response servePlayForward' "本机转发端点必须存在"
# 2026-09-24 bt **改写**旧口径「/l1play 与 localfile 同一道仅供本机门禁」：投屏（DLNA 设备自己拉流）
# 必须经局域网可达的本机转发才带得上请求头，所以门禁改为「带一次性投屏码才放行，没码仍只允许本机」。
# 这里只断「无码那一档没丢」；门禁行不得整条放开由 AH 节的正向断言负责。
must    "$RS" 'if \(!local\) \{' "servePlayForward 内必须保留「非本机且无码 ⇒ 拒绝」这一档"
must    "$RS" 'rb\.header\("Range", range\);' "必须透传 Range（少了这步只能播不能拖）"
must    "$RS" 'resp\.addHeader\("Content-Range", cr\);' "必须原样回传 Content-Range（少了这步只能播不能拖）"
must    "$RS" 'header\("Accept-Encoding", "identity"\)' "必须关掉 OkHttp 透明 gzip：否则长度/范围按解压后字节重算，Range 的字节账对不上"
must    "$RS" 'probe\.addHeader\("Content-Length"' "HEAD 探测必须回真实长度且**不**发正文（NanoHTTPD 对 HEAD 仍会照发正文，必须自己掐掉）"

# #9 缩放：设置页＝全局；播放器内＝只对当前影片当前线路
must    "$PH" 'isScalePicked' "「用户单独调过缩放」必须留痕（否则分不出「跟全局走」与「已经单独调过」）"
must    "$PF" 'PlayerHelper\.isScalePicked\(mVodPlayerCfg\)' "无标记时必须**每次播放实时读**设置页全局值（原来只在首次播放抄一次、抄完就落盘）"
must    "$VC" 'PlayerHelper\.markScalePicked\(mPlayerConfig\)' "播放器内改缩放必须打标记并存档（此后设置页再改不动它）"

# #10 设置页删两个条目 + 条数固定 50
must    "$HH" 'HIS_NUM = 50' "历史保留条数固定 50"
must    "$RDM" 'HistoryHelper\.HIS_NUM' "历史查询上限必须取统一的 50，不得再写死别的数"
mustnot "$HKC" 'HISTORY_NUM' "条数选择器的配置键必须删干净"
mustnot "$SA" 'tvHistoryNum' "设置页「历史记录」条目必须撤除（含代码绑定）"
mustnot "$SA" 'tvCrashLog' "设置页「导出崩溃日志」条目必须撤除（崩溃日志本身仍照常落盘）"
mustnot "$LST" 'llHistoryNum' "设置页「历史记录」布局必须撤除"
mustnot "$LST" 'llCrashLog' "设置页「导出崩溃日志」布局必须撤除"

echo "===== AG. 2026-09-24 A（缓存清单先开搜 · 档 2：预热＋自动补全）====="
# 背景：冷启动时站点池要等**网络**拉订阅（实测就绪 4.7~16.5 秒），这段真空里搜索只能转圈。
# 方案：先用「本地址上次成功拉取的原文」把池填上（打开就能搜），网络配置到达后**自动补全** ——
#       只搜新增站点、结果追加在已有结果后面，不清空不重排。
# 见 _l1_docs/30_成功率与崩溃/缓存清单先开搜_A方案优化定稿_20260924.md。回退任一项，对应症状立刻复发。

# —— 预热：one-shot ＋ 只填数据、不假装"装载完成" ——
must    "$AC" 'private volatile boolean cacheBootDone = false;' "预热必须一次性（一次启动最多读一次缓存文件）"
must    "$AC" '!useCache && !cacheBootDone && !bootPoolFromCache && !hasSubscription\(\) && cache\.exists\(\)' "触发条件必须同时成立；其中「池为空」同时挡住切源场景（切源那一刻池里还是旧源站点，非空 ⇒ 压根不读缓存）"
must    "$AC" 'bootPoolFromCache = true;' "预热成功必须置位，供搜索页分辨「这份定论是缓存给的」"

# —— 假空护栏（本方案的第一红线：不加它会把 #2 刚消灭的假空请回来）——
must    "$AC" 'public boolean isPoolFromCache\(\)' "必须能把「池来自预热缓存」问出来"
must    "$FS" 'val poolReady = settled && !cachePool' "复检判据①必须排除缓存池：缓存轮 0 结果只说明「那份清单里没有」，不说明「这个源里没有」"
must    "$FS" 'val settled = ApiConfig\.get\(\)\.isSitePoolSettled\(\)' "①仍须以「池已定论」为前提：排除缓存池是附加条件，不是替换"
must    "$FS" 'beginWait\("S3 等网络池"\)' "S3 必须有「等网络池」的出口（缓存池 0 结果时的等待落点）"
must    "$FS" 'if \(!settled\) \{' "S3 必须先确认池已定论：池还在途时不得判空"
must    "$FS" '搜索复检：池已稳定且无在途装载' "S3 必须有稳定出口（池定论＋稳定＋无在途装载 ⇒ 承认空态）：堵住「预热后网络失败 ⇒ 无限转圈」"
count   "$AC" 'bootPoolFromCache = false;' 3 "护栏的三个撤除点（预热失败 / 网络 onSuccess / 网络重试用尽）一个都不能少，少一个就会出现「永远等不到池变化」"

# —— 补全守卫（档 2 的核心）——
must    "$FS" 'private fun onGuardTick\(\)' "补全守卫要有实体实现"
must    "$FS" 'private fun beginGuard\(\)' "守卫启动入口要在（正常收尾与超时收尾两条路径都要调）"
count   "$FS" 'beginGuard\(\)' 3 "两处启动点 + 定义处 = 3：只在正常收尾启动会漏掉「部分站点超时」那条路径"
count   "$FS" 'guardDone = true' 2 "守卫必须有自锁（最多补一次），否则 jar 陆续装载会把「补一轮」放大成「补很多轮」"
must    "$FS" 'GUARD_MAX_TICKS = 40' "守卫必须有观测上限（20s，照订阅就绪实测 4.7~16.5s 定的；纯内存探池的兜底出口）"
must    "$FS" 'println\("搜索补全：源无变化，跳过"\)' "池没变化时必须一个请求都不发（不白搜一遍）"
must    "$FS" 'println\("搜索补全：差集="' "补轮埋点：这一轮差集几个站点要看得见"

# —— 补轮：只追加、不清空（用户口径：追加在后、不闪不重排）——
must    "$FS" 'appendOnly: Boolean = false' "searchResult 必须参数化：否则补轮会把已有结果整个洗掉"
count   "$FS" 'if \(!appendOnly\) \{' 3 "补轮不得清空适配器 / 不得清 tab 与站点名表 / 不得重加「全部显示」——三处都必须在 !appendOnly 保护下"
must    "$FS" 'if \(appendOnly && roundAskedKeys\.contains\(bean\.key\)\) \{' "补轮只搜首波没搜过的站点（差集），已搜过的不重复请求"
must    "$FS" 'roundAskedKeys\.addAll\(siteKey\)' "已搜集合必须随每轮累加（它是补轮差集的基准）"

echo "===== AH. 2026-09-24 bt：搜索收敛（A2 无限转圈 / A3 假空）+ 外部播放器转发（D1）+ 投屏转发 ====="
# A2 症状：生僻词搜索**一直停在加载中**。真机实测 68 轮 / 201 秒 / 0 命中 / 从不给空态。
# 根因：loadJarInternal 的 finally 原本无条件 LOAD_SEQ++，而装不上的 jar（加固不可用 / 主 jar 未挂载 /
#       失败）不进 spiders 表，于是每次搜索都被重新查询一遍 → 序号每轮都变 → 池签名永远在变
#       → S3 复检永不收敛，界面上就是转不完的圈。
# 修法：同一个 jar 只在**首次**有装载结论时计一次；load() 里账本随 classLoaders.clear() 一起重置。
# 回退后果：空结果场景原地复活成「无限转圈」。
must    "$JL" 'if \(SEQ_COUNTED\.add\(key\)\) LOAD_SEQ\.incrementAndGet\(\);' "装载序号必须按 jar 去重（A2 无限转圈的开关就在这一行）"
count   "$JL" 'LOAD_SEQ\.incrementAndGet\(\)' 1 "装载序号只能有这一个自增点，且必须带去重条件"
count   "$JL" 'SEQ_COUNTED\.clear\(\);' 1 "load() 里账本必须随 classLoaders.clear() 一起重置，否则重装后不再计数"

# A3 症状：主 jar 未挂载时**8ms 就摆出「暂无数据」**（真机 3/3 复现）——站点依赖主 jar，
#      getSpider 立刻返回空站点，站点被静默记成 0 条。
# 修法：把「主 jar 装载是否已有结论」暴露给搜索侧，装载中一律不判空；并统计被跳过的站点数进日志。
# 回退后果：冷启动首搜 / 刚进 App 就搜，又会闪一个「暂无数据」。
must    "$JL" 'public static boolean mainSettled\(\)' "必须能问出主 jar 装载是否已有结论"
count   "$JL" 'MAIN_INFLIGHT\.decrementAndGet\(\);' 1 "主 jar 装载状态必须在 finally 里归零，否则搜索会一直等一个已经结束的装载"
must    "$JL" 'public static long spiderNullSeq\(\)' "必须有「被跳过的空站点」计数（真机判据：问了没结果 vs 根本没问）"
count   "$JL" 'return skipNull\(key\);' 3 "三处跳过（主 jar 未挂载 / 加固不可用 / 构造异常）都要计数，少一处就少一份证据"
# 注意：Kotlin 的运算符在**行尾**，所以 `+` 与 JarLoader.mainSettled() 不在同一行，正则只断后者。
must    "$FS" '^\s*JarLoader\.mainSettled\(\)' "池签名必须含主 jar 装载状态（装完那一刻签名要变，S3 才会重搜）"
must    "$FS" 'val mainSettled = JarLoader\.mainSettled\(\)' "复检必须有第五条判据"
must    "$FS" 'if \(!JarLoader\.mainSettled\(\)\) \{' "S3 必须有「等主 jar 结论」的出口，否则装载中仍会判空"
must    "$FS" '搜索复检：0 结果 六条判据' "复检日志必须报六条判据（口径与代码同步）"
mustnot "$FS" '四条' "旧的「四条判据」口径不得残留——它允许在主 jar 装载中就判空"
mustnot "$FS" '五条判据' "bu 之前的五条口径不得残留——它漏了「本轮新面孔被跳过的站点」"
must    "$FS" '跳过=' "收尾摘要必须打出跳过站点数，否则真机分不清「问了没结果」和「根本没问」"

# D1 症状：外部播放器（MX）起来后**约 10~12 秒结束**，上游日志 HTTP 403。
# 根因①：站点直链不下发请求头 ⇒ 包装第一条判据静默退回 ⇒ 播放器拿到裸地址（日志里连包装埋点都没有）；
# 根因②：转发端点误用默认 client，readTimeout=10s ⇒ 流式转发被掐断（这是「另一个 10 秒」）。
# 回退后果：外部播放器在有防盗链的源上必然 403，且长时播放 / 拖动会被 10 秒超时打断。
must    "$PH" 'public static HashMap<String, String> fillMissingHeaders' "兜底补同源 Referer 必须可复用（净化预取与转发两条链路共用一份实现）"
must    "$PH" 'PlayTrace\.stage\("请求头", "补同源 Referer=" \+ origin\);' "补头必须留埋点（真机要能看见「补了什么」）"
mustnot "$PF" 'private static HashMap<String, String> fillMissingHeaders' "PlayFragment 里那份重复实现必须已删除，否则两边口径会漂移"
must    "$PF" 'PlayerHelper\.fillMissingHeaders\(url, rawHeaders\)' "净化链路必须改调共用实现"
must    "$OG" 'public static OkHttpClient getForwardClient\(\)' "转发端点必须有独立 client"
must    "$OG" 'readTimeout\(0, TimeUnit\.MILLISECONDS\)' "转发 client 的读超时必须不设限"
must    "$RS" 'OkGoHelper\.getForwardClient\(\)' "转发端点必须真的用那个 client"
mustnot "$RS" 'okhttp3\.OkHttpClient client = OkGoHelper\.getDefaultClient\(\);' "转发端点不得再直接用 10 秒读超时的默认 client"

# 投屏：与 D1 同源（DLNA 设备自己去上游取流，天生带不上请求头，缺头就是 403）。
# 修法：投屏地址换成**局域网可达**的本机转发端点，凭一次性随机码取流。
# 安全红线：没带码的请求依旧只允许本机（门禁一行没松）；上游地址与请求头不出现在局域网明文里。
# 回退后果：投屏在有防盗链的源上必然失败；若把「带码才放行」改成「局域网就放行」，
#           这个端点会变成同网段任何设备都能用的开放代理。
must    "$RS" 'public static String registerCastForward\(' "必须有投屏码登记入口"
count   "$RS" 'castForwardLookup\(' 2 "投屏码必须有登记 / 校验两处（定义处 + servePlayForward 调用处）"
must    "$RS" 'SecureRandom' "投屏码必须来自安全随机数（不可猜）"
must    "$RS" 'public static String lanIp\(\)' "投屏必须能拿到局域网地址（WifiManager 那条路在热点 / 有线下会给 0.0.0.0）"
must    "$RS" 'return denied\("该接口仅供本机使用。"\);' "没有投屏码且非本机时仍必须拒绝"
must    "$RS" 'fileName\.equals\("/dns-query"\)\)\) \{' "门禁行必须只剩 localfile 与 dns-query（/l1play 已改为按码分档，不得整条放开）"
must    "$PH" 'public static String wrapForCast\(' "投屏地址包装必须在（播放页与详情页两个入口共用）"
must    "$PH" '/l1play\?t="' "投屏端点必须只带一次性码，不得把上游地址与请求头写进局域网明文"
must    "$PF" 'PlayerHelper\.wrapForCast\(url, mPlayingHeaders\)' "播放页投屏必须走包装（并沿用当前线路的请求头）"
must    "$DA" 'PlayerHelper\.wrapForCast\(' "详情页投屏必须走包装（两个入口同一份行为）"

echo "===== AI. 2026-09-24 bu：判空不得吃掉「被跳过的站点」 + 池签名含来源 + 消幽灵收尾 ====="
# 症状：冷门词（用户实测 `军鸡`）两分钟前在剧圈99 是搜得到的，却被判成「暂无数据」。
# 根因①：每轮日志都 `跳过=1`（一个 jar 站点没被问到），而五条判据里没有一条看这个数 ——
#        「40 站里 1 站没问、39 站真没有」和「40 站全问过都没有」被判成同一件事。
# 根因②（A2 的连带代价）：loadSeq 去重后签名在 jar 首次结论处冻结，「首次装载失败 → 后来装上」
#        再也不会改签名 ⇒ S3 永不重搜 ⇒ 直接判空。
# 根因③：缓存池(41 站) 切网络池(41 站) 时前四段签名完全相同 ⇒ 换了内容的池被当成「没变」。
# 回退后果：冷门片名被系统性吃掉；每轮源里那个本地跑不了的 jar 会让判空永远无法收敛（或反之误判空）。
must    "$JL" 'public static Set<String> skippedKeys\(\)' "跳过账本必须可快照（判空只看新面孔，不看次数）"
count   "$JL" 'return skipNull\(key\);' 3 "三处跳过都必须把站点 key 记进账本，少一处就漏一个站点"
must    "$FS" 'JarLoader\.skippedKeys\(\) - roundSkippedBase' "判空判据必须只看「本轮新面孔」被跳过的站点"
must    "$FS" '搜索复检：本轮有 ' "有新面孔被跳过时必须先重搜一轮，不得直接判空"
must    "$FS" '搜索复检：池已稳定但有 ' "S3 出口（真机假空的落点）同样必须先重搜一轮"
must    "$FS" 'ApiConfig\.get\(\)\.isPoolFromCache\(\)$' "池签名必须含「池来源」，否则缓存池切网络池时签名不变"
must    "$FS" 'if \(count == 0\) \{' "收尾判据必须是 == 0（初值 0 + 孤立回调会打成 -1 触发伪收尾）"
mustnot "$FS" 'if \(count <= 0\) \{' "不得再用 <= 0：那会让孤立回调触发一次「本轮没开始」的伪收尾"
must    "$FS" '新增跳过=' "收尾摘要必须打出新增跳过数（真机要能一眼分清「问了没结果」与「根本没问」）"

echo "===== AJ. 2026-09-24 bv：搜索必须收敛 + 转发端点必须能把 m3u8 分片喂对 ====="
FSA=app/src/main/java/com/github/tvbox/osc/ui/adapter/FastSearchAdapter.java
# 症状：bu 复测 A2 四轮**全跑满 30 秒**且**不收敛**（loadSeq 每轮 +4 ⇒ 判据③恒假 ⇒ S3 秒级又起一轮）；
#       D1-MX 从"10 秒结束"退化成"197ms~1.8s 就报无法播放"（status=-1094995529）。
# 根因①：判据② 拿 roundAsked 比 roundSiteCount，而两者被赋成同一个值 ⇒ 恒真、测不出"有没有站点没被问到"。
# 根因②：池签名含 loadSeq（装载活动计数器）⇒ 每轮都在动 ⇒ 池未变恒假。
# 根因③：两处 getSearch 出口只投无人订阅的 LiveData ⇒ 该站点永不回调 ⇒ 计数漏一格。
# 根因④：m3u8 清单里 554 个分片全是相对路径，播放器拿转发端点当 base ⇒ 拼到本机 ⇒ 收到 HTML 首页。
# 回退后果：搜索永远转圈（用户拿不到任何结论）；外部播放器/投屏第一秒就死。
must    "$FS" 'private fun poolKeySig\(\)' "池签名必须用站点集合指纹表达「内容变没变」（bv 撤出 loadSeq）"
mustnot "$FS" 'JarLoader\.loadSeq\(\) \+ "\|" \+' "池签名不得再把装载序号当池内容信号（它是每轮都动的活动计数器）"
must    "$FS" 'ApiConfig\.get\(\)\.getSourceBeanList\(\)\.size\.toString\(\) \+ "\|" \+' "池签名仍必须以站点数起头（判空判据依赖它）"
must    "$FS" 'private var roundDone = 0' "判据② 必须有真实数据源：本轮实际回调回来的站点数"
must    "$FS" 'private fun poolKeySig\(\): Int' "站点集合指纹要返回短哈希（进日志不占篇幅）"
must    "$FS" 'roundDone\+\+' "每个站点回调都必须计入（含空结果回调）"
mustnot "$FS" 'roundAsked >= roundSiteCount' "判据② 不得再用恒真的 roundAsked 口径"
must    "$FS" 'val askedAll = !roundEndedByTimeout && roundDone >= roundSiteCount && roundSiteCount > 0' "判据② 必须是「实际回调数＝计划数 且 不是超时收尾」"
must    "$FS" 'private var roundEndedByTimeout = false' "超时收尾必须留下痕迹（它等价于「至少一个站点没回调」）"
must    "$FS" 'roundEndedByTimeout = true' "超时收尾时必须置位"
must    "$FS" '搜索停滞：' "0 结果的搜索必须有停滞检测（否则白等满 30 秒）"
must    "$FS" 'STALL_QUIET_MS' "停滞阈值必须是有名常量"
must    "$FS" 'else if \(allRunCount\.get\(\) > 0 && quiet >= STALL_QUIET_MS\)' "0 结果一档仍走 10 秒停滞阈值（bv 口径未动）"
must    "$FS" '部分站点未响应，未找到结果，请稍后重试' "「没问全」必须给独立结论，不得用「暂无数据」冒充"
must    "$FS" '个站点未响应（已回 ' "「没问全」收尾必须留埋点（能看出已回几个）"
must    "$SV" 'EventBus\.getDefault\(\)\.post\(new RefreshEvent\(RefreshEvent\.TYPE_SEARCH_RESULT, null\)\)' "getSearch 的两个出口必须走 EventBus（唯一被订阅的通道）"
count   "$SV" 'EventBus\.getDefault\(\)\.post\(new RefreshEvent\(RefreshEvent\.TYPE_SEARCH_RESULT, null\)\);' 7 "空结果回调必须全部走 EventBus（bv 补了「源里查不到该 key」与「类型不在 0/1/3/4」两处——它们原来只投无人订阅的 LiveData，站点永不回调就漏一格计数）"
must    "$RS" 'private byte\[\] rewriteM3u8\(' "转发端点必须能重写 m3u8 清单"
must    "$RS" 'm3u8 分片已重写' "重写必须留埋点"
must    "$RS" 'URI=\\"' "EXT-X-KEY/MAP 里嵌的 URI 也要重写（否则加密流照样死）"
must    "$RS" 'if \(code == 200 && cr == null && len >= 0 && len <= M3U8_MAX_BYTES && isM3u8\(mime, u\)\)' "只有 200 全量清单才重写（206 带 Range 语义，改了字节账对不上）"
must    "$RS" 'String prefix = "http://" \+ host \+ "/l1play' "重写目标必须用请求的 Host 拼端点（本机与投屏自动各得其所）"
must    "$RS" 'low.contains\("/l1play"\)' "已指向本机转发的不许再包一层（防自嵌套）"
mustnot "$RS" 'probe.addHeader\("Accept-Ranges", \(ar == null \|\| ar.isEmpty\(\)\) \? "bytes" : ar\);' "不得再硬写 Accept-Ranges: bytes（上游不支持 Range 时那是谎报可拖动）"
mustnot "$RS" 'resp.addHeader\("Accept-Ranges", \(ar == null \|\| ar.isEmpty\(\)\) \? "bytes" : ar\);' "同上（GET 分支）"
must    "$RS" 'String reqU = session.getParms\(\)\.get\("u"\);' "投屏分片要能复用同一个码（带自己的 u）"
must    "$FSA" 'DefaultConfig\.checkReplaceProxy\(item\.pic\.trim\(\)\)' "搜索页图片必须与首页同口径（trim + checkReplaceProxy）"
mustnot "$FSA" '"position=" \+ helper\.getLayoutPosition\(\)' "图片 transform 的 key 不得再混位置（列表合批刷新会让缓存全失效）"
must    "$OG" 'L1Executors\.fixed\("l1box-img", 8\)' "图片任务必须走独立线程池（不得与 OkGo 全局网络回调共抢 l1box-net）"
mustnot "$OG" 'executor\(HeavyTaskUtil\.getBigTaskExecutorService\(\)\)' "Picasso 不得再占用 OkGo 的全局回调池"

echo "===== AK. 2026-09-24 bw：有结果的搜索必须早收尾 + 复制链接必须给原链接 + 手势回调不得崩 ====="
TL=TabLayout/src/main/java/com/angcyo/tablayout/DslTabLayout.kt
# 症状①（bv 真机 8/8 轮）：有结果的搜索**必然**等满 30 秒看门狗 —— 最刺眼的一轮首条结果 75ms 就上屏，
#   转圈却挂满 30 秒。两个出口都进不去：正常收尾要 allRunCount==0（恒定有 1 站不回），停滞检测被判据
#   结果为空 挡着（当初刻意"有结果就不打扰"）。
# 症状②（用户提）：下载按钮的「复制链接」要的是原链接（不能是本机转发端点）。
# 症状③（bv 真机 1 次）：搜索页左侧站点栏 / 首页分类栏滑动时闪退，堆栈落在 DslTabLayout 的手势回调上。
# 回退后果：有结果的搜索也要白等 30 秒转圈；滑动列表随机闪退。
must    "$FS" 'private const val SETTLE_QUIET_MS = 3000L' "有结果的搜索必须有独立的「结果稳定」阈值（否则只能等满 30 秒看门狗）"
must    "$FS" 'quiet >= SETTLE_QUIET_MS' "有结果时必须按静默阈值收尾"
must    "$FS" 'onFirstWaveDone\(settled = true\)' "有结果时必须走正常收尾（超时收尾会 shutdownNow 掐死在途搜索、丢迟到结果）"
must    "$FS" 'val hasResult = searchAdapter\.data\.size > 0 \|\| searchAdapterFilter\.data\.size > 0' "有无结果必须两个适配器都看（过滤模式下数据在 filter 里）"
must    "$FS" '搜索稳定：' "结果稳定收尾必须留埋点（判修法生效的直接证据）"
must    "$FS" '结果稳定收尾' "收尾摘要要能区分「所有站点都答了」与「静默稳定」"
mustnot "$FS" 'searchAdapter\.data\.size <= 0 && quiet >= STALL_QUIET_MS' "两档阈值不得再混写成一条判据"
must    "$PH" 'public static String unwrapForward\(String url\)' "复制链接必须有一层兜底解包（转发端点要能还原成原地址）"
must    "$PH" 'URLDecoder\.decode\(kv\.substring\(eq \+ 1\), "UTF-8"\)' "解包必须真的解码 u 参数（不是原样搬）"
must    "$DA" 'url = PlayerHelper\.unwrapForward\(url\);' "下载按钮取到地址后必须过一遍解包"
count   "$TL" 'e1: MotionEvent\?' 2 "手势回调的两个起点参数都必须可空（框架在 ACTION_CANCEL 后会传 null，真机崩过）"
count   "$TL" 'e2: MotionEvent\?' 2 "同上（第二个参数）"
mustnot "$TL" 'e1: MotionEvent,' "手势回调不得再声明非空起点参数"

echo "===== AL. 2026-09-28 bx：滑动丝滑化 + 图片失败可辨/有账本 + 选集自适应 + 播放错误码透传 ====="
FSA=app/src/main/java/com/github/tvbox/osc/ui/adapter/FastSearchAdapter.java
MDL=app/src/main/java/com/github/tvbox/osc/picasso/MyOkhttpDownLoader.java
DEXML=app/src/main/res/layout/activity_detail.xml
PEC=player/src/main/java/xyz/doikki/videoplayer/player/PlayErrCode.java
EMP=player/src/main/java/xyz/doikki/videoplayer/exo/ExoMediaPlayer.java
IJP=player/src/main/java/xyz/doikki/videoplayer/ijk/IjkPlayer.java
# F 组（滑动）：by 真机实测证明"滑动中暂停落地＋暂停图片"会造成**右侧断触＋图片大面积变慢**，
# by2 改为**限流不暂停**：滑动中照常落地、每批最多 12 条（单帧负载恒定），图片不再被暂停。
# 回退后果：要么"滑着滑着突然不跟手"，要么图片请求被反复挂起重排队 → 大面积变慢。
must    "$FS" 'private const val SCROLL_BATCH_MAX = 12' "滑动中落地必须限流（不是暂停）"
must    "$FS" 'val take = if \(scrollPauseActive\) minOf\(SCROLL_BATCH_MAX, pendingResults\.size\)' "落地必须按滑动状态限批"
must    "$FS" 'if \(pendingResults\.isNotEmpty\(\)\) scheduleFlush\(\)' "限掉的批次必须排下一拍（否则丢结果）"
must    "$FS" 'if \(pendingResults\.isNotEmpty\(\) && !flushScheduled\) scheduleFlush\(\)' "停稳必须立刻把剩余批次落完"
mustnot "$FS" 'pauseTag' "不得再暂停图片加载（实测会让图片大面积变慢）"
mustnot "$FS" 'resumeTag' "同上"
mustnot "$FS" 'SCROLL_PAUSE_MAX_ITEMS' "旧的「攒批不落地」策略已按实测撤除"
must    "$FS" 'setItemViewCacheSize\(6\)' "列表缓存必须加厚（默认 2，来回滑重绑定太多；ck 起恢复固定 6）"
# G 组（图片）：用户口径 —— 失败仍用原来的灰图；"图为什么没出来"由收尾那行账本回答
# （by 实测：DNS 正常（DoH 命中 71/失败 2），失败以图站反爬为主）。
must    "$FSA" 'error\(R\.drawable\.img_loading_placeholder\)' "失败必须仍是原来的灰图（用户 09-28 口径）"
mustnot "$FSA" 'img_load_failed' "红叹号失败图已按用户口径撤除"
mustnot "$FSA" '\.tag\(IMG_TAG\)' "图片不得再打滑动暂停用的 tag"
must    "$MDL" 'imgStatsReset\(\)' "图片账本必须能清零（一轮搜索一份账）"
must    "$MDL" '成功率=' "账本必须给成功率（用户要的就是加载成功率）"
must    "$MDL" 'UnknownHostException' "必须单独统计未知主机（＝DNS 解析失败/被拦截，用户的怀疑点）"
must    "$MDL" 'SocketTimeoutException' "超时必须单独统计（连接/读取）"
must    "$MDL" 'countFail\(counted\.url\(\)\.host\(\), reasonOf\(e\)\)' "网络异常必须按 host＋原因记账"
must    "$MDL" 'countFail\(counted\.url\(\)\.host\(\), "HTTP" \+ response\.code\(\)\)' "非 2xx 必须按 HTTP 状态码记账（418/403 等反爬）"
must    "$MDL" 'IMG_HOST_REASON' "失败域名必须带原因（哪个域名是被拦的一眼可见）"
must    "$FS" 'MyOkhttpDownLoader\.imgStatsReset\(\)' "一轮搜索开始必须清账"
must    "$FS" '图片加载：' "收尾必须打图片统计行"
# H 组（选集）：用户口径 —— 恢复原设计（固定高度＋网格内部滚动），行数 4→6 行（190dp）。
must    "$DEXML" 'android:layout_height="190dp"' "选集网格高度＝约 6 行（用户 09-28 口径）"
mustnot "$DEXML" 'android:nestedScrollingEnabled="false"' "不得再关网格内部滚动（那会让整页跟着滚、详情页被上推）"
# 左栏（2026-09-28 三轮实测收敛）：tab 语义＝**只代表"有结果的站点"**（用户明确否掉"点搜索
# 就把 40 个站点全列出来"）；视图改动走"停手窗口"队列 ⇒ 拖动期间不会中途换子 View。
must    "$FS" 'SITE_TAB_SETTLE_MS' "左栏视图改动必须有「停手窗口」常量"
must    "$FS" 'setOnScrollChangeListener' "必须记录左栏最后一次滚动时刻（判定是否在拖）"
must    "$FS" 'private fun queueSiteTab\(' "站点 tab 必须走队列（不得随手 addView）"
mustnot "$FS" 'queueSiteTabsRebuild' "不得再预建全部站点 tab（语义错＋点无结果站点会崩）"
must    "$FS" 'private fun flushSiteTabsNow\(' "队列必须有停手后的统一落地"
must    "$FS" 'override fun dispatchTouchEvent\(ev: MotionEvent\)' "必须旁观左栏触摸流（只看滚动会漏掉「手指刚落下」那一瞬）"
must    "$FS" 'siteTabsTouchDown' "必须有「手指在左栏上」的状态（否则起手瞬间仍会被改视图掐断）"
must    "$FS" 'if \(siteTabsBusy\(\)\)' "落地前必须再确认已停手（又动了就继续顺延）"
# 崩溃修复（2026-09-28 真机 FATAL ×3）：filterResult 里 `(resultVods[key])!!` 在"该站点还没有
# 结果桶"时直接 NPE（老防线只挡住"名字不在站点表里"）。回退后果：点左栏某个暂无结果的站点必闪退。
mustnot "$FS" 'val list: List<Movie\.Video> = \(resultVods\[key\]\)!!' "过滤桶不得再用 !! 强解（无结果的站点会崩）"
must    "$FS" 'val list: List<Movie.Video>\? = resultVods\[key\]' "过滤桶必须按可空取用"

echo "===== AM. 2026-09-28 cb：图片吞吐/成功率 + 双侧跟手（P1） ====="
IMFL=app/src/main/java/com/github/tvbox/osc/util/L1ImageInflight.java
# 图片慢的真因（ca 实测）：命中 250+ 条结果但只有 5~24 张图下载完 —— 4 路并发 + 不可取消 +
# 10 秒超时 + 队列被"滑走的图"占满。AM 节把这些逐条钉住。
must    "$OG" 'L1Executors\.fixed\("l1box-img", 8\)' "图片下载并发必须为 8（原 4 路是吞吐下限）；ck 起恢复固定值"
mustnot "$OG" 'L1Executors\.fixed\("l1box-img", 4\)' "不得回退到 4 路并发（低档取值必须经 DeviceProfile，不得写死）"
must    "$OG" 'okhttp3\.Dispatcher imgDispatcher' "图片必须有独立 Dispatcher（不与接口请求共用）"
must    "$OG" 'IMAGE_CONNECT_TIMEOUT_MS' "图片必须有独立（更短）的连接超时"
must    "$OG" 'connectionPool\(new okhttp3\.ConnectionPool' "图片必须有独立连接池"
mustnot "$MDL" 'L1ImageDemand\.needed' "不得再按「可见窗口」跳过请求（误判会让整页图不加载）"
must    "$MDL" 'L1ImageInflight\.put\(url, call\)' "在途请求必须登记（供停稳/切站点时取消越界的下载）"
must    "$MDL" 'BROWSER_UA' "图片 UA 必须换成浏览器 UA（Dalvik UA 被豆瓣图床 418）"
must    "$MDL" 'refererFor\(' "必须按域名补 Referer（只图片链）"
must    "$IMFL" 'public static int cancelOutside' "必须能取消「已滚出可见范围」的在途下载"
must    "$IMFL" 'INFLIGHT\.put\(n, call\)' "在途表必须真的登记 Call（否则取消无从谈起）"
must    "$IMFL" '\.cancel\(\)' "越界的在途下载必须真的被 cancel（只统计不取消＝没修）"
must    "$IMFL" 'public static String summary' "必须留「取出/取消/在途」账本（纯日志）"
mustnot "$FSA" 'interface ImageWindowGate' "adapter 不得再有「可见门闸」（用户口径：不判错）"
mustnot "$FSA" 'shouldLoadImage' "不得再按可见性决定是否请求图片"
mustnot "$FS" 'setImageWindowGate' "宿主不得再给 adapter 设可见门闸"
must    "$FS" 'private fun cancelOutOfWindowImages\(' "停稳/切站点必须取消越界的在途下载"
mustnot "$FS" 'notifyItemRangeChanged\(r\.first' "不得再对可见区间强制重绑（那是为门闸兜底的补丁，会加重卡顿）"
must    "$FS" 'itemAnimator = null' "必须关掉 item 插入动画（手指下位移＝不跟手）"
must    "$FS" 'built < SITE_TAB_BATCH' "站点 chip 必须分帧创建（cc 实测一次性建 40 个＝364ms 卡顿；ck 起恢复 const 8）"
must    "$FS" 'private const val SITE_TAB_BATCH = 8' "chip 每帧批次固定 8（cc 实测一次性建 40 个会卡 364ms）"
must    "$FS" 'private fun drainSiteTabQueue' "chip 队列必须逐帧落地（不得一次性建完）"
must    "$FS" 'private fun auditVisibleImages' "停稳后必须做「可见图片体检」（被取消/被丢弃的图需要第二次机会）"
must    "$FS" 'IMG_AUDIT_DELAY_MS' "体检必须延迟（给正在下载的图落地时间，避免误判反复重绑）"
must    "$FS" 'lastVisibleImageAudit' "体检结论必须打进日志（可见区真实落地率）"
must    "$FS" '滑动中不取消任何下载' "取消越界下载只允许在停稳时做（滑动中取消会误伤即将可见的图）"
must    "$FSA" 'public static final String IMG_OK' "图片必须记「已成功」状态（体检与统计的唯一可靠口径）"
must    "$FSA" 'public static String imageCounterSummary' "必须统计「绑定发起/预取/空地址」，用于判断取出少是缓存还是没请求"
must    "$FSA" 'countPrefetchStart' "预取必须记账（否则分不清「图没出」是没请求还是失败）"
must    "$FS" 'prefetchAroundVisible' "必须有「可见±6 预取」（用户 09-28 口径）"
must    "$FS" 'IMG_PREFETCH_BUDGET' "预取必须有总额度（否则来回滑会把整轮 500+ 张全预热＝失去可见优先）"
must    "$FS" 'Picasso\.get\(\)\.load\(u\)\.fetch\(\)' "预取必须走 fetch()（只取字节进缓存，不绑视图、不改状态）"
must    "$FS" 'prefetchedUrls\.add\(u\)' "同一轮同一地址只能预取一次"
must    "$FS" 'System\.out\.println\(lastVisibleImageAudit\)' "体检结论必须当场打印（原来只挂收尾 ⇒ 每轮都打到「未体检」）"
must    "$MDL" 'if \(!call\.isCanceled\(\)\)' "我们自己取消的请求不得计入失败（cc 实测：取消数≈SocketException 数）"
must    "$FS" 'Choreographer\.getInstance\(\)\.postFrameCallback' "必须有应用内帧耗时统计（系统只报 >30 帧，微卡看不见）"
must    "$FS" '图片队列：' "收尾必须打图片队列账（取出/跳过/窗口）"
must    "$FS" '帧耗时\[' "收尾必须打帧耗时（丝滑的量化指标）"
must    "$FS" 'L1ImageInflight\.reset\(\)' "一轮搜索开始必须复位在途表与计数"
must    "$FS" 'startFrameWatch\(\)' "帧耗时统计必须启动"
must    "$FS" 'frameWorstStage' "最差帧必须带「当时阶段」（否则定位不了卡在哪一步）"
# ch（2026-09-29）：原断言 must '分段耗时：' 已撤 —— cg 轮查明该埋点测的东西本来就 <25ms，
# 覆盖范围已被帧埋点包含，故整体撤除（见文末 ch 段的 mustnot）。
mustnot "$PF" 'private static boolean isPlayableUrl' "不得再对播放地址做预判门闸（用户 09-28 口径：不判错、防止误判）"
# I 组一期（播放失败）：错误码此前被全工程丢弃，"很多源直接失败"无法分类；
# I2 同址同类去重＝整条兜底链不被空转重走。判死秒数一字未动。
must    "$PEC" 'public static String take\(\)' "错误码通道必须取走即清（一次错误只归因一次）"
must    "$PEC" 'public static String classify' "错误码必须能粗归因（网络/HTTP、解码）"
must    "$EMP" 'PlayErrCode\.set\("Exo:' "Exo 内核必须透传错误码"
must    "$EMP" 'PlayErrCode\.set\(""\)' "Exo 重试路径必须销旧账（防串染）"
must    "$IJP" 'PlayErrCode\.set\("Ijk:' "Ijk 内核必须透传 what/extra"
count   "$IJP" 'PlayErrCode\.set\("exc:' 2 "setDataSource/prepareAsync 抛异常也要留痕"
must    "$PF" 'PlayErrCode\.take\(\)' "兜底链入口必须取走内核错误码"
must    "$PF" '播放失败：code=' "归因必须留埋点（二期定刀的数据源）"
count   "$PF" 'mLastErrClass = null' 5 "同址同类签名的复位点必须齐全（声明/换解析/重播/手动重试/换集）"
must    "$PF" 'chainAlreadyTried' "同址同类错误必须走整链去重（跳过变形/降级直达重取）"
must    "$PF" '同址同类兜底试尽' "去重后的出口必须留埋点"

# ===== ce（2026-09-28）：/proxy 自转发兜底 + 按 do 路由 + 净化真归因 + 分段计时修正 =====
# A 类（站点下发本地代理地址）在 cd 轮露出第二层：门禁已放行，但 **jar 的 Proxy 直接返回 null**
# （同 jar 对 do=ck 能回 200 ⇒ jar 正常，只是派发表没有这个 key）—— 我们只能回 503。
# 而那条 URL 是自描述的（url=b64 上游、ck=b64 凭据）⇒ jar 不接单时自己转发。
must    "$JL" 'siteJarKeys\.put\(key, jarKey\)' "/proxy 路由必须记下「站点key→jarKey」（不然只能靠 recentJarKey猜）"
must    "$JL" 'siteJarKeys\.get\(doKey\)' "/proxy 必须按请求里的 do 找 jar（do 才是真正该处理它的站点 key）；cg 后改为「学到的表优先、站点 key 表兜底」，故只断言兜底那一段仍在"
must    "$JL" '\? mapped : recentJarKey' "按 do 找不到时必须回退 recentJarKey（行为与改动前一致）"
must    "$JL" '路由=' "诊断日志必须打出路由来源（do 映射 / recent 回退）"must    "$RS" 'private Response proxySelfForward' "jar 返回 null 时必须能自转发（否则 A 类 100% 失败）"
must    "$RS" 'proxySelfForward\(session, params, local\)' "自转发必须挂在「/proxy 无可用响应」这条分支上（jar 正常时一行不碰）"
must    "$RS" 'if \(!local && !castOk\)' "自转发的门禁必须与 /l1play 一致（否则成了局域网开放代理）"
must    "$RS" 'private Response serveForward' "转发本体必须抽出复用（range/HEAD/m3u8 重写只有一份）"
must    "$RS" '本地代理：自转发 url=' "自转发必须有日志（下一轮据此判成败）"
must    "$RS" 'Cookie: " \+ ck' "ck 按查询串失败后必须再试一次 Cookie 头（两种约定都存在）"
mustnot "$RS" 'parseForwardHeaders\(session\.getParms\(\)\.get\("h"\)\)' "转发本体不得再自己读 session 的 h（必须用入参，两条链路共用）"
# 净化失败的真归因：dd 轮埋点被 OkGo 坑了（HttpException 的 rawResponse 已置空 ⇒ code 读回 0）
must    "$PF" 'response\.getRawResponse\(\)' "净化失败必须从原始响应取真实状态码（OkGo 的 HttpException.code() 读回 0）"
must    "$PF" 'briefMsg' "净化失败必须带异常原文（类名之外的最后一句人话）"
# 分段计时（原 cd/ce/cf 版 → ch 已整体撤除，见文末 ch 段）。
# 留档原因：它连续 4 轮 0 条，最终查明不是埋点坏，而是**插入成本本来就 < 25ms 阈值**
# （flushSearchResults 每 120ms 一拍、每拍只落增量一小批）⇒ 它在如实报告"不慢"。
# 覆盖范围已被帧埋点包含，故连常量一起撤。上面两条 must 已随之删除。

# ===== cf（2026-09-29）：手绑放行判据去快照化 + 路由埋点判据修正 =====
# ce 轮实测（_ce_dump.txt）：初版 proxyCapable 也比「代理世代号」，而世代号每次 occupyProxy 都 +1、
# 与「这个 jar 还能不能用当前代理」无关（家族 jar 有 4 个，必然互相顶）。
# 实证：14942a7863 手绑于 gen1 → 两次接管后 gen=3 ⇒ 判死，站点 WexAiYueYue 100% 503；
# 对照组 0b9565d6a6 恰在两次接管之后手绑 ⇒ 放行、do=ck 返回 200。
mustnot "$PI" 'hb\.gen' "手绑放行判据不得再比代理世代号（无关接管会把该继续成立的绑定误判为失效）"
must    "$PI" 'if \(!hb\.holderKey\.isEmpty\(\) && !hb\.holderKey\.equals\(PROXY_HOLDER_KEY\)\) return false;' "手绑放行只比「绑定时那一任持有者」（换届必换 key，这才是代理是否被换掉的真实凭据）"
must    "$PI" 'PROXY_HOLDER_KEY\.isEmpty\(\)\) return false;' "绑定时无持有者必须有明确分支，不能落进「key 不等」的误杀"
must    "$PI" 'final String holderKey;' "手绑账本必须记 holderKey（诊断要能说清「绑定时是谁」）"
must    "$PI" 'static String handBoundBrief' "必须能打出「这个 key 在手绑账本里是什么状态」（原先把三种拒因合成一句话，看不出是没登记还是判据没过）"
must    "$JL" '手绑账本=' "/proxy 日志必须打出账本状态（否则下一次还是只能回去读代码猜）"
mustnot "$JL" 'route = \(key != null && key\.equals\(recentJarKey\)\)' "路由埋点判据不得再用 key.equals(recentJarKey)（取链前刚 getSpider 过 ⇒ 天然相等 ⇒ 恒打 recent）"
must    "$JL" 'boolean hitLearned = learned != null && !learned\.isEmpty\(\);' "路由来源必须由 mapped 是否命中直接决定（可用已知答案的样本验证取值）"
must    "$JL" 'do\(学到\)' "路由日志必须能区分「学到的 do」与「回退 recent」（cg 改为三态，取代原来的两态断言）"
must    "$JL" '拒:该jar从未手绑成功' "拒绝措辞必须区分「从未手绑」与「手绑后已换届」"
must    "$JL" '拒:手绑后已换届' "同上（另一种拒因）"

# ===== cg（2026-09-29）：do→jar 学到的路由表 + 分段计时收口 =====
# cf 轮实测（_cf_dump.txt）：/proxy 的 do 是 jar 自造的短标识（实测只有 hmys / ck），
# 而 siteJarKeys 存的是 sourceBean.getKey()（配置站点 key，如 海绵/WexAiReBo）——
# 两者是不同命名空间 ⇒ 13 次 do 全部 miss，恒回退 recentJarKey 碰运气
# （同一个 do=ck 被路由到两个不同 jar：0b9565d6a6 / ae48218142）。
must    "$JL" 'private final ConcurrentHashMap<String, String> doJarKeys' "必须新增 do→jar 路由表（do 与站点 key 不同命名空间，旧表查不中）"
must    "$JL" 'String learned = doKey\.isEmpty\(\) \? null : doJarKeys\.get\(doKey\);' "路由必须先查「学到的 do 表」"
must    "$JL" 'hitLearned \? learned : siteJarKeys\.get\(doKey\)' "学到的表优先、站点 key 表兜底，顺序不可颠倒"
must    "$JL" 'doJarKeys\.put\(doKey, key\);' "成功放行后必须把 do→jar 记下来（这是唯一能知道该 do 归谁的途径）"
must    "$JL" 'rs\[0\] != null' "只登记「真的处理出东西」的成功样本（返回 null 的不得固化，否则错路由被锁死）"
must    "$JL" '表规模=do表' "/proxy 日志必须打出两张表的规模（否则看不出路由表到底有没有被填起来）"
must    "$JL" 'do\(学到\)' "路由来源必须能区分「学到的」与「站点key命中」"
mustnot "$JL" 'route = \(routeDo == null\)' "路由日志不得再有按 key 猜的旧分支"

# ===== ch（2026-09-29）：三项收口（撤分段计时 / 修 frameStage 语义 / 修路由日志自证） =====
#
# ① 撤掉「分段耗时」埋点：cg 轮查明它不是埋点坏，而是**插入成本本来就 < 25ms 阈值**
#    （flushSearchResults 每 120ms 一拍、每拍只落增量一小批）⇒ 它在如实报告"不慢"，
#    覆盖范围已被帧埋点包含，没有继续存在的价值。**连常量一起撤，别留死代码。**
mustnot "$FS" 'SEG_WARN_MS' "分段耗时埋点必须整体撤除（cg 已证插入本就 <25ms，该埋点无价值）"
mustnot "$FS" 'pendingSegTag' "分段耗时的待结算字段必须撤除"
mustnot "$FS" 'private fun settlePendingSeg' "分段耗时的主动结算入口必须撤除"
mustnot "$FS" 'segLog\(' "分段耗时的打印函数必须撤除"
mustnot "$FS" '分段耗时：' "不得再输出分段耗时日志（已被帧埋点覆盖）"
#
# ② 修 frameStage 语义坑：cd/ce/cf/cg 四轮最差帧都标「结果落地」（240~314ms），
#    真凶是加固 jar 首次构造 WebView（313.51ms，与 WebViewFactory: Loading 同刻）。
#    根因＝frameStage 只在 stage() 被调用时更新、之后一直残留 ⇒ 标签必须能说清"谁在跑"。
must    "$FS" 'stage\("站点在跑"\)' "搜索任务提交前必须标记「站点在跑」（jar 首次构造 WebView 的状态，否则被误记成结果落地）"
must    "$FS" 'stage\("结果落地\(插入\)"\)' "结果落地的标签必须写明是「插入」，与站点初始化区分开"
must    "$FS" 'frameStage 只在 stage\(\) 被调用时更新' "frameStage 会残留这一坑必须写在注释里（否则后人继续误读最差帧标签）"
must    "$FS" 'WebViewFactory' "必须点明四轮 314ms 的真凶是 WebView 首次初始化（证据要留在代码上）"
#
# ③ 修路由日志的自证效应：cg 轮 `表规模=do表1` 是"含本条之后"的数 ——
#    成功路径上 doJarKeys.put 就在 proxyLog 之前 ⇒ 在 proxyLog 里查 containsKey 恒为真。
#    ⇒ 路由来源必须在 **put 之前**定死再传下去。
must    "$JL" 'String routeSrc = hitLearned \? "do\(学到\)"' "路由来源必须在 put 之前定死（否则 proxyLog 里查表恒真，日志自证）"
must    "$JL" 'put 早于 proxyLog' "自证效应必须写进注释（否则后人会把表规模当成路由命中证据）"
must    "$JL" 'String routeSrc\)' "日志参数必须是「已定死的来源串」，不能再是 Boolean 让日志自己去猜"
mustnot "$JL" 'doJarKeys\.containsKey\(String\.valueOf\(params' "proxyLog 里不得再现场查 doJarKeys（自证效应，cg 轮踩过）"
must    "$JL" '新学到' "首次学到某个 do 时必须标出来（区分「本次学到」与「本次命中已有」）"

# ① 手绑世代账本：cf 轮实测条件不成立（配置里无 WexAiYueYue、整轮 1 次手绑 0 次接管），
# 改由**静态推理收口**判定为已修复，结论必须固化在注释里，否则后人无从复核。
must    "$PI" '静态推理收口' "手绑世代账本的收口结论必须固化在注释里（不再实测，论证要留在代码上）"
must    "$PI" '触发条件本身被移除' "必须说清「原 bug 的触发条件已不存在」这一核心论据"
must    "$PI" '删除式修复' "必须点明这是删除式修复、非参数调优（区别在于前者无需实测覆盖）"

echo "===== （末尾）ck：撤销低配机档位收敛（2026-09-30 · 用户口径「低档还是去掉吧，保持 8 线程」） ====="
# 背景：cj 引入的 7 个档位方法（图片线程 / 图片 Dispatcher / 连接池 / 磁盘缓存 / 列表复用池 / chip 批量）已撤销，
# 恢复改造前的**固定值**。撤销理由：
#   ① 中/高档取值本就与改造前逐字相同 ⇒ 对绝大多数设备零收益；
#   ② 它引入一条隐藏路径：App.onTrimMemory/onLowMemory → markMemoryPressure() → tier **永久**降低档，
#      于是**任何机器**一旦内存紧张，siteTabBatch 就从 8 掉到 4（chip 建得更慢）——
#      本意是「低配机兜底」，实际变成「可能影响所有设备」。撤销即消灭该路径。
# 固定值原样恢复：线程 8 / Dispatcher 12+6 / 连接池 8 / 磁盘缓存 64MB / itemViewCache 6 / chip 批量 8。
must    "$OG" 'imgDispatcher\.setMaxRequests\(12\)' "图片并发恢复固定 12"
must    "$OG" 'imgDispatcher\.setMaxRequestsPerHost\(6\)' "单图床并发恢复固定 6"
must    "$OG" 'new okhttp3\.ConnectionPool\(8,' "图片连接池恢复固定 8"
must    "$OG" '"img_cache"\), 64 \* 1024 \* 1024' "图片磁盘缓存恢复固定 64MB"
must    "$OG" 'L1Executors\.fixed\("l1box-img", 8\)' "图片线程池恢复固定 8"
must    "$FS" 'setItemViewCacheSize\(6\)' "列表复用池恢复固定 6"
must    "$FS" 'private const val SITE_TAB_BATCH = 8' "chip 每帧批次恢复固定 8（且必须是 const，不再走方法）"
# 反向：这 7 个档位方法不得复活（复活＝把「降档」这条隐藏路径又装回来）
mustnot "$DP" 'imageThreads' "不得再出现图片并行度档位方法"
mustnot "$DP" 'imageMaxRequests' "不得再出现图片并发档位方法"
mustnot "$DP" 'imageConnectionPool' "不得再出现连接池档位方法"
mustnot "$DP" 'imageCacheBytes' "不得再出现磁盘缓存档位方法"
mustnot "$DP" 'itemViewCacheSize' "不得再出现列表复用池档位方法"
mustnot "$DP" 'siteTabBatch' "不得再出现 chip 批次档位方法"
mustnot "$OG" 'DeviceProfile\.' "OkGoHelper 不得再引用 DeviceProfile（图片链路已全固定值）"
# 改造前就有的档位方法必须保留（撤销范围要精确，不得误伤）
must    "$DP" 'public static int searchConcurrency\(\)' "搜索并发档位必须保留（ak 定稿，与本次撤销无关）"
must    "$DP" 'public static int detailConcurrency\(\)' "详情并发档位必须保留"
must    "$DP" 'public static int timeoutRunnerMax\(\)' "超时隔离线程上限必须保留"
must    "$DP" 'public static int spiderShards\(\)' "spider 分片档位必须保留"
# ck 临时埋点（验证完整体撤除，届时本段一并删除）
mustnot "$FS" 'L1_TRACE = true' "cu 收尾：埋点总开关必须**已关闭**（字符串会被编译期消除）"
must    "$FS" 'private const val L1_TRACE = false' "cu 收尾：开关必须为 false（const+inline ⇒ 观测代码编译期彻底消除）"
must    "$FS" '左栏插入：' "必须埋左栏插入点（含前后 scrollY，用于判「插入时是否真静止」）"
must    "$FS" '停稳收尾：' "必须埋右栏停稳三件活的逐项耗时"
must    "$FS" 'frameWorstState' "最差帧必须记下「当时两侧在不在动」"
# 未 init 必须仍为中档（与改造前完全一致）—— 这是整个分档机制的安全前提
must    "$DP" 'private static volatile int tier = TIER_MID' "未 init 时必须仍是中档（保证分档机制自身不出错时行为不变）"

echo "===== （末尾）cl：左右一起修（2026-09-30 · fling 边界/中止 + 放开追加守卫 + 关掉居中） ====="
# 全部有 ck 三轮真机数据支撑：
#  ① fling 上界原用 maxHeight（内容总高），比真正可滚范围 maxScrollY 多算**一整个视高**
#     ⇒ 视觉到底后仍继续空转；
#  ② computeScroll 的越界中止只查 currX，而竖向 fling 的 currX 恒为 0
#     ⇒ **竖向 fling 永不提前中止**（实测单段 4668ms / 8348ms，正常仅 1~2 秒）；
#  ①②叠加 ⇒ 空转期间插入新 chip 使 maxScrollY 变大时，scrollTo 会把 scrollY **"吸"到新底部**
#     （实测「前Y=517 → 后Y=1511」，而 1511 恰为新上限）—— 这就是用户报的"卡住不跟手"。
#  ③ "追加"在滑动中被守卫挡住 ⇒ 实测用户滑 8.4 秒期间 chip **零更新**、停手后才一次性冒出（批量=8）
#     —— 即用户报的"一滑动就停止出结果"。而 addView 是**追加到末尾**、不移除也不移动已有子 View
#     ⇒ 滑动中安全；真正会打断手势的 removeAllViews()（清空重建）单独守住。
#  ④ 右栏关掉 tv_selectedItemIsCentered（反编译证实它只走焦点路径，防"停下又顿一下"）。
TL="TabLayout/src/main/java/com/angcyo/tablayout/DslTabLayout.kt"
LS="app/src/main/res/layout/activity_fast_search.xml"
must    "$TL" 'startFling\(-velocity\.toInt\(\), 0, maxScrollY\)' "左栏 fling 上界必须用 maxScrollY（原 maxHeight 多算一整个视高）"
mustnot "$TL" 'startFling\(-velocity\.toInt\(\), 0, maxHeight\)' "fling 上界不得再用 maxHeight"
must    "$TL" '_overScroller\.currY < minScrollY \|\| _overScroller\.currY > maxScrollY' "竖向越界必须判 currY（原只查 currX ⇒ 竖向永不中止）"
must    "$TL" 'isHorizontal\(\)\) \{' "越界判定必须按横竖分支"
must    "$FS" '去掉了 siteTabsBusy\(\) 检查' "drainSiteTabQueue 必须去掉 busy 守卫（追加在滑动中安全）"
must    "$FS" '只保留给' "flushSiteTabsNow 的守卫只保留给清空重建"
must    "$LS" 'tv_selectedItemIsCentered="false"' "右栏 mGridView 必须关掉选中项居中"
must    "$LS" 'tv_selectedItemIsCentered="true"' "mGridViewFilter 必须保持 true 作对照"

echo "===== （末尾）cm：触摸/滚动取证埋点（2026-09-30 · **只观测，不改行为**） ====="
# 为什么需要：用户主诉是"左栏断触"，而 ck/cl 的埋点全在测"插入了什么"，
# **从没看过手势本身** —— 这就是上一轮定不了主因的根本原因。
# 用户已确认**断触发生在同一轮搜索内** ⇒ 排除"清空重建(removeAllViews)"（那只在重搜时发生），
# 矛头指向 `needScroll` 从 false→true 的一次性翻转：翻转前后 onTouchEvent 处理路径整体切换
# （super ↔ _gestureDetector），且接管瞬间 ViewGroup 会给子 View 发 ACTION_CANCEL。
# 右栏则要判断"惯性滑行是否均匀"（帧耗时正常却体感顿挫 ⇒ 问题在位移连续性，不在渲染）。
must    "$FS" '★CANCEL' "必须记录 ACTION_CANCEL —— 断触的直接证据"
must    "$FS" '★★可滚翻转' "必须记录 needScroll 翻转时刻与当时的触摸状态"
must    "$FS" '右栏状态→' "必须记录右栏完整的滚动状态迁移时间线（拖动/惯性/静止）"
must    "$FS" '右栏滑行\[' "必须结算右栏逐帧位移（判滑行是否均匀）"
must    "$FS" 'traceAccumDy' "必须逐帧累积位移（且只在停稳时结算，不逐帧打印）"
mustnot "$TL" '★左栏开始拦截' "cu 收尾：该埋点已撤（左侧问题确认解决），源码与 dex 都不得再有"

echo "===== （末尾）cn：不再把「能否滚动」押在 GestureDetector 的返回值上（2026-09-30） ====="
# 依据：cm 第二轮取证（用户"中间位置连续快速滑动"）量化出 ——
#   左栏 56 次手势：46 次比值 0.97~1.00 完美跟手，**20 次「手势内滚动=0 且抬起后惯性=0」**
#   （手指滑 350~684px，内容一格没动）。
#   强相关：零响应手势里只有 25% 出现「开始拦截」，正常手势 94%。
# 根因：intercept = super.onInterceptTouchEvent(ev) || _gestureDetector.onTouchEvent(ev)
#   ⇒ **能否滚动完全取决于 detector 的状态机**。而原实现只在 needScroll=true 时才喂 detector，
#     `needScroll=false`（chip 不足）期间的 DOWN 被漏喂 ⇒ detector 缺基准点 ⇒ onScroll 永不触发
#     ⇒ intercept 恒 false ⇒ 事件让给 chip ⇒ 滚动彻底不发生。
# 做法（两项，均只在 detector **未处理**时生效 ⇒ 正常路径逐字不变）：
#   ① **无条件**喂 detector（消除漏喂 DOWN 的窗口；顺带消除 `||` 短路隐患）
#   ② 原始 MOVE 位移兜底：拦截阶段兜底接管 + 处理阶段兜底自滚
must    "$TL" 'val detectorHandled' "必须把 detector 调用提前并无条件执行（原实现只在 needScroll 时喂 ⇒ 漏喂 DOWN 即断链）"
must    "$TL" '\|\| detectorHandled' "拦截判定必须改用提前算好的 detectorHandled（防退回原先的短路写法）"
must    "$TL" 'l1ArmFallback' "必须在 DOWN 时记录兜底基准点"
must    "$TL" 'l1MovedEnough' "必须按原始位移判断是否脱离 slop（不依赖 detector）"
must    "$TL" 'l1FallbackScroll' "必须有兜底滚动实现"
must    "$TL" '_l1Slop' "兜底阈值必须取系统 scaledTouchSlop（不得写死）"
mustnot "$TL" '★★兜底接管' "必须埋「兜底接管」cu 收尾：该埋点已撤"
mustnot "$TL" '★★兜底滚动生效' "必须埋「兜底滚动生效」cu 收尾：该埋点已撤"
must    "$TL" '!handled && event\.actionMasked == MotionEvent\.ACTION_MOVE' "兜底自滚必须限定在 ACTION_MOVE（UP/CANCEL 帧兜底会多滚一次，破坏正常路径）"
must    "$TL" 'if \(!needScroll\) return true' "onFling 必须加 needScroll 守卫（无条件喂 detector 后，无内容可滚时不得起 fling）"
# 兜底必须只在 detector 未处理时执行（保证"正常路径零改动"这一性质不被破坏）
must    "$TL" 'if \(!intercept && ev\.actionMasked == MotionEvent\.ACTION_MOVE && l1MovedEnough\(ev\)\)' "拦截兜底必须先确认 detector 未接管（不得无条件接管）"

echo "===== （末尾）co：右栏 fling 速度取证（2026-09-30 · **只观测，不改行为**） ====="
# 依据：cn 实测「接触时间短地快速滑」时，惯性阶段只有 **16~28ms（1~2 帧）**，
# 而尾6帧仍为 100~200px/帧 ⇒ **不是自然减速**。需分辨两种可能：
#   甲 速度没被采纳（系统 vy 远小于手指真实速度） ⇒ 修法：抬速兜底
#   乙 fling 启动后被立刻中止                     ⇒ 修法：查中止者
# 安全性已核实：TvRecyclerView **整个库**（javap + 二进制常量池全类扫描）0 处
#   setOnFlingListener / SnapHelper / mOnFlingListener ⇒ 挂探针不会覆盖库内逻辑。
must    "$FS" '★右栏fling请求' "必须记录系统实际请求的 fling 速度（与手速同帧对照才能分辨甲乙）"
must    "$FS" 'traceRightGestureVelocity\(ev\)' "速度取证必须在 dispatchTouchEvent 中独立进行（不得依赖 View 内部状态）"
must    "$FS" 'object : RecyclerView\.OnFlingListener\(\)' "必须挂 onFling 探针"
must    "$FS" 'return false // 回退路径' "onFling 探针必须返回 false 交回默认处理（改成 true 等于劫持 fling）"
must    "$FS" '距UP=' "滑行结算必须给出「距UP」（＝从抬起到停稳的时长，量化「惯性有多短」）"
must    "$FS" 'l1RvHandVy' "必须把手速带进 fling 日志（否则无法同帧对照两个速度）"
must    "$FS" '★右栏UP 手速=' "必须在 UP 时刻记录手速与横速（RecyclerView 发起 fling 要求 |xvel|<|yvel|）"
must    "$FS" '可下滚=' "必须记录 UP 时刻能否继续滚（RecyclerView 发起 fling 的另一前置条件）"

echo "===== （末尾）cp：把「拖动帧」与「惯性帧」分开记账（2026-09-30 · **只观测**） ====="
# 依据：co 已确证 ① 速度 100% 被采纳（vy 逐条等于手速）② 每次 UP 都发起了 fling（59/59）
#   ③ **但 vy 与惯性时长完全无相关**（vy=23392→4ms，vy=17010→1074ms）⇒ **发起后被极早中止**；
#   ④ 已排除：app 层无 stopScroll/scrollToPosition、父容器非 nested-scrolling、item 不可获焦
#      （item_search.xml 无 focusable ⇒ 库里的 requestChildRectangleOnScreen→smoothScrollBy 不触发）、
#      库内 stopScroll/abortAnimation/forceFinished 全 0 处、帧耗时 6~7ms。
# 但 traceDyList 从上次 IDLE 起就累积、把拖动帧与惯性帧**混装** ⇒ 出现「距UP=18ms 却有 7 帧」
#   这种自相矛盾，无法判断惯性阶段到底有没有产生滚动。本版把它切开：
#   惯性帧=0 ⇒ 被**立即中止**；惯性帧>0 但末帧仍大 ⇒ **中途被中止**。
must    "$FS" 'traceSettleDy' "必须单独累积惯性阶段的帧（否则无法区分「立即中止」与「中途中止」）"
must    "$FS" 'rv\.scrollState' "累积时必须按当前滚动状态分流"
must    "$FS" '右栏惯性\[停稳\]' "必须单独输出惯性阶段的帧数/位移/首末帧"
must    "$FS" '惯性帧=0（\*\*fling 一帧都没跑\*\*）' "必须显式标注「惯性帧=0」这个决定性情形"
must    "$FS" 'scrollState == RecyclerView\.SCROLL_STATE_SETTLING' "分流条件必须是 SETTLING（不得用别的状态猜）"
must    "$FS" 'traceSettleDy\.clear\(\)' "结算后必须清空惯性列表（否则跨手势累积）"

echo "===== （末尾）cq：用同速度复算「理论滑行距离」（2026-09-30 · **只观测**） ====="
# 依据：cp 实测 `惯性帧=2`（13ms）、末帧 161px/帧（≈23000px/s）却直接静止
#   ⇒ `OverScroller` 自己认为「跑完了」，必须知道它到底算出了多远：
#   ① 独立 scroller 也算出很短 ⇒ **scroller 本身就算出短距离**（指向系统级因素）
#   ② 独立 scroller 算出很长、而实测只有几百 px ⇒ **fling 被替换或中止**
# ⚠️ 前提发现：设备 `animator_duration_scale = 0.5`（安卓 fling 由动画时钟驱动，0.5x ⇒ 时长减半）。
#   ⇒ 必须把该值与密度随埋点一起记录，才能把它当变量排除。
must    "$FS" 'l1ProbeScroller' "必须用独立的 OverScroller 复算理论距离（不得只靠推算）"
must    "$FS" '理论距离=' "必须记录理论滑行距离"
must    "$FS" '动画缩放=' "必须记录 animator_duration_scale（它直接决定 fling 时长）"
must    "$FS" 'ANIMATOR_DURATION_SCALE' "必须读系统动画缩放设置"
must    "$FS" 'probe\.fling\(0, 0, velocityX, velocityY' "探针必须用与系统相同的参数起 fling"

echo "===== （末尾）cr：修掉探针的「飞轮累加」（2026-09-30 · **只观测**） ====="
# cq 轮实测踩到：`OverScroller` 有**飞轮（flywheel）**机制 —— `fling()` 开头若
#   `mFlywheel && !isFinished()`，会把**上一次的残余速度加上去**。
#   复用同一个探针实例 ⇒ 速度逐次累加 ⇒ 理论距离单调爆炸
#   （cq 实测从 13,706 一路涨到 2,546,629，与 vy 完全脱钩）⇒ **该轮理论距离数据无效**。
# 修法：fling 前先 `abortAnimation()`（把 mFinished 置 true，飞轮条件即不成立）。
must    "$FS" 'probe\.abortAnimation\(\)' "探针 fling 前必须先 abortAnimation（否则飞轮累加、理论距离无效）"

echo "===== （末尾）cs：打出惯性帧序列，分辨「匀速」还是「衰减」（2026-09-30 · **只观测**） ====="
# 依据：cq 实测「平均速度 / 初速」达 60%~117%（自然 `SplineOverScroller` 衰减应远低于 100%），
#   且短惯性样本普遍「末帧≈首帧」（例 169→161）⇒ **滚动几乎没有减速**。
# 「匀速跑一段 + 突然停」精确匹配 `smoothScrollBy`（线性插值器），
#   而 `fling` 是指数/样条衰减 ⇒ 必须把逐帧序列打出来分辨。
# 判据：前 12 帧基本恒定（±10%）⇒ 匀速 ⇒ `smoothScrollBy` 类；
#      前 12 帧单调递减 ⇒ 正常 fling 衰减。
# 只在 IDLE 时一次性打印（不逐帧刷），避免自造卡顿污染结论。
must    "$FS" '惯性前12帧=' "必须打出惯性阶段前 12 帧的 dy 序列（分辨匀速/衰减的唯一判据）"
must    "$FS" 'traceSettleDy\.take\(12\)' "序列必须取自**惯性**列表（不能取自混装的 traceDyList）"

echo "===== （末尾）ct：自研惯性滑动，绕开 smoothScrollBy（2026-09-30 · **改行为，带回退开关**） ====="
# 依据（cs 实测铁证）：惯性帧序列**完全恒定、零衰减**（例 164~188 / 192~219 / 179~205，12 帧内无递减趋势）
#   ⇒ 只可能是 `smoothScrollBy`（**线性插值器**）；真正的 `fling` 必然指数/样条衰减。
#   ⇒ 系统 fling 被 `TvRecyclerView.requestChildRectangleOnScreen → smoothScrollBy` 覆盖。
# 改法：`onFling` 返回 true **自己驱动** —— 真实时钟 + 指数衰减：
#   ① 绕开 smoothScrollBy（系统 fling 不再启动） ② 不受 animator_duration_scale 影响
# 回退：`l1OwnFling = false` 即回到系统行为（其余代码不参与）。
must    "$FS" '★右栏自研fling' "必须埋自研 fling 的结果（帧/位移/时长/结束原因）"
must    "$FS" 'private val l1OwnFling = true' "必须带回退开关且默认开启"
must    "$FS" 'l1FlingTauMs' "衰减时间常数必须显式定义（决定滑行距离 ≈ v0 × TAU）"
must    "$FS" 'startOwnFling\(velocityY\)' "onFling 必须调用自研 fling 并返回 true 劫持"
must    "$FS" 'endOwnFling\("用户按下"\)' "用户按下必须立即停掉自研惯性（否则与手指拖动叠加）"
must    "$FS" 'postOnAnimation' "自研 fling 必须用 postOnAnimation 逐帧驱动"
must    "$FS" 'computeVerticalScrollOffset\(\)' "必须用 offset 不再变化来判断撞界并停止"
