#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""APK dex 符号复核：确认本轮关键代码真的进了包（不是只改了源码）。
不依赖完整 dex 解析，按 0x00 切分 UTF-8 片段后做子串匹配；
对「标识符名」与「中文串」都足够可靠（minify 关闭，名字不被混淆）。
用法：python _br_dexsym.py [apk路径]     默认取当前交付版（ck）

⚠ 默认路径必须随每批交付版更新。曾发生：批次已出 cj，但默认还停在 ch，
  于是新批次的锚点全报「缺失」——那是查到旧包了，不是真缺失。
  判据：报缺失时先确认默认路径指向的批次是否就是本次构建的那一版。
"""
import io
import re
import sys
import zipfile

APK = sys.argv[1] if len(sys.argv) > 1 else \
    r"FreeBox-src/L1Box_v1.1.1_release_20260930cu.apk"

# (批号, 符号, 类别)
CHECKS = [
    # ---- #1 搜索历史长按直接删除 ----
    ("#1", "removeSearchHistory", "标识符"),
    # ---- #2 假空结构性消灭（4 态状态机） ----
    ("#2", "reviewZeroResult", "标识符"),
    ("#2", "handleNoSearchableSite", "标识符"),
    ("#2", "onWaitTick", "标识符"),
    ("#2", "beginWait", "标识符"),
    ("#2", "LOADING_SWITCH_AT_RETRY", "标识符"),
    ("#2", "poolSignature", "标识符"),
    ("#2", "正在初始化播放源", "中文串"),
    ("#2", "播放源仍在加载", "中文串"),
    # ---- #3 起播成功自动收掉错误提示 ----
    ("#3", "REFETCH_MAX", "标识符"),
    # ---- #5 续播中央弹窗可点跳转 ----
    ("#5", "showResumeJumpTip", "标识符"),
    ("#5", "resume_jump_layer", "资源名"),
    ("#5", "resume_jump_text", "资源名"),
    ("#5", "shape_player_resume_jump_bg", "资源名"),
    ("#5", "点击跳转 ", "中文串"),
    # ---- #6 线路列表按地址高亮 ----
    ("#6", "setSelectedUrl", "标识符"),
    ("#6", "setCurrentUrl", "标识符"),
    ("#6", "切换影视线路", "中文串"),
    ("#6", "切换影视源", "中文串"),
    # ---- #7 画质按旋转角对调宽高 ----
    ("#7", "getVideoRotation", "标识符"),
    # ---- #8 外部播放器喂本机转发端点 ----
    ("#8", "/l1play", "字符串"),
    ("#8", "servePlayForward", "标识符"),
    ("#8", "wrapForExternalPlayer", "标识符"),
    ("#8", "isHeaderForwardPlayer", "标识符"),
    ("#8", "Accept-Encoding", "字符串"),
    ("#8", "Content-Range", "字符串"),
    ("#8", "Accept-Ranges", "字符串"),
    # ---- #9 缩放标记 ----
    ("#9", "markScalePicked", "标识符"),
    ("#9", "isScalePicked", "标识符"),
    # ---- #10 设置页删两条目 + 条数固定 50 ----
    ("#10", "HIS_NUM", "标识符"),
    # ---- A 缓存清单先开搜（2026-09-24）----
    ("A", "isPoolFromCache", "标识符"),
    ("A", "bootPoolFromCache", "标识符"),
    ("A", "cacheBootDone", "标识符"),
    ("A", "onGuardTick", "标识符"),
    ("A", "beginGuard", "标识符"),
    ("A", "roundAskedKeys", "标识符"),
    ("A", "GUARD_MAX_TICKS", "标识符"),
    ("A", "缓存预热", "中文串"),
    ("A", "搜索补全", "中文串"),
    ("A", "搜索复检：池已稳定且无在途装载", "中文串"),
    # ---- bt：搜索收敛（A2 无限转圈 / A3 假空）+ 外部播放器转发（D1）+ 投屏 ----
    ("bt", "SEQ_COUNTED", "标识符"),
    ("bt", "MAIN_INFLIGHT", "标识符"),
    ("bt", "mainSettled", "标识符"),
    ("bt", "SPIDER_NULL", "标识符"),
    ("bt", "spiderNullSeq", "标识符"),
    ("bt", "skipNull", "标识符"),
    ("bt", "getForwardClient", "标识符"),
    ("bt", "fillMissingHeaders", "标识符"),
    ("bt", "wrapForCast", "标识符"),
    ("bt", "registerCastForward", "标识符"),
    ("bt", "castForwardLookup", "标识符"),
    ("bt", "lanIp", "标识符"),
    ("bu", "skippedKeys", "标识符"),
    ("bu", "六条判据", "中文串"),
    ("bu", "新增跳过=", "中文串"),
    ("bu", "先重搜一轮", "中文串"),
    # ---- bv：搜索收敛（判据②真数据源 + 池指纹 + 停滞检测 + 部分未响应）----
    ("bv", "poolKeySig", "标识符"),
    ("bv", "roundDone", "标识符"),
    ("bv", "roundEndedByTimeout", "标识符"),
    ("bv", "STALL_QUIET_MS", "标识符"),
    ("bv", "startStallCheck", "标识符"),
    ("bv", "搜索停滞：", "中文串"),
    ("bv", "个站点未响应（已回 ", "中文串"),
    ("bv", "部分站点未响应，未找到结果，请稍后重试", "中文串"),
    ("bv", "已回=", "中文串"),
    # ---- bv：转发端点 m3u8 分片重写 ----
    ("bv", "rewriteM3u8", "标识符"),
    ("bv", "resolveUrl", "标识符"),
    ("bv", "wrapForward", "标识符"),
    ("bv", "M3U8_MAX_BYTES", "标识符"),
    ("bv", "m3u8 分片已重写", "中文串"),
    # ---- bv：图片独立池 ----
    ("bv", "l1box-img", "字符串"),

    # ---- bw（2026-09-24）：有结果的搜索早收尾 + 复制链接兜底解包 ----
    # ⚠ E-3（DslTabLayout 的 MotionEvent? 可空签名）**不进 dex 清单**：可空性是 Kotlin 元数据 /
    #    注解，不进 dex 字符串表，只由源码闸门与编译通过来保证。
    ("bw", "SETTLE_QUIET_MS", "标识符"),
    ("bw", "搜索稳定：", "中文串"),
    ("bw", "结果稳定收尾", "中文串"),
    ("bw", "unwrapForward", "标识符"),

    # ---- bx（2026-09-28）：滑动丝滑化 + 图片加载账本 + 选集 5 行 + 播放错误码透传 + 左栏断触 ----
    # ⚠ F1/F2/H2 大多是逻辑/布局改动不落字符串表（滚动监听、tag、固定 dp），只由源码闸门与编译通过保证。
    ("bx", "SCROLL_BATCH_MAX", "标识符"),
    ("bx", "SITE_TAB_SETTLE_MS", "标识符"),
    ("bx", "图片加载：", "中文串"),
    ("bx", "成功率=", "中文串"),
    ("bx", "未知主机/DNS", "中文串"),
    ("bx", "PlayErrCode", "类名"),
    ("bx", "播放失败：code=", "中文串"),
    ("bx", "同址同类兜底试尽", "中文串"),

    # ---- cb（2026-09-28）：图片吞吐/成功率 + 双侧跟手（P1）----
    # ⚠ 并发数、itemAnimator、suppressLayout、可见窗口门闸这些是行为改动，不落字符串表，
    #    只由源码闸门与编译通过保证；这里收能落进 dex 的锚点。
    # ---- cc（2026-09-28 全局重做·不判错版）----
    ("cc", "L1ImageInflight", "类名"),
    ("cc", "取消=", "中文串"),
    # ch 撤除：("cc", "分段耗时：", "中文串") —— 分段计时整组已删，改由 GONE 段断言其不存在
    ("cc", "帧耗时[", "中文串"),

    # ---- dd（2026-09-28）：可见图片体检 + 分帧建栏 + 取消不算失败 ----
    ("dd", "可见图片：已加载=", "中文串"),
    ("dd", "图片发起：", "中文串"),
    # ch 撤除：("dd", "建站点栏(", "中文串") —— 同上（分段计时整组已删）

    # ---- cd（2026-09-28）：本地代理放行（手绑 jar）+ 净化状态码 + 图片可见±6 预取 ----
    ("cd", "本地代理：do=", "中文串"),
    ("cd", "proxyCapable", "标识符"),
    ("cd", "PROXY_GEN", "标识符"),
    ("cd", "proxy unavailable", "中文串"),
    ("cd", "DNS解析失败", "中文串"),
    ("cd", "读取超时", "中文串"),
    ("cd", "图片预取：本批=", "中文串"),
    ("cd", "预取=", "中文串"),
    # ---- ce（2026-09-28）：/proxy 自转发 + 按 do 路由 + 净化真归因 + 分段计时 ----
    ("ce", "本地代理：自转发 url=", "中文串"),
    ("ce", "路由=", "中文串"),
    # ch 撤除：("ce", "结果落地帧(", "中文串") —— 同上（分段计时整组已删）
    ("ce", "proxySelfForward", "标识符"),
    ("ce", "siteJarKeys", "标识符"),
    # ---- cf（2026-09-29）：手绑放行判据去快照化 + 路由埋点判据修正 ----
    ("cf", "手绑账本=", "中文串"),
    ("cf", "拒:该jar从未手绑成功", "中文串"),
    ("cf", "拒:手绑后已换届", "中文串"),
    ("cf", "recent(回退)", "中文串"),
    ("cf", "handBoundBrief", "标识符"),
    # ---- cg（2026-09-29）：do→jar 学到的路由表 + 分段计时收口 ----
    ("cg", "doJarKeys", "标识符"),
    ("cg", "do(学到)", "中文串"),
    ("cg", "表规模=do表", "中文串"),
    # 注：cg 的 `settlePendingSeg` / `分段耗时：` 两条锚点已由 ch **主动撤除**（见下），
    #     连同上面的「静态推理收口」一起说明：**注释与已删代码都不该留 dex 锚点**。
    # 注：①「静态推理收口」是**纯注释文字**，不进 dex（注释不编译），
    #     故此处不设锚点 —— 它的防回退由源码闸门（check_l1box_regressions.sh 的 cg 段）负责。
    # ---- ch（2026-09-29）：三项收口（撤分段计时 / 修 frameStage 语义 / 修路由日志自证） ----
    ("ch", "站点在跑", "中文串"),
    ("ch", "结果落地(插入)", "中文串"),
    ("ch", "do(站点key)", "中文串"),
    ("ch", "新学到", "中文串"),
    # ch 撤除项（分段耗时整组）由 GONE 段管，不放这里。
    # ---- ck（2026-09-30 撤销档位收敛 + 临时埋点）----
    # cj 的 7 个档位方法已撤销，其方法名转入下方 GONE 段（必须不存在）。
    # 这里改为查**本轮新加的埋点字符串**：它们只在真发生时才输出，
    # dex 里存在即证明埋点接线进了包（源码带了却被 R8 内联/裁剪的情形只能靠 dex 排除）。
    # ---- cm（2026-09-30 触摸/滚动取证）----
    # 只看不改的观测埋点；dex 里存在即证明接线进包（源码带了却被 R8 裁掉的情形只能靠 dex 排除）。
    # ---- cn（2026-09-30 手势兜底）：这两条**只在 detector 未处理时才打印**，
    # 因此它们进包 ≠ 会触发；但若实测日志里出现，就直接证明 detector 路径确有漏网。
    # 用字符串常量而非方法名：private 小方法可能被 R8 内联，字符串不会。
    # ---- co（2026-09-30 右栏 fling 速度取证）：只观测。
    # `★右栏fling请求` 只在 RecyclerView 真发起 fling 时才打印 ⇒ 它"没出现"本身就是结论。
    # ---- cp（2026-09-30 惯性/拖动分开记账）：只观测。
    # `惯性帧=0` 那条是决定性情形 —— 出现即证明 fling 一帧都没跑。
    # ---- cq（2026-09-30 理论距离复算）：只观测。`动画缩放=` 用来把系统缩放当变量排除。
    # ---- cs（2026-09-30 惯性帧序列）：只观测。`惯性前12帧=` 是分辨匀速/衰减的唯一判据。
    # ---- ct（2026-09-30 自研 fling）：`★右栏自研fling` 出现即证明劫持路径生效。
    ("cb", "图片队列：", "中文串"),
    ("cb", "movie.douban.com", "字符串"),
    ("cb", "Mozilla/5.0 (Linux; Android 13; M2102J2SC)", "字符串"),
    ("bt", "S3 等主jar装载结论", "中文串"),
    ("bt", "跳过站点=", "中文串"),
    ("bt", "补同源 Referer=", "中文串"),
    ("bt", "改喂局域网转发端点", "中文串"),
    ("bt", "投屏码已登记", "中文串"),
    ("bt", "投屏链接已失效，请重新投屏。", "中文串"),
]

# 必须【不存在】的旧符号（撤除项）
GONE = [
    # ---- cz（2026-09-30 收尾）：以下埋点锚点改为「必须不存在」----
    # L1_TRACE=false + inline l1Trace ⇒ 编译期消除观测代码 ⇒ 这些字符串不进 dex。
    # 出现即说明开关没关掉、或埋点代码被意外恢复。
    ("ck", "左栏插入：", "中文串｜cu 已关闭该埋点"),
    ("ck", "停稳收尾：", "中文串｜cu 已关闭该埋点"),
    ("ck", "距末滚动=", "中文串｜cu 已关闭该埋点"),
    ("cm", "★CANCEL", "中文串｜cu 已关闭该埋点"),
    ("cm", "右栏状态→", "中文串｜cu 已关闭该埋点"),
    ("cm", "右栏滑行[", "中文串｜cu 已关闭该埋点"),
    ("cm", "★★可滚翻转→", "中文串｜cu 已关闭该埋点"),
    ("cm", "★左栏开始拦截", "中文串｜cu 已关闭该埋点"),
    ("cn", "★★兜底接管", "中文串｜cu 已关闭该埋点"),
    ("cn", "★★兜底滚动生效", "中文串｜cu 已关闭该埋点"),
    ("co", "★右栏fling请求", "中文串｜cu 已关闭该埋点"),
    ("co", "距UP=", "中文串｜cu 已关闭该埋点"),
    ("co", "★右栏UP 手速=", "中文串｜cu 已关闭该埋点"),
    ("cp", "★右栏惯性[停稳]", "中文串｜cu 已关闭该埋点"),
    ("cp", "惯性帧=0", "中文串｜cu 已关闭该埋点"),
    ("cq", "理论距离=", "中文串｜cu 已关闭该埋点"),
    ("cq", "动画缩放=", "中文串｜cu 已关闭该埋点"),
    ("cs", "惯性前12帧=", "中文串｜cu 已关闭该埋点"),
    ("ct", "★右栏自研fling", "中文串｜cu 已关闭该埋点"),
    # ck（2026-09-30）撤销 cj 的档位收敛：这 7 个方法名必须**不在** dex 里。
    # 撤销理由：① 中/高档取值本就与改造前逐字相同 ⇒ 对绝大多数设备零收益；
    # ② 它引入一条隐藏路径 App.onTrimMemory/onLowMemory → markMemoryPressure() → tier 永久降低档，
    #    使**任何机器**内存紧张时 siteTabBatch 从 8 掉到 4 —— 兜底变成了「可能影响所有设备」。
    ("cj", "imageThreads", "cj 档位方法（ck 已撤销：见上方 ck 段注释）"),
    ("cj", "imageMaxRequests", "cj 档位方法（ck 已撤销）"),
    ("cj", "imageMaxRequestsPerHost", "cj 档位方法（ck 已撤销）"),
    ("cj", "imageConnectionPool", "cj 档位方法（ck 已撤销）"),
    ("cj", "imageCacheBytes", "cj 档位方法（ck 已撤销）"),
    ("cj", "itemViewCacheSize", "cj 档位方法（ck 已撤销）"),
    ("cj", "siteTabBatch", "cj 档位方法（ck 已撤销）"),
    ("#2", "READY_RETRY_MAX", "旧固定重试上限（已被状态机取代）"),
    ("#2", "AUTO_RESEARCH_MAX", "旧自动重搜次数上限（已改为池变化驱动）"),
    ("#2", "verdictEmpty", "旧判空函数（已换成 reviewZeroResult）"),
    ("#7", "HISTORY_NUM", "设置页条数选择器的配置键（已删）"),
    ("#10", "tvHistoryNum", "设置页历史记录条目绑定（已删）"),
    ("#10", "llCrashLog", "设置页导出崩溃日志条目绑定（已删）"),
    ("bt", "四条判据", "旧的判空口径（bt 已升到五条：加「主 jar 装载已有结论」，bu 又升到六条）"),
    ("bu", "五条判据", "旧的判空口径（bu 已升到六条：加「本轮新面孔被跳过的站点」）"),
    ("bt", "站点未下发请求头，兜底补同源 Referer", "bt 起草时的独立埋点（最终并入「补同源 Referer=」）"),
    ("bx", "img_load_failed", "红叹号失败图——用户 09-28 实测后要求改回灰图，资源文件已删"),
    ("bx", "SCROLL_PAUSE_MAX_ITEMS", "旧的「滑动暂停落地」策略——真机实测导致右侧断触，by2 改为限流"),
    ("ca", "queueSiteTabsRebuild", "预建「本轮全部站点 tab」的做法——语义错（用户否掉）＋点无结果站点必崩，ca 已撤"),
    ("cb", "Dalvik/2.1.0 (Linux; U; Android 13; M2102J2SC Build/TKQ1.220829.002)",
     "旧的图片 UA——实测被豆瓣图床 418，cb 已换浏览器 UA"),
    ("cc", "跳过播放器与兜底链", "旧的播放地址预判门闸——用户 09-28 口径「不判错、防止误判」，cc 已撤"),
    ("cc", "skip: 不在可见窗口内", "旧的图片「跳过请求」判错——误判导致整页图不加载，cc 已撤"),
    ("cc", "L1ImageDemand", "旧的「可见需求窗口」类（已被 L1ImageInflight 在途取消取代）"),
    ("ch", "分段耗时：", "分段计时日志——cg 轮查明它测的插入成本本就 <25ms，覆盖范围已被帧埋点包含，ch 整组撤除"),
    ("ch", "settlePendingSeg", "分段计时的主动结算入口（随分段计时一起撤）"),
    ("ch", "结果落地帧(", "分段计时的「结果落地帧」明细（随分段计时一起撤）"),
    ("ch", "建站点栏(", "分段计时的「建站点栏」明细（随分段计时一起撤）"),
]


def split_strings(blob):
    """按 0x00 切分，产出所有 >=4 字节的 UTF-8 可解码片段。"""
    out = []
    for piece in blob.split(b"\x00"):
        if len(piece) < 4:
            continue
        try:
            out.append(piece.decode("utf-8"))
        except UnicodeDecodeError:
            pass
    return out


def main():
    zf = zipfile.ZipFile(APK)
    dex_names = sorted(n for n in zf.namelist() if re.match(r"classes\d*\.dex$", n))
    print(f"APK: {APK}")
    print(f"dex 文件: {len(dex_names)} 个 -> {', '.join(dex_names)}")

    haystack = []
    for n in dex_names:
        haystack.extend(split_strings(zf.read(n)))
    blob = "\n".join(haystack)
    print(f"字符串片段总数: {len(haystack)}\n")

    ok = bad = 0
    print("===== 必须存在 =====")
    for need, sym, kind in CHECKS:
        if sym in blob:
            print(f"  OK   {need:>3}  [{kind}] {sym}")
            ok += 1
        else:
            print(f"  !!   {need:>3}  [{kind}] {sym}   <== 缺失")
            bad += 1

    print("\n===== 必须不存在 =====")
    for need, sym, why in GONE:
        if sym not in blob:
            print(f"  OK   {need:>3}  {sym}  ({why})")
            ok += 1
        else:
            print(f"  !!   {need:>3}  {sym}   <== 仍在包内：{why}")
            bad += 1

    print(f"\n===== 合计 {ok} 通过 / {bad} 失败 =====")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
