# -*- coding: utf-8 -*-
"""敏感信息扫描：待上传内容里是否含密钥/口令/token"""
import os, io, re

ROOT = r"<工作区>"
TARGETS = [os.path.join(ROOT, "FreeBox-src")]
SKIP_DIRS = {"build", ".gradle", ".git", ".idea", "__pycache__"}
PATTERNS = [
    (r"KS_B64", "工作流内嵌密钥base64"),
    (r"storePassword", "签名库口令"),
    (r"keyPassword", "密钥口令"),
    (r"BEGIN (RSA |EC )?PRIVATE KEY", "PEM私钥"),
    (r"(?i)\b(password|passwd|pwd)\s*[=:]\s*['\"][^'\"]{3,}", "明文口令"),
    (r"(?i)\b(api[_-]?key|secret[_-]?key|access[_-]?token|auth[_-]?token)\s*[=:]\s*['\"][^'\"]{8,}", "APIKEY/Token"),
    (r"gh[pousr]_[A-Za-z0-9]{30,}", "GitHub Token"),
    (r"sk-[A-Za-z0-9]{20,}", "通用API密钥"),
    (r"AKIA[0-9A-Z]{16}", "AWS密钥"),
]
TEXT_EXT = {".java", ".kt", ".xml", ".gradle", ".properties", ".yml", ".yaml",
            ".json", ".js", ".md", ".sh", ".py", ".pro", ".txt", ".cfg", ".ini"}

hits = []
for base in TARGETS:
    for dp, dns, fns in os.walk(base):
        dns[:] = [d for d in dns if d not in SKIP_DIRS]
        for f in fns:
            ext = os.path.splitext(f)[1].lower()
            if ext not in TEXT_EXT:
                continue
            p = os.path.join(dp, f)
            if os.path.getsize(p) > 3 * 1024 * 1024:
                continue
            try:
                txt = io.open(p, "r", encoding="utf-8", errors="ignore").read()
            except Exception:
                continue
            for pat, label in PATTERNS:
                for m in re.finditer(pat, txt):
                    line_no = txt.count("\n", 0, m.start()) + 1
                    # 取该行摘要
                    ls = txt.rfind("\n", 0, m.start()) + 1
                    le = txt.find("\n", m.start())
                    line = txt[ls:le if le > 0 else len(txt)].strip()
                    hits.append((os.path.relpath(p, ROOT), line_no, label, line[:120]))

print("敏感信息扫描结果（共 %d 条）" % len(hits))
print("=" * 100)
for f, ln, label, line in hits:
    print("[%s] %s:%d" % (label, f, ln))
    print("      %s" % line)
