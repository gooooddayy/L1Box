package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * Exo 磁盘缓存策略（L1Box 定制，2026-09-18）。
 *
 * 为什么需要单独一个类：开关状态来自 App 的设置页（Hawk），而 player 模块是被 App 依赖的一方，
 * 不能反向引用 App 的类。所以约定：**App 层负责读偏好并调 {@link #setEnabled(boolean)} 同步进来**，
 * player 模块只认这里的静态状态。
 *
 * 为什么默认开：磁盘缓存不增加任何网络请求 —— 它是"播放器本来就要发的请求，顺便在本地留一份副本"，
 * 命中时反而**减少**请求。所以在"会不会触发站点风控"这件事上它是中性偏正面的，与多线程预取
 * （并发↑、速率↑、乱序、流量数倍）完全不是一类东西。
 *
 * 唯一的红线是**本机端点**，见 {@link #isCacheableUrl(String)}。
 */
public final class ExoCacheConfig {

    private static final String TAG = "L1Cache";

    /**
     * 缓存目录名。**创建点（ExoMediaSourceHelper.newCache）与清理点（本类）共用这一个常量。**
     * 两处各写一份字面串迟早会漂移，而漂移的后果是"清了但没清到"或"清了却还在写"，都极难排查。
     */
    public static final String CACHE_DIR_NAME = "exo-video-cache";

    /** 待删目录前缀：进程启动时把 {@link #CACHE_DIR_NAME} 改名成「前缀 + 时间戳」，再后台慢慢删 */
    private static final String STALE_PREFIX = "exo-video-cache-old-";

    /**
     * 删除前的延迟。删 200~300MB 要占一小段磁盘 IO，冷启动后用户很可能立刻点播，
     * 错开这段时间避免与起播抢 IO。（与 App 里 cleanPlayerCache 的 5 秒延迟同一思路，这里给得更宽）
     */
    private static final long PURGE_DELAY_MS = 10_000L;

    /** 每个顶层分片目录删完后的让位间隔：把一次长 IO 拆成若干短脉冲，进一步降低对播放的干扰 */
    private static final long PURGE_SLICE_GAP_MS = 200L;

    /** 默认开。用户可在设置页关闭，关闭后立即对下一次播放生效（无需重启） */
    private static volatile boolean sEnabled = true;

    /** 缓存上限，默认 256MB（LRU 自动淘汰，不需要用户清理） */
    private static volatile long sMaxBytes = 256L << 20;

    /** 下限 32MB：再小连一集都存不下几片，缓存形同虚设 */
    private static final long MIN_BYTES = 32L << 20;
    /** 上限 1GB：避免在存储紧张的设备上把外置缓存撑满 */
    private static final long MAX_BYTES = 1024L << 20;

    private ExoCacheConfig() {
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static long getMaxBytes() {
        return sMaxBytes;
    }

    public static void setMaxBytes(long bytes) {
        if (bytes < MIN_BYTES) bytes = MIN_BYTES;
        if (bytes > MAX_BYTES) bytes = MAX_BYTES;
        sMaxBytes = bytes;
    }

    /**
     * 该地址能不能进磁盘缓存。**这是本类最重要的一段判断，动它之前先读完注释。**
     *
     * 必须排除「指向本机的地址」，理由是硬的：
     * 净化命中后交给播放器的是 `http://127.0.0.1:9978/l1.m3u8`，网盘源经加固 jar 暴露的是
     * `http://127.0.0.1:9978/proxy?...` —— 这两种端点**每一集都是同一个固定地址**（集与集的区别
     * 只在服务端内存里，不在 URL 上）。而 Exo 的缓存 key 默认取 URI，于是：
     * 第 1 集播完 → 缓存里有这份 URL 的清单与分片 → 第 2 集请求同一个 URL → **直接命中第 1 集的残留**，
     * 观感就是"换集之后还在播上一集"。不做这层排除就开缓存，这是必然发生的回归，不是概率问题。
     *
     * 本地文件与内置资源（file/content/asset）也排除：它们本来就不走网络，缓存只会白写一份到盘。
     * rtmp/rtsp 走的是上游独立的 MediaSource 分支、根本不经过缓存工厂，这里再兜一道防止将来改成走缓存。
     */
    public static boolean isCacheableUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        if (u.isEmpty()) return false;
        if (u.contains("://127.0.0.1") || u.contains("://localhost")
                || u.contains("://[::1]") || u.contains("://0.0.0.0")) {
            return false;
        }
        if (u.startsWith("file:") || u.startsWith("content:") || u.startsWith("asset:")) return false;
        if (u.startsWith("rtmp") || u.startsWith("rtsp")) return false;
        return true;
    }

    // ==================== 进程启动时清理上一次会话的缓存 ====================

    /**
     * 清理上一次会话遗留的缓存。**由 App.onCreate 调用（每次进程冷启动必然经过一次）**。
     *
     * 口径（用户 2026-09-18 拍板）：**软件内退出再进入要保留缓存（能命中），完全退出软件才清掉**，
     * 目的是不让它长期占用户的存储。Android 没有"应用退出"回调，但**"进程启动"是个精确信号**：
     * - 从播放页退回首页 / 切集 / 切后台 —— 进程还在，onCreate 不跑 ⇒ 缓存保留，重看照样命中；
     * - 划掉任务 / 强停 / 被系统回收后再进入 —— 进程重建，onCreate 跑 ⇒ 上次遗留被清掉。
     *
     * 为什么"改名"而不是"直接删"：删 200~300MB 要 0.5~2 秒。同步删会拖慢启动；与播放并发又会争同一个
     * 目录。改名只动一个目录项，**与目录大小无关（毫秒级）**，改完播放器自动新建同名新目录 ——
     * 写的是新目录、删的是旧目录，零竞态。删一半被杀也安全：下次启动会扫到残留的 old 目录继续删（幂等）。
     *
     * 清理全程不抛异常：失败最多是这次没清干净，绝不影响播放。
     */
    public static void purgeStaleOnProcessStart(Context ctx) {
        try {
            File base = cacheBaseDir(ctx);
            if (base == null) return;
            File cur = new File(base, CACHE_DIR_NAME);
            if (cur.exists()) {
                File stale = new File(base, STALE_PREFIX + System.currentTimeMillis());
                if (cur.renameTo(stale)) {
                    Log.i(TAG, "缓存清理：上次会话遗留已转入待删（与目录大小无关，耗时 "
                            + "1 次改名），后台删除中");
                } else {
                    Log.w(TAG, "缓存清理：改名失败，本次跳过（不影响播放，下次启动再试）");
                }
            }
            final File[] pending = collectStale(base);
            if (pending.length == 0) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 延迟再删：冷启动后用户很可能立刻点播，错开这段避免与起播抢磁盘 IO
                        Thread.sleep(PURGE_DELAY_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    long files = 0L, bytes = 0L;
                    for (File f : pending) {
                        long[] r = deleteRecursively(f);
                        files += r[0];
                        bytes += r[1];
                        try {
                            // 每个顶层分片目录之间让一下，把一次长 IO 拆成若干短脉冲
                            Thread.sleep(PURGE_SLICE_GAP_MS);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    Log.i(TAG, "缓存清理完成：删除 " + files + " 个文件 / " + (bytes >> 20)
                            + "MB，存储空间已归还");
                }
            }, "exo-cache-purge").start();
        } catch (Throwable th) {
            Log.w(TAG, "缓存清理异常（已忽略，不影响播放）: " + th);
        }
    }

    /** 缓存目录基准。与 ExoMediaSourceHelper.newCache 的选择顺序保持一致（外置优先，取不到退回内置） */
    private static File cacheBaseDir(Context ctx) {
        if (ctx == null) return null;
        File dir = ctx.getExternalCacheDir();
        if (dir == null) dir = ctx.getCacheDir();
        return dir;
    }

    /** 收集待删的旧缓存目录（可能不止一个：上次删到一半被杀会留下残骸） */
    private static File[] collectStale(File base) {
        File[] list = base.listFiles();
        if (list == null || list.length == 0) return new File[0];
        File[] tmp = new File[list.length];
        int n = 0;
        for (File f : list) {
            if (f != null && f.isDirectory() && f.getName().startsWith(STALE_PREFIX)) tmp[n++] = f;
        }
        if (n == 0) return new File[0];
        if (n == list.length) return tmp;
        File[] out = new File[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /** 递归删除，返回 {文件数, 字节数}（仅供日志；删不掉的留到下次启动再删，不当失败处理） */
    private static long[] deleteRecursively(File f) {
        long files = 0L, bytes = 0L;
        try {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (c == null) continue;
                    if (c.isDirectory()) {
                        long[] r = deleteRecursively(c);
                        files += r[0];
                        bytes += r[1];
                    } else {
                        bytes += c.length();
                        if (c.delete()) files++;
                    }
                }
            }
            f.delete();
        } catch (Throwable ignored) {
        }
        return new long[]{files, bytes};
    }
}
