package com.github.tvbox.osc.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 订阅地址规范化。
 *
 * 用户拿到的订阅链接来自公众号、二维码、群消息、浏览器地址栏，形态很脏：
 * 带 BOM / 零宽字符、前后夹说明文字、带首尾引号、大小写混杂的 scheme、漏写 http://。
 * 这些在"添加"阶段表现为"订阅格式不正确"，在"启用"阶段表现为拉不到配置，
 * 而用户看到的现象完全一样，无从排查。这里把它们统一收敛成可请求的形态。
 *
 * 纯字符串处理，不依赖 Android —— 可直接用桌面 JVM 跑用例表验证。
 */
public class L1SubUrl {

    /** 带 :// 的 scheme，用于判断"是否已带协议头" */
    private static final Pattern HAS_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*://");
    /** 取 scheme 部分（含 ://）用于小写归一 */
    private static final Pattern SCHEME_HEAD = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.\\-]*://)");
    /** 取"域名:端口"里的域名，用于识别漏写协议的裸地址（www.a.com/x、1.2.3.4:8080/x） */
    private static final Pattern BARE_HOST = Pattern.compile("^([a-zA-Z0-9\\-._]+\\.[a-zA-Z]{2,}|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$");

    /**
     * 规范化订阅地址：
     * ① 去掉 BOM / 零宽字符 / 所有空白（含换行）② 去掉首尾成对引号
     * ③ scheme 统一小写 ④ 漏写协议头的裸域名/裸 IP 补 http://
     * clan:// 是 App 内部协议，只做 ①②③，绝不补 http。
     * 解析不出来时原样返回（宁可不改，也不能改坏）。
     */
    public static String normalize(String raw) {
        String s = stripInvisible(raw);
        if (s.isEmpty()) return "";
        s = stripQuotes(s);
        if (s.isEmpty()) return "";

        Matcher m = SCHEME_HEAD.matcher(s);
        if (m.find()) {
            String head = m.group(1);
            String lower = head.toLowerCase();
            String rest = s.substring(head.length());
            if (lower.startsWith("clan://")) return "clan://" + rest;   // 内部协议原样
            return lower + rest;
        }
        // 没带协议头：只有长得像"裸域名/裸 IP"才补 http，避免把说明文字拼成地址
        if (BARE_HOST.matcher(s).matches()) return "http://" + s;
        return s;
    }

    /** 是不是一个"可以拿去请求"的地址（协议头合法）。用于给出准确结论，而不是笼统的"格式不正确" */
    public static boolean isRequestable(String url) {
        if (url == null) return false;
        String s = normalize(url);
        if (!HAS_SCHEME.matcher(s).find()) return false;
        return !s.startsWith("clan://") || s.length() > "clan://".length();
    }

    /**
     * 去 BOM、零宽字符与所有空白。
     * 顺序有讲究：先按字符去除（BOM \uFEFF、零宽空格 \u200B\u200C\u200D、不换行空格 \u00A0），
     * 再按 ASCII 判定去掉空白（含 URL 中间被误粘进去的空格/换行 —— 地址里不该有空白）。
     */
    public static String stripInvisible(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\uFEFF' || c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\u00A0') continue;
            if (c <= ' ') continue;                       // 空格 / \t \n \r 等控制字符
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 去掉首尾成对引号：用户从聊天窗口/分享面板复制时经常连引号一起带上 */
    private static String stripQuotes(String s) {
        int start = 0, end = s.length();
        while (start < end) {
            char c = s.charAt(start);
            if (c == '"' || c == '\'' || c == '\u201C' || c == '\u201D' || c == '\u2018' || c == '\u2019' || c == '\u300C' || c == '\u300D') start++;
            else break;
        }
        while (end > start) {
            char c = s.charAt(end - 1);
            if (c == '"' || c == '\'' || c == '\u201C' || c == '\u201D' || c == '\u2018' || c == '\u2019' || c == '\u300C' || c == '\u300D') end--;
            else break;
        }
        return s.substring(start, end);
    }
}
