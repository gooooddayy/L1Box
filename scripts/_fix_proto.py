# -*- coding: utf-8 -*-
"""在真实订阅配置上验证修法：保留换行 + 字符串感知删除 // 行注释，再走原判据。
不改动任何工程代码，纯离线模拟。"""
import json, sys, urllib.request

KEYS = ['"sites"', '"urls"', '"storeHouse"', '"spider"', '"parses"']


def strip_invisible_keep_nl(raw):
    """只去 BOM / 零宽 / 其他控制字符，**保留 \\n \\r 与空格**（JSON 的空白本就合法）"""
    out = []
    for c in raw:
        if c in "\ufeff\u200b\u200c\u200d\u00a0":
            continue
        if c < ' ' and c not in "\n\r\t":
            continue
        out.append(c)
    return "".join(out).strip()


def clean_json(s):
    """原 cleanJson + 新增：字符串外遇到 // 删到行尾（现在换行还在，边界可界定）"""
    if s is None:
        return None
    sb = []
    in_str = False
    esc = False
    i = 0
    n = len(s)
    while i < n:
        c = s[i]
        if in_str:
            sb.append(c)
            if esc:
                esc = False
            elif c == '\\':
                esc = True
            elif c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            sb.append(c)
            i += 1
            continue
        if c == '/':
            # 块注释 /* … */
            if i + 1 < n and s[i + 1] == '*':
                i += 2
                while i + 1 < n and not (s[i] == '*' and s[i + 1] == '/'):
                    i += 1
                i += 2
                continue
            # ★ 新增：行注释 // … 删到行尾（换行保留 ⇒ 有明确边界）
            if i + 1 < n and s[i + 1] == '/':
                i += 2
                while i < n and s[i] not in '\r\n':
                    i += 1
                continue
        if c == ',':
            j = i + 1
            while j < n and s[j].isspace():
                j += 1
            if j < n and s[j] in '}]':
                i += 1
                continue
        sb.append(c)
        i += 1
    return "".join(sb)


def looks_parsable(s):
    if s is None or len(s) < 2:
        return False
    depth = 0
    in_str = False
    esc = False
    for i, c in enumerate(s):
        if in_str:
            if esc:
                esc = False
            elif c == '\\':
                esc = True
            elif c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
        elif c in '{[':
            depth += 1
        elif c in '}]':
            depth -= 1
            if depth < 0:
                return False
        elif c == '/' and i + 1 < len(s) and s[i + 1] in '/*':
            return False
    return depth == 0 and not in_str


def has_sub_key(s):
    return any(k in s for k in KEYS)


def strip_invisible_old(raw):
    """现行实现：连空格与换行一并删掉"""
    out = []
    for c in raw:
        if c in "\ufeff\u200b\u200c\u200d\u00a0":
            continue
        if c <= ' ':
            continue
        out.append(c)
    return "".join(out).strip()


def pick_json(s):
    i = s.find('{')
    j = s.rfind('}')
    if i < 0 or j <= i + 1:
        return None
    block = s[i:j + 1]
    cleaned = clean_json(block)
    self_contained = s[0] == '{' and s[-1] == '}'
    if (has_sub_key(cleaned) or self_contained) and looks_parsable(cleaned):
        return cleaned
    return None


def judge(raw, mode):
    s = strip_invisible_old(raw) if mode == 'old' else strip_invisible_keep_nl(raw)
    if not s:
        return 'EMPTY', None
    js = pick_json(s)
    if js is None:
        return 'NOT_SUB', None
    # 真正的解析成功判据：Gson 能解（这里用 json.loads 等价模拟）
    try:
        d = json.loads(js)
    except Exception as e:
        return 'NOT_SUB(Gson会炸:%s)' % str(e)[:40], None
    return 'OK', d


TESTS = [
    ("本机下载的真实配置 XYQTVBox.json", r"<工作区>\_probe1.json"),
]

print("=" * 96)
print("修法验证：保留换行 + 字符串感知删除 // 行注释")
print("=" * 96)
for name, path in TESTS:
    raw = open(path, encoding='utf-8', errors='replace').read()
    print(f"\n【{name}】{len(raw)} 字符")
    for mode in ('old', 'new'):
        verdict, d = judge(raw, mode)
        tag = '现行' if mode == 'old' else '修法'
        extra = ''
        if d is not None:
            extra = f"  顶层键={list(d)[:8]}  sites数={len(d.get('sites') or [])}"
        print(f"   {tag}: {verdict}{extra}")
