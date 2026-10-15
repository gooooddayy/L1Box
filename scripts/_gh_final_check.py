# -*- coding: utf-8 -*-
"""GitHub 侧最终复核：仓库状态 / Release 唯一性 / tag 唯一性 / tree 对撞 / asset sha256 对撞。"""
import hashlib, json, urllib.request
from pathlib import Path

ROOT = Path(r"<工作区>")
OWNER, REPO = "gooooddayy", "L1Box"
tok = (ROOT / ".gh_token.txt").read_text(encoding="utf-8").strip().splitlines()[0]
HDR = {
    "Authorization": "Bearer " + tok,
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "l1box-final-check",
}


def get(p):
    req = urllib.request.Request("https://api.github.com" + p, headers=HDR)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read() or b"{}")


r = get(f"/repos/{OWNER}/{REPO}")
print("仓库        :", r["full_name"], "｜ private =", r["private"], "｜ default =", r["default_branch"])

ref = get(f"/repos/{OWNER}/{REPO}/git/ref/heads/main")
cm = get(f"/repos/{OWNER}/{REPO}/git/commits/{ref['object']['sha']}")
print("main commit :", ref["object"]["sha"][:12])
print("远端 tree   :", cm["tree"]["sha"])

rels = get(f"/repos/{OWNER}/{REPO}/releases")
print("Release 数量:", len(rels))
for x in rels:
    print("   • tag =", x["tag_name"], "｜ name =", x["name"], "｜ draft =", x["draft"], "｜ pre =", x["prerelease"])
    for a in x["assets"]:
        print("     asset:", a["name"], a["size"], "B ｜ digest =", a.get("digest"))

tags = get(f"/repos/{OWNER}/{REPO}/tags")
print("Tag 列表    :", [t["name"] for t in tags])

# 本地对账包：直接取**远端 asset 同名文件**（写死路径会过期——本脚本曾拿旧包对账报出假不一致）
_asset_name = next((a["name"] for x in rels for a in x["assets"]), None)
local = (ROOT / "FreeBox-src" / _asset_name) if _asset_name else None
if local is not None and local.exists():
    print("本地 sha256 :", "sha256:" + hashlib.sha256(local.read_bytes()).hexdigest())
else:
    print("本地 sha256 : （FreeBox-src 下未找到同名包:", _asset_name, "）")

import subprocess
lt = subprocess.run(["git", "-C", str(ROOT / "_gh_upload_L1Box"), "rev-parse", "HEAD^{tree}"],
                    capture_output=True, text=True).stdout.strip()
print("本地 tree   :", lt)
print("TREE_MATCH  :", lt == cm["tree"]["sha"])
