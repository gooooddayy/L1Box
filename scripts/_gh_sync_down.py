# -*- coding: utf-8 -*-
"""把 GitHub 远端内容同步下来：逐文件对撞（远端 blob sha vs 本地 git hash-object）。

- 只读远端（公开仓库，无需 token）
- --apply 时把"仅远端有"或"内容不同"的文件写到本地工作区
"""
import json, subprocess, sys, urllib.request, base64, os
from pathlib import Path

ROOT = Path(r"<工作区>")
REPO_DIR = ROOT / "_gh_upload_L1Box"
OWNER, REPO = "gooooddayy", "L1Box"
APPLY = "--apply" in sys.argv
HDR = {"Accept": "application/vnd.github+json", "User-Agent": "l1box-sync-down"}


def get(path):
    req = urllib.request.Request("https://api.github.com" + path, headers=HDR)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read() or b"{}")


def local_sha(path, data):
    """本地 blob sha（与 git 一致，注意 CRLF 已在 .gitattributes 里处理过则按实际字节）"""
    p = subprocess.run(["git", "-C", str(REPO_DIR), "hash-object", "--stdin"],
                       input=data, capture_output=True)
    return p.stdout.decode().strip()


ref = get(f"/repos/{OWNER}/{REPO}/git/ref/heads/main")
sha = ref["object"]["sha"]
cm = get(f"/repos/{OWNER}/{REPO}/git/commits/{sha}")
tree_sha = cm["tree"]["sha"]
print("远端 commit:", sha)
print("远端 tree  :", tree_sha)

tree = get(f"/repos/{OWNER}/{REPO}/git/trees/{tree_sha}?recursive=1")
remote = {}
for e in tree.get("tree", []):
    if e["type"] == "blob":
        remote[e["path"]] = e["sha"]

# 本地文件清单
out = subprocess.run(["git", "-C", str(REPO_DIR), "ls-files", "-s"], capture_output=True)
local = {}
for line in out.stdout.decode().splitlines():
    meta, path = line.split("\t", 1)
    _mode, lsha, _stage = meta.split()
    local[path] = lsha

print(f"\n远端文件 {len(remote)} 个 ｜ 本地文件 {len(local)} 个")

only_remote = sorted(set(remote) - set(local))
only_local = sorted(set(local) - set(remote))
diff = sorted(p for p in set(remote) & set(local) if remote[p] != local[p])

print(f"\n仅远端有 : {len(only_remote)}")
for p in only_remote[:30]:
    print("   +", p)
print(f"\n仅本地有 : {len(only_local)}")
for p in only_local[:30]:
    print("   -", p)
print(f"\n内容不同 : {len(diff)}")
for p in diff:
    print("   ~", p)

if not APPLY:
    print("\n（只读对撞，未写入）")
    sys.exit(0)

targets = only_remote + diff
if not targets:
    print("\n无需同步：本地与远端逐字节一致")
    sys.exit(0)

print(f"\n===== 同步 {len(targets)} 个文件到本地 =====")
for p in targets:
    blob = get(f"/repos/{OWNER}/{REPO}/git/blobs/{remote[p]}")
    content = base64.b64decode(blob["content"])
    f = REPO_DIR / p
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_bytes(content)
    print(f"   写入 {p}  ({len(content)} B)")

# 复核
print("\n===== 复核 =====")
out = subprocess.run(["git", "-C", str(REPO_DIR), "ls-files", "-s"], capture_output=True)
local = {}
for line in out.stdout.decode().splitlines():
    meta, path = line.split("\t", 1)
    _mode, lsha, _stage = meta.split()
    local[path] = lsha
still = [p for p in set(remote) & set(local) if remote[p] != local[p] and p not in targets]
print("仍未一致（未同步的）:", still if still else "无")
print("SYNC_DOWN_DONE")
