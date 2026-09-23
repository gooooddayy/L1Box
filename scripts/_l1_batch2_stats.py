# -*- coding: utf-8 -*-
"""
批二验证脚本：Exo 磁盘缓存（默认开 / 可关 / 本机端点排除 / 命中率观测）。

用法：
    python _l1_batch2_stats.py <logcat 文件> [文件2 ...]

核对四件事：
  1. 缓存库有没有建起来（目录、上限）—— 没建起来就直连，等于没开
  2. 有没有命中（命中读取的次数与字节）—— 开了但从不命中，说明缓存没起作用
  3. 缓存有没有被忽略及其原因
  4. 【高危项】本机端点（127.0.0.1 的 /l1.m3u8、/proxy）之后**不得**出现命中
     —— 这两种端点每集都是同一个固定地址，一旦被缓存就会「换集还在播上一集」

日志 tag：L1Cache（本批新增）、L1Play（起播链路）
抓取命令：
    adb shell "nohup logcat -f /data/local/tmp/x.log -v time L1Cache:I L1Play:I L1Buffer:I L1Diag:I AndroidRuntime:E *:S >/dev/null 2>&1 &"
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


def main():
    paths = sys.argv[1:] or ["_bj_test.log"]

    ready = []            # 缓存就绪
    hits = []             # 命中读取
    ignored = []          # 缓存被忽略
    sessions = []         # 起播会话（url 是否本机端点 / 是否出现命中）
    starts = []           # 每次成功的起播（地址指纹 + 耗时 + 内核）
    seen = set()          # 已出现过的地址指纹，用于区分首次/重播
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
                    if b.startswith("磁盘缓存已就绪"):
                        ready.append(b)
                    elif b.startswith("命中读取"):
                        hits.append(b)
                        if cur is not None:
                            cur["命中"] = cur.get("命中", 0) + 1
                    elif b.startswith("缓存被忽略"):
                        ignored.append(b)
                elif "L1Play" in line:
                    b = body_of(line, "L1Play")
                    if not b:
                        continue
                    if b.startswith("播放心跳："):
                        b = b[len("播放心跳："):]
                    if b.startswith("起播 url="):
                        url = b.split("url=", 1)[1].strip()
                        cur = {"url": url, "本机": ("127.0.0.1" in url or "localhost" in url),
                               "key": url.split("…")[0].strip() or url,
                               "命中": 0}
                        sessions.append(cur)
                    elif b.startswith("起播 已就绪") and cur is not None:
                        ms = num_after(b, "耗时 ", "ms")
                        core = b.split("内核=", 1)[1].strip() if "内核=" in b else "?"
                        if ms is not None:
                            starts.append({"key": cur["key"], "ms": ms, "内核": core})

    print("=" * 76)
    print("文件:", " + ".join(paths))
    print("=" * 76)

    # ---- 1. 缓存库 ----
    print("\n【1】缓存库是否建起来")
    if ready:
        print("    就绪 %d 次（进程生命周期内只应出现一次）" % len(ready))
        for s in ready[:3]:
            print("      ", s)
    else:
        print("    !! 没有「磁盘缓存已就绪」—— 缓存没启用（检查设置页开关 / 是否走了本机地址）")

    # ---- 2. 命中情况 ----
    print("\n【2】命中情况（开了但从不命中＝缓存没起作用）")
    print("    命中读取回调 %d 次" % len(hits))
    if hits:
        print("    最近 5 条：")
        for s in hits[-5:]:
            print("      ", s)
    else:
        print("    提示：首次播放某集必然不命中（缓存里还没有），要「回看/重播同一集」才会命中")

    # ---- 3. 缓存被忽略 ----
    print("\n【3】缓存被忽略")
    if ignored:
        print("    %d 次：" % len(ignored))
        for s in ignored[:8]:
            print("      ", s)
    else:
        print("    无")

    # ---- 4. 【高危】本机端点不得被缓存 ----
    print("\n【4】高危项：本机端点（/l1.m3u8、/proxy）有没有被缓存")
    local = [s for s in sessions if s["本机"]]
    if not local:
        print("    本次日志里没有本机端点起播（净化未命中），该项本次测不到")
        print("    静态防线仍在：闸门断言 isCacheableUrl 必须对 127.0.0.1 返回 false")
    else:
        bad = [s for s in local if s.get("命中", 0) > 0]
        print("    本机端点起播 %d 次，其中有命中的 %d 次" % (len(local), len(bad)))
        for s in local:
            print("      %s  命中=%d" % (s["url"][:60], s.get("命中", 0)))
        if bad:
            print("    !! 本机端点出现了缓存命中 —— 换集可能播上一集，必须立刻关掉缓存开关并排查")
        else:
            print("    OK  本机端点没有命中（排除生效）")

    # ---- 5. 起播耗时：同地址「首次」vs「重播」----
    # 这是本批唯一能直接量出缓存价值的对比：同一个直链地址第二次播放才能命中缓存，
    # 所以把会话按地址分组，比同组内第 1 次与第 2 次的耗时。不分组就是在拿不同源的不同集比。
    print("\n【5】起播耗时：同地址「首次」vs「重播」（缓存价值的直接体现）")
    if not starts:
        print("    没解析到「起播 已就绪」行")
    else:
        first, again = [], []
        print("    %-46s %-12s %s" % ("地址指纹", "次序", "起播耗时"))
        for s in starts:
            order = "首次"
            if s["key"] in seen:
                order = "重播"
                again.append(s["ms"])
            else:
                seen.add(s["key"])
                first.append(s["ms"])
            print("      %-44s %-10s %dms   %s" % (s["key"][:44], order, s["ms"], s["内核"]))

        def med(a):
            if not a:
                return None
            b = sorted(a)
            n = len(b)
            return b[n // 2] if n % 2 else (b[n // 2 - 1] + b[n // 2]) // 2

        m1, m2 = med(first), med(again)
        print("\n    首次 %d 次：%s → 中位 %s" % (len(first), sorted(first), str(m1) + "ms" if m1 else "-"))
        if again:
            print("    重播 %d 次：%s → 中位 %s" % (len(again), sorted(again), str(m2) + "ms" if m2 else "-"))
            if m1 and m2 and m2 > 0:
                print("    → 重播比首次快 %.1f 倍" % (m1 / float(m2)))
        else:
            print("    重播 0 次 —— 说明这轮没重播过同一集/同一地址，缓存价值这次量不出来")

    print("\n" + "=" * 76)


if __name__ == "__main__":
    main()
