#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""探针：验证 token 能走 api.github.com 写仓库，并读取远端现状。
token 只在内存中出现，绝不打印。"""
import json
import sys
import urllib.request
import urllib.error
from pathlib import Path

ROOT = Path(r"<工作区>")
OWNER, REPO = "gooooddayy", "L1Box"
API = "https://api.github.com"

tok = (ROOT / ".gh_token.txt").read_text(encoding="utf-8", errors="replace").splitlines()
tok = tok[0].strip() if tok else ""
if not tok.startswith(("github_pat_", "ghp_")):
    print("TOKEN_MISSING_OR_BAD")
    sys.exit(2)

HDR = {
    "Authorization": "Bearer " + tok,
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "l1box-upload",
}


def call(method, path, body=None, raw=False, timeout=30):
    url = path if path.startswith("http") else API + path
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        HDR["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=HDR, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, (r.read() if raw else json.loads(r.read() or b"{}"))
    except urllib.error.HTTPError as e:
        return e.code, (e.read()[:400] if raw else {"_err": e.read()[:400].decode("utf-8", "replace")})
    except Exception as e:
        return 0, {"_err": repr(e)}


def main():
    s, me = call("GET", "/user")
    print("GET /user                ->", s, me.get("login") if isinstance(me, dict) else "")
    s, rp = call("GET", f"/repos/{OWNER}/{REPO}")
    if s != 200:
        print("GET /repos               ->", s, rp)
        return
    print("GET /repos               ->", s, "private=", rp.get("private"),
          "default_branch=", rp.get("default_branch"), "size=", rp.get("size"), "KB")
    print("  permissions           =", rp.get("permissions"))
    s, ref = call("GET", f"/repos/{OWNER}/{REPO}/git/ref/heads/{rp.get('default_branch')}")
    print("GET ref                  ->", s, ref.get("object", {}).get("sha") if s == 200 else ref)
    if s == 200:
        sha = ref["object"]["sha"]
        s2, cm = call("GET", f"/repos/{OWNER}/{REPO}/git/commits/{sha}")
        print("GET commit               ->", s2, "parents=",
              [p["sha"][:8] for p in cm.get("parents", [])] if s2 == 200 else cm)
        print("  tree sha               =", cm.get("tree", {}).get("sha", "")[:12] if s2 == 200 else "")
    # api 速率
    s3, lim = call("GET", "/rate_limit")
    if s3 == 200:
        core = lim["resources"]["core"]
        print("rate_limit core          ->", core["remaining"], "/", core["limit"])


if __name__ == "__main__":
    main()
