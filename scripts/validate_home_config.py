#!/usr/bin/env python3
# validate_home_config.py — 离线校验 L1Box 内置首页配置，防止"首页一直加载"类 bug 复发
#
# 当初的致命问题（已修复，但配置改错会复发）：
#   1. 顶层 `spider` 字段指向 404 死链 → 首页 spider jar 永远下不来 → 首页取不到数据一直转圈。
#      （现在代码已不依赖该字段，但若重新填了死链仍会浪费一次失败下载，故标记为 WARNING）
#   2. type=3 的源必须有 `ext`（spider 初始化数据 URL），否则 spider 无数据可取。
#   3. 首页源（如豆瓣）必须存在于 sites 中且 key/api/ext 齐全，否则 getSource 查不到 → NPE 转圈。
#
# 用法：
#   python validate_home_config.py [path/to/home_config.json]
#   默认路径：FreeBox-src/app/src/main/assets/home_config.json
# 退出码：发现 ERROR 级问题返回 1，否则 0（可在 CI / 构建前调用）。

import json
import os
import sys

DEFAULT_PATH = "FreeBox-src/app/src/main/assets/home_config.json"


def load_json(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        print(f"ERROR   文件不存在: {path}")
        return None
    except json.JSONDecodeError as e:
        print(f"ERROR   JSON 解析失败: {e}")
        return None


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PATH
    print(f">>> 校验: {path}")
    data = load_json(path)
    if data is None:
        return 1

    errors = []
    warnings = []

    # ---- 顶层 spider 字段（历史死链重灾区）----
    if "spider" in data:
        sp = data["spider"]
        warnings.append(
            f"顶层 `spider` 字段存在（当前代码已不依赖它，但若为死链会浪费一次失败下载）: {sp[:80]}..."
        )

    # ---- 解析 sites / source 列表 ----
    sites = data.get("sites") or data.get("source") or []
    if not isinstance(sites, list):
        errors.append("`sites`/`source` 不是数组")
    # 2026-09-05 起：首页内容跟随订阅站点（不再内置豆瓣/广告源），空 sites 是**预期正常状态**，
    # 不再作为 ERROR。空源时首页显示"添加订阅源"引导，不依赖任何内置站点。
    if not sites:
        print("  (提示) sites 为空——首页内容由订阅站点提供，空源显示\"添加订阅源\"引导（正常）")

    home_keys = set()
    for i, site in enumerate(sites):
        if not isinstance(site, dict):
            errors.append(f"sites[{i}] 不是对象")
            continue
        key = site.get("key", "")
        stype = str(site.get("type", "")).strip()
        api = site.get("api", "")
        ext = site.get("ext", "")
        name = site.get("name", key) or f"#{i}"
        if not key:
            errors.append(f"sites[{i}]（{name}）缺少 key")
            continue
        home_keys.add(key)
        # type=3 必须带 ext（spider 初始化数据）
        if stype == "3":
            if not ext:
                errors.append(f"源「{name}」type=3 但缺少 ext（spider 初始化数据），会导致取数为空")
            elif not (ext.startswith("http://") or ext.startswith("https://")):
                warnings.append(f"源「{name}」ext 不是 http(s) URL: {ext[:60]}")
        if not api:
            warnings.append(f"源「{name}」缺少 api（type=3 需要 spider 类名，如 csp_NewDouBanGuard）")

    # ---- 首页源必须存在（getSource 按 key 命中，缺失即 NPE 转圈）----
    # 2026-09-05 起：不再强制要求内置首页源。空 sites 时首页走订阅站点，无内置源是正常的。
    if sites:
        if "Douban" not in home_keys and "douban" not in (k.lower() for k in home_keys):
            # 允许其他名字，但必须至少有一个 type=3 源作为首页展示源
            has_type3 = any(str(s.get("type", "")).strip() == "3" for s in sites if isinstance(s, dict))
            if not has_type3:
                errors.append("未找到任何 type=3 首页展示源（首页将无数据）")
            else:
                print("  (提示) 首页源 key 非 'Douban'，确认 HomeFragment 的 homeSourceBean.key 与之匹配")

    # ---- 汇总 ----
    print("-" * 60)
    for w in warnings:
        print(f"WARNING {w}")
    for e in errors:
        print(f"ERROR   {e}")

    if errors:
        print(f"\n结果: 失败（{len(errors)} 个 ERROR，{len(warnings)} 个 WARNING）")
        return 1
    if warnings:
        print(f"\n结果: 通过（含 {len(warnings)} 个 WARNING，不阻塞）")
    else:
        print("\n结果: 通过（无问题）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
