# -*- coding: utf-8 -*-
"""生成《项目文件目录（全量说明）》—— 遍历仓库镜像，提取每个文件的用途说明。

用法：python _gen_filelist.py
输出：_l1_docs/00_总纲与索引/文件目录_全量说明_20260930.md
"""
import io
import os
import collections

ROOT = r'_gh_upload_L1Box'
OUT = r'_l1_docs/00_总纲与索引/文件目录_全量说明_20260930.md'


def head_line(p):
    """取文件开头的标题(md)或首条注释/文档串(py/sh)"""
    try:
        with io.open(p, encoding='utf-8', errors='ignore') as f:
            lines = []
            for i, line in enumerate(f):
                if i >= 14:
                    break
                lines.append(line.rstrip())
    except Exception:
        return ''
    for l in lines:                      # markdown 标题
        s = l.strip()
        if s.startswith('#'):
            return s.lstrip('#').strip()
    for l in lines:                      # py/sh 注释或 docstring
        s = l.strip()
        if s.startswith('#') and len(s) > 3 and not s.startswith('#!'):
            t = s.lstrip('#').strip()
            if t:
                return t
        if s.startswith('"""') or s.startswith("'''"):
            return s.strip('"\'').strip()
    return ''


def human(n):
    for u in ('B', 'KB', 'MB'):
        if n < 1024:
            return '%.0f %s' % (n, u)
        n /= 1024.0
    return '%.1f GB' % n


def dir_size(d):
    tot = cnt = 0
    for r, _, fs in os.walk(d):
        for f in fs:
            try:
                tot += os.path.getsize(os.path.join(r, f))
                cnt += 1
            except Exception:
                pass
    return tot, cnt


def esc(s):
    return (s or '—').replace('|', '\\|').replace('\n', ' ')


# ── 一、顶层各目录统计 ──────────────────────────────────────────────
DIR_DESC = {
    'app': '主模块：竖屏 UI、搜索页、订阅解析、播放页逻辑、图片链路',
    'player': '播放器模块：dkplayer + ijkplayer + exoplayer2 扩展（缓冲接管、内核选择）',
    'docs': '开发过程文档：判定书 / 交付说明 / 测试清单 / 踩坑记录 / 工作日志',
    'scripts': '开发期工具：构建、质量闸门、真机日志分析（**参考用**，含路径占位符）',
    'TabLayout': '左侧站点栏控件 DslTabLayout（**本仓库内 fork，可改**）',
    'quickjs': 'JS 引擎（spider 脚本执行环境）',
    'crash': '崩溃捕获与上报',
    'tools': '桌面 JVM 桩类（免真机跑订阅解析用例）+ fakeboot',
    'ViewPager1Delegate': '竖屏分类栏滑动委托',
    'gradle': 'Gradle wrapper 配置',
}

# ── 二、scripts 用途（手写补充，未覆盖的自动取文件首条注释）──
SCRIPT_DESC = {
    'build_l1box.sh': '**构建 + 签名 + 自动装机**（唯一出包入口；`bash build_l1box.sh <输出APK名>`）',
    'sign_l1box.sh': '单独给已产出的 APK 签名（v1/v2/v3）',
    'check_l1box_regressions.sh': '**源码回归闸门**（883 条 must/mustnot/count 断言）—— 改完必跑',
    'check_l1box_compat.sh': '兼容性检查（minSdk / 权限 / 架构）',
    'gen_icon.py': '生成应用图标各密度版本',
    '_bn_capture.sh': '真机日志抓取（含设备端扩大环形缓冲做灾备）',
    '_bn_checkpoint.sh': '日志检查点（标记 + 分段 dump）',
    '_round_dump.py': '按"轮次"切分日志 dump',
    '_log_skeleton.py': '日志骨架提取（去掉噪声，只留我们 App）',
    '_search_timeline.py': '搜索链路时间线重建',
    '_win_dump.py': 'Windows 端 dump 辅助',
    '_ax_dump.py': '早期 dump 工具（09-11 批次）',
    '_aw_timeline.py': '早期时间线工具（09-11 批次）',
    '_bq_stat.py': 'bq 批次统计',
    '_dexcheck.py': 'dex 存在性检查（早期版，已被 `_br_dexsym.py` 取代）',
    '_dexstr.py': 'dex 字符串提取',
    '_l1_batch1_stats.py': '批一统计（缓存与起播线）',
    '_l1_batch2_stats.py': '批二统计',
    '_l1_batch3_stats.py': '批三统计',
    '_l1_probe_buffer.py': '缓冲探针（起播/缓冲水位）',
    '_l1_startup_stats.py': '冷启动统计',
}

# ── 三、"我要改 X，去哪个文件"（手写导引）──
GUIDE = [
    ('**搜索页**（左右两侧、站点栏、结果网格）', '`app/src/main/java/com/github/tvbox/osc/ui/activity/FastSearchActivity.kt`（~1800 行，核心）'),
    ('搜索页布局', '`app/src/main/res/layout/activity_fast_search.xml`'),
    ('搜索结果项', '`app/src/main/java/com/github/tvbox/osc/ui/adapter/FastSearchAdapter.java` + `res/layout/item_search.xml`'),
    ('**左侧站点栏的手势/滚动**', '`TabLayout/src/main/java/com/angcyo/tablayout/DslTabLayout.kt`（内含 L1Box 的手势兜底）'),
    ('**右栏惯性滑动**', '`FastSearchActivity.kt` 的 `mGridView.setOnFlingListener`（自研 fling，`l1OwnFling`）'),
    ('图片下载/并发/缓存', '`app/src/main/java/com/github/tvbox/osc/util/OkGoHelper.java`（`initPicasso`）'),
    ('图片在途账本', '`app/src/main/java/com/github/tvbox/osc/util/L1ImageInflight.java`'),
    ('设备能力分级', '`app/src/main/java/com/github/tvbox/osc/util/DeviceProfile.java`'),
    ('**本地代理 / 路由 / 门禁**', '`app/src/main/java/com/github/tvbox/osc/server/` 下的 Server / Proxy 相关类'),
    ('播放页（详情页）', '`app/src/main/java/com/github/tvbox/osc/ui/activity/` 下的 Detail / Play 相关 Activity'),
    ('播放器内核选择/缓冲', '`player/src/main/java/xyz/doikki/videoplayer/` + `app/.../player/` 下的 controller/render'),
    ('播放错误码', '`player/src/main/java/xyz/doikki/videoplayer/player/PlayErrCode.java`'),
    ('订阅源解析', '`app/src/main/java/com/github/tvbox/osc/data/` + `api/` + `bean/`'),
    ('首页 / 展示配置', '`app/src/main/java/com/github/tvbox/osc/ui/activity/HomeActivity.java`'),
    ('崩溃处理', '`crash/` 模块 + `app/.../base/` 下的 CrashHandler'),
    ('缓存策略（起播/内存）', '`app/src/main/java/com/github/tvbox/osc/util/` 下的 ExoCacheConfig / 缓冲相关类'),
]


def build():
    L = []
    A = L.append

    A('# L1Box 项目文件目录（全量说明）')
    A('')
    A('> **用途**：想知道"某个文件是干嘛的""我要改的东西在哪"时查这里。')
    A('> 全局背景与硬教训见 [项目交接总纲](项目交接总纲_20260930.md)；本文件只管**文件层面**的说明。')
    A('>')
    A('> 说明：本仓库**同时是源码与项目记忆**。下面按"目录 → 文件"逐层说明；')
    A('> 文档类（`docs/`）与脚本类（`scripts/`）**逐个列出**，源码类只列**关键文件**（其余按模块归类）。')
    A('')

    # ── 〇 全局速览
    A('## 〇、全局速览')
    A('')
    dirs = []
    for name in sorted(os.listdir(ROOT)):
        p = os.path.join(ROOT, name)
        if os.path.isdir(p) and name != '.git':
            sz, cnt = dir_size(p)
            dirs.append((name, cnt, sz))
    dirs.sort(key=lambda x: -x[1])
    total_files = sum(c for _, c, _ in dirs)
    total_size = sum(s for _, _, s in dirs)
    A('| 目录 | 文件数 | 体积 | 职责 |')
    A('|---|---:|---:|---|')
    for name, cnt, sz in dirs:
        A('| `%s/` | %d | %s | %s |' % (name, cnt, human(sz), DIR_DESC.get(name, '—')))
    A('| **合计** | **%d** | **%s** | 另有顶层配置文件见 §一 |' % (total_files, human(total_size)))
    A('')

    # ── 一 顶层
    A('## 一、顶层文件与配置')
    A('')
    A('| 文件 | 用途 |')
    A('|---|---|')
    top_desc = {
        'README.md': '仓库首页：项目定位、构建方法、**开发历史与交接入口**',
        '.gitignore': '排除构建产物 / APK / 签名 / 本机配置（例外放行 `fakeboot.dex`）',
        'LICENSE': '开源许可',
        'settings.gradle': 'Gradle 工程声明（`rootProject.name = TVBoxMobile` —— **只是工程显示名，与包名无关，别改**）',
        'build.gradle': '根构建脚本（插件版本、仓库地址）',
        'gradle.properties': 'Gradle/Kotlin 编译参数（**不应含本机绝对路径**）',
        'gradlew': 'Gradle wrapper（Linux/macOS）',
        'gradlew.bat': 'Gradle wrapper（Windows）',
    }
    for name in sorted(os.listdir(ROOT)):
        p = os.path.join(ROOT, name)
        if os.path.isfile(p) and name != '.gitmodules':
            A('| `%s` | %s |' % (name, top_desc.get(name, '—')))
    A('')

    # ── 二 源码模块
    A('## 二、源码模块（关键文件）')
    A('')
    A('> 源码必须与出包 APK **逐字节一致** —— 不要为了"整洁"重命名/挪动文件。')
    A('')

    A('### 2.1 `app/` —— 主模块')
    A('')
    A('构成：`java 239` / `xml 193`（布局与资源）/ `png 35` / `kt 15` / `js 12` / `jar 3` / `json 6`')
    A('')
    A('| 子包 | 职责 |')
    A('|---|---|')
    for pkg, desc in [
        ('`api/`', '接口定义与配置（ApiConfig 等）'),
        ('`base/`', '基类（BaseActivity / App / CrashHandler 等）'),
        ('`bean/`', '数据模型（影片、线路、解析结果）'),
        ('`cache/`', '缓存管理'),
        ('`callback/`', '回调接口'),
        ('`constant/`', '常量与配置项'),
        ('`data/`', '数据仓储层'),
        ('`event/`', '事件总线消息'),
        ('`picasso/`', '图片加载定制（圆角变换、占位图、失败图）'),
        ('`player/`', '播放器控制器与渲染（controller / render / thirdparty）'),
        ('`receiver/`', '广播接收（开机自启等）'),
        ('`server/`', '**本地代理服务**（`/proxy`、路由、门禁）'),
        ('`subtitle/`', '字幕处理'),
        ('`ui/`', '**界面层**：activity / adapter / dialog / fragment / widget'),
        ('`util/`', '**工具层**：DeviceProfile、OkGoHelper、L1ImageInflight、L1Executors…'),
        ('`com/github/catvod/`', 'spider 运行时（crawler / net / utils），与加固 jar 交互'),
    ]:
        A('| %s | %s |' % (pkg, desc))
    A('')
    A('**最常改的几个文件**：')
    A('')
    A('| 文件 | 说明 |')
    A('|---|---|')
    A('| `ui/activity/FastSearchActivity.kt` | **搜索页全部逻辑**（~1800 行）：站点栏调度、结果落地、图片窗口、帧埋点、自研 fling |')
    A('| `ui/adapter/FastSearchAdapter.java` | 搜索结果网格适配器 |')
    A('| `util/OkGoHelper.java` | **图片链路**：独立 client、并发、UA、Referer、磁盘缓存 |')
    A('| `util/DeviceProfile.java` | 设备能力分级（搜索并发、线程上限、超时隔离） |')
    A('| `util/L1ImageInflight.java` | 图片在途账本（避免"取消即丢失"） |')
    A('| `server/` 下的代理类 | 本地代理服务与 `/proxy` 路由门禁 |')
    A('')

    A('### 2.2 `player/` —— 播放器模块')
    A('')
    A('| 子目录 | 职责 |')
    A('|---|---|')
    A('| `xyz/doikki/videoplayer/` | dkplayer 框架（播放器抽象、controller、render） |')
    A('| `xyz/doikki/videoplayer/player/PlayErrCode.java` | **播放错误码透传 + I2 同址同类去重** |')
    A('| `tv/danmaku/ijk/media/player/` | ijkplayer（软解内核） |')
    A('| `com/google/android/exoplayer2/` | exoplayer2 及 ffmpeg 扩展（硬解内核） |')
    A('')

    A('### 2.3 其他模块')
    A('')
    A('| 模块 | 文件数 | 职责 |')
    A('|---|---:|---|')
    for name in ['TabLayout', 'quickjs', 'crash', 'tools', 'ViewPager1Delegate', 'gradle']:
        p = os.path.join(ROOT, name)
        if os.path.isdir(p):
            sz, cnt = dir_size(p)
            A('| `%s/` | %d | %s |' % (name, cnt, DIR_DESC.get(name, '—')))
    A('')
    A('> **`TabLayout/` 特别注意**：它不是外部依赖，是**本仓库内的 fork**。')
    A('> 09-30 修左右滑动时在这里加了「手势兜底」（`l1MovedEnough` / `l1FallbackScroll` / `l1ArmFallback`）——')
    A('> 改它等于自己维护这个 fork，**动手前先确认是否必要**。')
    A('')

    # ── 三 scripts
    sp = os.path.join(ROOT, 'scripts')
    if os.path.isdir(sp):
        files = sorted(f for f in os.listdir(sp) if os.path.isfile(os.path.join(sp, f)))
        A('## 三、`scripts/` —— 开发期工具（%d 个）' % len(files))
        A('')
        A('> ⚠️ 这些脚本按**当时的本机路径**写的，其中的 `<本机>` / `<工作区>` 是**路径占位符**，')
        A('> 用前请替换；`build_l1box.sh` 还假定工作区下存在 `FreeBox-src/` 结构，与当前仓库布局不同，**直接跑需要微调**。')
        A('> 完整的闸门与 dex 工具在本工作区上一级目录（`_gate_precheck.py` / `_br_dexsym.py`，未纳入仓库）。')
        A('')
        A('| 文件 | 用途 |')
        A('|---|---|')
        for f in files:
            d = SCRIPT_DESC.get(f) or head_line(os.path.join(sp, f)) or '—'
            A('| `%s` | %s |' % (f, esc(d)))
        A('')

    # ── 四 docs
    dp = os.path.join(ROOT, 'docs')
    if os.path.isdir(dp):
        A('## 四、`docs/` —— 开发文档（全量）')
        A('')
        A('> **这是本项目最有价值的部分**：每次改动「**先取证 → 再判定 → 才动手**」留下的完整证据链。')
        A('> 阅读顺序建议：`00_总纲与索引` → 最近 5 个批次 → 需要时按日期查 `30`/`40`。')
        A('')
        subs = [d for d in sorted(os.listdir(dp)) if os.path.isdir(os.path.join(dp, d))]
        # 先给总览
        A('| 子目录 | 份数 | 放什么 |')
        A('|---|---:|---|')
        sub_desc = {
            '00_总纲与索引': '**总纲、全景梳理、问题全记录、索引** —— 先读这里',
            '10_缓存与起播': '缓冲策略、起播看门狗、升档守门',
            '20_播放器': '播放器作用域、内核选择、多设备适配',
            '20_播放器 ': '',
            '30_成功率与崩溃': '**各批次复测判定书**（数量最多，按日期查）',
            '40_判定与审计': '交付说明、测试清单/方法、清理清单、推送清单',
            '50_订阅源解析': '订阅解析容错',
            '60_工作日志': '**08-19 ~ 09-30 逐日全过程记录**',
            '90_归档过时': '已作废',
            '附_真机截图': '真机截图',
        }
        for s in subs:
            fs = [f for f in os.listdir(os.path.join(dp, s)) if f.endswith('.md')]
            A('| `%s/` | %d | %s |' % (s, len(fs), sub_desc.get(s, '—')))
        A('')

        for s in subs:
            d = os.path.join(dp, s)
            md = sorted(f for f in os.listdir(d) if f.endswith('.md'))
            other = sorted(f for f in os.listdir(d) if not f.endswith('.md'))
            if not md and not other:
                continue
            A('### %s（%d 个文档）' % (s, len(md)))
            A('')
            A('| 文件 | 内容 |')
            A('|---|---|')
            for f in md:
                A('| `%s` | %s |' % (f, esc(head_line(os.path.join(d, f)))))
            for f in other:
                sz = os.path.getsize(os.path.join(d, f))
                A('| `%s` | （素材，%s） |' % (f, human(sz)))
            A('')

        # docs 根的散落文件
        loose = sorted(f for f in os.listdir(dp) if os.path.isfile(os.path.join(dp, f)))
        if loose:
            A('### `docs/` 根目录散落文件')
            A('')
            A('| 文件 | 内容 |')
            A('|---|---|')
            for f in loose:
                A('| `%s` | %s |' % (f, esc(head_line(os.path.join(dp, f)))))
            A('')

    # ── 五 导引
    A('## 五、"我要改 X，该去哪个文件"')
    A('')
    A('| 我要改… | 去这里 |')
    A('|---|---|')
    for what, where in GUIDE:
        A('| %s | %s |' % (what, where))
    A('')

    # ── 六 怎么用这份目录
    A('## 六、怎么配合这份目录干活')
    A('')
    A('```text')
    A('1. 读 README → 读 项目交接总纲（知道全貌与硬教训）')
    A('2. 改代码前：在 §五 找到目标文件；在 §四 的 docs 里搜相关批次，看有没有踩过坑')
    A('3. 动手 → 跑质量闸门 → 出包 → 装机 → 抓日志 → 判定')
    A('4. 判定结论写进 docs/（按 00~90 归类），工作日志追加到 docs/60_工作日志/')
    A('```')
    A('')
    A('---')
    A('')
    A('> 本文件由 `_gen_filelist.py` 按仓库实际内容自动生成（文档标题取自各文件首行），')
    A('> 新增文档后**重新跑一次即可刷新**。手写部分（§一 §二 §五）在脚本顶部的映射表里维护。')
    A('')

    txt = '\n'.join(L)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with io.open(OUT, 'w', encoding='utf-8', newline='') as f:
        f.write(txt)
    print('已生成:', OUT)
    print('  行数 =', len(L))
    print('  体积 = %.1f KB' % (len(txt.encode('utf-8')) / 1024.0))


if __name__ == '__main__':
    build()
