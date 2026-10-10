package com.github.tvbox.osc.util;

import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * jar 缓存写入观测（2026-10-10，df 观测版）。
 *
 * <p>背景（真机铁证）：csp.jar 会变成"非法 zip/dex" ——
 * <pre>
 * java.lang.ClassNotFoundException: Didn't find class "com.github.catvod.spider.Init"
 *   on path: DexPathList[[zip file ".../files/csp.jar"]]
 * Suppressed: java.io.IOException: Failed to open dex files ... because: Expected valid zip or dex file
 * </pre>
 * ⇒ {@code JarLoader.loadClassLoader} 失败（fLoaded=false）⇒ {@code callback.error("")}
 * ⇒ HomeFragment 的 {@code mSilentReload} 分支弹「线路切换失败,请重试」并且 **不加载首页**。
 *
 * <p>而工程里**有两条**独立路径都会 {@code delete()} + 重写同一个 jar 文件，且无文件级互斥：
 * <ol>
 *   <li>{@code ApiConfig.downloadJar}（OkGo convertResponse，首页加载流程）</li>
 *   <li>{@code JarLoader.loadJarInternal}（取数时 {@code SourceViewModel → getCSP → getSpider}）</li>
 * </ol>
 *
 * <p>本类**只做观测**（不改变任何行为），用来区分两种可能：
 * <ul>
 *   <li><b>A 并发交错</b>：{@code 并发>1}，写完头的字节是半截/异常，大小与预期不符</li>
 *   <li><b>B 内容本身不是 jar</b>：文件头是 {@code <}（HTML/文本）之类，与并发无关</li>
 * </ul>
 * 判定方法：看 `JAR写入[...] 开始 并发=N` 是否出现 N&gt;1，以及 `结束 ... 判定=` 的结论。
 */
public final class JarWriteTrace {

    /** 当前正在写 jar 的线程数（两个写入者共用；>1 即并发写同一文件） */
    private static final AtomicInteger INFLIGHT = new AtomicInteger();

    private JarWriteTrace() {
    }

    /** 写入开始。返回当前并发数。 */
    public static int begin(String who, File f) {
        int n = INFLIGHT.incrementAndGet();
        System.out.println("JAR写入[" + who + "] 开始 并发=" + n
                + " 文件=" + name(f)
                + " 原大小=" + (f != null && f.exists() ? f.length() : -1));
        return n;
    }

    /** 写入结束（放 finally 里，异常路径也记账，避免并发计数泄漏）。 */
    public static void end(String who, File f) {
        int n = INFLIGHT.decrementAndGet();
        System.out.println("JAR写入[" + who + "] 结束 剩余并发=" + n
                + " 文件=" + name(f)
                + " 大小=" + (f != null && f.exists() ? f.length() : -1)
                + " 头=" + head(f)
                + " 判定=" + verdict(f));
    }

    private static String name(File f) {
        return f == null ? "(null)" : f.getName();
    }

    /** 文件头 8 字节（可打印字符直接显示，其余转 \xNN） */
    private static String head(File f) {
        if (f == null || !f.exists() || f.length() <= 0) return "(空或不存在)";
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b = new byte[8];
            int r = in.read(b);
            if (r <= 0) return "(读不到)";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r; i++) {
                int v = b[i] & 0xFF;
                if (v >= 32 && v < 127) {
                    sb.append((char) v);
                } else {
                    sb.append("\\x").append(String.format("%02X", v));
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(读取异常)";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 合法性判定：ZIP=PK.. ／ DEX=dex\n ／ HTML=「<」开头 ／ 其它=未知 */
    private static String verdict(File f) {
        if (f == null || !f.exists()) return "文件不存在";
        if (f.length() == 0) return "空文件";
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b = new byte[4];
            int r = in.read(b);
            if (r < 4) return "太短";
            if (b[0] == 'P' && b[1] == 'K') return "合法ZIP";
            if (b[0] == 'd' && b[1] == 'e' && b[2] == 'x') return "合法DEX";
            if (b[0] == '<') return "非法(HTML/文本开头)";
            return "非法(未知头)";
        } catch (Throwable t) {
            return "(判定异常)";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
