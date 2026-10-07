# -*- coding: utf-8 -*-
"""公开仓库前脱敏：先 --report 看现状，再 --apply 执行（只改暂存副本）。

用法:
  python _gh_scrub.py --report    只报告，不改
  python _gh_scrub.py --apply     执行脱敏（只动 _gh_upload_L1Box/）
"""
import io, os, re, sys, collections

ROOT = '_gh_upload_L1Box'
APPLY = '--apply' in sys.argv

# 订阅/接口域名映射（保持可区分，但不暴露真名）
HOST_MAP = {
    'api.rmedphk.com':   'api.<订阅源A>.com',
    'sdapi.e2wu4ht.com': 'sdapi.<订阅源A>.com',
    'api.umygrx3.com':   'api.<订阅源B>.com',
    'api.w32z7vtd.com':  'api.<订阅源B>.com',
    'upload.baicanuc.cn': 'upload.<订阅源C>.cn',
    'tv.nxog.top':        'tv.<订阅源C>.top',
}

# 精确串替换
LITERAL = [
    ('8d88c676', '<设备序列号>'),
    ('PJE110', '测试机'),
    ('C:/Users/Administrator/WorkBuddy/2026-08-19-14-43-05', '<工作区>'),
    ('C:\\Users\\Administrator\\WorkBuddy\\2026-08-19-14-43-05', '<工作区>'),
    ('C:/Users/Administrator/.workbuddy', '<本机>/.workbuddy'),
    ('C:\\Users\\Administrator\\.workbuddy', '<本机>\\.workbuddy'),
    ('C:/Users/Administrator/.gradle', '<本机>/.gradle'),
]
LITERAL += list(HOST_MAP.items())

# 正则替换
REGEX = [
    (re.compile(r'192\.168\.\d{1,3}\.\d{1,3}'), '192.168.x.x'),
    # 通用整机路径：任何盘符 + Users\<用户名>（覆盖根目录配置文件，LITERAL 只列了固定两处工作区前缀）
    (re.compile(r'[A-Za-z]:[\\/]{1,2}Users[\\/]{1,2}[A-Za-z0-9_.\-]+[\\/]{1,2}'), '<本机>/'),
    # Gradle properties 的**转义形态**：反斜杠写作 `\\`、冒号写作 `\:`（形如 `C\:\\Users\\X\\`）。
    # 上一条要求盘符后紧跟 `:`，因此**匹配不到**这种形态 —— 实测漏网 `gradle.properties` 的
    # `org.gradle.java.home`（2026-09-29 修过一次、09-30 同步时又带回，属重复踩坑）。
    # 危害双重：① 泄露本机账号/工作区路径 ② clone 者构建因路径不存在而失败。
    (re.compile(r'[A-Za-z]\\?:[\\/]{1,3}Users[\\/]{1,3}[A-Za-z0-9_.\-]+[\\/]{1,3}'), '<本机>/'),
    (re.compile(r'(?<![\w/])/c/Users/[A-Za-z0-9_.\-]+/'), '<本机>/'),
    (re.compile(r'C:[\\/]Users[\\/][A-Za-z0-9_.\-]+[\\/]'), '<本机>/'),
    # Windows 路径里出现的 "AppData/Local/Android/Sdk" 之类别名保留，只抹用户名
    (re.compile(r'\b49\.233\.180\.127:880/zm3u8/[0-9a-f]+'), '<订阅接口地址已隐去>'),
    (re.compile(r'download01\.fangcloud\.com/download/[0-9a-f]+'), '<下载地址已隐去>'),
    (re.compile(r'https?://[^\s`"\'<>)\]]*\.rdt\.t[^\s`"\'<>)\]]*'), '<链接已隐去>'),

    # ── 2026-10-07 公开前合规：源站名（固定映射，保证跨文档一致）/ 公网 IP / CDN 域名 ──
    (re.compile(r'<站点名A>|<别名A>'), '<站点A>'),
    (re.compile(r'<站点名B>|<别名B>'), '<站点B>'),
    (re.compile(r'<站点名C>'), '<站点C>'),
    (re.compile(r'<站点名D>'), '<站点D>'),
    (re.compile(r'<站点名E>|<别名E>'), '<站点E>'),
    (re.compile(r'<站点名F>'), '<站点F>'),
    (re.compile(r'<站点名G>'), '<站点G>'),
    (re.compile(r'<站点名H>|<别名H>'), '<站点H>'),
    (re.compile(r'<站点名I>'), '<站点I>'),
    # 公网 IP（保留 127.0.0.1 本机代理与 192.168.x 内网）
    (re.compile(r'https?://(?!(?:127\.0\.0\.1|192\.168\.))(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?'), '<源站地址>'),
    # 测试中出现的 CDN / 源站域名
    (re.compile(r'[a-z0-9\-]+\.(?:ssrcdn|ssscdn|feifei-kan|dbokutv)\.com'), '<源站域名>'),
    (re.compile(r'[a-z0-9\-]+\.eos-[a-z0-9\-]+\.cmecloud\.cn'), '<源站域名>'),

    # ── 测试中出现的源站/图床/CDN/平台地址（明确列表，保留 github/douban/baidu/DNS 等公开域名）──
    (re.compile(r'[a-z0-9\-\.]*\.?(?:hxx2023\.cc|ruxiangsuisu\.cn|jundie\.top|wmvbo\.com|123clouddisk\.com|6a7nnf7\.com|jdyx\.pro|ffzy-play[a-z0-9]*\.com|ffzy-online\.com|skzs\.com|xhscdn\.com|bytetos\.com|gejiba\.com|waimaimingtang\.com|kstor[a-z]*\.vip|play-cdn[0-9]*\.com|hkybqufgh\.com|capcutvod\.com|ssrcdn\.com|ssscdn\.com|icve\.com\.cn|fffg[a-z]*d\.com|fangcloud\.com|moji\.com|4thline\.org|iqiyi\.com)'), '<源站地址>'),

    (re.compile(r'<站点名J>[A-Za-z0-9]+'), '<站点J>'),
]

# 全仓库脱敏白名单目录（源码 app/ player/ quickjs/ 等一律排除，必须与出包 APK 逐字节一致）
SCRUB_PREFIXES = ('docs/', 'scripts/', '')   # '' = 根目录下的配置文件
SCRUB_ROOT_FILES = {'.gitignore', 'gradle.properties', 'settings.gradle', 'build.gradle',
                    'gradlew', 'gradlew.bat', 'README.md'}

SKIP_DIRS = {'.git', 'build', '.gradle'}
TEXT_EXT = ('.md', '.txt', '.sh', '.py', '.gradle', '.properties', '.xml', '.kt', '.java',
            '.json', '.yml', '.yaml', '.pro', 'gitignore', '.bat')

report = collections.OrderedDict()
for i, (a, _) in enumerate(LITERAL):
    report['L%d %s' % (i, a[:40])] = []
for i, (rx, _) in enumerate(REGEX):
    report['R%d %s' % (i, rx.pattern[:40])] = []

changed = []
scanned = 0
for dp, dn, fn in os.walk(ROOT):
    dn[:] = [d for d in dn if d not in SKIP_DIRS]
    for f in fn:
        p = os.path.join(dp, f)
        rel = p.replace(ROOT + os.sep, '').replace('\\', '/')
        # 脱敏范围：docs/、scripts/ 与根目录配置文件；源码目录（app/ player/ quickjs/ ...）
        # 必须与出包 APK 逐字节一致，绝不进入
        in_docs = rel.startswith('docs/') or rel.startswith('scripts/')
        in_root = ('/' not in rel) and rel in SCRUB_ROOT_FILES
        if not (in_docs or in_root):
            continue
        if not (f.endswith(TEXT_EXT) or f == '.gitignore'):
            continue
        try:
            if os.path.getsize(p) > 6 * 1024 * 1024:
                continue
            txt = io.open(p, 'r', encoding='utf-8', errors='ignore').read()
        except Exception:
            continue
        scanned += 1
        orig = txt
        for i, (a, b) in enumerate(LITERAL):
            c = txt.count(a)
            if c:
                report['L%d %s' % (i, a[:40])].append((rel, c))
                txt = txt.replace(a, b)
        for i, (rx, b) in enumerate(REGEX):
            n = len(rx.findall(txt))
            if n:
                report['R%d %s' % (i, rx.pattern[:40])].append((rel, n))
                txt = rx.sub(b, txt)
        if txt != orig and APPLY:
            io.open(p, 'w', encoding='utf-8', newline='').write(txt)
            changed.append(rel)

print('模式: %s   扫描文件: %d\n' % ('APPLY(已改)' if APPLY else 'REPORT(只读)', scanned))
print('=== 命中统计 ===')
for k, v in report.items():
    if not v:
        continue
    tot = sum(c for _, c in v)
    print('\n[%s]  共 %d 处 / %d 文件' % (k.split(' ', 1)[1], tot, len(v)))
    for rel, c in sorted(v, key=lambda x: -x[1])[:6]:
        print('      %-64s %d' % (rel, c))
    if len(v) > 6:
        print('      ... 另有 %d 个文件' % (len(v) - 6))

if APPLY:
    print('\n=== 已改写 %d 个文件 ===' % len(changed))
    for r in changed[:40]:
        print('   ', r)
else:
    print('\n（以上仅为报告，未做任何修改；确认后加 --apply 执行）')
