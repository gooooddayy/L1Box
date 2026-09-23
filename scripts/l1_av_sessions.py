# -*- coding: utf-8 -*-
"""av 全场景测试：把埋点还原成「用户动作级」会话表。

为什么需要它：埋点数 != 用户看到的错误数。
上游 errorWithRetry → autoRetry() 会在失败后"静默自动重试一次"（改动前就是这样），
所以一次用户点击失败会在日志里留下 2 条失败埋点 + 2 轮取链/起播。
这个脚本按"取链 开始 距上一条失败埋点 < 1.5s ⇒ 同一动作的自动重试"来分离。
"""
import io
import re
import sys

LOG = sys.argv[1] if len(sys.argv) > 1 else "logcat_av_test_20260915.log"
OUT = sys.argv[2] if len(sys.argv) > 2 else "_av_sessions.md"

line_re = re.compile(r"^(\d\d-\d\d)\s+(\d\d:\d\d:\d\d\.\d+)\s+\w/(\S+)\s*\(\s*(\d+)\):\s*(.*)$")


def secs(t):
    h, m, s = t.split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)


rows = []
for ln in io.open(LOG, encoding="utf-8", errors="replace"):
    if "L1Play" not in ln:
        continue
    m = line_re.match(ln.strip())
    if not m:
        continue
    _, t, tag, pid, msg = m.groups()
    if tag != "L1Play":
        continue
    rows.append((t, pid, msg.strip()))

sessions = []
cur = None
last_fail_t = None


def flush():
    if cur:
        sessions.append(cur)


for t, pid, msg in rows:
    ts = secs(t)
    if msg.startswith("播放心跳：取链 换集"):
        # 显式动作边界：只有「换集」（含首次点播）算一次新的用户动作。
        # ⚠ 不要用"距上次失败 < N 秒"来猜是不是同一次动作里的自动重取 —— 退避重取的间隔
        #   已从 0 拉长到 1.2s/2.5s，任何固定阈值都会把第 2 次重取误判成一次新点播
        #   （实测把 11 次点播数成 12 次、把 1 次失败数成 2 次，成功率凭空少了 7 个点）。
        flush()
        cur = {"t": t, "pid": pid, "site": "", "ep": "", "res": "", "detail": [],
               "retry": 0, "failcause": "", "notes": [], "series": ""}
        cur["series"] = re.sub(r"^.*换集 ", "", msg).split("#")[0]
        continue
    if msg.startswith("播放心跳：取链 开始"):
        if cur is None:
            cur = {"t": t, "pid": pid, "site": "", "ep": "", "res": "", "detail": [],
                   "retry": 0, "failcause": "", "notes": [], "series": ""}
        elif cur["res"] == "FAIL":
            # 同一动作内、失败之后再取链 = 自动重取（退避重取）或用户手动重试
            cur["retry"] += 1
            cur["notes"].append(f"{t} 自动重取")
        mm = re.search(r"ep=(\d+) 站点=(\S+)", msg)
        if mm:
            cur["ep"], cur["site"] = mm.group(1), mm.group(2)
        continue
    if cur is None:
        continue
    elif msg.startswith("播放心跳：取链 拿到直链"):
        cur["url"] = msg.split("url=", 1)[1]
    elif msg.startswith("播放心跳：净化 命中"):
        cur["notes"].append("净化命中→本地HLS " + msg.split("ms=")[-1] + "ms")
    elif msg.startswith("播放心跳：净化 无需处理"):
        cur["notes"].append("净化无需处理 " + msg.split("ms=")[-1] + "ms")
    elif msg.startswith("播放心跳：净化 预取失败"):
        cur["notes"].append("净化预取失败(" + msg.split("(")[-1].split(")")[0] + ")")
    elif msg.startswith("播放心跳：变形"):
        cur["notes"].append("变形重试 " + msg.split("重试 url=")[-1][:60])
    elif msg.startswith("播放心跳：兜底 降级"):
        cur["notes"].append("内置降级→" + msg.split("降级到 ")[-1].split("（")[0])
    elif msg.startswith("播放心跳：看门狗"):
        cur["notes"].append("看门狗 " + msg.replace("播放心跳：看门狗 ", "")[:40])
    elif "已就绪" in msg:
        cur["res"] = "OK"
        cur["detail"] = msg.split("耗时 ")[-1]
    elif msg.startswith("播放失败"):
        cur["res"] = "FAIL"
        cur["failcause"] = msg.replace("播放失败：", "")
        last_fail_t = ts
    elif msg.startswith("播放心跳：重试 用户手动重试"):
        cur["notes"].append("用户手动重试")

flush()

ok = [s for s in sessions if s["res"] == "OK"]
bad = [s for s in sessions if s["res"] == "FAIL"]
unknown = [s for s in sessions if s["res"] not in ("OK", "FAIL")]

# 观看时长 = 本次「已就绪」到下一次「取链 开始」的间隔（用户测多设备/连播时看是否够 30 秒）
starts = [r for r in rows if r[2].startswith("播放心跳：取链 开始")]
watch = []
for t, pid, msg in rows:
    if "已就绪" not in msg:
        continue
    ts = secs(t)
    nxt = [r for r in starts if secs(r[0]) > ts]
    if nxt:
        watch.append((t, secs(nxt[0][0]) - ts))
wd = sorted(d for _, d in watch)
watch_line = ""
if wd:
    watch_line = ("- 可算观看时长 {n} 段：≥30s **{g}** 段，中位 {med:.1f}s，最短 {mn:.1f}s，最长 {mx:.1f}s".format(
        n=len(wd), g=sum(1 for _, d in watch if d >= 30), med=wd[len(wd) // 2], mn=wd[0], mx=wd[-1]))
watch_short = "- 不足 30s 的片段：" + "、".join(
    "{} {:.0f}s".format(t, d) for t, d in sorted(watch, key=lambda x: x[1]) if d < 30)
total_line = "- 埋点跨度：{:.0f} 分钟".format((secs(rows[-1][0]) - secs(rows[0][0])) / 60)

out = []
out.append("# av 全场景测试 · 用户动作级会话表\n")
out.append(f"日志：`{LOG}`　埋点区间：{rows[0][0]} ~ {rows[-1][0]}\n")
out.append(f"- 用户动作（点播）次数：**{len(sessions)}**")
out.append(f"- 起播就绪：**{len(ok)}**　失败：**{len(bad)}**　结果未留痕（用户提前离开）：**{len(unknown)}**")
if sessions:
    out.append(f"- 起播成功率：**{len(ok) / len(sessions) * 100:.1f}%**（口径 ≥95%）")
out.append(watch_line)
out.append(watch_short)
out.append(total_line)
out.append("")
out.append("| # | 时间 | 站点 | 集 | 结果 | 说明 |")
out.append("|---|------|------|---|------|------|")
for i, s in enumerate(sessions, 1):
    res = {"OK": "✅就绪", "FAIL": "❌失败"}.get(s["res"], "⚠️无留痕")
    desc = []
    if s["res"] == "OK":
        desc.append(s["detail"])
    if s["failcause"]:
        desc.append(s["failcause"])
    if s["retry"]:
        desc.append(f"内含自动重试×{s['retry']}")
    desc += s["notes"]
    if s.get("url"):
        desc.append("url=" + s["url"][:46])
    out.append("| {} | {} | {} | {} | {} | {} |".format(
        i, s["t"], s["site"], s["ep"], res, "<br>".join(desc)))

io.open(OUT, "w", encoding="utf-8").write("\n".join(out) + "\n")
print("\n".join(out[:8]))
print("...")
print(f"写出 {OUT}")
