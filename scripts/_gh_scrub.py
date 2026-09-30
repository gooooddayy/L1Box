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
    'api.<订阅源A>.com':   'api.<订阅源A>.com',
    'sdapi.<订阅源A>.com': 'sdapi.<订阅源A>.com',
    'api.<订阅源B>.com':   'api.<订阅源B>.com',
    'api.<订阅源B>.com':  'api.<订阅源B>.com',
    'upload.<订阅源C>.cn': 'upload.<订阅源C>.cn',
    'tv.<订阅源C>.top':        'tv.<订阅源C>.top',
}

# 精确串替换
LITERAL = [
    ('<设备序列号>', '<设备序列号>'),
    ('测试机', '测试机'),
    ('<工作区>', '<工作区>'),
    ('<本机>/WorkBuddy\\2026-08-19-14-43-05', '<工作区>'),
    ('<本机>/.workbuddy', '<本机>/.workbuddy'),
    ('<本机>/.workbuddy', '<本机>\\.workbuddy'),
    ('<本机>/.gradle', '<本机>/.gradle'),
]
LITERAL += list(HOST_MAP.items())

# 正则替换
REGEX = [
    (re.compile(r'192\.168\.\d{1,3}\.\d{1,3}'), '192.168.x.x'),
    # 通用整机路径：任何盘符 + Users\<用户名>（覆盖根目录配置文件，LITERAL 只列了固定两处工作区前缀）
    (re.compile(r'[A-Za-z]:[\\/]{1,2}Users[\\/]{1,2}[A-Za-z0-9_.\-]+[\\/]{1,2}'), '<本机>/'),
    # Gradle properties 的**转义形态**：反斜杠写作 `\\`、冒号写作 `\:`（形如 `<本机>/`）。
    # 上一条要求盘符后紧跟 `:`，因此**匹配不到**这种形态 —— 实测漏网 `gradle.properties` 的
    # `org.gradle.java.home`（2026-09-29 修过一次、09-30 同步时又带回，属重复踩坑）。
    # 危害双重：① 泄露本机账号/工作区路径 ② clone 者构建因路径不存在而失败。
    (re.compile(r'[A-Za-z]\\?:[\\/]{1,3}Users[\\/]{1,3}[A-Za-z0-9_.\-]+[\\/]{1,3}'), '<本机>/'),
    (re.compile(r'(?<![\w/])/c/Users/[A-Za-z0-9_.\-]+/'), '<本机>/'),
    (re.compile(r'C:[\\/]Users[\\/][A-Za-z0-9_.\-]+[\\/]'), '<本机>/'),
    # Windows 路径里出现的 "AppData/Local/Android/Sdk" 之类别名保留，只抹用户名
    (re.compile(r'\b49\.233\.180\.127:880/zm3u8/[0-9a-f]+'), '<订阅接口地址已隐去>'),
    # 原为一条私有网盘下载地址的正则，换机后按需填回你自己的域名
    # (re.compile(r'download01\.<私有网盘域名>/download/[0-9a-f]+'), '<下载地址已隐去>'),
    (re.compile(r'https?://[^\s`"\'<>)\]]*\.rdt\.t[^\s`"\'<>)\]]*'), '<链接已隐去>'),
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
