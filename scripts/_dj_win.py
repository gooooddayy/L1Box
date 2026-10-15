import re, io, sys

src = r"_dj_dump_1012c.txt"
t0, t1 = sys.argv[1], sys.argv[2]
pid = sys.argv[3] if len(sys.argv) > 3 else None
pat = re.compile(r'(System\.out|AndroidRuntime|MemoryGuard|SubscriptionActivity|L1Sub|Unsupported class loader|Choreographer|ActivityManager: Displayed)')
ts = re.compile(r'^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)\s+(\d+)\s+(\d+)\s')
rows = []
for line in io.open(src, encoding='utf-8', errors='replace'):
    m = ts.match(line)
    if not m:
        continue
    t = m.group(1)[6:]          # 去掉 "10-12 " 前缀，只比 HH:MM:SS.mmm
    if not (t0 <= t <= t1):
        continue
    if pid and m.group(2) != pid:
        continue
    if pat.search(line):
        rows.append(line.rstrip())
print(f"===== {t0} ~ {t1} pid={pid} 命中 {len(rows)} =====")
for r in rows:
    print(r[:260])
