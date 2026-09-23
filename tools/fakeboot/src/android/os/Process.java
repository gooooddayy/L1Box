package android.os;

import android.util.Log;

/**
 * 替身 Process：仅提供给 spider jar 的类加载器使用（JarToastBlockLoader 按类名拦截）。
 * jar 内加固层的 killProcess / sendSignal 自杀路径在此被无效化，只落后台日志。
 * myPid/myTid/myUid 通过 boot classpath 的真实 Os 取真实值，供 jar 正常逻辑使用。
 */
public class Process {

    public static int myPid() {
        return Os.getpid();
    }

    public static int myTid() {
        return Os.gettid();
    }

    public static int myUid() {
        return Os.getuid();
    }

    public static final void killProcess(int pid) {
        Log.d("JarToast", "已拦截 jar 自杀(killProcess): pid=" + pid);
    }

    public static final void sendSignal(int pid, int signal) {
        Log.d("JarToast", "已拦截 jar 自杀(sendSignal): pid=" + pid + " sig=" + signal);
    }

    public static final void setThreadPriority(int priority) {
    }

    public static final void setThreadPriority(int tid, int priority) {
    }

    public static final int getThreadPriority(int tid) {
        return 0;
    }
}
