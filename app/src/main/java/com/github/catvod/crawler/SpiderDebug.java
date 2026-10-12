package com.github.catvod.crawler;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 宿主提供给 spider jar 的日志出口（tag = SpiderLog），jar 里的日志都从这里出。
 *
 * 2026-10-12（dk）：顺带承担一个"哨兵"职责 —— 这批加固 jar 的 `Init.init` 会先做**三道二次打包自检**
 * （包名白名单／App 名白名单／签名黑名单），任一不过就打一句带「疑似二次打包」的文案，
 * 并用 `goto` **跳过它自己的全部正常初始化**（`saveConfig`／`startFloatBall`／**`startGoProxy`**／4 个后台线程），
 * 改起一个睡 5 秒后自杀的线程。
 *
 * 那句文案是**同步打在我们调用它的那条线程上**的（`Init.init` 是普通同步方法），
 * 所以它是"这个 jar 自己放弃了初始化"的**唯一可靠信号** —— 光看反射有没有抛异常是看不出来的。
 *
 * 取证与判定书：`_l1_docs/30_成功率与崩溃/Init3自杀线程_根因取证与改动评估_20261012.md`
 * （含该 jar 的反汇编与解密出的两份白名单）。
 *
 * ⚠️ 这里只做**记录**，不改日志行为：文案照常输出，真机排查不受影响。
 */
public class SpiderDebug {

    /** 单调递增序号：只在"出现自检失败文案"时 +1，供调用前后比对 */
    private static final AtomicLong SELF_CHECK_SEQ = new AtomicLong();

    /** 最近一次自检失败文案的线程 id */
    private static volatile long sSelfCheckThreadId = -1;

    /** 最近一次自检失败文案对应的序号 */
    private static volatile long sSelfCheckSeq = 0;

    /**
     * jar 自检失败的已知文案片段。
     * 三处判据（包名／App 名／签名）失败后都会带这一句，所以只认这一个片段即可。
     */
    private static boolean isSelfCheckFailed(String msg) {
        return msg != null && msg.contains("疑似二次打包");
    }

    /** 调用加固 jar 自身入口**之前**取一份序号，调用之后交给 {@link #selfCheckFailedSince} 比对 */
    public static long selfCheckSeq() {
        return sSelfCheckSeq;
    }

    /**
     * 本次调用窗口内、**且发生在同一条线程上**，是否出现过自检失败文案。
     *
     * 两个条件都要满足才算，缺一不可：
     * ① 序号变过 ⇒ 这次调用窗口内确实有人打过；
     * ② 线程相同 ⇒ 打它的就是**我们这条调用线程**（别的 jar 在别的线程上失败不会被算到本次头上）。
     */
    public static boolean selfCheckFailedSince(long seqBefore) {
        return sSelfCheckSeq != seqBefore && sSelfCheckThreadId == Thread.currentThread().getId();
    }

    public static void log(Throwable th) {
        try {
            android.util.Log.d("SpiderLog", th.getMessage(), th);
        } catch (Throwable th1) {

        }
    }

    public static void log(String msg) {
        try {
            if (isSelfCheckFailed(msg)) {
                sSelfCheckThreadId = Thread.currentThread().getId();
                sSelfCheckSeq = SELF_CHECK_SEQ.incrementAndGet();
            }
            android.util.Log.d("SpiderLog", msg);
        } catch (Throwable th1) {

        }
    }

    public static String ec(int i) {
        return "";
    }
}
