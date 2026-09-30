#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ce 版复测判定（2026-09-29）

只做一件事：把一轮 logcat dump 收敛成「ce 要回答的 5 个问题」的答案。
  1. 自转发兜底到底成不成（变体1 查询串 / 变体2 Cookie 的返回码）
  2. /proxy 按 do 路由是否命中
  3. 净化预取失败的真实远端状态码（cd 轮是 Http0）
  4. 分段耗时「结果落地帧」到底花了多少 ms
  5. 起播成功率（对照 cd 轮 1/5）

用法:
    python _ce_judge.py <logcat_dump_file> [输出文件]
"""
import io
import re
import sys
import collections

DUMP = sys.argv[1] if len(sys.argv) > 1 else r"<工作区>\_ce_dump.txt"
OUT = sys.argv[2] if len(sys.argv) > 2 else r"<工作区>\_ce_judge_out.txt"

# logcat threadtime: 09-29 12:30:00.123  1234  1234 I System.out: msg
LINE = re.compile(r"^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+(\w)\s+(System\.out|L1Play|L1Diag)\s*:\s?(.*)$")

BUCKETS = [
    ("A_自转发", ["本地代理：自转发"]),
    ("B_proxy路由", ["本地代理：do="]),
    ("C_proxy其它", ["本地代理：/proxy"]),
    ("D_净化失败", ["净化", "预取失败"]),
    ("E_分段耗时", ["分段耗时："]),
    ("F_播放失败", ["播放失败："]),
    ("G_取链", ["播放心跳：取链"]),
    ("H_起播", ["播放心跳：起播"]),
    ("I_净化链", ["播放心跳：净化", "播放心跳：去BOM"]),
    ("J_兜底", ["播放心跳：兜底"]),
    ("K_出链", ["播放心跳：出链"]),
    ("L_图片体检", ["图片体检", "图片加载：", "图片队列：", "图片预取："]),
    ("M_图片发起", ["图片发起："]),
    ("N_帧耗时", ["帧耗时：", "站点栏", "结果落地"]),
    ("O_搜索", ["搜索轮次：", "搜索稳定：", "搜索停滞："]),
    ("P_崩溃", ["FATAL", "JarKillNeutraliz", "加固 jar"]),
]


def main():
    rows = []
    pids = collections.Counter()
    with io.open(DUMP, encoding="utf-8", errors="replace") as f:
        for raw in f:
            raw = raw.rstrip("\r\n")
            m = LINE.match(raw)
            if not m:
                continue
            t, pid, tid, lvl, tag, msg = m.groups()
            msg = msg.strip()
            pids[pid] += 1
            for name, keys in BUCKETS:
                if any(k in msg for k in keys):
                    rows.append((t, pid, name, msg))
                    break

    buf = collections.defaultdict(list)
    for t, pid, name, msg in rows:
        buf[name].append((t, pid, msg))

    # ---- 结论化计算 ----
    lines = []
    w = lines.append
    w("=" * 96)
    w("ce 版复测判定（dump=%s）" % DUMP)
    w("=" * 96)
    w("")

    # 1 自转发
    w("【1】/proxy 自转发兜底（A 类主修：jar 返回 null 时我们自己取）")
    ff = buf.get("A_自转发", [])
    if not ff:
        w("   ⚠️ 一条都没打 —— 说明这轮没走到「jar 返回 null」的分支（可能没测 A 类站点）")
    else:
        hit = collections.Counter()
        for t, pid, m in ff:
            if "变体1(查询串)=" in m:
                c1 = m.split("变体1(查询串)=")[1].split()[0].split("（")[0]
                c2 = ""
                if "变体2(Cookie)=" in m:
                    c2 = m.split("变体2(Cookie)=")[1].split()[0]
                hit[(c1, c2)] += 1
            elif "被拒" in m:
                hit[("REJECTED", "")] += 1
            elif "跳过" in m:
                hit[("SKIP", "")] += 1
            elif "异常" in m:
                hit[("EXC", "")] += 1
        for (c1, c2), n in hit.most_common():
            w("   ×%d  变体1(查询串)=%s  变体2(Cookie)=%s" % (n, c1, c2 or "-"))
        for t, pid, m in ff[:12]:
            w("   %s [%s] %s" % (t, pid, m[:190]))
        w("   ⇒ 判定：变体1/2 出现 2xx(200/206) ＝ 自转发**可行**；400/401/403/404/5xx ＝ 上游不认这份凭据")

    w("")
    w("【2】/proxy 按 do 路由（看 路由=do 还是 路由=recent）")
    rt = buf.get("B_proxy路由", [])
    if not rt:
        w("   ⚠️ 没打 —— /proxy 这轮没被调用")
    else:
        cnt = collections.Counter()
        for t, pid, m in rt:
            r = "do" if "路由=do" in m else ("recent" if "路由=recent" in m else "?")
            v = "?"
            if "判定=" in m:
                v = m.split("判定=")[1].split()[0]
            cnt[(r, v)] += 1
        for (r, v), n in cnt.most_common():
            w("   ×%d  路由=%s  判定=%s" % (n, r, v))
        for t, pid, m in rt[-10:]:
            w("   %s [%s] %s" % (t, pid, m[:190]))
        if all("路由=?" in x[2] or ("路由=" not in x[2]) for x in rt):
            w("   ⚠️ 日志里没有「路由=」字段 ⇒ 这轮装的不是 ce（看【附3】的装机校验）")
            w("   （cd 及更早版本打的是：本地代理：do=… key=… 持有者=… 判定=… 耗时=…ms）")

    w("")
    w("【3】净化预取失败的真归因（cd 轮全是 Http0 ⇒ 这轮要看真状态码）")
    pf = []
    for t, pid, name, m in rows:
        if "预取失败" in m or ("净化" in m and "远端地址" in m) or ("Http" in m and "净化" in m):
            pf.append((t, pid, m))
    if not pf:
        w("   ⚠️ 没打（这轮没出现净化失败，或失败走的是超时分支）")
    else:
        cnt = collections.Counter()
        for t, pid, m in pf:
            mm = re.search(r"Http(\d{3})", m)
            cnt[mm.group(0) if mm else "超时/无码"] += 1
        for k, n in cnt.most_common():
            w("   ×%d  %s" % (n, k))
        for t, pid, m in pf[:15]:
            w("   %s [%s] %s" % (t, pid, m[:200]))
        w("   ⇒ 403＝补 Referer/UA 有救；404＝地址失效；5xx＝源站；超时＝源站慢")

    w("")
    w("【4】分段耗时「结果落地帧」（阈值 25ms 才打。cd 轮一条没有）")
    sg = buf.get("E_分段耗时", [])
    if not sg:
        w("   ⚠️ 依然一条没触发 ⇒ 说明单帧插入耗时都 <25ms，240ms 的代价不在插入本身")
    else:
        vals = []
        for t, pid, m in sg:
            mm = re.search(r"=(\d+)ms", m)
            if mm:
                vals.append(int(mm.group(1)))
            w("   %s [%s] %s" % (t, pid, m[:170]))
        if vals:
            vals.sort()
            w("   ⇒ 样本 %d 条  中位=%dms  最大=%dms" % (len(vals), vals[len(vals) // 2], vals[-1]))

    w("")
    w("【5】起播成功率（对照 cd 轮 5 次成功 1）")
    fails = buf.get("F_播放失败", [])
    # 起播看门狗失败 = 明确失败；兜底试尽也是失败
    watch = [x for x in fails if "看门狗" in x[2]]
    exhaust = [x for x in fails if "试尽" in x[2] or "预算" in x[2]]
    chains = [x for x in rows if x[2] == "G_取链" and "拿到直链" in x[2]]
    start_ok = [x for x in rows if x[2] == "H_起播" and "已就绪" in x[2]]
    w("   取链拿到直链：%d 次" % len(chains))
    w("   起播看门狗失败：%d 次" % len(watch))
    w("   兜底试尽/预算耗尽：%d 次" % len(exhaust))
    w("   起播到出画面：%d 次" % len(start_ok))
    if chains:
        w("   ⇒ 粗算成功率 = 出画面 %d / 取链 %d = %.0f%%" % (
            len(start_ok), len(chains), 100.0 * len(start_ok) / max(1, len(chains))))
    w("")
    w("   失败原因逐条（最多 25 条）：")
    for t, pid, m in fails[:25]:
        w("   %s [%s] %s" % (t, pid, m[:200]))

    w("")
    w("【附】其它埋点计数")
    for name, _ in BUCKETS:
        w("   %-12s %d 行" % (name, len(buf.get(name, []))))

    w("")
    w("【附2】图片成功率 / 体检 / 帧统计原文（末 8 条）")
    pic = buf.get("L_图片体检", []) + buf.get("M_图片发起", []) + buf.get("N_帧耗时", [])
    for t, pid, m in pic[-8:]:
        w("   %s [%s] %s" % (t, pid, m[:200]))

    w("")
    w("【附3】版本指纹（确认这轮跑的确实是 ce/cf）")
    fp = {
        "自转发埋点（ce 新增）": any("自转发" in x[3] for x in rows),
        "路由=do/recent（ce 新增）": any("路由=" in x[3] and "本地代理" in x[3] for x in rows),
        "分段耗时：…=（ce 修正后才会出数）": any("分段耗时：" in x[3] for x in rows),
        "手绑账本=（cf 新增）": any("手绑账本=" in x[3] for x in rows),
        "拒:该jar从未手绑成功 / 拒:手绑后已换届（cf 新增）":
            any(("拒:该jar从未手绑成功" in x[3]) or ("拒:手绑后已换届" in x[3]) for x in rows),
        "recent(回退)（cf 新增标注）": any("recent(回退)" in x[3] for x in rows),
    }
    for k, v in fp.items():
        w("   %-46s %s" % (k, "✅ 有" if v else "❌ 无"))

    io.open(OUT, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("done ->", OUT)
    print("命中行数: %d  进程分布: %s" % (len(rows), dict(pids.most_common(5))))


if __name__ == "__main__":
    main()
