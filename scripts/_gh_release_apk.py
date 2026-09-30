# -*- coding: utf-8 -*-
"""上传 cj APK 到 GitHub Release（新建 tag + release + asset）。

用法:
  python _gh_release_apk.py --dry      只打印计划
  python _gh_release_apk.py --apply    执行
"""
import json, sys, time, urllib.request, urllib.error, hashlib, os
from pathlib import Path

ROOT = Path(r"<工作区>")
APK = ROOT / "FreeBox-src" / "L1Box_v1.1.1_release_20260930cu.apk"
OWNER, REPO = "gooooddayy", "L1Box"
API = "https://api.github.com"
APPLY = "--apply" in sys.argv

TAG = "1.0.0"
REL_NAME = "L1Box_v1.1.1_release_20260930cu"
BODY = """竖屏手机版 **1.1.1**（versionCode 31）。

### 本版重点：搜索页左右两侧滑动体验修复

**左侧（站点栏）—— 修「快速滑动会断触」**

根因：左栏控件的「能否滚动」完全押在 `GestureDetector` 的返回值上。站点数不足时
（`needScroll=false`）事件根本不喂给 detector ⇒ detector 缺失「按下」基准 ⇒ `onScroll` 永不触发
⇒ 手势被整段吞掉。实测：56 次左栏手势中 **20 次零响应**（手指滑 350~684 px，内容一格未动，
抬起后也无惯性），而正常手势的跟手比值为 0.97~1.00。

修法：不再把「能否滚动」押在 detector 上 ——
1. **无条件喂 detector**（补齐按下基准，同时消除 `||` 短路）
2. **原始位移兜底**：detector 未接管/未处理时，按手指位移自行滚动

两项**只在 detector 未处理时生效** ⇒ 正常路径逐字不变。

**右侧（结果网格）—— 修「快滑很快就停、慢滑反而滑得远」**

根因：实测惯性阶段的**逐帧位移恒定、零衰减**
（例：`164,167,167,168,166,166,185,165,164,183`），而真正的 `fling` 必然指数衰减
⇒ 说明滚动实际由 `smoothScrollBy`（线性插值器）驱动，距离只取决于目标位置、与速度无关。
另有独立佐证：59 次手势的「系统 fling 速度」与「手指真实速度」逐条完全相等（速度从未被算小），
但 fling 速度与惯性时长**完全无相关** —— 物理上不可能，只能是「发起后被替换」。

修法：`onFling` 自己驱动惯性（真实时钟 + 指数衰减 `v(t) = v0 * e^(-t/tau)`），绕开 `smoothScrollBy`。
附带解决系统「动画程序时长缩放」对惯性时长的影响。实测惯性时长 **约 200 ms → 约 1500 ms**，
且距离与滑动速度成正比。

**撤销设备档位收敛**

上一版引入的 7 个设备档位方法全部移除，恢复改造前的固定值
（图片线程 8 / 并发 12 / 单图床 6 / 连接池 8 / 磁盘缓存 64 MB / 列表复用 6 / chip 每帧 8）。
同时去掉一条隐藏路径：系统内存紧张时会把档位**永久**降档 —— 本意是照顾低配机，
实际会让**所有机型**在内存紧张时受影响。

### 在此之前累积的改动（09-22 ~ 09-29）

- **播放链路**：`/proxy` 自转发兜底、按 `do` 路由选 jar、净化失败真归因（取真实状态码）
- **图片链路**：独立请求客户端（并发/UA/Referer）、可见范围 ±6 预取、失败原因分类统计
- **播放器**：错误码透传 + 同址同类去重、续播浮层
- **稳定性**：加固 jar 加载路径兜底（`targetSdk` 保持 28）

### 安装包

| 项目 | 值 |
|---|---|
| 文件 | `L1Box_v1.1.1_release_20260930cu.apk` |
| 体积 | 60,851,047 B |
| MD5 | `b5f957353a05c3dfa531dad774abf19` |

`minSdk 24` / `targetSdk 28` / `compileSdk 33`。
"""


def call(method, path, body=None, raw=None, ctype="application/json", timeout=600, tries=4):
    url = path if path.startswith("http") else API + path
    for k in range(tries):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        hdr = {
            "Authorization": "Bearer " + TOKEN,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "l1box-upload",
            "Content-Type": ctype,
        }
        req = urllib.request.Request(url, data=data, headers=hdr, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            rawr = e.read()[:400]
            if e.code in (200, 201, 422) and k == tries - 1:
                return e.code, {"_err": rawr.decode("utf-8", "replace")}
            if e.code >= 500 or e.code == 429:
                time.sleep(min(2 ** k, 20)); continue
            return e.code, {"_err": rawr.decode("utf-8", "replace")}
        except Exception as e:
            if k == tries - 1:
                return 0, {"_err": repr(e)}
            time.sleep(min(2 ** k, 20))
    return 0, {"_err": "retries exhausted"}


TOKEN = ""
tf = ROOT / ".gh_token.txt"
if tf.exists():
    TOKEN = tf.read_text(encoding="utf-8").strip().splitlines()[0].strip()

md5 = hashlib.md5(APK.read_bytes()).hexdigest()
size = APK.stat().st_size
print("APK   :", APK.name)
print("体积  :", size, "B")
print("MD5   :", md5)
print("tag   :", TAG, "｜ release:", REL_NAME)
if not APPLY:
    print("\n（干跑，未执行）")
    sys.exit(0)

# 1. 取 main 当前 sha
s, r = call("GET", f"/repos/{OWNER}/{REPO}/git/ref/heads/main")
if s != 200:
    print("取 main 失败", s, r); sys.exit(1)
main_sha = r["object"]["sha"]
print("main  =", main_sha[:12])

# 2. 建 tag ref（annotated tag 走 tag object；这里用轻量 tag 指向 commit）
s, r = call("POST", f"/repos/{OWNER}/{REPO}/git/refs",
            {"ref": "refs/tags/" + TAG, "sha": main_sha})
if s == 201:
    print("tag 创建:", TAG)
elif s == 422:
    print("tag 已存在，跳过")
else:
    print("tag 创建失败", s, r); sys.exit(1)

# 3. 建 release
s, r = call("POST", f"/repos/{OWNER}/{REPO}/releases",
            {"tag_name": TAG, "name": REL_NAME, "body": BODY,
             "draft": False, "prerelease": False, "target_commitish": "main"})
if s == 201:
    rel_id, upload_url = r["id"], r["upload_url"]
    print("release id =", rel_id)
elif s == 422:
    print("release 已存在，查询复用")
    s2, r2 = call("GET", f"/repos/{OWNER}/{REPO}/releases/tags/{TAG}")
    if s2 != 200:
        print("查询 release 失败", s2, r2); sys.exit(1)
    rel_id, upload_url = r2["id"], r2["upload_url"]
else:
    print("release 创建失败", s, r); sys.exit(1)

# 4. 上传 asset
data = APK.read_bytes()
up = upload_url.split("{")[0] + "?name=" + urllib.parse.quote(APK.name)
s, r = call("POST", up, raw=data, ctype="application/vnd.android.package-archive")
if s == 201:
    print("asset 上传成功:", r["name"], r["size"], "B  id=", r["id"])
    print("下载地址:", r["browser_download_url"])
else:
    print("asset 上传失败", s, r); sys.exit(1)

# 5. 复核
s, r = call("GET", f"/repos/{OWNER}/{REPO}/releases/{rel_id}")
if s == 200:
    print("\n=== 复核 ===")
    print("release:", r["name"], "｜tag:", r["tag_name"], "｜draft:", r["draft"])
    for a in r["assets"]:
        print("  asset:", a["name"], a["size"], "B")
print("RELEASE_OK")
