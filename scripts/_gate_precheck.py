#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
闸门断言预检（check_l1box_regressions.sh 的快速自检）

为什么需要：
    整脚本 780+ 条 grep，跑一次 1~2 分钟，且**只输出 OK 行、不输出进度** ——
    被宿主 SIGTERM 掐断时，分不清"跑完了"还是"没跑完"。
    本脚本用 Python 把所有断言在**秒级**内预检一遍，直接列出可疑条目，
    避免靠"构建一次 5 分钟"来发现问题（2026-09-29 为此返工了两次）。

用法：
    python _gate_precheck.py            # 预检全部
    python _gate_precheck.py cf cg      # 只看某些批次（按说明文字里出现的标签过滤）

退出码：0=无问题；1=有可疑条目
"""
import re
import sys
import os

WS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(WS, 'FreeBox-src')
GATE = os.path.join(WS, 'check_l1box_regressions.sh')


def load_var_map(lines):
    """收集脚本里 `VAR=app/...` 形式的路径变量"""
    m = {}
    for line in lines:
        mm = re.match(r'^([A-Z][A-Z0-9]*)=(app/.+)$', line.strip())
        if mm:
            m[mm.group(1)] = mm.group(2)
    return m


def main():
    only = set(sys.argv[1:])
    with open(GATE, encoding='utf-8', errors='replace') as fh:
        lines = fh.read().splitlines()
    varmap = load_var_map(lines)

    checked = 0
    bad = []
    for i, line in enumerate(lines, 1):
        m = re.match(r"^\s*(must|mustnot|count)\s+\"\$([A-Z0-9]+)\"\s+'((?:[^'\\]|\\.)*)'(\s+(\d+))?", line)
        if not m:
            continue
        kind, var, pat, _, need = m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)
        if var not in varmap:
            continue
        if only:
            # 过滤：批次标签取自行尾说明或前一行注释；这里简化为"说明文字含标签"
            if not any(t in line for t in only):
                continue
        checked += 1
        path = os.path.join(ROOT, varmap[var])
        if not os.path.isfile(path):
            bad.append((i, var, pat, '文件缺失: ' + varmap[var])); continue
        with open(path, encoding='utf-8', errors='replace') as fh:
            txt = fh.read()
        try:
            hit = re.search(pat, txt, re.M)
        except re.error as e:
            bad.append((i, var, pat, '正则错误: %s' % e)); continue
        if kind == 'must' and not hit:
            bad.append((i, var, pat, 'must 未命中'))
        elif kind == 'mustnot' and hit:
            bad.append((i, var, pat, 'mustnot 却命中了'))
        elif kind == 'count':
            # count 是「行数 ≥ N」；这里逐行计数，与闸门的 grep -c 口径一致
            n = sum(1 for ln in txt.splitlines() if re.search(pat, ln))
            if need is not None and n < int(need):
                bad.append((i, var, pat, 'count 只找到 %d 处，需 ≥%s' % (n, need)))

    print('变量表 %d 个；已检查断言 %d 条' % (len(varmap), checked))
    if bad:
        print('--- 可疑 %d 条 ---' % len(bad))
        for i, var, pat, why in bad:
            print('  L%-5d [%s] %s\n         /%s/' % (i, var, why, pat))
        return 1
    print('--- 全部通过 ---')
    return 0


if __name__ == '__main__':
    sys.exit(main())
