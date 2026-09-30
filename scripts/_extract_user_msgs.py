# -*- coding: utf-8 -*-
"""从会话 jsonl 抽出「用户原话」清单，用于反查「说过但没写成文档」的需求。
用法: python _extract_user_msgs.py <jsonl> <输出md>
规则:
  - 只取 type=message 且 role=user
  - 跳过 tool_result / system-reminder / cb_summary 等上下文注入
  - 去掉 <...> 标签块，只留人话
"""
import sys, json, re, datetime

src, dst = sys.argv[1], sys.argv[2]
TAG = re.compile(r'<(system-reminder|user_info|memory|additional_data|current_time|cb_summary|craft_mode|memory_and_skills_reminder)[\s\S]*?</\1>', re.I)
ANYTAG = re.compile(r'<[^>]{0,80}>')

rows = []
with open(src, 'r', encoding='utf-8', errors='replace') as f:
    for line in f:
        if '"role":"user"' not in line:
            continue
        try:
            d = json.loads(line)
        except Exception:
            continue
        if d.get('type') != 'message' or d.get('role') != 'user':
            continue
        parts = d.get('content')
        if isinstance(parts, str):
            txt = parts
        elif isinstance(parts, list):
            txt = '\n'.join(p.get('text', '') for p in parts
                            if isinstance(p, dict) and p.get('type') in ('input_text', 'text'))
        else:
            continue
        t = TAG.sub('', txt)
        t = ANYTAG.sub('', t).strip()
        # 跳过纯注入/空壳/工具回执
        if not t or t.startswith('<') or 'tool_use_id' in t:
            continue
        if len(t) < 2:
            continue
        ts = d.get('timestamp')
        when = datetime.datetime.fromtimestamp(ts / 1000).strftime('%m-%d %H:%M') if ts else '??'
        rows.append((when, t))

with open(dst, 'w', encoding='utf-8') as o:
    o.write('# 用户原话清单（从会话 jsonl 抽取）\n\n')
    o.write(f'共 **{len(rows)}** 条。用于反查「说过但没落地成文档」的需求。\n\n')
    cur = None
    for when, t in rows:
        day = when[:5]
        if day != cur:
            o.write(f'\n## {day}\n\n')
            cur = day
        one = ' '.join(t.split())
        if len(one) > 300:
            one = one[:300] + ' …'
        o.write(f'- `{when}` {one}\n')
print(f'抽出用户原话 {len(rows)} 条 -> {dst}')
