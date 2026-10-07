#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 FreeBox-src 的源码模块同步进 _gh_upload_L1Box（推 GitHub 的 staging 仓库）。

设计要点：
- **只同步源码模块**，不碰 staging 自己的 README/.gitignore/scripts/tools/docs
- **排除规则与 .gitignore 一致**（构建产物、缓存、签名、本机配置）
- **--delete 语义**：源里删掉的文件，目标也删（保证镜像一致），但在排除名单内的一律不动
- 先 dry-run 报告，加 --apply 才真写
"""
import os
import shutil
import sys

WS = r"C:\Users\Administrator\WorkBuddy\2026-08-19-14-43-05"
SRC = os.path.join(WS, "FreeBox-src")
DST = os.path.join(WS, "_gh_upload_L1Box")

# 要同步的源码模块（这两个仓库共有的顶层）
MODULES = ["app", "player", "TabLayout", "ViewPager1Delegate", "quickjs", "crash", "gradle"]

# 要同步的单文件
FILES = ["build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat", "LICENSE"]

# 排除的目录名（任意层级）
EXCLUDE_DIRS = {"build", ".gradle", ".idea", "output", "release"}
# 排除的扩展名
EXCLUDE_EXT = {".apk", ".idsig", ".jks", ".keystore", ".iml", ".bak", ".orig", ".tmp", ".ap_", ".aab", ".dex"}
# 排除的文件名
EXCLUDE_NAMES = {"local.properties", "proguardMapping.txt"}
# .dex 例外（运行时必需）
ALLOW_DEX = {"fakeboot.dex"}

APPLY = "--apply" in sys.argv

added, updated, removed, skipped = [], [], [], []
srcdir_count = dstdir_count = 0


def excluded(name, is_dir):
    if is_dir:
        return name in EXCLUDE_DIRS
    if name in EXCLUDE_NAMES:
        return True
    ext = os.path.splitext(name)[1].lower()
    if ext in EXCLUDE_EXT:
        return name in ALLOW_DEX  # dex 例外放行，其余排除
    return False


def copy_file(rel):
    global srcdir_count
    s = os.path.join(SRC, rel)
    d = os.path.join(DST, rel)
    srcdir_count += 1
    if not os.path.exists(d):
        added.append(rel)
    else:
        # 内容相同则跳过
        try:
            if os.path.getsize(s) == os.path.getsize(d):
                with open(s, "rb") as f1, open(d, "rb") as f2:
                    if f1.read() == f2.read():
                        skipped.append(rel)
                        return
        except Exception:
            pass
        updated.append(rel)
    if APPLY:
        os.makedirs(os.path.dirname(d), exist_ok=True)
        shutil.copy2(s, d)


def walk_sync(rel_dir=""):
    abs_src = os.path.join(SRC, rel_dir) if rel_dir else SRC
    if not os.path.isdir(abs_src):
        return
    for name in sorted(os.listdir(abs_src)):
        rel = os.path.join(rel_dir, name) if rel_dir else name
        p = os.path.join(abs_src, name)
        if os.path.isdir(p):
            if excluded(name, True):
                continue
            walk_sync(rel)
        else:
            if excluded(name, False):
                continue
            copy_file(rel)


def find_orphans(rel_dir=""):
    """目标里存在、源里不存在（或已被排除）的文件 → 应删"""
    global dstdir_count
    abs_dst = os.path.join(DST, rel_dir) if rel_dir else DST
    if not os.path.isdir(abs_dst):
        return
    for name in sorted(os.listdir(abs_dst)):
        rel = os.path.join(rel_dir, name) if rel_dir else name
        p = os.path.join(abs_dst, name)
        if os.path.isdir(p):
            if name == ".git":
                continue
            find_orphans(rel)
            continue
        dstdir_count += 1
        if excluded(name, False):
            continue
        s = os.path.join(SRC, rel)
        if not os.path.exists(s):
            removed.append(rel)
            if APPLY:
                os.remove(p)


print("=== 同步 %s -> %s ===" % (SRC, DST))
print("模式:", "APPLY（真写）" if APPLY else "DRY-RUN（只看）")
print()

for m in MODULES:
    walk_sync(m)
for f in FILES:
    p = os.path.join(SRC, f)
    if os.path.isfile(p):
        copy_file(f)

# 孤儿清理：只在同步过的模块范围内
for m in MODULES:
    find_orphans(m)

print("新增 %d 个" % len(added))
for x in added[:40]:
    print("   + " + x)
if len(added) > 40:
    print("   ... 其余 %d 个" % (len(added) - 40))
print()
print("更新 %d 个" % len(updated))
for x in updated[:40]:
    print("   M " + x)
if len(updated) > 40:
    print("   ... 其余 %d 个" % (len(updated) - 40))
print()
print("删除 %d 个（源里已无）" % len(removed))
for x in removed[:20]:
    print("   - " + x)
if len(removed) > 20:
    print("   ... 其余 %d 个" % (len(removed) - 20))
print()
print("跳过（内容相同）%d 个" % len(skipped))
print()
print("合计：源文件 %d，目标文件 %d" % (srcdir_count, dstdir_count))
