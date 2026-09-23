# -*- coding: utf-8 -*-
"""校验本批新增的字符串/符号是否真的进了 APK 的 dex。"""
import zipfile
import sys
import os

apk = sys.argv[1] if len(sys.argv) > 1 else None
if not apk or not os.path.exists(apk):
    print("用法: python _dexcheck.py <apk路径>")
    sys.exit(1)

needles = [
    b"L1LoadControl",
    b"onMemoryPressure",
    "缓冲升档 @ ".encode("utf-8"),
    # 2026-09-18 批一新增
    "接管：档位=".encode("utf-8"),
    "未接管：".encode("utf-8"),
    "未升档 @ ".encode("utf-8"),
    "起播放行 @ ".encode("utf-8"),
    "需要 ".encode("utf-8"),
    "堆上限=".encode("utf-8"),
    "内核=".encode("utf-8"),
    b"RESERVE_BYTES",
    b"shouldStartPlayback",
    b"deepBytes",
]

with zipfile.ZipFile(apk) as z:
    dexs = [n for n in z.namelist() if n.endswith(".dex")]
    print("APK:", apk)
    print("dex 数量:", len(dexs))
    blobs = {}
    for n in dexs:
        blobs[n] = z.read(n)

for nd in needles:
    hit = [n for n, b in blobs.items() if nd in b]
    label = nd.decode("utf-8", "replace")
    if hit:
        print("  OK   %-22s  出现在 %s" % (label, ",".join(hit)))
    else:
        print("  !!   %-22s  未找到" % label)
