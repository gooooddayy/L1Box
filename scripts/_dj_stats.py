import io, re, collections

src = r"_dj_dump_1012c.txt"
lines = io.open(src, encoding='utf-8', errors='replace').read().splitlines()
APP = re.compile(r'^\d\d-\d\d (\d\d:\d\d:\d\d\.\d\d\d)\s+(\d+)\s+(\d+)\s+(\w)\s+(\S+):\s?(.*)$')

events = []
for ln in lines:
    m = APP.match(ln)
    if not m:
        continue
    t, pid, tid, lvl, tag, msg = m.groups()
    if tag not in ('System.out', 'AndroidRuntime', 'App', 'AndroidRuntimeException'):
        continue
    if 'okhttp' in msg or 'OkHttp' in msg:
        continue
    if not re.search(r'(首页|L1Sub|JAR|jar|Init|NoSuchMethod|爬虫|订阅|线路)', msg):
        continue
    events.append((t, pid, tag, msg))

print("总事件行:", len(events), " 时间范围:", events[0][0], "→", events[-1][0])
print()

def cnt(pat):
    c = [e for e in events if re.search(pat, e[3])]
    return len(c)

print("== 计数 ==")
for label, pat in [
    ("换源过渡（显示加载中）", "换源过渡"),
    ("换源装载成功", "换源装载成功"),
    ("换源装载失败", "换源装载失败"),
    ("换源重载去重跳过", "换源重载去重"),
    ("真拉首页", "真拉首页"),
    ("首页网格 发起网络加载", "发起网络加载"),
    ("jar 下载", "jar 下载"),
    ("jar 缓存归属不符", "归属不符"),
    ("自定义爬虫代码加载成功", "自定义爬虫代码加载成功"),
    ("HTTP 403", "HTTP 403"),
    ("网络不佳/已使用缓存", "网络不佳"),
    ("Init$3 / NoSuchMethod", r"Init\$3|NoSuchMethodError"),
    ("订阅内容不可用", "订阅内容不可用"),
]:
    print(f"  {label:24s} {cnt(pat)}")

print()
print("== jar 下载明细 ==")
for t, pid, tag, msg in events:
    if 'jar 下载' in msg:
        print(" ", t, msg[:150])

print()
print("== 崩溃时刻（去重）==")
crash = []
for t, pid, tag, msg in events:
    if 'NoSuchMethodError' in msg and tag == 'AndroidRuntime':
        crash.append((t, pid))
for t, pid in crash:
    print(" ", t, "pid=", pid)

print()
print("== 换源装载失败明细 ==")
for t, pid, tag, msg in events:
    if '换源装载失败' in msg:
        print(" ", t, msg[:170])

print()
print("== 归属不符明细 ==")
for t, pid, tag, msg in events:
    if '归属不符' in msg:
        print(" ", t, msg[:190])
