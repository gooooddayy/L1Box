package com.github.tvbox.osc.util;

import android.app.ActivityManager;
import android.content.Context;

/**
 * 设备能力分级。
 *
 * 存在的理由：并发数这类参数一旦写死，就等于"按最高配设备的标准要求所有设备"。
 * 高配机上毫无压力，2G 内存的老机器上却是线程争抢与 ANR 的直接来源。
 * 这里把设备能力量化为三档，各处按档位取值：
 *
 * - 低档（堆 <=128MB，或系统标记为低内存设备）：并发收敛，宁可慢一点也不能崩
 * - 中档（堆 128~255MB）：与改造前一致
 * - 高档（堆 >=256MB）：与改造前一致，不做额外提权，避免引入未知问题
 *
 * 未 init 时全部返回中档默认值，即与改造前行为完全一致（安全兜底）。
 */
public final class DeviceProfile {
    public static final int TIER_LOW = 0;
    public static final int TIER_MID = 1;
    public static final int TIER_HIGH = 2;

    // 播放缓冲水位档位（2026-09-12）。刻意与并发档位分开一套：
    // 4GB 手机的 getMemoryClass() 报 192/256MB、isLowRamDevice() 又为 false，在并发上算中/高档并无问题，
    // 但在缓冲水位上它是必须单独照顾的一类（物理内存小、又最需要抗弱网），故按 totalMem 再分一层。
    public static final int BUFFER_OFF = 0;
    public static final int BUFFER_CONSERVATIVE = 1;
    public static final int BUFFER_STANDARD = 2;
    public static final int BUFFER_AGGRESSIVE = 3;

    private static volatile int tier = TIER_MID;
    private static volatile int cores = 4;
    private static volatile int heapMb = 0;
    private static volatile long totalMemMb = 0;
    /** 未 init 时为 BUFFER_OFF（不接管缓冲策略），与"未 init 返回中档 = 与改造前一致"同一原则 */
    private static volatile int bufferTier = BUFFER_OFF;

    private DeviceProfile() {
    }

    /** 进程启动时调用一次。任何异常都退回中档默认值，绝不影响启动。 */
    public static void init(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                int mb = am.getMemoryClass();
                if (mb > 0) {
                    heapMb = mb;
                    tier = mb <= 128 ? TIER_LOW : (mb < 256 ? TIER_MID : TIER_HIGH);
                }
                // 低内存设备标记（ro.config.low_ram，Android Go 机 / 部分老盒子）：
                // 比堆大小更权威 —— 这类设备堆可能判到中档，但系统随时更激进地回收后台进程，
                // 并发必须收敛。API 19 起可用，与 minSdk 24 匹配。
                if (am.isLowRamDevice()) tier = TIER_LOW;
                // 物理内存：getMemoryClass() 只反映 Java 堆上限，识别不出"4GB 内存的低端机"。
                // 单独采一次真实 RAM 供缓冲水位分档用（API 16 起可用，与 minSdk 24 匹配）。
                try {
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    totalMemMb = mi.totalMem / (1024L * 1024L);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        bufferTier = computeBufferTier(heapMb, totalMemMb);
        try {
            int c = Runtime.getRuntime().availableProcessors();
            if (c > 0) cores = c;
        } catch (Throwable ignored) {
        }
    }

    /**
     * 运行期内存压力（系统 onLowMemory / TRIM_MEMORY_RUNNING_LOW 等）→ 并发档位降到低档。
     *
     * 只降不升：避免在"紧张—宽松"之间来回切换导致行为抖动。影响范围仅限之后新建的
     * 线程池（已有池的运行状态不动），因此对正在进行的播放与列表没有任何副作用。
     */
    public static void markMemoryPressure() {
        tier = TIER_LOW;
    }

    public static int tier() {
        return tier;
    }

    /**
     * 缓冲水位档位（只影响播放缓冲，不影响任何并发）。
     *
     * 分档依据是"物理内存 + 堆上限"两件事，而不是 isLowRamDevice：
     * - 低档设备（堆 ≤128MB，或系统标记为低内存设备）直接不接管缓冲策略；
     * - 4GB 标称机的 MemTotal 实际约 3.6~3.9GB，取 4608MB 作阈值 → 保守档；
     * - 8GB 标称机 MemTotal 约 7.2~7.8GB，取 7168MB 作阈值（标称 ≠ MemTotal）。
     *
     * 运行期内存压力**不改这里**：那是"这一次内存紧张"的临时状态，由缓冲控制器自己用冷却期处理，
     * 否则设备紧张一次后整个进程都享受不到深水位（列表里滚图就常触发 RUNNING_LOW）。
     */
    private static int computeBufferTier(int heapMb, long totalMemMb) {
        if (tier == TIER_LOW || heapMb <= 128) return BUFFER_OFF;
        if (totalMemMb > 0 && totalMemMb <= 4608) return BUFFER_CONSERVATIVE;
        if (heapMb >= 256 && totalMemMb >= 7168) return BUFFER_AGGRESSIVE;
        return BUFFER_STANDARD;
    }

    public static int bufferTier() {
        return bufferTier;
    }

    public static String bufferTierName() {
        switch (bufferTier) {
            case BUFFER_CONSERVATIVE:
                return "conservative";
            case BUFFER_STANDARD:
                return "standard";
            case BUFFER_AGGRESSIVE:
                return "aggressive";
            default:
                return "off";
        }
    }

    /** 诊断用：形如 "tier=mid heap=192 cores=8 ram=3900MB buffer=conservative" */
    public static String describe() {
        return "tier=" + (tier == TIER_LOW ? "low" : tier == TIER_MID ? "mid" : "high")
                + " heap=" + heapMb + " cores=" + cores + " ram=" + totalMemMb + "MB"
                + " buffer=" + bufferTierName();
    }

    /** spider 分片数：同一站点始终同片串行，这里只决定跨站点的并行度 */
    public static int spiderShards() {
        return tier == TIER_LOW ? 2 : 3;
    }

    /**
     * 搜索并发：低端按核数收敛，中高端 10（ak 版定稿，与 ab 逐字一致）。
     *
     * 沿革：上游原值 10 → ae 提到 16 → ah 回调 10 → ai 恢复 16 → ak 定稿回 10。
     * ak 定稿依据（2026-09-13 用户拍板）：减少崩溃是必须的，加速只是锦上添花。真机实证崩溃开关是
     * 「加固 jar 初始化密度」——16 并发 + quick 快速返回把多个家族 jar 的首次初始化挤进同一时间窗，
     * 兑现潜伏的 killall 互杀链（aj 会话 27 次 killall、2 次崩溃）；ab 的 10 并发把初始化天然摊开。
     * 注：并发本身不产生 killall，只是密度推手；机制级封堵见 ProtectedInitJar 的 DexNative 家族判据，
     * 两者叠加＝时序运气 + 机制防线双保险。
     */
    public static int searchConcurrency() {
        return tier == TIER_LOW ? Math.min(cores, 6) : 10;
    }

    /** 详情页并发：低端 3，其余维持原来的 5 */
    public static int detailConcurrency() {
        return tier == TIER_LOW ? 3 : 5;
    }

    /**
     * 超时隔离线程上限（给同步阻塞调用加超时时用）。
     * 这是硬上限：无论哪台设备、哪一处漏了回收，同时被卡住的线程数都不会超过它。
     */
    public static int timeoutRunnerMax() {
        return tier == TIER_LOW ? 2 : 4;
    }
}
