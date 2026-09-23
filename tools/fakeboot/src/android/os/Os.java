package android.os;

/** 编译桩：运行时由系统真实 Os 接管（boot classpath），不打入 dex */
public class Os {
    public static int getpid() { return 0; }
    public static int gettid() { return 0; }
    public static int getuid() { return 0; }
}
