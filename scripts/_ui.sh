#!/usr/bin/env bash
# _ui.sh — 抓 UI 树并列出可点击节点（辅助真机测试用）
export MSYS_NO_PATHCONV=1
ADB=<本机>/AppData/Local/Android/Sdk/platform-tools/adb.exe
WS="<工作区>"
# ⚠️ 先删远端旧文件：dump 失败时会 pull 回上一次的旧树 ⇒ 界面看着"没变化"（真踩过）
"$ADB" shell rm -f /sdcard/u.xml
"$ADB" shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
rm -f "$WS/_u.xml"
"$ADB" pull /sdcard/u.xml "$WS/_u.xml" >/dev/null 2>&1
if [ ! -f "$WS/_u.xml" ]; then echo "  !! uiautomator dump 失败（这屏可能是 SurfaceView/播放中）⇒ 请用截图判断"; exit 0; fi
"$ADB" shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | sed 's/^ */  前台: /'
python - <<'PY'
import re, io
s = io.open(r"<工作区>/_u.xml", encoding="utf-8", errors="replace").read()
print("== 当前窗口 ==", (re.search(r'package="([^"]+)"', s) or [None,"?"])[1] if False else "")
n = 0
for m in re.finditer(r'<node[^>]*>', s):
    tag = m.group(0)
    clickable = 'clickable="true"' in tag
    if not clickable:
        continue
    txt = (re.search(r'text="([^"]*)"', tag) or [None, ""])[1]
    desc = (re.search(r'content-desc="([^"]*)"', tag) or [None, ""])[1]
    rid = (re.search(r'resource-id="([^"]*)"', tag) or [None, ""])[1]
    b = (re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag))
    if not b:
        continue
    x = (int(b.group(1)) + int(b.group(3))) // 2
    y = (int(b.group(2)) + int(b.group(4))) // 2
    if not (txt or desc or rid):
        continue
    n += 1
    print(f"  [{n:2d}] ({x:4d},{y:4d})  text={txt[:26]!r}  desc={desc[:26]!r}  id={rid.split('/')[-1][:30]}")
if n == 0:
    print("  （没有可点击节点）")
PY
