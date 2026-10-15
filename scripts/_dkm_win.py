import re, io, sys

src = sys.argv[1]
t0, t1 = sys.argv[2], sys.argv[3]
pid = sys.argv[4] if len(sys.argv) > 4 else None
pat = re.compile(r'(System\.out|AndroidRuntime|\bApp\b|SpiderLog|L1Sub|MemoryGuard|加固 jar|首页加载|首页网格|缓存预热|自检)')
ts = re.compile(r'^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)\s+(\d+)\s+(\d+)\s')
rows = []
for line in io.open(src, encoding='utf-8', errors='replace'):
    m = ts.match(line)
    if not m:
        continue
    t = m.group(1)[6:]
    if not (t0 <= t <= t1):
        continue
    if pid and m.group(2) != pid:
        continue
    if pat.search(line):
        rows.append(line.rstrip()[:230])
print(f"===== {t0} ~ {t1} pid={pid} 命中 {len(rows)} =====")
for r in rows:
    print(r)
