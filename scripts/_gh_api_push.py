#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""L1Box → GitHub 整包上传（走 api.github.com 的 Git Data API）

为什么不用 git push：本机到 github.com:443 持续被 reset，64MB 单次传输必断。
本方案把上传拆成 798 个独立的小请求，每个请求自带宽限重试，
已成功的 blob 记在 _gh_api_blobs.json 里，重跑直接跳过 —— 天然断点续传。

用法:
  python _gh_api_blobs_probe.py            # 干跑：只列清单，不联网
  python _gh_api_pushapi.py                # 上传 blobs（可反复重跑）
  python _gh_api_pushapi.py --commit       # blobs 齐了后建 tree/commit/ref
"""
import base64
import datetime
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(r"<工作区>")
REPO_DIR = ROOT / "_gh_upload_L1Box"
CACHE = ROOT / "_gh_api_blobs.json"
OWNER, REPO = "gooooddayy", "L1Box"
API = "https://api.github.com"
WORKERS = 6
MAX_TRY = 5

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
    "Content-Type": "application/json",
}


def iso8601(txt):
    """git 的 '1790145668 +0800' -> ISO 8601（GitHub API 只认后者）"""
    ts, _, tz = txt.strip().partition(" ")
    try:
        off = datetime.timedelta(hours=int(tz[:3]), minutes=int(tz[0] + tz[3:5]))
        return datetime.datetime.fromtimestamp(
            int(ts), datetime.timezone(off)).isoformat()
    except Exception:
        return txt.strip()


def call(method, path, body=None, timeout=180):
    url = path if path.startswith("http") else API + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers=HDR, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()[:300]
        return e.code, {"_err": raw.decode("utf-8", "replace")}
    except Exception as e:
        return 0, {"_err": repr(e)}


def git(*args, binary=False):
    r = subprocess.run(["git"] + list(args), cwd=str(REPO_DIR),
                       capture_output=True)
    if r.returncode:
        raise SystemExit("git 失败: %s\n%s" % (args, r.stderr.decode("utf-8", "replace")))
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def list_files():
    """[(path, mode, sha)]，path 为 utf-8 字符串。--batch 读内容，避免 autocrlf 差异。"""
    out = git("ls-files", "-s", "-z", binary=True)
    items = []
    for rec in out.split(b"\x00"):
        if not rec:
            continue
        meta, path = rec.split(b"\t", 1)
        mode, sha, _stage = meta.split()
        items.append((path.decode("utf-8"), mode.decode(), sha.decode()))
    return items


def read_blobs(shas):
    """一次 cat-file --batch 批量取内容 -> {sha: bytes}"""
    p = subprocess.Popen(["git", "cat-file", "--batch"], cwd=str(REPO_DIR),
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    inp = ("\n".join(shas) + "\n").encode()
    out, _ = p.communicate(inp)
    got, i = {}, 0
    while i < len(out):
        nl = out.index(b"\n", i)
        header = out[i:nl].split()
        if len(header) < 3:
            break
        sha, _typ, size = header[0].decode(), header[1], int(header[2])
        body = out[nl + 1: nl + 1 + size]
        got[sha] = body
        i = nl + 1 + size + 1
    return got


def upload_blob(path, content, tries=MAX_TRY):
    b64 = base64.b64encode(content).decode()
    last = ""
    for k in range(tries):
        s, r = call("POST", f"/repos/{OWNER}/{REPO}/git/blobs",
                    {"content": b64, "encoding": "base64"})
        if s == 201:
            return r["sha"], ""
        last = "HTTP %s %s" % (s, str(r)[:160])
        time.sleep(min(2 ** k, 20))
    return None, last


def do_blobs(files, blobs):
    # 🔴 2026-09-30 修正：原实现只判「路径是否已在缓存里」⇒ **内容改了也不重传**，
    #    commit 建的 tree 仍指向旧 blob ⇒ 远端文档陈旧（实测：README 新增的一行没上去）。
    #    正解＝同时比对 **git blob sha（内容哈希）**；旧缓存里缺 git_sha 的条目会被判为"需重传"。
    todo = [(p, m, s) for (p, m, s) in files
            if p not in blobs or blobs[p].get("git_sha") != s]
    print("待上传 %d 个文件（已缓存 %d）" % (len(todo), len(blobs)))
    if not todo:
        return True, blobs
    contents = read_blobs(sorted({s for (_p, _m, s) in todo}))
    done = fail = 0
    lock_json = ROOT / "_gh_api_blobs.json"
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        futs = {}
        for p, m, s in todo:
            futs[ex.submit(upload_blob, p, contents[s])] = (p, m, s)
        for f in as_completed(futs):
            p, m, git_sha = futs[f]
            sha, err = f.result()
            if sha:
                blobs[p] = {"sha": sha, "mode": m, "git_sha": git_sha}
                done += 1
            else:
                fail += 1
                print("  ✗ %s  %s" % (p, err))
            if (done + fail) % 25 == 0:
                lock_json.write_text(json.dumps(blobs, ensure_ascii=False, indent=0),
                                     encoding="utf-8")
                print("  进度 %d/%d  成功 %d 失败 %d" % (done + fail, len(todo), done, fail))
    lock_json.write_text(json.dumps(blobs, ensure_ascii=False, indent=0), encoding="utf-8")
    print("blobs 完成：成功 %d，失败 %d，缓存共 %d" % (done, fail, len(blobs)))
    return fail == 0, blobs


def do_commit(files, blobs):
    missing = [p for (p, _m, _s) in files if p not in blobs]
    if missing:
        print("还有 %d 个文件没有 blob，先补上传（例：%s）" % (len(missing), missing[:3]))
        return False
    head = git("cat-file", "commit", "HEAD")
    meta = {}
    for line in head.splitlines():
        if line.startswith(("author ", "committer ")):
            k, v = line.split(" ", 1)
            meta[k] = v
        if line.strip() == "":
            break
    msg = head.split("\n\n", 1)[1]
    # 远端当前 head 作为 parent（快进）
    s, ref = call("GET", f"/repos/{OWNER}/{REPO}/git/ref/heads/main")
    if s != 200:
        print("取 ref 失败", s, ref)
        return False
    remote_sha = ref["object"]["sha"]
    print("远端 main =", remote_sha[:12], "｜本地 commit =", git("rev-parse", "HEAD").strip()[:12])

    tree = [{"path": p, "mode": blobs[p]["mode"], "type": "blob", "sha": blobs[p]["sha"]}
            for (p, _m, _s) in sorted(files)]
    # GitHub 对超大 tree 请求会直接超时（实测 798 条一次性提交返回 422 timed out）
    # ⇒ 分批增量：每批 80 条，带上一批的 sha 作 base_tree 累积。每批失败单独重试。
    tree_sha = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--tree=")), None)
    batch = 80
    if tree_sha:
        print("复用已有 tree =", tree_sha)
    for i in range(0, len(tree), batch) if not tree_sha else ():
        chunk = tree[i:i + batch]
        body = {"tree": chunk}
        if tree_sha:
            body["base_tree"] = tree_sha
        for k in range(MAX_TRY):
            s, r = call("POST", f"/repos/{OWNER}/{REPO}/git/trees", body)
            if s == 201:
                tree_sha = r["sha"]
                break
            time.sleep(min(2 ** k, 15))
        else:
            print("建 tree 失败（第 %d 批）" % (i // batch + 1), s, r)
            return False
        print("  tree 批次 %d/%d  累计 %d 条  sha=%s"
              % (i // batch + 1, (len(tree) + batch - 1) // batch,
                 min(i + batch, len(tree)), tree_sha[:10]))
    print("tree =", tree_sha)

    body = {"message": msg, "tree": tree_sha, "parents": [remote_sha]}
    for k in ("author", "committer"):
        if k in meta:
            name_email, _, date = meta[k].rpartition("> ")
            name, _, email = name_email.partition(" <")
            body[k] = {"name": name, "email": email.rstrip(">"), "date": iso8601(date)}
    s, r = call("POST", f"/repos/{OWNER}/{REPO}/git/commits", body)
    if s != 201:
        print("建 commit 失败", s, r)
        return False
    new_sha = r["sha"]
    print("commit =", new_sha)

    # 对齐复刻场景：parent 是更早的 e16a88f 而当前远端 head 可能是上一轮的孤儿 commit
    # ⇒ 非快进，需要 force 覆盖（旧 commit 内容相同，仅 message 字节差异，覆盖无损失）。
    need_force = body["parents"][0] != remote_sha
    s, r = call("PATCH", f"/repos/{OWNER}/{REPO}/git/refs/heads/main",
                {"sha": new_sha, "force": need_force})
    if s != 200:
        print("更新 ref 失败", s, r)
        return False
    print("REF_OK  main ->", r["object"]["sha"])
    return True


def main():
    files = list_files()
    blobs = json.loads(CACHE.read_text(encoding="utf-8")) if CACHE.exists() else {}
    blobs = {k: v for k, v in blobs.items() if k in {p for (p, _m, _s) in files}}
    print("本地待推文件 %d 个" % len(files))
    if "--commit" in sys.argv:
        ok = do_commit(files, blobs)
    else:
        ok, _ = do_blobs(files, blobs)
    print("DONE_OK" if ok else "DONE_FAIL")


if __name__ == "__main__":
    main()
