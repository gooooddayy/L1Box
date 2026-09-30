#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""远端抽查：关键文件逐个 GET contents，核对 git blob sha 与本地一致。"""
import json
import subprocess
import sys
import urllib.request
import urllib.error
from pathlib import Path

ROOT = Path(r"<工作区>")
REPO_DIR = ROOT / "_gh_upload_L1Box"
OWNER, REPO = "gooooddayy", "L1Box"
API = "https://api.github.com"

tok = (ROOT / ".gh_token.txt").read_text(encoding="utf-8", errors="replace").splitlines()[0].strip()
HDR = {"Authorization": "Bearer " + tok, "Accept": "application/vnd.github+json",
       "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "l1box-verify"}

SAMPLES = [
    "README.md",
    ".gitignore",
    "app/src/main/assets/fakeboot.dex",          # 防崩溃替身层（曾被 *.dex 静默忽略）
    "player/src/main/jniLibs/arm64-v8a/libijkffmpeg.so",  # 最大 12MB
    "app/src/main/assets/js/lib/模板.js",         # 中文路径
    "docs/00_总纲与索引/L1Box_知识总纲_20260914.md",
    "gradlew",
    "scripts/build_l1box.sh",
    "settings.gradle",
    "quickjs/src/main/jniLibs/arm64-v8a/libquickjs-android-wrapper.so",
]


def local_blob_sha(path):
    return subprocess.run(["git", "rev-parse", "HEAD:" + path], cwd=str(REPO_DIR),
                          capture_output=True).stdout.decode().strip()


def remote_meta(path):
    req = urllib.request.Request(
        f"{API}/repos/{OWNER}/{REPO}/contents/{urllib.request.quote(path)}?ref=main",
        headers=HDR)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            j = json.loads(r.read())
            return j.get("sha"), j.get("size")
    except urllib.error.HTTPError as e:
        return None, "HTTP %d" % e.code


def main():
    ref = urllib.request.urlopen(
        urllib.request.Request(f"{API}/repos/{OWNER}/{REPO}/git/ref/heads/main",
                               headers=HDR), timeout=30)
    head = json.loads(ref.read())["object"]["sha"]
    print("远端 main =", head)
    ok = bad = 0
    for p in SAMPLES:
        lsha = local_blob_sha(p)
        rsha, size = remote_meta(p)
        good = (rsha == lsha)
        ok += good
        bad += (not good)
        print("  %s  %s  (%s B)  %s" % ("✓" if good else "✗", p, size, "sha一致" if good else "sha不一致: %s vs %s" % (lsha[:10], str(rsha)[:10])))
    # 全量条目数核对
    req = urllib.request.Request(
        f"{API}/repos/{OWNER}/{REPO}/git/trees/7e616da2cea3edfc187f3579631814b16fd9625c",
        headers=HDR)
    with urllib.request.urlopen(req, timeout=60) as r:
        t = json.loads(r.read())
    print("远端 tree 条目数 =", len(t.get("tree", [])), "｜本地应为 798")
    print("抽查通过 %d / 失败 %d" % (ok, bad))


if __name__ == "__main__":
    main()
