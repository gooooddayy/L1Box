package xyz.doikki.videoplayer.exo;

import android.os.SystemClock;
import android.util.Log;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.DefaultAllocator;

/**
 * 按设备档位加深缓冲水位的缓冲控制器（L1Box 定制，2026-09-12）。
 *
 * 为什么要这么绕：Exo 的 minBufferMs / maxBufferMs / targetBufferBytes 都是构造期 final，运行期
 * 改不了；整体替换 LoadControl 实例又会让新旧分配器不一致（旧 SampleQueue 用旧分配器、新控制器
 * 读新分配器的计数 → 状态错乱）。所以这里走「一个实例 + 一道自有时间闸门」：
 *
 * - 阶段一（播放位置 0~10 秒）：闸门压在 50 秒 —— 即 Exo 默认的水位，**前 10 秒与改造前零差异**，
 *   误点即走不会多下流量。
 * - 阶段二（位置 ≥ 10 秒）：放开闸门，交给构造期定下的深水位（150/240/300 秒）+ 字节闸门。
 *   位置取自 shouldContinueLoading 的第一个入参，不需要任何 Activity/定时器；切集后位置归零，
 *   新一集自动重新走「先保守 10 秒」。
 *
 * 三级兜底（对应「4GB 手机也不能报错」）：
 * 1. 参数不变式：minBufferMs = maxBufferMs = 深水位，且 >= bufferForPlaybackMs(1500) >= 0 ——
 *    Exo 构造期那 5 条断言全部满足（违反会抛 IllegalArgumentException，这是唯一"配置型报错"来源）；
 * 2. 升档前守门：可用堆不足「水位字节 + 余量」就不升档，维持现状 50 秒；可用堆低于 MIN_AVAIL_BYTES
 *    时干脆不接管（返回默认控制器）；
 * 3. 内存压力回落：App 的内存压力回调调用 {@link #onMemoryPressure()}，立刻把分配器目标尺寸调小
 *    以触发 trim() 归还空闲块（只释放未被引用的块，不动正在播的数据），并进入冷却期不再升档。
 *
 * 注意：本类只影响 Exo。IJK 侧的水位与缓存开关保持原样，不受这里影响。
 *
 * 2026-09-18 修正（本轮）：
 * ① 升档守门原本是「可用堆 ≥ 深水位字节 × 3」，而深水位字节本身是「创建那一刻的可用堆 ÷ 3」
 *    推出来的 ⇒ 等价于要求「运行时可用堆 ≥ 创建时可用堆」，而播满 10 秒后播放器已吃掉一块内存，
 *    运行时必然更低 ⇒ 条件结构性不成立。实测 15 次会话里够条件的 6 次只成功 4 次，失败还无日志。
 *    现改为：字节闸门按**堆上限**的比例定，守门判据 = 可用堆 ≥ 水位字节 + RESERVE_BYTES。
 * ② 补观测：「接管/未接管」「未升档原因」「起播放行时的缓冲量」三处日志，否则"加深到底有没有生效"
 *    永远只能靠猜（这正是本轮之前的状况）。
 */
public class L1LoadControl extends DefaultLoadControl {

    private static final String TAG = "L1Buffer";

    /** 档位：不接管（保持 Exo 默认，含起播门槛 2500ms）；未识别/初始化前的安全兜底 */
    public static final int TIER_OFF = 0;
    /** 档位：保守（≤4.5GB 物理内存的低端机） */
    public static final int TIER_CONSERVATIVE = 1;
    /** 档位：标准 */
    public static final int TIER_STANDARD = 2;
    /** 档位：激进 */
    public static final int TIER_AGGRESSIVE = 3;

    /** 深水位时长（毫秒）。下标 = 档位。150/240/300 秒。 */
    private static final int[] DEEP_MS = {0, 150_000, 240_000, 300_000};
    /**
     * 深水位字节上限。下标 = 档位。48/72/128MB。
     *
     * 2026-09-18 第三轮：标准档 96MB → **128MB**（用户拍板）。依据是 Exo 自己的默认常量 ——
     * `DefaultLoadControl.DEFAULT_MUXED_BUFFER_SIZE = 137.6MB` / `DEFAULT_VIDEO_BUFFER_SIZE = 125MB`，
     * 也就是说**改造前的 96MB 比 Exo 官方默认还低 30%**，128MB 仍低于官方默认值。
     * 本站内存实测：堆上限 512MB、播放中可用堆 384~484MB ⇒ 升档守门（需 128+32=160MB）富余 220MB 以上。
     * 低端两档（48/72MB）**刻意不动** —— 它们的物理内存本就吃紧，加深水位只会挤占播放器其他开销。
     *
     * 注意本值只是"申请值"，真正生效的字节闸门还会被 `create()` 里的 `maxMemory()/4` 夹取，
     * 所以低堆设备上会自动收敛（例如堆上限 384MB 的机器上 128MB 会落到 96MB），不会硬顶。
     */
    private static final int[] DEEP_BYTES = {0, 48 << 20, 72 << 20, 128 << 20};

    /** 起播门槛：Exo 默认 2500ms → 1500ms。只管"多久出画面"，与水位字节无关 */
    private static final int START_PLAYBACK_MS = 1500;
    /** 重缓冲门槛：保持 Exo 默认 5000ms。调小会"恢复快但反复卡"，调大卡顿恢复更慢 */
    private static final int AFTER_REBUFFER_MS = 5000;

    /** 阶段一水位：与 Exo 默认 minBufferMs/maxBufferMs 一致（50 秒） */
    private static final long STAGE1_US = 50_000_000L;
    /** 观看满 10 秒（播放位置 ≥ 10s）才升档 */
    private static final long ESCALATE_AT_US = 10_000_000L;

    /** 可用堆低于此值干脆不接管 —— 避免"接管后水位还不如现状" */
    private static final long MIN_AVAIL_BYTES = 96L << 20;
    /** 按可用堆夹取后的字节闸门下限（32MB ≈ 现状 50 秒 @5Mbps） */
    private static final int MIN_DEEP_BYTES = 32 << 20;
    /**
     * 升档后要给系统留的余量：判据是「可用堆 ≥ 水位字节 + 本余量」。
     *
     * 2026-09-18 修正：原来用 AVAIL_FACTOR=3 与深水位字节相乘，而深水位字节又是
     * 「创建那一刻的可用堆 ÷ 3」推出来的 ⇒ 等价于要求「运行时可用堆 ≥ 创建时可用堆」，
     * 播满 10 秒后播放器已经吃掉一块内存，运行时必然更低 ⇒ 条件结构性不成立。
     * 实测（`_be_test1.log`）15 次会话里够条件的 6 次只成功 4 次，且失败时一行日志都没有。
     */
    private static final long RESERVE_BYTES = 32L << 20;
    /** 内存压力时把分配器目标尺寸降到这个值（触发 trim 归还空闲块） */
    private static final int PRESSURE_BYTES = 24 << 20;
    /** 内存压力后的冷却期：这段时间内不升档，之后按守门条件重新评估 */
    private static final long COOLDOWN_MS = 60_000L;

    /** 全进程共享的冷却截止时刻（SystemClock.elapsedRealtime），0 = 无冷却 */
    private static volatile long pressureUntilMs = 0L;

    private final int deepMs;
    private final int deepBytes;
    private volatile boolean escalated = false;
    /** 本集是否已记录过"未升档原因"（换集/拖回开头时复位，避免同一集刷屏） */
    private volatile boolean holdReported = false;
    /** 本集是否已记录过"起播放行"（只观测，不改行为） */
    private volatile boolean startReported = false;
    /** 已针对哪一次冷却做过 trim（避免每 10ms 调用一次都去 trim） */
    private volatile long trimmedForUntil = 0L;

    private L1LoadControl(int deepMs, int deepBytes) {
        // = C.DEFAULT_BUFFER_SEGMENT_SIZE：与 Exo 默认分配器逐字一致（64KB 分片）
        super(new DefaultAllocator(true, 65536),
                deepMs, deepMs, START_PLAYBACK_MS, AFTER_REBUFFER_MS,
                deepBytes, false, 0, false);
        this.deepMs = deepMs;
        this.deepBytes = deepBytes;
    }

    /**
     * 工厂：任何异常、任何不满足条件的情况都退回 Exo 默认控制器（= 现状）。
     * 拿不到更好，但绝不比原来更差。
     */
    public static LoadControl create(int tier) {
        try {
            if (tier <= TIER_OFF || tier >= DEEP_MS.length) {
                Log.i(TAG, "未接管：档位=" + tier + "，起播门槛与水位保持 Exo 默认");
                return new DefaultLoadControl();
            }
            long avail = availBytes();
            if (avail < MIN_AVAIL_BYTES) {
                Log.i(TAG, "未接管：可用堆不足(" + (avail >> 20) + "MB < " + (MIN_AVAIL_BYTES >> 20) + "MB)");
                return new DefaultLoadControl();
            }
            // 字节闸门按「堆上限」的比例定，而不是拿"创建那一刻的可用堆"去除 —— 后者会随时间漂移，
            // 导致升档守门自相矛盾（见 RESERVE_BYTES 注释）
            long budget = Runtime.getRuntime().maxMemory() / 4;
            int bytes = (int) Math.min(DEEP_BYTES[tier], Math.max(MIN_DEEP_BYTES, budget));
            Log.i(TAG, "接管：档位=" + tier + " 起播门槛=" + START_PLAYBACK_MS + "ms 水位="
                    + (DEEP_MS[tier] / 1000) + "s/" + (bytes >> 20) + "MB 可用堆=" + (avail >> 20)
                    + "MB 堆上限=" + (Runtime.getRuntime().maxMemory() >> 20) + "MB");
            return new L1LoadControl(DEEP_MS[tier], bytes);
        } catch (Throwable th) {
            Log.w(TAG, "缓冲控制器创建失败，退回默认: " + th);
            return new DefaultLoadControl();
        }
    }

    /**
     * 系统报内存紧张时调用（App.onTrimMemory 前台等级 / onLowMemory）。
     * 只降不升是刻意的：紧张时把空闲缓冲块还回堆，并在冷却期内不再加深，避免把内存又吃回去。
     */
    public static void onMemoryPressure() {
        pressureUntilMs = SystemClock.elapsedRealtime() + COOLDOWN_MS;
    }

    @Override
    public boolean shouldContinueLoading(long playbackPositionUs, long bufferedDurationUs, float playbackSpeed) {
        boolean holdAtStage1 = false;
        try {
            long now = SystemClock.elapsedRealtime();
            if (now < pressureUntilMs) {
                // 内存压力冷却期：本集维持现状水位，并把空闲缓冲块还给堆（每轮冷却只做一次）
                long until = pressureUntilMs;
                if (trimmedForUntil != until) {
                    trimmedForUntil = until;
                    releaseIdleBuffers();
                }
                holdAtStage1 = bufferedDurationUs >= STAGE1_US;
            } else if (playbackPositionUs < ESCALATE_AT_US) {
                // 新一集（或拖回开头）重新计时。刻意放在"位置判定"而不是只靠 onStopped()：
                // 切集走的是 release() 路径，不保证会回调 onStopped，只靠它会让第二集起播就直接吃深水位。
                escalated = false;
                holdReported = false;
                holdAtStage1 = bufferedDurationUs >= STAGE1_US;
            } else if (!escalated) {
                // 守门判据＝可用堆 ≥ 水位字节 + 余量（而不是与"创建时的可用堆"自比）
                long need = Math.max((long) deepBytes + RESERVE_BYTES, MIN_AVAIL_BYTES);
                long avail = availBytes();
                if (avail >= need) {
                    escalated = true;
                    Log.i(TAG, "缓冲升档 @ " + (playbackPositionUs / 1_000_000L) + "s：水位 "
                            + (deepMs / 1000) + "s / " + (deepBytes >> 20) + "MB");
                } else {
                    // 守门不过：维持现状水位（宁可不变，也不冒险）。每集只记一次原因，便于事后定位
                    holdAtStage1 = bufferedDurationUs >= STAGE1_US;
                    if (!holdReported) {
                        holdReported = true;
                        Log.i(TAG, "未升档 @ " + (playbackPositionUs / 1_000_000L) + "s：可用堆 "
                                + (avail >> 20) + "MB < 需要 " + (need >> 20) + "MB（水位 "
                                + (deepMs / 1000) + "s/" + (deepBytes >> 20) + "MB）");
                    }
                }
            }
        } catch (Throwable ignored) {
            // 自研判断出任何问题都不影响播放：下面直接交回 Exo 原生逻辑
        }
        if (holdAtStage1) return false;
        return super.shouldContinueLoading(playbackPositionUs, bufferedDurationUs, playbackSpeed);
    }

    /**
     * 只观测、不改行为：记下「起播放行那一刻的缓冲量」。
     *
     * 为什么需要它：Exo 的起播时刻由 shouldStartPlayback 决定，而入参 bufferedDurationUs 对 HLS
     * 是**分片粒度**的（一整片下载完才跃升），所以"门槛 1500ms"在这类源上可能根本不起作用 ——
     * 实测起播中位 1058ms 却低于 1500ms 门槛，正指向这个原因。不把真实缓冲量打出来，
     * 既无法判断门槛该定多少，也无法判断"调门槛"这件事有没有意义。
     *
     * 本方法**原样返回 super 的结果**，不改变任何起播行为。
     */
    @Override
    public boolean shouldStartPlayback(long bufferedDurationUs, float playbackSpeed, boolean rebuffering, long targetLiveOffsetUs) {
        boolean ok = super.shouldStartPlayback(bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs);
        try {
            if (ok && !startReported) {
                startReported = true;
                Log.i(TAG, "起播放行 @ 缓冲=" + (bufferedDurationUs / 1000L) + "ms 门槛="
                        + (rebuffering ? AFTER_REBUFFER_MS : START_PLAYBACK_MS) + "ms"
                        + (rebuffering ? " (重缓冲后)" : "") + " 倍速=" + playbackSpeed);
            }
        } catch (Throwable ignored) {
        }
        return ok;
    }

    /** 每集 stop 时复位升档状态（切集/换源后重新走"先保守 10 秒"） */
    @Override
    public void onStopped() {
        super.onStopped();
        escalated = false;
        holdReported = false;
        startReported = false;
    }

    private void releaseIdleBuffers() {
        try {
            Allocator allocator = getAllocator();
            // 只降尺寸：DefaultAllocator 仅在"新值 < 旧值"时 trim()，且只释放未被引用的块
            if (allocator instanceof DefaultAllocator) {
                ((DefaultAllocator) allocator).setTargetBufferSize(PRESSURE_BYTES);
            }
        } catch (Throwable ignored) {
        }
    }

    private static long availBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
    }
}
