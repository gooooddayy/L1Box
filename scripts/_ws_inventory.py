# -*- coding: utf-8 -*-
"""本地物料体积盘点 + GitHub 仓库完整性检查（判断能否只靠仓库继续开发）。"""
import os
from pathlib import Path

WS = Path(r"<工作区>")
SKIP = {".git"}


def du(p):
    total = 0
    n = 0
    for root, dirs, files in os.walk(p):
        dirs[:] = [d for d in dirs if d not in SKIP]
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
                n += 1
            except OSError:
                pass
    return total, n


def mb(b):
    return b / 1024 / 1024


print("=== 工作区顶层各项体积 ===")
grand = 0
for e in sorted(WS.iterdir(), key=lambda x: x.name):
    if e.name in SKIP:
        continue
    try:
        s, n = du(e) if e.is_dir() else (e.stat().st_size, 1)
    except OSError:
        continue
    grand += s
    kind = "目录" if e.is_dir() else "文件"
    print(f"  {e.name:<30} {mb(s):>9.1f} MB  ({n:>6} 文件) [{kind}]")
print(f"  {'【本地合计】':<30} {mb(grand):>9.1f} MB")

kit = WS / "_gh_upload_L1Box"
s, n = du(kit)
print(f"\n=== GitHub 仓库（_gh_upload_L1Box）合计 {mb(s):.1f} MB / {n} 文件 ===")
for sub in ["app", "player", "quickjs", "crash", "TabLayout", "ViewPager1Delegate",
            "gradle", "keystore", "scripts", "tools", "docs"]:
    p = kit / sub
    if p.exists():
        ss, nn = du(p)
        print(f"  {sub:<22} {mb(ss):>8.1f} MB ({nn} 文件)")
    else:
        print(f"  {sub:<22} 缺失")

print("\n=== 换机后需要自备（仓库里没有的）===")
for name, p in [
    ("JDK 11", WS / "dl" / "jdk11"),
    ("Gradle 缓存", WS / ".guserhome2"),
    ("Android SDK", Path(os.path.expanduser("~")) / "AppData/Local/Android/Sdk"),
]:
    if p.exists():
        ss, nn = du(p)
        print(f"  {name:<14} 本地 {mb(ss):>8.1f} MB  → 仓库中【不含】")
    else:
        print(f"  {name:<14} 未找到")

print("\n=== 构建入口是否在仓库里 ===")
for rel in ["gradlew", "gradlew.bat", "scripts/build_l1box.sh", "scripts/sign_l1box.sh",
            "scripts/check_l1box_regressions.sh", "keystore", "README.md",
            "docs/00_总纲与索引/项目交接总纲_20260930.md",
            "docs/00_总纲与索引/文件目录_全量说明_20260930.md"]:
    print(f"  {'OK ' if (kit / rel).exists() else '!! '} {rel}")
