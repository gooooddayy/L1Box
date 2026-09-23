# -*- coding: utf-8 -*-
"""列出日志里所有 app 侧（System.out / 各 tag）消息的「去数字」骨架，看有哪些可用锚点。"""
import re, sys, io, collections

TS = re.compile(r'^(\d\d-\d\d)\s+(\d\d:\d\d):(\d\d)\.(\d{3})\s+\w/([^(]+)\(\s*(\d+)\):\s?(.*)$')

def skel(m):
    s = re.sub(r'\d+', '#', m)
    s = re.sub(r'#[#\./:\-]*', '#', s)
    return s[:70]

def main():
    for path in sys.argv[1:]:
        c = collections.Counter()
        first = {}
        with io.open(path, 'r', encoding='utf-8', errors='replace') as f:
            for line in f:
                mt = TS.match(line.rstrip('\n'))
                if not mt:
                    continue
                msg = mt.group(7)
                k = skel(msg)
                c[k] += 1
                if k not in first:
                    first[k] = mt.group(2) + ':' + mt.group(3)
        print('=' * 78)
        print('文件: %s' % path)
        print('=' * 78)
        for k, n in c.most_common():
            print('%5d  %s   (首次 %s)' % (n, k, first[k]))

if __name__ == '__main__':
    main()
