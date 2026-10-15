# -*- coding: utf-8 -*-
"""APK 差异对照：回答「两版之间功能有没有被改动、改动落在哪里」。

用法:
  python _apk_diff.py <旧版.apk> <新版.apk> [--out 报告.md]

三层证据:
  ① 文件级   —— 新增 / 删除 / 内容变化的 entry（assets、资源、dex 全都在内）
  ② 类符号级 —— dex 里出现 / 消失的类描述符
  ③ 字符串级 —— 真解析 dex string_ids 拿到字符串池，做差集，
                并按「合规清理预期清单」自动分类为：预期内 / 需人工确认
"""
import re, struct, sys, zipfile
from pathlib import Path

WS = Path(r"<工作区>")
CLS = re.compile(rb"L(?:[A-Za-z0-9_$]{1,64}/)+[A-Za-z0-9_$]{1,64};")

# 公开前合规清理「预期会消失」的特征词（命中即判为预期内）
EXPECT_KW = [
    # 一、本次移除的第三方对接
    "assrt", "iqiyi", "suggest.video", "ali_api", "ali.js", "aliyundrive",
    "alicloud", "云盘", "网盘", "防盗链", "vipparse", "parseflags", "vip_parse",
    # 二、字幕站爬取用到的页面选择器 / 字段（随字幕源一并移除）
    "filelist", "btn_download", "onthefly", "pagelinkcard", "sublist_box_title",
    "download/%s", "no_redir", "searchword", "regexshooterfileonclick",
    # 三、被删方法自身的符号（getSuggest 及其匿名内部类）
    "getsuggest", "showsuggestdialog", "subtitleloader", "subtitleviewmodel",
    'item["name"].asstring', "response.body()!!.string()", "onclick",
    # 四、Kotlin 编译期行号映射表（SMAP，非功能代码，仅因行号位移而变化）
    "smap",
    # 五、评分链路（cw 2026-10-07 整体移除：弹窗/适配器/第三方评分接口/文案）
    "douban", "rating", "wmdb", "评分",
    # 七、联想链路（cx 2026-10-07 整体移除：请求方法/弹窗类/字段/布局/回调）
    "suggest", "uggestions", "onselectlistener",
    # 六、重构噪音（非功能，改代码必然产生）：合成 lambda 编号重排、
    #     被精简 bean 的字段残留、被删类的构造函数签名
    "lambda", "episode", "img", "year=", "setlist", "binding", "$bean", "$text",
    "landroid/content/context", "ljava",
    # 八、db（2026-10-09 换源失败清池）：被替换掉的旧空源文案；
    #     以及 Kotlin/R8 编译期生成的合成名（同一份代码重编，L 串长度就会变一位）
    "订阅没有加载出可用站点", "vllllll", "vlllllll",
    # 九、db 改名（2026-10-09）：对外版本号 1.1.1 → 1.0.0，唯一差异就是这个版本串
    "1.1.1",
    # 十、dh（2026-10-12）：互斥 key 由物理文件名改为 jarKey ⇒ 旧日志文案里那句被替换掉
    #     （`延后重试 文件=` → `延后重试 jar=`）；另附 R8 合成名（代码位移后 L/Z 串长度变化）
    "延后重试", "llllll",
    # 十一、dj（2026-10-12）：并发下载改为"挂等待共享结果" ⇒ 旧的延后重试常量被一并清掉（死代码）
    "JAR_RETRY_DELAY_MS",
]
# 类符号消失的预期特征
EXPECT_CLS_KW = ["getSuggest", "SubtitleViewModel", "assrt", "ali", "douban",
                 "ExternalSyntheticLambda", "suggest", "uggestions"]


def entries(p):
    with zipfile.ZipFile(p) as z:
        return {i.filename: (i.CRC, i.file_size) for i in z.infolist()}


def _dex_blobs(p):
    with zipfile.ZipFile(p) as z:
        return [z.read(n) for n in z.namelist() if n.endswith(".dex")]


def class_syms(p):
    s = set()
    for d in _dex_blobs(p):
        for m in CLS.findall(d):
            s.add(m.decode("ascii", "replace"))
    return s


def dex_strings(p):
    """真正解析 dex string_ids 表，返回字符串集合（比正则可靠）。"""
    out = set()
    for d in _dex_blobs(p):
        if d[:3] != b"dex":
            continue
        try:
            cnt, off = struct.unpack_from("<II", d, 56)
        except struct.error:
            continue
        for i in range(cnt):
            try:
                so = struct.unpack_from("<I", d, off + i * 4)[0]
                q = so
                while d[q] & 0x80:          # 跳过 uleb128 的 utf16 长度
                    q += 1
                q += 1
                end = d.index(b"\x00", q)
                s = d[q:end].decode("utf-8")   # 严格解码：MUTF-8 surrogate 残片会被丢弃
                if any(ord(c) < 0x20 for c in s):  # 丢控制字符串
                    continue
                out.add(s)
            except Exception:
                continue
    return out


def hit(s, kws):
    low = s.lower()
    return any(k.lower() in low for k in kws)


def fmt(n):
    return f"{n/1024:.1f} KB"


def dump(L, title, items, limit=None):
    L.append(f"### {title}\n")
    if not items:
        L.append("> 无\n")
        return
    for s in (items[:limit] if limit else items):
        L.append(f"- `{s}`")
    if limit and len(items) > limit:
        L.append(f"- …其余 {len(items)-limit} 条见同目录原始输出")
    L.append("")


def main():
    old, new = Path(sys.argv[1]), Path(sys.argv[2])
    out = WS / "_apk_diff_report.md"
    if "--out" in sys.argv:
        out = Path(sys.argv[sys.argv.index("--out") + 1])

    ea, eb = entries(old), entries(new)
    only_old = sorted(set(ea) - set(eb))
    only_new = sorted(set(eb) - set(ea))
    changed = sorted(n for n in set(ea) & set(eb) if ea[n][0] != eb[n][0])
    same = [n for n in set(ea) & set(eb) if ea[n][0] == eb[n][0]]

    ca, cb = class_syms(old), class_syms(new)
    gone_cls, new_cls = sorted(ca - cb), sorted(cb - ca)

    sa, sb = dex_strings(old), dex_strings(new)
    gone_s, new_s = sorted(sa - sb), sorted(sb - sa)
    gone_ok = [s for s in gone_s if hit(s, EXPECT_KW)]
    gone_ask = [s for s in gone_s if not hit(s, EXPECT_KW)]
    cls_ok = [c for c in gone_cls if hit(c, EXPECT_CLS_KW)]
    cls_ask = [c for c in gone_cls if not hit(c, EXPECT_CLS_KW)]

    total = len(ea) | len(eb)
    L = []
    L.append(f"# APK 差异对照：{old.name} → {new.name}\n")
    L.append(f"- 旧版：`{old.name}` / {old.stat().st_size:,} B")
    L.append(f"- 新版：`{new.name}` / {new.stat().st_size:,} B")
    L.append(f"- 体积变化：**{new.stat().st_size - old.stat().st_size:+,} B**\n")

    L.append("## 结论速览\n")
    L.append(f"- 包内文件 **{total}** 个 → **逐字节未变动 {len(same)} 个（{len(same)/total*100:.1f}%）**")
    L.append(f"- 文件被删 {len(only_old)} 个 ｜ 新增 {len(only_new)} 个 ｜ 内容变化 {len(changed)} 个")
    L.append(f"- 类符号：消失 {len(gone_cls)} 个（预期内 {len(cls_ok)} / **需人工确认 {len(cls_ask)}**）")
    L.append(f"- 字符串：消失 {len(gone_s)} 条（预期内 {len(gone_ok)} / **需人工确认 {len(gone_ask)}**）\n")

    L.append("## ① 文件级\n")
    dump(L, "被删除的文件", [f"{n}（{fmt(ea[n][1])}）" for n in only_old])
    dump(L, "新增的文件", [f"{n}（{fmt(eb[n][1])}）" for n in only_new])
    L.append("### 内容变化的文件\n")
    for n in changed:
        L.append(f"- `{n}`　{ea[n][1]:,} → {eb[n][1]:,} B（{eb[n][1]-ea[n][1]:+,} B）")
    L.append("")

    L.append("## ② dex 类符号级\n")
    L.append(f"- 类总数：旧 {len(ca)} → 新 {len(cb)}\n")
    dump(L, "消失的类（预期内）", cls_ok)
    dump(L, "消失的类（需人工确认）", cls_ask)
    dump(L, "新增的类", new_cls)

    L.append("## ③ dex 字符串池级\n")
    L.append(f"- 字符串总数：旧 {len(sa)} → 新 {len(sb)}\n")
    dump(L, "消失的字符串（预期内 · 抽样 60 条）", gone_ok, 60)
    dump(L, "消失的字符串（需人工确认 · 全列）", gone_ask)
    dump(L, "新增的字符串（抽样 40 条）", new_s, 40)

    out.write_text("\n".join(L), encoding="utf-8")
    print(f"文件: 未变 {len(same)}/{total} · 删 {len(only_old)} · 增 {len(only_new)} · 变 {len(changed)}")
    print(f"类  : 消失 {len(gone_cls)}（预期 {len(cls_ok)} / 待确认 {len(cls_ask)}）")
    print(f"字符串: 消失 {len(gone_s)}（预期 {len(gone_ok)} / 待确认 {len(gone_ask)}）· 新增 {len(new_s)}")
    print("报告:", out)


if __name__ == "__main__":
    main()
