package com.github.catvod.crawler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import dalvik.system.DexClassLoader;

/**
 * jar 类加载器拦截层：jar 代码里的 android.widget.Toast / android.os.Process
 * 全部替换为替身版（assets/fakeboot.dex）。jar 弹窗一律不显示、只落后台日志（tag=JarToast）；
 * jar 加固层的 killProcess/sendSignal 自杀被无效化。其余类全部委托宿主原加载链，类身份不受影响。
 */
public class JarToastBlockLoader extends ClassLoader {

    /** 需要替换为替身的类名：Toast=静默弹窗，Process=拦截 jar 自杀 */
    private static final Set<String> FAKE_CLASSES = new HashSet<>(Arrays.asList(
            "android.widget.Toast",
            "android.os.Process"
    ));

    private static volatile DexClassLoader fakeLoader;

    /** 生成套在宿主加载器外层的拦截器；任何一步失败都原样返回 parent，不影响 jar 正常加载 */
    static ClassLoader create(ClassLoader parent) {
        try {
            if (fakeLoader == null) {
                synchronized (JarToastBlockLoader.class) {
                    if (fakeLoader == null) {
                        android.content.Context app = com.github.tvbox.osc.base.App.getInstance();
                        File out = new File(app.getFilesDir(), "fakeboot.dex");
                        if (!out.exists() || out.length() <= 0) {
                            InputStream is = app.getAssets().open("fakeboot.dex");
                            OutputStream os = new FileOutputStream(out);
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                            try {
                                is.close();
                                os.close();
                            } catch (Exception ignored) {
                            }
                        }
                        // W^X 策略（Android 10+ targetSdk 29+）要求 dex 只读，否则告警甚至拒载
                        out.setReadOnly();
                        // parent 传 null：android.* 引用直达系统真实类，替身类签名兼容性由系统类兜底
                        fakeLoader = new DexClassLoader(out.getAbsolutePath(), app.getCacheDir().getAbsolutePath(), null, null);
                        // 自检：替身 dex 真正可加载才计入，否则打日志暴露问题
                        fakeLoader.loadClass("android.widget.Toast");
                        System.out.println("JarToast: 替身层加载成功(Toast/Process 已挂拦截)");
                    }
                }
            }
            return new JarToastBlockLoader(parent, fakeLoader);
        } catch (Throwable th) {
            th.printStackTrace();
            return parent;
        }
    }

    private final ClassLoader fake;

    private JarToastBlockLoader(ClassLoader parent, ClassLoader fake) {
        super(parent);
        this.fake = fake;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (FAKE_CLASSES.contains(name)) {
            try {
                return fake.loadClass(name);
            } catch (Throwable ignored) {
            }
        }
        return super.loadClass(name, resolve);
    }
}
