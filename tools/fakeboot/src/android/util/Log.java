package android.util;

/** 编译桩：仅为 fake Toast 打日志用，运行时由系统真实 Log 接管，绝不打入 dex */
public class Log {
    public static int d(String tag, String msg) {
        return 0;
    }
}
