# -*- coding: utf-8 -*-
"""
批一验证脚本：埋点补强 + 升档守门修复 的效果核对。

用法：
    python _l1_batch1_stats.py <logcat 文件> [文件2 ...]

它只读日志，不联网、不改任何东西。核对五件事：
  1. 缓冲控制器有没有接管（以及档位/门槛/水位/堆大小）
  2. 起播放行那一刻的真实缓冲量 —— 用来判断「1500ms 门槛」在 HLS 分片粒度下到底起不起作用
  3. 升档成功率（升档次数 vs 未升档次数 + 未升档原因）
  4. 起播耗时分布 + 走的是哪个内核（门槛只对 Exo 生效）
  5. 逐会话明细：净化耗时 / 起播耗时 / 起播缓冲 / 升档位置 —— 判定「起播长尾到底卡在哪」

日志 tag：L1Buffer（缓冲区观测）、L1Play（播放链路埋点）

注意：logcat 行格式是 `I/L1Buffer(31862): 消息` —— tag 后面跟线程号括号而不是冒号，
所以取消息必须按 "):" 切，不能按 "TAG:" 切（踩过这个坑，会把所有行都判成空）。
"""
import io
import sys
import os

TAGS = ("L1Buffer", "L1Play")


def ms_of(line):
    """logcat 前缀 MM-DD HH:MM:SS.mmm 取时间戳(毫秒)"""
    try:
        return _to_ms(line[6:18])
    except Exception:
        return None


def _to_ms(hms):
    h, m, rest = hms.split(":")
    s, mm = rest.split(".")
    return ((int(h) * 60 + int(m)) * 60 + int(s)) * 1000 + int(mm)


def body_of(line, tag):
    """取 logcat 消息正文。tag 后是 `(线程号):`，所以按 '):' 切"""
    i = line.find(tag)
    if i < 0:
        return ""
    j = line.find("):", i)
    if j < 0:
        return ""
    return line[j + 2:].strip()


def pct(sorted_list, q):
    if not sorted_list:
        return 0
    idx = min(len(sorted_list) - 1, int(len(sorted_list) * q))
    return sorted_list[idx]


def num_after(s, key, end):
    """从字符串里取 key 与 end 之间的数字，取不到返回 None。

    end 允许为空串（= key 后面直接跟数字、没有结束符，如 `ms=1467`）——
    注意不能用 split("") 取，Python 会抛 ValueError: empty separator。
    """
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
    paths = sys.argv[1:] or ["_bi_test.log"]

    took_over = []
    not_took = []
    start_ok = []         # (缓冲量ms, 门槛ms, 原文)
    escalate = []
    hold = []
    ready = []
    fails = []
    sessions = []         # 逐会话明细
    cur = None

    for path in paths:
        if not os.path.exists(path):
            print("找不到日志文件:", path)
            continue
        with io.open(path, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                if "L1Buffer" in line:
                    b = body_of(line, "L1Buffer")
                    if not b:
                        continue
                    t = ms_of(line)
                    if b.startswith("接管："):
                        took_over.append(b)
                    elif b.startswith("未接管："):
                        not_took.append(b)
                    elif "起播放行 @" in b:
                        start_ok.append((num_after(b, "缓冲=", "ms"), num_after(b, "门槛=", "ms"), b))
                        if cur is not None:
                            cur["起播缓冲"] = num_after(b, "缓冲=", "ms")
                    elif "缓冲升档 @" in b:
                        escalate.append(b)
                        if cur is not None:
                            cur["升档位置"] = b.split("@", 1)[1].split("：", 1)[0].strip()
                    elif "未升档 @" in b:
                        hold.append(b)
                        if cur is not None:
                            cur["升档位置"] = "未升档"
                elif "L1Play" in line:
                    b = body_of(line, "L1Play")
                    if not b:
                        continue
                    if b.startswith("播放心跳："):
                        b = b[len("播放心跳："):]
                    if b.startswith("取链 换集"):
                        cur = {"边界": b}
                        sessions.append(cur)
                    elif b.startswith("取链 开始") and cur is not None:
                        cur["站点"] = b.split("站点=")[-1].strip()
                        # 换集行里带线路名：`取链 换集 FF有广#0，额度重置`
                        try:
                            seg = cur["边界"].split("换集", 1)[1].split("#", 1)[0].strip()
                            cur["线路"] = seg
                        except Exception:
                            pass
                    elif b.startswith("净化") and "ms=" in b and cur is not None:
                        cur.setdefault("净化", []).append(num_after(b, "ms=", ""))
                    elif b.startswith("起播 已就绪"):
                        d = num_after(b, "耗时 ", "ms")
                        k = b.split("内核=")[-1].strip() if "内核=" in b else "?"
                        ready.append((d, k))
                        if cur is not None:
                            cur["起播"] = d
                            cur["内核"] = k
                    elif b.startswith("播放失败"):
                        fails.append(b)

    print("=" * 78)
    print("文件:", " + ".join(paths))
    print("=" * 78)

    # ---- 1. 是否接管 ----
    print("\n【1】缓冲控制器接管情况")
    print("    接管行 %d 条，未接管行 %d 条" % (len(took_over), len(not_took)))
    uniq = {}
    for s in took_over:
        uniq[s] = uniq.get(s, 0) + 1
    for s, n in uniq.items():
        print("      x%-3d %s" % (n, s))
    for s in not_took[:3]:
        print("      未接管样例: %s" % s)
    if not took_over and not not_took:
        print("      !! 一条都没有 —— 说明本次日志里根本没走 Exo（检查内核），或者日志被配额丢了")

    # ---- 2. 起播放行缓冲量 ----
    print("\n【2】起播放行时的真实缓冲量（门槛是否真起作用，看这里）")
    nums = sorted(x[0] for x in start_ok if x[0] is not None)
    if nums:
        print("    样本 %d 次  中位 %dms  P90 %dms  最小 %dms  最大 %dms" % (
            len(nums), nums[len(nums) // 2], pct(nums, 0.9), nums[0], nums[-1]))
        ths = {}
        for _, th, _s in start_ok:
            ths[th] = ths.get(th, 0) + 1
        print("    门槛分布：", ths)
        over = [n for n in nums if n >= 3000]
        print("    缓冲 ≥3000ms 的占比：%d/%d（高＝缓冲是「整片跃升」，门槛数值影响很小）" % (len(over), len(nums)))
        print("    样例：")
        for _, _, s in start_ok[:6]:
            print("      ", s)
    else:
        print("    !! 没有「起播放行」记录 —— 检查是否 override 了 shouldStartPlayback（未接管时不会有）")

    # ---- 3. 升档成功率 ----
    print("\n【3】升档成功率（本批修复的核心）")
    tot = len(escalate) + len(hold)
    print("    升档 %d 次，未升档 %d 次" % (len(escalate), len(hold)))
    if tot:
        print("    升档占比 %.0f%%（修复前实测 4/6=67%%，且失败时无日志）" % (100.0 * len(escalate) / tot))
    if escalate:
        print("    升档记录：")
        for s in escalate[:10]:
            print("      ", s)
    if hold:
        print("    未升档记录（含原因，本批新增）：")
        for s in hold[:10]:
            print("      ", s)

    # ---- 4. 起播耗时 + 内核 ----
    print("\n【4】起播耗时与内核")
    if ready:
        durs = sorted(d for d, _ in ready if d is not None)
        kerns = {}
        for _, k in ready:
            kerns[k] = kerns.get(k, 0) + 1
        if durs:
            print("    样本 %d 次  中位 %dms  P90 %dms  最大 %dms" % (
                len(durs), durs[len(durs) // 2], pct(durs, 0.9), durs[-1]))
        print("    内核分布：", kerns)
    else:
        print("    没有「起播 已就绪」记录")
    if fails:
        print("    播放失败 %d 次：" % len(fails))
        for s in fails[:5]:
            print("      ", s)

    # ---- 5. 逐会话明细 ----
    print("\n【5】逐会话明细（净化 vs 起播 vs 缓冲 vs 升档）")
    if sessions:
        print("    %-4s %-14s %-9s %-9s %-10s %-10s %s" % (
            "序", "站点/线路", "净化", "起播", "起播缓冲", "升档位置", "内核"))
        for i, s in enumerate(sessions, 1):
            pur = s.get("净化") or []
            pur_txt = "/".join("%sms" % p for p in pur) if pur else "-"
            st = "%sms" % s["起播"] if s.get("起播") is not None else "未观测"
            buf = "%sms" % s["起播缓冲"] if s.get("起播缓冲") is not None else "-"
            esc = s.get("升档位置", "-")
            site = "%s/%s" % (s.get("站点", "?"), s.get("线路", "?"))
            print("    #%-3d %-14s %-9s %-9s %-10s %-10s %s" % (
                i, site[:14], pur_txt[:9], st[:9], buf[:10], esc[:10], s.get("内核", "-")))

        # 净化占起播比例：判定起播长尾是不是净化造成的
        pairs = [(s["净化"][-1], s["起播"]) for s in sessions
                 if s.get("净化") and s.get("起播") is not None]
        if pairs:
            print("\n    净化占起播耗时的比例（判定起播长尾卡在哪）：")
            for p, d in pairs:
                print("      净化 %5dms / 起播 %5dms = %3.0f%%" % (p, d, 100.0 * p / d if d else 0))
    else:
        print("    （没找到会话边界「取链 换集」，无法归集）")

    print("\n" + "=" * 78)


if __name__ == "__main__":
    main()
