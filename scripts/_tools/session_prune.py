import json, os, re, gzip, hashlib, shutil, time

PROJ = r"<本机>\.workbuddy\projects\<工作区目录名>"   # 形如 c-Users-<用户名>-WorkBuddy-<工作区时间戳>
SRC = os.path.join(PROJ, "541a152a-4c04-49ea-8612-0fcc730b2300.jsonl")
WORK = r"<工作区>"
TMP = os.path.join(WORK, "_session_pruned.jsonl")
BAK = os.path.join(WORK, "_session_backup.jsonl.gz")

MARK = "\n…〔已裁剪，原文 %d 字符〕"
DEDUP = "〔此处原有 %d 字符的对话摘要，与最后一次注入的内容重复，已去重〕"
KEEP_LAST_SUMMARY = 2

BUDGET = {
    ("function_call_result", "providerData.toolResult.content"): 500,
    ("function_call_result", "output.text"): 500,
    ("function_call_result", "output[].text"): 500,
    ("function_call_result", "providerData.toolResult.renderer.value"): 200,
    ("function_call_result", "providerData.toolResult.rawResponse"): 300,
    ("function_call", "arguments"): 800,
    ("function_call", "providerData.reasoning"): 300,
    ("function_call", "providerData.argumentsDisplayText"): 250,
    ("reasoning", "rawContent[].text"): 400,
    ("reasoning", "content[].text"): 400,
}
DEFAULT_LIMIT = 600
NO_LIMIT_TYPES = {"message"}
NO_LIMIT_PATHS = {"message"}

log = []
st = {"lines": 0, "fail": 0, "cut_str": 0, "cut_map": 0, "dedup": 0, "dedup_bytes": 0}


def trunc(s, limit):
    if len(s) <= limit:
        return s
    st["cut_str"] += 1
    return s[:limit] + (MARK % len(s))


def walk(node, t, path):
    if isinstance(node, dict):
        if t == "file-history-snapshot" and path.endswith("trackedFileBackups") and len(node) > 3:
            st["cut_map"] += 1
            return {k: walk(node[k], t, path + "." + k) for k in list(node.keys())[:3]}
        return {k: walk(v, t, (path + "." + k) if path else k) for k, v in node.items()}
    if isinstance(node, list):
        if len(node) > 40 and "todos" in path:
            node = node[:40]
        return [walk(v, t, path + "[]") for v in node]
    if isinstance(node, str):
        if t in NO_LIMIT_TYPES and path.startswith("content"):
            return node
        if path in NO_LIMIT_PATHS:
            return node
        lim = BUDGET.get((t, path), DEFAULT_LIMIT)
        if node[:1] in "{[":
            try:
                inner = json.loads(node)
            except Exception:
                return trunc(node, lim)
            return json.dumps(walk(inner, t, path + "#json"), ensure_ascii=False)
        return trunc(node, lim)
    return node


# ---------- 1) 定位重复摘要，保留最后 2 份 ----------
summary_lines = []
with open(SRC, "r", encoding="utf-8", errors="replace") as f:
    for i, line in enumerate(f, 1):
        if "<cb_summary>" not in line:
            continue
        try:
            o = json.loads(line)
        except Exception:
            continue
        if o.get("type") != "message":
            continue
        for b in o.get("content", []):
            if isinstance(b, dict) and b.get("type") == "input_text" and "<cb_summary>" in (b.get("text") or ""):
                summary_lines.append(i)
                break
keep = set(summary_lines[-KEEP_LAST_SUMMARY:])
log.append("1) 摘要注入 %d 处，保留最后 %d 处" % (len(summary_lines), len(keep)))

# ---------- 2) 流式变换（二进制读数，精确记录 EOF 偏移） ----------
ids, parents = set(), []
off = 0
with open(SRC, "rb") as fi, open(TMP, "wb") as fo:
    for i, raw in enumerate(fi, 1):
        off += len(raw)
        st["lines"] += 1
        line = raw.decode("utf-8", "replace")
        try:
            o = json.loads(line)
        except Exception:
            st["fail"] += 1
            fo.write(raw)
            continue
        t = o.get("type", "?")
        if t == "message" and "<cb_summary>" in line and i not in keep:
            for b in o.get("content", []):
                if isinstance(b, dict) and b.get("type") == "input_text" and "<cb_summary>" in (b.get("text") or ""):
                    txt = b["text"]
                    new = re.sub(r"<cb_summary>.*?</cb_summary>", DEDUP % len(txt), txt, flags=re.S)
                    if new == txt:
                        new = re.sub(r"<cb_summary>.*", DEDUP % len(txt), txt, flags=re.S)
                    st["dedup"] += 1
                    st["dedup_bytes"] += len(txt) - len(new)
                    b["text"] = new
        o = walk(o, t, "")
        if o.get("id"):
            ids.add(o["id"])
        if o.get("parentId"):
            parents.append(o["parentId"])
        fo.write((json.dumps(o, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8"))

tmp_size = os.path.getsize(TMP)
bad = [p for p in parents if p not in ids]
log.append("2) 变换完成：%d 行 / %.1f MB，解析失败 %d 行，parentId 断链 %d 条"
           % (st["lines"], tmp_size / 1048576, st["fail"], len(bad)))
if bad or st["fail"]:
    log.append("   ❌ 校验未通过，中止，未改动原文件")
    with open(os.path.join(WORK, "_apply_report.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(log))
    raise SystemExit(1)

# ---------- 3) 压缩备份原文件 ----------
t0 = time.time()
with open(SRC, "rb") as fi, gzip.open(BAK, "wb", compresslevel=6) as fo:
    shutil.copyfileobj(fi, fo, 4 << 20)
log.append("3) 原文件已压缩备份：%s（%.1f MB，耗时 %.0fs）"
           % (BAK, os.path.getsize(BAK) / 1048576, time.time() - t0))

# ---------- 4) 就地覆盖（保留 delta：备份期间新追加的事件） ----------
with open(TMP, "rb") as f:
    newdata = f.read()
with open(SRC, "rb") as f:
    f.seek(off)
    delta = f.read()
log.append("4) 备份窗口内新追加的事件：%.1f KB（将原样接回）" % (len(delta) / 1024))

old_size = os.path.getsize(SRC)
with open(SRC, "r+b") as f:
    f.seek(0)
    f.write(newdata)
    if delta:
        f.write(delta)
    f.truncate(len(newdata) + len(delta))
    f.flush()
    os.fsync(f.fileno())

# ---------- 5) 落地校验 ----------
new_size = os.path.getsize(SRC)
lines = 0
badjson = 0
last_ok = None
with open(SRC, "r", encoding="utf-8", errors="replace") as f:
    for line in f:
        lines += 1
        try:
            o = json.loads(line)
            last_ok = o.get("type")
        except Exception:
            badjson += 1
log.append("5) 覆盖完成：%.1f MB -> %.1f MB，共 %d 行，解析失败 %d 行，末行类型=%s"
           % (old_size / 1048576, new_size / 1048576, lines, badjson, last_ok))
log.append("   截断字符串 %d 处；收口文件表 %d 处；摘要去重 %d 处（省 %.2f MB）"
           % (st["cut_str"], st["cut_map"], st["dedup"], st["dedup_bytes"] / 1048576))

with open(os.path.join(WORK, "_apply_report.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(log))
print("ok")
