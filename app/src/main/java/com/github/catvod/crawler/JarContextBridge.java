package com.github.catvod.crawler;

import android.app.Application;
import android.content.Context;

/**
 * 传给 spider jar 的 Context 桥接：getClassLoader 返回拦截层加载器。
 * 加固 jar 通过 context.getClassLoader() 创建内层 DexClassLoader
 * （DexNative.getLoader / startGoProxy 等），桥接后内层加载器继承
 * Toast/Process 拦截链，jar 内层 payload 的弹窗与自杀调用同样被拦截。
 * 必须继承 Application：jar 内部普遍把传入 Context 强转为 Application（(Application) ctx），
 * 用普通 ContextWrapper 会 ClassCastException 导致 jar 加载失败（n 版回归根因）。
 *
 * 说明：源 jar 的"正在初始化本地代理"等初始化横幅是经真实 Activity 的 DecorView
 * addView 挂载（非 Toast、非本桥接的窗口服务），此处不做窗口拦截——横幅的隐藏由
 * ToastSweeper 在显示层统一处理（改 y 坐标推出屏幕，不破坏 jar 业务逻辑）。
 */
public class JarContextBridge extends Application {

    private final ClassLoader loader;

    public JarContextBridge(Context base, ClassLoader loader) {
        attachBaseContext(base);
        this.loader = loader;
    }

    @Override
    public ClassLoader getClassLoader() {
        return loader;
    }
}
