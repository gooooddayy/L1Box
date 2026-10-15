# -*- coding: utf-8 -*-
"""站内 SEO 设置：写 Description + Topics（不含 TVBox 商标词；含空壳声明以明确不含内容）。

用法: python _gh_seo_apply.py           # 干跑
      python _gh_seo_apply.py --apply   # 执行
"""
import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(r"<工作区>")
OWNER, REPO = "gooooddayy", "L1Box"
APPLY = "--apply" in sys.argv

tok = (ROOT / ".gh_token.txt").read_text(encoding="utf-8").strip().splitlines()[0]
HDR = {
    "Authorization": "Bearer " + tok,
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "l1box-seo",
    "Content-Type": "application/json",
}

# 文案：① 靠近影视用户（影视 / 追剧）② 明确"空壳·自行配置"（法律上最清晰）
#       ③ 不含任何 TVBox 商标词 ④ 不含破解/ VIP /免费看一类的侵权暗示词
DESC = "安卓手机影视播放器（空壳 · 自行配置）· 竖屏追剧客户端 · Kotlin 开源 · 不含任何内容源 · AGPL-3.0"

# Topics：全部取自同品类热门仓库的高频安全词（GitHub topics 仅支持小写字母/数字/连字符，不能用中文）
# 刻意不加 tv / android-tv —— 本项目是**手机竖屏**版，加 TV 词会引来期待遥控器大屏界面的用户（不匹配）
TOPICS = [
    "android",
    "kotlin",
    "java",
    "player",
    "video-player",
    "media-player",
    "video",
    "mobile",
    "apk",
    "exoplayer",
    "ijkplayer",
    "android-app",
    "hls",
    "streaming",
    "multimedia",
    "portrait",
    "framework",
    "open-source",
    "agplv3",
]


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request("https://api.github.com" + path, data=data, headers=HDR, method=method)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read() or b"{}")


cur = call("GET", f"/repos/{OWNER}/{REPO}")
print("当前 description :", cur.get("description"))
print("当前 topics      :", cur.get("topics"))
print()
print("拟改为 description:", DESC)
print("拟改为 topics     :", TOPICS, f"（{len(TOPICS)} 个，上限 20）")
if not APPLY:
    print("\n（干跑，未执行）")
    sys.exit(0)

r = call("PATCH", f"/repos/{OWNER}/{REPO}", {"description": DESC})
print("\n描述写入 :", r.get("description"))
# ⚠️ topics 放在 PATCH /repos 里会被**静默忽略**（实测 topics 仍为空），必须走专用端点
t = call("PUT", f"/repos/{OWNER}/{REPO}/topics", {"names": TOPICS})
print("topics   :", t.get("names"))
ok = r.get("description") == DESC and set(t.get("names") or []) == set(TOPICS)
print("SEO_APPLY_OK" if ok else "SEO_APPLY_MISMATCH")
