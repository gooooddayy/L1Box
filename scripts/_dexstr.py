#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 dex 里提取与搜索线相关的字符串，用于跨版本对照。
不做完整 dex 解析，只按 0x00 切分 UTF-8 片段后过滤关键词——
对我们关心的这几条中文串足够可靠。"""
import glob
import sys

KEYS = sys.argv[2].split(",") if len(sys.argv) > 2 else [
    "搜索轮次", "等待次数", "站点池定论", "可搜站点", "自动重搜", "jar装载序号",
]


def decode(b):
    try:
        return b.decode("utf-8")
    except UnicodeDecodeError:
        return None


def main(root):
    for v in ["bi", "bj", "bk", "bl", "bm", "bn"]:
        files = sorted(glob.glob(f"{root}/{v}/classes*.dex"))
        found = set()
        for f in files:
            data = open(f, "rb").read()
            for chunk in data.split(b"\x00"):
                if len(chunk) < 3 or len(chunk) > 400:
                    continue
                s = decode(chunk)
                if not s:
                    continue
                if any(k in s for k in KEYS):
                    found.add(s.strip())
        print(f"########## {v} ({len(files)} dex) ##########")
        for s in sorted(found):
            print(f"   {s}")
        print()


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
