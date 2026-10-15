# -*- coding: utf-8 -*-
"""关键词调研：抓 GitHub 同类热门仓库的 description / topics，统计高频词，作为 SEO 用词依据。"""
import collections
import json
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(r"<工作区>")
tok = (ROOT / ".gh_token.txt").read_text(encoding="utf-8").strip().splitlines()[0]
HDR = {
    "Authorization": "Bearer " + tok,
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "l1box-seo",
}


def search(q, per=30):
    url = ("https://api.github.com/search/repositories?q=" + urllib.parse.quote(q)
           + f"&sort=stars&order=desc&per_page={per}")
    req = urllib.request.Request(url, headers=HDR)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


QUERIES = [
    "topic:video-player",
    "topic:media-player",
    "topic:player android",
    "android video player in:name,description",
    "播放器 in:name,description",
    "影视 in:name,description",
    "电视盒子 in:name,description",
    "tvbox in:name,description",
]

topic_counter = collections.Counter()
for q in QUERIES:
    try:
        d = search(q)
    except Exception as e:
        print("搜索失败:", q, e)
        continue
    items = d.get("items", [])
    print(f"\n=== {q}   （命中 {d.get('total_count')} 个，取前 {len(items)}）===")
    for it in items[:12]:
        desc = (it.get("description") or "").replace("\n", " ")[:78]
        print(f"  {it['stargazers_count']:>6}★  {it['full_name']:<42} | {desc}")
        for t in it.get("topics", []):
            topic_counter[t] += 1

print("\n\n=== 高频 topics（跨查询累计，前 45）===")
for t, c in topic_counter.most_common(45):
    print(f"  {c:>3}  {t}")
