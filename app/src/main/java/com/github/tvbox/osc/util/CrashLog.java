package com.github.tvbox.osc.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import com.github.tvbox.osc.base.App;

/**
 * 崩溃日志本地落盘：崩溃时把完整堆栈写入 filesDir/crashes/，只保留最近 5 份。
 * 设置页「导出崩溃日志」把最新一份复制到 /sdcard/L1Box_crash_log.txt 便于反馈。
 * 全部 try/catch 兜底——崩溃处理器内部绝不允许再抛异常。
 */
public final class CrashLog {
    private static final int KEEP = 5;
    private static final String OUT_NAME = "L1Box_crash_log.txt";

    private CrashLog() {
    }

    private static File dir() {
        return new File(App.getInstance().getFilesDir(), "crashes");
    }

    /** 在 UncaughtExceptionHandler 内调用：落盘后返回是否成功（仅用于日志） */
    public static boolean save(Thread thread, Throwable throwable) {
        try {
            File d = dir();
            if (!d.exists()) d.mkdirs();
            String name = "crash_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()) + ".txt";
            StringBuilder sb = new StringBuilder();
            sb.append("time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()))
                    .append("\nthread: ").append(thread == null ? "?" : thread.getName())
                    .append("\nversion: ").append(versionName()).append("\n\n");
            sb.append(stackOf(throwable));
            Throwable cause = throwable == null ? null : throwable.getCause();
            while (cause != null) {
                sb.append("\nCaused by:\n").append(stackOf(cause));
                cause = cause.getCause();
            }
            FileOutputStream fos = new FileOutputStream(new File(d, name));
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            trimOld(d);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 导出最新一份崩溃日志，返回可读的目标位置描述；无记录返回 null。
     * 三级兜底（保证在 Android 10~14 无存储权限时也能导出）：
     *   1) 直接写 /sdcard 根目录（已授予「所有文件访问」时可用）
     *   2) MediaStore 写入公共「下载」目录（Android 10+ 免权限，文件管理器可见）→ 返回 Download/xxx
     *   3) 应用专属外部目录（无需权限）
     */
    public static String exportLatest() {
        try {
            File latest = latestCrashFile();
            if (latest == null) return null;
            byte[] data = readAll(latest);
            if (data == null) return null;

            // 1) /sdcard 根目录
            try {
                File out = new File(android.os.Environment.getExternalStorageDirectory(), OUT_NAME);
                FileOutputStream fos = new FileOutputStream(out);
                fos.write(data);
                fos.close();
                return out.getAbsolutePath();
            } catch (Throwable ignored) {
            }

            // 2) Android 10+ 免权限：公共下载目录
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                try {
                    android.content.ContentResolver cr = App.getInstance().getContentResolver();
                    cr.delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            android.provider.MediaStore.Downloads.DISPLAY_NAME + "=?", new String[]{OUT_NAME});
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, OUT_NAME);
                    cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
                    android.net.Uri uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri != null) {
                        java.io.OutputStream os = cr.openOutputStream(uri);
                        if (os != null) {
                            os.write(data);
                            os.close();
                            return "Download/" + OUT_NAME;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            // 3) 应用专属外部目录
            File dir = App.getInstance().getExternalFilesDir(null);
            if (dir != null) {
                File out = new File(dir, OUT_NAME);
                FileOutputStream fos = new FileOutputStream(out);
                fos.write(data);
                fos.close();
                return out.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static File latestCrashFile() {
        File[] files = dir().listFiles();
        if (files == null || files.length == 0) return null;
        Arrays.sort(files);
        return files[files.length - 1];
    }

    private static byte[] readAll(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            fis.close();
            return bos.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 设置页展示：是否有崩溃记录 */
    public static boolean hasCrash() {
        File[] files = dir().listFiles();
        return files != null && files.length > 0;
    }

    private static void trimOld(File d) {
        File[] files = d.listFiles();
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files);
        for (int i = 0; i < files.length - KEEP; i++) files[i].delete();
    }

    private static String stackOf(Throwable t) {
        if (t == null) return "(null)";
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String versionName() {
        try {
            return App.getInstance().getPackageManager()
                    .getPackageInfo(App.getInstance().getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
