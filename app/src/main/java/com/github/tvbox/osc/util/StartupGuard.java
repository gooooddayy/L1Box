package com.github.tvbox.osc.util;

import com.github.tvbox.osc.base.App;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 启动崩溃循环自恢复 —— 面向所有设备的"救砖"兜底。
 *
 * 要解决的场景：某台设备（或某份本地数据）在启动阶段反复崩溃，现象是"打开就闪退，
 * 清数据才能用"，普通用户无从下手。造成这种情况的常见原因是本地缓存/临时文件损坏，
 * 而不是程序代码本身。
 *
 * 做法：用一个纯文件计数器（刻意不依赖 Hawk / 数据库 / SharedPreferences —— 崩溃原因
 * 完全可能正是它们）记录"没走完的启动次数"：
 *   1. App.onCreate 最早期（任何业务初始化之前）+1；
 *   2. 启动平稳（首屏出现后仍持续运行一段时间）→ 归零；
 *   3. 连续 3 次都没走完 → 判定为启动崩溃循环，清掉**纯缓存类**数据后放行。
 *
 * 绝不触碰用户数据：订阅配置、收藏、播放历史、账号设置一概不动，最坏情况只是
 * 首页缓存重建（首屏多等一次网络请求）。
 *
 * 全部 try/catch 兜底，任何异常都不影响正常启动。
 */
public final class StartupGuard {
    /** 连续多少次未走完启动即触发自恢复 */
    private static final int THRESHOLD = 3;
    private static final String FILE = "startup_guard.dat";

    private StartupGuard() {
    }

    private static File file() {
        return new File(App.getInstance().getFilesDir(), FILE);
    }

    /** 在 App.onCreate 最早期调用。返回 true 表示本次是"崩溃循环恢复"，已清理缓存 */
    public static boolean begin() {
        try {
            File f = file();
            int n = readCount(f) + 1;
            if (n > THRESHOLD) {
                recover();
                writeCount(f, 0);
                android.util.Log.w("StartupGuard", "检测到启动崩溃循环，已清理首页缓存后放行");
                return true;
            }
            writeCount(f, n);
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 启动平稳后调用（首屏可见并持续运行一段时间），把计数归零 */
    public static void success() {
        try {
            File f = file();
            if (readCount(f) != 0) writeCount(f, 0);
        } catch (Throwable ignored) {
        }
    }

    /** 只清纯缓存（丢了会自动重建），不碰任何用户数据 */
    private static void recover() {
        try {
            File dir = App.getInstance().getFilesDir();
            new File(dir, "home_cache.json").delete();
            new File(dir, "home_cache.json.tmp").delete();
            new File(dir, "startup_guard.dat.tmp").delete();
        } catch (Throwable ignored) {
        }
    }

    private static int readCount(File f) {
        try {
            if (!f.exists()) return 0;
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[16];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return 0;
            return Integer.parseInt(new String(buf, 0, n, "UTF-8").trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void writeCount(File f, int n) {
        try {
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(String.valueOf(n).getBytes("UTF-8"));
            out.close();
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Throwable ignored) {
        }
    }
}
