# -*- coding: utf-8 -*-
"""从宿主机 logcat 快照重建搜索轮次耗时（顺序配对版）。

bl/bm 没有耗时埋点，靠 logcat 时间戳反推；bn 起有「搜索耗时：」直接给数。
配对规则：按时间顺序扫，「搜索轮次」= 开一轮，「搜索收尾」= 关最近那一轮。
一轮若被下一个「搜索轮次」抢先，标记为「被下一轮取代（本轮很可能有结果）」。

用法：python _search_timeline.py <log> [log2 ...]
"""
import re, sys, io, os

TS = re.compile(r'^(\d\d)-(\d\d)\s+(\d\d):(\d\d):(\d\d)\.(\d{3})\s+\w/([^(]+)\(\s*(\d+)\):\s?(.*)$')
ROUND = '搜索轮次'
CLOSE = '搜索收尾'
FIRST = '搜索首条结果'
COST = '搜索耗时：'
JARLOAD = ('自定义爬虫代码加载成功', '加固 jar 手绑成功', 'JarKillNeutralizer')
NOISE = ('[okhttp]', '[OkHttp]')


def ms(h, m, s, z):
    return ((h * 60 + m) * 60 + s) * 1000 + z


def mmss(t):
    h, m, s, z = t // 3600000, (t % 3600000) // 60000, (t % 60000) // 1000, t % 1000
    return '%02d:%02d:%02d.%03d' % (h % 24, m, s, z)


def shell(m):
    """把日志里的可变部分抹掉，方便归类"""
    m = re.sub(r'\d+', '#', m)
    return m[:60]


def parse(path):
    rows = []
    with io.open(path, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            mt = TS.match(line.rstrip('\n'))
            if not mt:
                continue
            rows.append((ms(int(mt.group(3)), int(mt.group(4)), int(mt.group(5)), int(mt.group(6))),
                         mt.group(7).strip(), mt.group(8), mt.group(9)))
    return rows


def report(path):
    rows = parse(path)
    print('=' * 90)
    print('文件: %s   带时间戳行数=%d' % (path, len(rows)))
    print('=' * 90)

    # 把每行的 app 消息聚成事件流（已按时间排好，logcat 天然有序）
    cur = None
    rid = 0
    for i, (t, tag, pid, msg) in enumerate(rows):
        if msg.startswith(ROUND):
            if cur is not None:
                cur['ended'] = ('被下一轮取代', t, '（本轮多半是"有结果"的正常收尾，无收尾日志）')
                dump(cur)
            rid += 1
            cur = {'id': rid, 't0': t, 'pid': pid, 'msg': msg, 'events': [], 'ended': None}
            continue
        if cur is None or pid != cur['pid']:
            continue
        if msg.startswith(CLOSE):
            cur['ended'] = ('收尾', t, msg)
            dump(cur); cur = None
            continue
        if msg.startswith(FIRST) or msg.startswith(COST):
            cur['events'].append((t, msg))
            continue
        if any(k in msg for k in JARLOAD):
            cur['events'].append((t, shell(msg)))
            continue
    if cur is not None:
        dump(cur)

    # 顺带把独立的 bn 埋点行列出来（若有）
    extra = [r for r in rows if r[3].startswith(FIRST) or r[3].startswith(COST)]
    if extra:
        print('\n---- bn 新增埋点（直接给数） ----')
        for t, tag, pid, msg in extra:
            print('  %s  pid=%s  %s' % (mmss(t), pid, msg))


def dump(r):
    end = r['ended']
    if end and end[0] == '收尾':
        dur = '%.1fs' % ((end[1] - r['t0']) / 1000.0)
    elif end:
        dur = '%.1fs(至下一轮)' % ((end[1] - r['t0']) / 1000.0)
    else:
        dur = '未闭合'
    print('\n[轮次 %d] %s  pid=%s  耗时=%s' % (r['id'], mmss(r['t0']), r['pid'], dur))
    print('   起: %s' % r['msg'])
    if end:
        print('   结(%s): %s' % (end[0], end[2]))
    if r['events']:
        print('   轮内关键事件 %d 条：' % len(r['events']))
        for t, m in r['events'][:12]:
            print('     +%7.0fms  %s' % (t - r['t0'], m[:88]))


if __name__ == '__main__':
    for p in sys.argv[1:]:
        if os.path.exists(p):
            report(p)
        else:
            print('缺失: %s' % p)
