package com.github.tvbox.osc.util;

import java.io.UnsupportedEncodingException;

/**
 * 订阅响应内容净化：把服务端返回的任意文本收敛成 JSON。
 *
 * 现实中"订阅地址"返回的东西千奇百怪，而这正是"解析失败"最大的来源：
 *  · 裸 base64（无任何前缀）—— 原实现只认 {@code [A-Za-z0-9]{8}**} 前缀，识别不出来；
 *  · base64 里包的是 **gzip/zlib 压缩流**（"压缩后再编码"是很常见的发布形态）；
 *  · 被 HTML 包一层（源站引导页 / 中转页 / 拦截页）；
 *  · JSON 前后带说明文字、带 BOM、带 `//` 注释、带尾逗号；
 *  · **顶层直接是数组**（`[{...}]`），而不是常见的对象；
 *  · GBK 编码（结构是 ASCII 所以 JSON 能解析，但站点名全乱码）。
 *
 * 判定策略：**只认结果**。任何解码尝试都必须"解出带订阅特征字段的 JSON 对象"才算成功，
 * 解出乱码一律丢弃 —— 所以宁可漏认，绝不误认。
 *
 * 纯字符串处理，不依赖 Android —— 可直接用桌面 JVM 跑用例表验证。
 */
public class L1SubContent {

    /** 拿到可用 JSON */
    public static final int OK = 0;
    /** 响应为空 */
    public static final int EMPTY = 1;
    /** 返回的是网页，不是订阅配置 */
    public static final int HTML = 2;
    /** 有内容，但既不是 JSON 也不是可识别的订阅编码 */
    public static final int NOT_SUB = 3;

    /** 订阅配置的特征字段：命中任意一个才认为"这确实是一份订阅" */
    private static final String[] KEYS = {"\"sites\"", "\"urls\"", "\"storeHouse\"", "\"spider\"", "\"parses\""};

    /**
     * 把原始响应收敛成 JSON 字符串；拿不到返回 null。
     * 顺序：直接取 JSON → 裸 base64 解一轮 → 再解一轮（有的源是双层编码）。
     */
    public static String toJson(String raw) {
        String s = L1SubUrl.stripInvisible(raw);
        if (s.isEmpty()) return null;

        String json = pickJson(s);
        if (json != null) return json;

        // base64 兜底：最多两轮
        for (int round = 0; round < 2; round++) {
            String decoded = decodeBase64(s);
            if (decoded == null) break;
            s = L1SubUrl.stripInvisible(decoded);
            if (s.isEmpty()) break;
            json = pickJson(s);
            if (json != null) return json;
        }
        return null;
    }

    /** 给用户一个准确结论，而不是笼统的"解析失败" */
    public static int diagnose(String raw) {
        String s = L1SubUrl.stripInvisible(raw);
        if (s.isEmpty()) return EMPTY;
        if (looksLikeHtml(s)) return HTML;
        if (toJson(raw) != null) return OK;
        return NOT_SUB;
    }

    /** 该不该按 HTML 处理：有标签特征，且首个 '{' 不在开头（真 JSON 不会以标签开头） */
    public static boolean looksLikeHtml(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        if (c == '{' || c == '[') return false;
        String low = s.length() > 512 ? s.substring(0, 512).toLowerCase() : s.toLowerCase();
        return low.contains("<!doctype") || low.contains("<html") || low.contains("<head")
                || low.contains("<body") || low.contains("<div") || low.contains("<meta");
    }

    /**
     * 从文本里取出可用的 JSON。按"形态越确定越优先"处理：
     *  ① 顶层是数组：只在数组元素里找（见 {@link #pickFromArray}）；
     *  ② 其余：取第一个 '{' 到最后一个 '}'；
     *  ③ 对取到的段做字符串感知清洗（{@link #cleanJson}：去注释、去尾逗号）后再校验特征字段。
     *
     * 为什么数组要单独走一条路：`[{...},{...}]` 用"首个 '{' 到末个 '}'"切出来的是
     * `{...},{...}` —— 不是合法 JSON，而它又含特征字段，于是会一路带到 Gson 才炸，
     * 报出来还是笼统的"解析配置失败"。分形态处理后，数组源要么被正确取到一个对象，
     * 要么明确判不成，不再产生"半截合法"的中间产物。
     */
    static String pickJson(String s) {
        if (startsWithArray(s)) return pickFromArray(s);
        String block = slice(s);
        if (block == null) return null;
        String cleaned = cleanJson(block);
        // 不含特征字段时：只有当整段内容本身就是 JSON（首尾就是花括号）才采信，
        // 否则很可能是从一段文本里误截出来的片段。
        boolean selfContained = s.charAt(0) == '{' && s.charAt(s.length() - 1) == '}';
        if ((hasSubKey(cleaned) || selfContained) && looksParsable(cleaned)) return cleaned;
        return null;
    }

    /** 跳过前导空白后是否以 '[' 开头（以 '{' 或其他字符开头都不算数组形态） */
    private static boolean startsWithArray(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') return true;
            if (c == '{') return false;
            if (!Character.isWhitespace(c)) return false;
        }
        return false;
    }

    /**
     * 顶层数组：取第一个"含订阅特征字段"的对象。
     * 用括号配对扫描而不是 indexOf，因为元素内部还有嵌套的 {} / []，字符串里也可能出现花括号
     * （站点名、分类名里带花括号是常事）。
     */
    private static String pickFromArray(String s) {
        int open = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') {
                open = i;
                break;
            }
            if (!Character.isWhitespace(c)) return null;
        }
        if (open < 0) return null;
        int close = s.lastIndexOf(']');
        if (close <= open + 1) return null;
        String body = s.substring(open + 1, close);
        int depth = 0, start = -1;
        boolean inStr = false, esc = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                if (depth > 0) depth--;
                if (depth == 0 && start >= 0) {
                    String obj = cleanJson(body.substring(start, i + 1));
                    if (hasSubKey(obj) && looksParsable(obj)) return obj;
                    start = -1;
                }
            }
        }
        return null;
    }

    /**
     * 字符串感知的宽松清洗：删掉 JSON 规范不允许的注释（`//` 行注释、`/* *​/` 块注释）与尾逗号
     * （`[1,2,]`、`{...,}`）。生成器导出的配置文件里这两种很常见，而 Gson 会直接抛。
     *
     * 必须感知字符串边界：站点名/地址里带 `//` 或逗号是常态，全局替换会把内容改坏。
     * 清洗只做"删除"，不改写任何字符，因此对本来就合法的 JSON 等价于原样返回（幂等）。
     */
    static String cleanJson(String s) {
        if (s == null) return null;
        if (s.indexOf('/') < 0 && s.indexOf(',') < 0) return s;   // 快路径：没有可清洗的字符
        StringBuilder sb = new StringBuilder(s.length());
        boolean inStr = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                sb.append(c);
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') {
                inStr = true;
                sb.append(c);
                continue;
            }
            if (c == '/') {
                // 只处理块注释：`/* */` 自带结束标记，删掉是安全的。
                // **行注释 `//` 不在这里处理** —— 它要靠换行界定结束位置，而进入本方法前
                // L1SubUrl.stripInvisible 已经把换行当作不可见字符删掉了：`{//说明\n"sites":...}`
                // 在这里实际是 `{//说明"sites":...}`，注释与正文已经粘死、无法区分边界。
                // 早期版本在这里按"删到行尾"处理，结果在没有换行可找时一路删到字符串末尾，
                // 把整份配置吃光、只留下一个 `{` —— 那比不处理更糟。认不出就如实判失败。
                if (i + 1 < s.length() && s.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < s.length() && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                    i++;
                    continue;
                }
            }
            if (c == ',') {
                // 尾逗号：后面跳过空白若是 } 或 ] 就丢弃这个逗号，否则是正常分隔符，原样保留
                int j = i + 1;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                if (j < s.length() && (s.charAt(j) == '}' || s.charAt(j) == ']')) continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 结果守门：字符串感知地检查"这段文本像不像能解析的 JSON"。
     *  · **括号必须平衡、字符串必须闭合** —— 拦住被截断的半截产物（典型是孤零零一个 `{`）。
     *    半截产物一旦当成结果返回，后面的流程会一路把它带到 Gson 才炸，报出来还是笼统的
     *    "解析配置失败"，反而不如在这里就判失败、给出准确结论。
     *  · **字符串外不得残留 `//` 或 `/*`** —— JSON 在那两个位置只可能是注释，残留说明没清洗干净；
     *    与其交给解析器去猜，不如如实判失败（宁可漏认，绝不误认）。
     */
    static boolean looksParsable(String s) {
        if (s == null || s.length() < 2) return false;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) return false;
            } else if (c == '/' && i + 1 < s.length() && (s.charAt(i + 1) == '/' || s.charAt(i + 1) == '*')) {
                return false;
            }
        }
        return depth == 0 && !inStr;
    }

    private static String slice(String s) {
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start + 1) return null;
        return s.substring(start, end + 1);
    }

    private static boolean hasSubKey(String s) {
        for (String k : KEYS) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    /**
     * 宽松 base64 解码：兼容标准表与 URL-safe 表、缺省 padding、混入的空白与引号。
     * 拿不准就返回 null（调用方会用 JSON 校验兜底，所以这里宽松不会造成误判）。
     */
    public static String decodeBase64(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' ) c = '+';
            else if (c == '_') c = '/';
            if (isBase64Char(c)) sb.append(c);
            else if (c == '=' || c == '"' || c == '\'') continue;   // 容忍包了引号/多余 padding
            else return null;                                        // 混入其它字符：不是 base64
        }
        int n = sb.length();
        while (n > 0 && sb.charAt(n - 1) == '=') n--;
        if (n < 64) return null;                                     // 太短，不可能是编码后的订阅
        byte[] out = b64(sb, n);
        if (out == null || out.length == 0) return null;
        // 解出来的可能还是压缩流（"压缩后再 base64"是这类源站很常见的发布形态）
        byte[] plain = maybeInflate(out);
        if (plain == null) return null;
        String utf8 = new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        return repairIfMojibake(utf8, plain);
    }

    /** 解压输出上限：订阅配置不可能到这个量级，超了说明解出来的不是文本，直接放弃 */
    private static final int MAX_INFLATE = 4 * 1024 * 1024;

    /**
     * base64 解出来的是不是压缩流：只按魔数识别（0x1f8b=gzip，0x78 开头=zlib）。
     * 识别不出就原样返回 —— 绝不"盲目试解压"，那会把正常内容解成垃圾。
     */
    static byte[] maybeInflate(byte[] b) {
        if (b == null || b.length < 4) return b;
        int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
        if (b0 == 0x1F && b1 == 0x8B) return inflate(b, true);
        if (b0 == 0x78 && (b1 == 0x01 || b1 == 0x5E || b1 == 0x9C || b1 == 0xDA)) return inflate(b, false);
        return b;
    }

    /**
     * 解压：用流式读并在读取过程中设上限 —— 数据量由入参决定，不设限就有被构造包吃光内存的风险。
     * 超限、解不开一律返回 null（宁可漏认，绝不误认）。gzip 走 GZIPInputStream 而不是 Inflater：
     * gzip 有独立的 10 字节头与 8 字节尾，raw/deflate 的 Inflater 解不了。
     */
    private static byte[] inflate(byte[] in, boolean gzip) {
        java.io.InputStream is = null;
        try {
            java.io.ByteArrayInputStream bin = new java.io.ByteArrayInputStream(in);
            is = gzip ? new java.util.zip.GZIPInputStream(bin)
                    : new java.util.zip.InflaterInputStream(bin, new java.util.zip.Inflater(false));
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(4096);
            int got;
            while ((got = is.read(buf)) > 0) {
                if (bos.size() + got > MAX_INFLATE) return null;
                bos.write(buf, 0, got);
            }
            byte[] out = bos.toByteArray();
            return out.length == 0 ? null : out;
        } catch (Throwable th) {
            return null;
        } finally {
            try {
                if (is != null) is.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 自实现 base64 解码：不依赖 android.util.Base64（API 26 的 java.util.Base64 更用不了，minSdk 24） */
    private static byte[] b64(CharSequence s, int n) {
        int outLen = (n * 6) / 8;
        if (outLen <= 0) return null;
        byte[] out = new byte[outLen];
        int buf = 0, bits = 0, o = 0;
        for (int i = 0; i < n; i++) {
            int v = val(s.charAt(i));
            if (v < 0) return null;
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                if (o >= outLen) break;
                out[o++] = (byte) ((buf >> bits) & 0xFF);
            }
        }
        return out;
    }

    private static boolean isBase64Char(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '+' || c == '/' || c == '=' || c == '-' || c == '_';
    }

    private static int val(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        return -1;
    }

    /**
     * 字节 → 文本：OKHttp 的 body().string() 在 Content-Type 未声明 charset 时按 UTF-8 解，
     * 遇到 GBK 源站会把中文解成一串 U+FFFD（JSON 结构是 ASCII 所以照常能解析，
     * 只有站点名/分类名烂掉），这里补一层重解。
     */
    public static String decodeText(byte[] raw) {
        if (raw == null || raw.length == 0) return "";
        return repairIfMojibake(new String(raw, java.nio.charset.StandardCharsets.UTF_8), raw);
    }

    /**
     * UTF-8 解出替换符（U+FFFD）说明原文不是 UTF-8：多半是 GBK。
     * 只有在 UTF-8 明显解坏、而 GBK 能解干净时才替换，避免把正常内容改坏。
     */
    private static String repairIfMojibake(String utf8, byte[] raw) {
        if (utf8.indexOf('\uFFFD') < 0) return utf8;
        try {
            String gbk = new String(raw, "GBK");
            if (gbk.indexOf('\uFFFD') < 0) return gbk;
        } catch (UnsupportedEncodingException ignored) {
        }
        return utf8;
    }

    /** 结论的可读名，用于日志：让"解析失败"能区分成三类具体原因 */
    public static String diagName(int d) {
        switch (d) {
            case OK: return "OK";
            case EMPTY: return "空响应";
            case HTML: return "返回的是网页(非订阅)";
            case NOT_SUB: return "内容不是订阅配置";
            default: return "未知";
        }
    }

    /**
     * 给**用户看**的短结论。与 diagName 分开：那个给日志看，可以带技术措辞；
     * 这个要能让用户直接据此行动（改地址 / 换源 / 检查网络），所以只说人话。
     */
    public static String diagTip(String raw) {
        switch (diagnose(raw)) {
            case EMPTY: return "地址没有返回内容";
            case HTML: return "返回的是网页，不是订阅配置";
            case NOT_SUB: return "返回的内容不是订阅配置";
            default: return "";
        }
    }

    /** 取前 n 个字符用于日志取样：换行折成空格，避免日志被撑成多行 */
    public static String head(String s, int n) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < n; i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 ? ' ' : c);
        }
        return sb.toString();
    }

    /** 统计订阅里 sites 数组的条目数（粗略扫描，用于"0 站点"的准确结论，不引入 JSON 依赖） */
    public static int countSites(String json) {
        if (json == null) return -1;
        int i = json.indexOf("\"sites\"");
        if (i < 0) return 0;
        int p = json.indexOf('[', i);
        if (p < 0) return 0;
        int depth = 0, count = 0;
        boolean inStr = false, esc = false;
        for (int k = p; k < json.length(); k++) {
            char c = json.charAt(k);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == '[' || c == '{') {
                depth++;
                if (c == '{' && depth == 2) count++;
            } else if (c == ']' || c == '}') {
                depth--;
                if (depth <= 0) break;
            }
        }
        return count;
    }
}
