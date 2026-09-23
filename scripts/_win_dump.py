import re, sys, io

LOG, t_start, t_end = sys.argv[1], sys.argv[2], sys.argv[3]
KEEP = re.compile(sys.argv[4]) if len(sys.argv) > 4 else None

LINE = re.compile(r'^(\d\d)-(\d\d)\s+(\d\d):(\d\d):(\d\d)\.(\d+)\s+(\w)/(\S+)\s*\(\s*(\d+)\):\s?(.*)$')
SKIP = re.compile(r'\s+at |^\s+\.\.\. \d+ more')

def hhmmss(x):
    h, m, s = x.split(':')
    return (int(h) * 60 + int(m)) * 60 + float(s)

a, b = hhmmss(t_start), hhmmss(t_end)
with io.open(LOG, 'r', encoding='utf-8', errors='replace') as f:
    for ln in f:
        m = LINE.match(ln.rstrip('\n'))
        if not m:
            continue
        mo, d, h, mi, s, ms, lvl, tag, p, msg = m.groups()
        t = (int(h) * 60 + int(mi)) * 60 + int(s) + int(ms) / 1000.0
        if not (a <= t <= b):
            continue
        if SKIP.search(msg):
            continue
        if KEEP and not KEEP.search(tag + ' ' + msg):
            continue
        print('%s %s/%-16s(%s) %s' % (mi + ':' + s + '.' + ms, lvl, tag[:16], p, msg[:170]))
