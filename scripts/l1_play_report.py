# -*- coding: utf-8 -*-
"""L1Box 播放埋点分析器：把 logcat 里的 L1Play 行变成可判定的结论。

用途：
    测试方案里的判定项（起播成功率、看门狗是否误判、兜底链是否有界、真失败是否都留痕）
    全部从这里出数 —— 不靠肉眼读日志，也不靠"看着还行"。

用法：
    python l1_play_report.py <日志文件> [--since HH:MM:SS] [--until HH:MM:SS]

输入可以是：
    adb shell "grep -a L1Play /data/local/tmp/l1_xx.log" > xx.log    （推荐，只含埋点）
    adb logcat -d -s L1Play                                          （也可以）

埋点格式（见 util/PlayTrace.java）：
    播放心跳：<stage> <detail>
    播放失败：<stage> reason=<...> ms=<...>
"""

import io
import re
import sys

LINE = re.compile(r'(\d\d)-(\d\d)\s+(\d\d:\d\d:\d\d\.\d+).*?L1Play.*?:\s?(.*)$')
HEART = '播放心跳：'
FAIL = '播放失败：'

STAGES = ['取链', '净化', '起播', '兜底', '看门狗', '出链', '变形', '去BOM', '重试', '请求头', '隧道']


def parse(path, since=None, until=None):
    rows = []
    try:
        raw = io.open(path, encoding='utf-8', errors='ignore').read()
    except Exception as e:
        print('读取失败：%s' % e)
        return rows
    for line in raw.split('\n'):
        m = LINE.search(line)
        if not m:
            continue
        t = m.group(3)
        msg = m.group(4).strip().replace('\r', '')
        if since and t < since:
            continue
        if until and t > until:
            continue
        if msg.startswith(HEART):
            body = msg[len(HEART):]
            stage = body.split(' ', 1)[0]
            detail = body[len(stage):].strip()
            rows.append({'t': t, 'kind': 'heart', 'stage': stage, 'detail': detail})
        elif msg.startswith(FAIL):
            body = msg[len(FAIL):]
            stage = body.split(' ', 1)[0]
            detail = body[len(stage):].strip()
            ms = re.search(r'ms=(-?\d+)', detail)
            reason = re.search(r'reason=(.*?)(?:\s+ms=|$)', detail)
            rows.append({'t': t, 'kind': 'fail', 'stage': stage, 'detail': detail,
                         'ms': int(ms.group(1)) if ms else None,
                         'reason': reason.group(1).strip() if reason else ''})
    return rows


def count(rows, pred):
    return len([r for r in rows if pred(r)])


def has(rows, stage, kw):
    return [r for r in rows if r['stage'] == stage and kw in r['detail']]


def pct(a, b):
    if b <= 0:
        return 'n/a'
    return '%.1f%%' % (100.0 * a / b)


def median(xs):
    if not xs:
        return None
    ys = sorted(xs)
    n = len(ys)
    return ys[n // 2] if n % 2 else (ys[n // 2 - 1] + ys[n // 2]) // 2


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    path = sys.argv[1]
    since = until = None
    for i, a in enumerate(sys.argv):
        if a == '--since' and i + 1 < len(sys.argv):
            since = sys.argv[i + 1]
        if a == '--until' and i + 1 < len(sys.argv):
            until = sys.argv[i + 1]

    rows = parse(path, since, until)
    if not rows:
        print('没有解析到任何 L1Play 行（区间 %s~%s）' % (since, until))
        return

    span = '%s ~ %s' % (rows[0]['t'], rows[-1]['t'])
    print('=' * 72)
    print('L1Box 播放埋点分析  样本 %d 行   区间 %s' % (len(rows), span))
    print('=' * 72)

    # ---- 阶段分布 ----
    print('\n[阶段分布]')
    for st in STAGES:
        n = count(rows, lambda r, s=st: r['stage'] == s)
        if n:
            print('  %-6s %4d  (其中失败 %d)' % (st, n,
                  count(rows, lambda r, s=st: r['stage'] == s and r['kind'] == 'fail')))

    # ---- 起播 ----
    starts = has(rows, '起播', '起播 url=') + [r for r in rows if r['stage'] == '起播' and r['detail'].startswith('url=')]
    starts = [r for r in rows if r['stage'] == '起播' and r['detail'].startswith('url=')]
    ready = has(rows, '起播', '已就绪')
    times = []
    for r in ready:
        m = re.search(r'耗时 (\d+)ms', r['detail'])
        if m:
            times.append(int(m.group(1)))

    print('\n[起播]')
    print('  起播尝试 %d 次 / 已就绪 %d 次 → 就绪率 %s' % (len(starts), len(ready), pct(len(ready), len(starts))))
    if times:
        print('  耗时: 最快 %dms  中位 %dms  最慢 %dms' % (min(times), median(times), max(times)))
        buckets = [(0, 1000), (1000, 3000), (3000, 10000), (10000, 10 ** 9)]
        for lo, hi in buckets:
            n = len([t for t in times if lo <= t < hi])
            label = ('<%ds' % (hi // 1000)) if hi < 10 ** 9 else '>10s'
            label = ('<1s' if lo == 0 else '%d~%s' % (lo // 1000, label))
            print('    %-8s %d' % (label, n))

    # ---- 净化路径 ----
    print('\n[净化]')
    honor = has(rows, '净化', '命中')
    skip = has(rows, '净化', '无需处理')
    failp = has(rows, '净化', '预取失败')
    print('  命中(走本地 HLS) %d / 无需处理 %d / 预取失败 %d' % (len(honor), len(skip), len(failp)))
    kinds = {}
    for r in failp:
        m = re.search(r'预取失败或超时\(([^)]*)\)', r['detail'])
        kinds[m.group(1) if m else '未归因'] = kinds.get(m.group(1) if m else '未归因', 0) + 1
    for k, v in sorted(kinds.items(), key=lambda x: -x[1]):
        print('    失败归因 %-24s %d' % (k, v))
    for r in honor[-3:]:
        print('    %s  %s' % (r['t'], r['detail']))

    # ---- 看门狗（这批的重点） ----
    print('\n[看门狗]')
    rel = has(rows, '看门狗', '起播有进展，解除')
    stall = has(rows, '看门狗', '有数据在流')
    recheck = has(rows, '看门狗', '再复核')
    kill = [r for r in rows if r['kind'] == 'fail' and r['stage'] == '起播' and '看门狗判定' in r['reason']]
    print('  解除(有真进展) %d / 空转观察 %d / 无数据复核 %d / 判死 %d' % (len(rel), len(stall), len(recheck), len(kill)))
    if stall:
        print('  ⚑ 出现过「有数据无画面」—— 说明判据修正确实被真实网络条件触发过：')
        for r in stall[:6]:
            print('    %s  %s' % (r['t'], r['detail']))
    if kill:
        print('  ⚑ 判死明细（需要人工确认是不是误杀）：')
        for r in kill:
            print('    %s  %s' % (r['t'], r['reason']))

    # ---- 兜底链 ----
    print('\n[兜底链]')
    fb = has(rows, '兜底', '降级到')
    raw_fb = has(rows, '兜底', '回退原始直连')
    variant = has(rows, '变形', '重试 url=')
    print('  内置降级 %d 次 / 回退直连 %d 次 / 变形重试 %d 次' % (len(fb), len(raw_fb), len(variant)))

    # 有界性：按"换集"切段，段内降级次数
    seg = []
    cur = []
    for r in rows:
        if r['stage'] == '取链' and '换集' in r['detail']:
            if cur:
                seg.append(cur)
            cur = []
        cur.append(r)
    if cur:
        seg.append(cur)
    worst = 0
    worst_seg = None
    for s in seg:
        n = count(s, lambda r: r['stage'] == '兜底' and '降级到' in r['detail'])
        if n > worst:
            worst, worst_seg = n, s
    print('  分 %d 段（按换集切）；单集最多降级 %d 次' % (len(seg), worst))
    if worst > 4:
        print('  !! 单集降级次数 > 4，疑似循环或额度未生效 —— 需要查')
    else:
        print('  OK 单集降级次数在额度内（回退直连 1 + 变形 1 + 内置最多 2）')

    # ---- 真失败是否留痕 ----
    print('\n[失败留痕]')
    fails = [r for r in rows if r['kind'] == 'fail']
    print('  失败埋点共 %d 条' % len(fails))
    by_stage = {}
    for r in fails:
        by_stage.setdefault(r['stage'], []).append(r)
    for st, rs in sorted(by_stage.items()):
        mss = [r['ms'] for r in rs if r['ms'] and r['ms'] >= 0]
        print('    %-6s %d 条   耗时 %s' % (st, len(rs),
              ('中位 %dms' % median(mss)) if mss else '无有效耗时'))
    all_exhaust = has(rows, '出链', '自动兜底试尽')
    budget = has(rows, '出链', '预算用尽')
    print('  兜底试尽出口 %d 次（必须与"出链"失败埋点配对，配对率 %s）'
          % (len(all_exhaust), pct(len(by_stage.get('出链', [])), len(all_exhaust) if all_exhaust else 1)))
    print('  预算用尽出口 %d 次' % len(budget))

    print('\n[结论要点]')
    print('  · 起播失败但没留痕的情形应为 0：检查上面各 stage 是否都有条目')
    print('  · 判死次数 = %d；若判死之后紧跟"已就绪"且耗时正常，则需人工判定是否误杀' % len(kill))
    print('=' * 72)


if __name__ == '__main__':
    main()
