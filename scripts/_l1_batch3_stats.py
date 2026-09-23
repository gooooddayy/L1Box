# -*- coding: utf-8 -*-
"""
批三验证脚本：深水位 96→128MB ＋ 进程冷启动清理上次会话的磁盘缓存。

用法：
    python _l1_batch3_stats.py <logcat 文件> [文件2 ...]

核对五件事：
  1. 【清理】冷启动有没有清掉上次遗留（改名待删 → 后台删除 → 归还了多少 MB）
  2. 【水位】接管日志里水位是不是 128MB（不是就说明 maxMemory()/4 把它夹小了）
  3. 【升档】够条件的会话里升档成功率（128MB 后守门是否仍能过；不过会打"未升档"原因）
  4. 【缓存】清理后新进程内缓存有没有重建、有没有命中（清理不能把缓存功能一起杀掉）
  5. 【起播】耗时分布（128MB 水位不该拖慢起播；清理的 IO 也不该影响起播）

日志 tag：L1Cache（缓存/清理）、L1Buffer（水位/升档）、L1Play（起播链路）
抓取命令：
    adb shell "nohup logcat -f /data/local/tmp/bk.log -v time L1Cache:I L1Play:I L1Buffer:I L1Diag:I AndroidRuntime:E *:S >/dev/null 2>&1 &"
"""
import io
import sys
import os


def body_of(line, tag):
    """取 logcat 消息正文。tag 后是 `(线程号):`，所以按 '):' 切（不能按 'TAG:' 切）"""
    i = line.find(tag)
    if i < 0:
        return ""
    j = line.find("):", i)
    if j < 0:
        return ""
    return line[j + 2:].strip()


def num_after(s, key, end):
    """从字符串里取 key 之后的数字；end 为空表示取到第一个非数字字符为止"""
    try:
        seg = s.split(key, 1)[1]
        if end:
            return int(seg.split(end, 1)[0].strip())
        digits = ""
        for ch in seg:
            if ch.isdigit():
                digits += ch
            elif digits:
                break
        return int(digits) if digits else None
    except Exception:
        return None


def med(a):
    if not a:
        return None
    b = sorted(a)
    n = len(b)
    return b[n // 2] if n % 2 else (b[n // 2 - 1] + b[n // 2]) // 2


def main():
    paths = sys.argv[1:] or ["_bk_test.log"]

    purges = []       # 清理：转入待删
    purged = []       # 清理完成：删除 N 个文件 / XMB
    ready = []        # 缓存库就绪
    hits = []         # 命中读取
    ignored = []      # 缓存被忽略
    takeovers = []    # 接管（含水位参数）
    escalates = []    # 升档
    holds = []        # 未升档（含原因）
    startlines = []   # 起播放行（缓冲量）
    sessions = []     # 起播会话
    starts = []       # 起播成功（地址指纹 + 耗时 + 内核）
    seen = set()
    cur = None

    for path in paths:
        if not os.path.exists(path):
            print("找不到日志文件:", path)
            continue
        with io.open(path, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                if "L1Cache" in line:
                    b = body_of(line, "L1Cache")
                    if not b:
                        continue
                    if b.startswith("缓存清理完成"):
                        purged.append(b)
                    elif b.startswith("缓存清理："):
                        purges.append(b)
                    elif b.startswith("磁盘缓存已就绪"):
                        ready.append(b)
                    elif b.startswith("命中读取"):
                        hits.append(b)
                    elif b.startswith("缓存被忽略"):
                        ignored.append(b)
                elif "L1Buffer" in line:
                    b = body_of(line, "L1Buffer")
                    if b.startswith("接管："):
                        takeovers.append(b)
                    elif b.startswith("缓冲升档 @ "):
                        escalates.append(b)
                    elif b.startswith("未升档 @ "):
                        holds.append(b)
                    elif b.startswith("起播放行 @ "):
                        startlines.append(b)
                elif "L1Play" in line:
                    b = body_of(line, "L1Play")
                    if b.startswith("播放心跳："):
                        b = b[len("播放心跳："):]
                    if b.startswith("起播 url="):
                        url = b.split("url=", 1)[1].strip()
                        cur = {"url": url, "key": url.split("…")[0].strip() or url,
                               "本机": ("127.0.0.1" in url or "localhost" in url)}
                        sessions.append(cur)
                    elif b.startswith("起播 已就绪") and cur is not None:
                        ms = num_after(b, "耗时 ", "ms")
                        core = b.split("内核=", 1)[1].strip() if "内核=" in b else "?"
                        if ms is not None:
                            starts.append({"key": cur["key"], "ms": ms, "内核": core})

    print("=" * 76)
    print("文件:", " + ".join(paths))
    print("=" * 76)

    # ---- 1. 清理 ----
    print("\n【1】进程冷启动清理（完全退出软件 → 下次启动把空间还回来）")
    if purged:
        total = 0
        for s in purged:
            mb = num_after(s, "/ ", "MB")
            if mb:
                total += mb
        print("    清理完成 %d 次，共归还 %d MB" % (len(purged), total))
        for s in purged:
            print("      ", s)
    elif purges:
        print("    有「转入待删」但没等到「清理完成」—— 后台删除线程还没跑完就把日志拉了")
        print("    （改名是瞬时的、空间归还才是最终效果，这条要看下一轮日志或设备目录）")
    else:
        print("    本次日志里没有清理记录")
        print("    若这轮确实做过「完全退出再进入」，那说明清理没触发，要查 App.onCreate 调用点")

    # ---- 2. 水位 ----
    print("\n【2】接管水位（目标 128MB；若被 maxMemory()/4 夹小会在这里暴露）")
    if takeovers:
        for s in takeovers[:4]:
            print("      ", s)
        for s in takeovers:
            if "/128MB" in s:
                print("    OK  水位 128MB 已生效")
                break
        else:
            print("    !! 没有出现 /128MB —— 被堆上限夹小了，看「堆上限=」那一项")
    else:
        print("    没有接管日志（可能日志起点晚于播放器创建）")

    # ---- 3. 升档 ----
    print("\n【3】升档成功率（128MB 后守门是否仍能过）")
    print("    升档 %d 次 / 未升档 %d 次" % (len(escalates), len(holds)))
    for s in escalates:
        print("      升档  ", s)
    for s in holds:
        print("      未升档", s)
    if holds and not escalates:
        print("    !! 全部未升档 —— 128MB 的守门（需 160MB 可用堆）在这台机器上过不去，应退回 96MB")
    elif escalates:
        print("    OK  至少有一次成功升档")

    # ---- 4. 缓存功能仍活着 ----
    print("\n【4】清理之后缓存功能有没有跟着一起废掉")
    print("    缓存库就绪 %d 次 / 命中读取 %d 次 / 被忽略 %d 次" % (len(ready), len(hits), len(ignored)))
    for s in ready[:2]:
        print("      ", s)
    if ready and not hits:
        print("    提示：建起来了但没命中 —— 首次播某集本来就不命中，要重播同一集才会命中")
    elif not ready:
        print("    !! 缓存库没建起来（检查设置页开关）")

    # ---- 5. 起播 ----
    print("\n【5】起播耗时（128MB 水位与清理 IO 都不该拖慢起播）")
    if startlines:
        print("    起播放行缓冲量（门槛名义值 1500ms，HLS 是整片跃升所以往往远高于它）：")
        for s in startlines[:6]:
            print("      ", s)
    if not starts:
        print("    没解析到「起播 已就绪」行")
    else:
        first, again = [], []
        for s in starts:
            if s["key"] in seen:
                again.append(s["ms"])
            else:
                seen.add(s["key"])
                first.append(s["ms"])
        print("    起播 %d 次，全部 %s" % (len(starts), sorted(s["ms"] for s in starts)))
        print("    中位 %sms" % med([s["ms"] for s in starts]))
        m1, m2 = med(first), med(again)
        print("    首次 %d 次中位 %s ；重播 %d 次中位 %s" % (
            len(first), str(m1) + "ms" if m1 else "-", len(again), str(m2) + "ms" if m2 else "-"))
        if m1 and m2 and m2 > 0:
            print("    → 重播比首次快 %.1f 倍" % (m1 / float(m2)))

    print("\n" + "=" * 76)


if __name__ == "__main__":
    main()
