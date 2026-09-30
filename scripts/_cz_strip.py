# -*- coding: utf-8 -*-
"""
cz 收尾：把 ck~ct 的埋点 dex 锚点从「必须存在」移到「必须不存在」。

为什么：`L1_TRACE` 是 `const false`、`l1Trace` 是 `inline` ⇒ Kotlin **编译期彻底消除**
所有观测代码 ⇒ 这些中文字符串不会出现在 dex 里。
所以把它们改判为「必须不存在」，反而是更强的断言（证明埋点确实没进包）。
"""
import io
import re

# ── 1. dex 脚本：GOOD → GONE ────────────────────────────────────────────────
p = "_br_dexsym.py"
s = io.open(p, encoding="utf-8").read()
pat = re.compile(r'^\s*\("(ck|cm|cn|co|cp|cq|cs|ct)",\s*"(.*?)",\s*"(.*?)"\),\s*$')

kept, moved = [], []
for ln in s.split("\n"):
    m = pat.match(ln)
    if m:
        moved.append(m.groups())
    else:
        kept.append(ln)
assert len(moved) >= 15, "只找到 %d 条埋点锚点，少于预期（15+）" % len(moved)
s = "\n".join(kept)

i = s.find("GONE = [")
assert i > 0, "找不到 GONE 列表"
j = s.find("\n", i)
block = ["    # ---- cz（2026-09-30 收尾）：以下埋点锚点改为「必须不存在」----",
         "    # L1_TRACE=false + inline l1Trace ⇒ 编译期消除观测代码 ⇒ 这些字符串不进 dex。",
         "    # 出现即说明开关没关掉、或埋点代码被意外恢复。"]
for a, b, c in moved:
    block.append('    ("%s", "%s", "%s｜cz 已关闭该埋点"),' % (a, b, c))
s = s[:j + 1] + "\n".join(block) + "\n" + s[j + 1:]
io.open(p, "w", encoding="utf-8", newline="").write(s)
print("dex：已把 %d 条埋点锚点移到 GONE" % len(moved))
for a, b, _ in moved:
    print("   %-4s %s" % (a, b))

# ── 2. 闸门：库内那条（源码已删）改判 mustnot ────────────────────────────────
p2 = "check_l1box_regressions.sh"
t = io.open(p2, encoding="utf-8").read()
old = """must    "$TL" '★左栏开始拦截' "必须记录左栏开始拦截的瞬间（＝系统向子View发 CANCEL 的时刻）\""""
new = """mustnot "$TL" '★左栏开始拦截' "cz 收尾：该埋点已撤（左侧问题确认解决），源码与 dex 都不得再有\""""
assert old in t, "闸门里找不到那条库内断言"
t = t.replace(old, new, 1)
io.open(p2, "w", encoding="utf-8", newline="").write(t)
print("\n闸门：库内那条已改为 mustnot")
