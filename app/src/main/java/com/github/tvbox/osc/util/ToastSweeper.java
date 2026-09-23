package com.github.tvbox.osc.util;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.github.tvbox.osc.base.App;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Toast 兜底清扫器（类加载器拦截之外的保险层）：
 * 订阅 jar 可经原生 JNI 直接弹真 Toast（绕过任何类加载器拦截），无法在源头拦截，
 * 只能在显示层移除（不禁止、只隐藏，jar 后台逻辑零影响）。
 * 两级清扫，主扫为 Choreographer 帧钩子：
 * ① 帧钩子——回调在每帧绘制管线最前段执行（先于本帧所有窗口 layout/draw），
 *   Toast 窗口被移除时还未渲染任何一帧，人眼零闪现；仅前台且有 Activity 时自循环，
 *   退后台自动停摆省电。
 * ② 250ms 轮询兜底——覆盖极端情况，并负责在后台期间重新拉起帧循环。
 * 反射对象全部静态缓存（只查一次），单次扫描为微秒级，不影响搜索/滑动流畅度。
 * 反射失败自动停用，不影响正常运行。
 */
public class ToastSweeper {

    private static final String TAG = "ToastSweeper";

    /** 前台 Activity 计数：>0 时帧循环自转，退后台停摆 */
    private static int resumedCount = 0;
    private static boolean failed = false;
    private static boolean inited = false;
    private static boolean frameScheduled = false;
    /** 当前前台 Activity（decor 监护目标） */
    private static Activity topActivity;
    /** 已隐藏的树内横幅：只透明不删除，每帧强制保持隐藏，避免 jar 重绘拉锯 */
    private static final java.util.HashSet<View> hiddenBanners = new java.util.HashSet<View>();
    /** 已设置 add 监听的 content 容器，避免重复设置 */
    private static final java.util.HashSet<Integer> hookedContents = new java.util.HashSet<Integer>();

    /** 宿主/系统类前缀白名单：这些包的视图不是 jar 塞的 */
    private static final String[] HOST_CLASS_PREFIXES = {
            "android.", "androidx.", "com.google.android",
            "com.github.tvbox.osc", "com.scwang."
    };

    private static boolean isHostClass(View v) {
        String n = v.getClass().getName();
        for (String p : HOST_CLASS_PREFIXES) {
            if (n.startsWith(p)) return true;
        }
        return false;
    }

    // ---- 反射缓存（初始化时查询一次） ----
    private static Object wmGlobal;
    private static Field fViews, fParams;
    private static Method mRemoveView, mUpdateLayout;

    /**
     * 逐节点诊断日志总开关（默认关闭）。
     *
     * isBannerViewGroup 在 Choreographer 每帧扫描中被逐个 ViewGroup 调用，开启后
     * 每秒会往 logcat 写上千行：既把其他日志全挤出缓冲区（排查时看不到任何有用信息），
     * 又要在低端机上为字符串拼接与日志写入付出真实的 CPU 开销。
     * 只在需要重新取证"横幅到底是什么"时临时改为 true，用完改回。
     */
    private static final boolean DEBUG = false;

    /**
     * 帧循环扫描节流间隔（纳秒）。全树递归扫描是 O(视图数) 的重活，原先每帧执行
     * （60Hz 屏每秒 60 次、120Hz 屏每秒 120 次），在前台持续占用 CPU。
     * 按时间戳节流到约 11Hz 后开销降至约 1/6~1/11；用时间戳而非帧计数，
     * 可保证高刷屏与普通屏行为一致。
     * 注意：横幅在 addView 瞬间由 OnHierarchyChangeListener 快速路径即时隐藏，
     * 不经过此处，因此降频不改变横幅的响应时机。
     */
    private static final long SWEEP_INTERVAL_NANOS = 90_000_000L;
    private static long lastSweepNanos = 0L;

    /** 宿主自有提示语（前缀匹配，含动态拼接文案的前缀部分），新增宿主 Toast 时同步维护 */
    private static final String[] HOST_TEXTS = {
            "再按一次退出程序", "url加载失败", "倍速参数异常", "删除成功", "名称不要过长",
            "后台播放已关闭", "图片识别失败", "地址不能为空", "导入失败", "已切换到",
            "已切换线路", "已删除", "已加入收藏夹", "已复制", "已移除收藏夹",
            "已跳过片头 ", "已从 ",
            "当前为无痕浏览", "当前已经是最后一集", "当前已经是第一集", "当前没有可投屏",
            "投屏失败", "投屏暂不可用", "播放出错", "文件不存在，无法投屏", "文件为空或无法读取",
            "文件权限被永久拒绝", "无法打开文件选择器", "无法读取文件", "暂停失败", "暂无评分信息",
            "更新订阅失败", "未找到应用", "未获取到本机局域网地址", "未识别到二维码",
            "检测到订阅配置异常", "正在重新拉取线路站点", "没有内置字幕", "没有可切换的音轨",
            "没有音轨", "线路切换失败", "继续播放失败", "缓存已清空", "网络不佳",
            "获取播放信息错误", "获取权限失败", "获取相机权限失败", "解析来自", "订阅地址与",
            "订阅失败", "订阅更新失败", "订阅格式不正确", "设备无响应", "该地址无法投屏",
            // 订阅解析给出的**具体**结论（订阅线第二批）：内容坏了但有缓存可回退、
            // 内容坏了且无缓存、网络层失败、订阅里一个站点都没有。
            // 必须进白名单：isHostToast 是**允许列表**，不在这里的 Toast 一律按 jar 弹窗移除。
            "订阅内容异常", "订阅拉取失败", "订阅里没有可用站点",
            "该视频经过本地代理", "请先播放视频后再投屏", "请授予相机权限后重试", "请输入名称",
            "请输入搜索内容", "请输入订阅地址", "资源异常", "跳转失败", "部分权限未正常授予",
            "需要文件访问权限", "首页加载超时",
            // "正在初始化播放源，自动重试…"（搜索页 0 结果与初始化重叠时的自动重搜提示）。
            // 必须进白名单：isHostToast 是**允许列表**，不在这里的 Toast 一律按 jar 弹窗移除。
            // 注意别写成 jar 横幅那条"正在初始化本地代理"，两者只差两个字，极易误加。
            "正在初始化播放源"
    };

    /**
     * 源 jar（wexguard 加固）在换源/首次加载源时，经 WindowManager.addView 挂的
     * 系统级悬浮横幅（覆盖到状态栏高度，非 TYPE_TOAST）。文案随源不同而不同，
     * 常见几类已知文案如下。命中则把窗口推出屏幕（只改 y 坐标、不 removeView），
     * 从而「后台照常初始化 Go 代理、前端不显示」，且不破坏 jar 的 addView/removeView 生命周期。
     */
    private static final String[] BANNER_TEXTS = {
            "正在初始化本地代理", "正在等待 Go 服务响应", "正在等待Go服务响应",
            "go多线程启动失败", "Go多线程启动失败", "本地代理", "Go 服务"
    };

    /**
     * 树内横幅文本特征：以「正在」开头、或包含若干源 jar 常用关键词。
     *
     * 2026-09-11 事故：原先还带一条 `.*%$`（以百分号结尾即算横幅）。播放器手势调节时
     * 会显示「亮度50%」「音量30%」——正好命中该规则，而播放器整块布局又满足
     * 「顶部 + 宽≥400 + 含 ProgressBar(SeekBar)」，于是整块播放器被当成横幅隐藏成
     * 0 尺寸+INVISIBLE，且被 keepHiddenBanners 永久保持：表现为「上下滑一下即黑屏、
     * 按钮全消失、怎么点都没反应」。百分比不能单独作为横幅特征，已删除该分支。
     */
    private static final java.util.regex.Pattern BANNER_HINT_RE =
            java.util.regex.Pattern.compile("^正在.*|服务响应|启动失败|读取.*配置|下载.*配置|初始化.*代理");

    /** 宿主自有视图类前缀：只可能出现在宿主自己的布局树里（jar 横幅全由标准控件拼成） */
    private static final String[] HOST_OWNED_PREFIXES = {
            "com.github.tvbox.osc.", // 宿主与内置控制条：MyVideoView/VodController/字幕视图…
            "xyz.doikki."            // 内置播放内核
    };

    /**
     * 候选 ViewGroup 内是否含宿主自有视图。jar 的初始化横幅全部由标准控件
     * （LinearLayout/TextView/ProgressBar）拼成；一旦命中宿主自有类，说明这是宿主自己的
     * 布局（最典型的就是播放器整块），绝不能整体隐藏——只继续往里找 jar 塞在其中的横幅。
     */
    private static boolean containsHostView(View v, int depth) {
        if (v == null || depth > 6) return false;
        String n = v.getClass().getName();
        for (String p : HOST_OWNED_PREFIXES) {
            if (n.startsWith(p)) return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsHostView(g.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    public static void start() {
        try {
            App.getInstance().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(Activity a) {
                    resumedCount++;
                    topActivity = a;
                    scheduleFrame(); // 回前台立即拉起帧循环
                }

                @Override
                public void onActivityPaused(Activity a) {
                    resumedCount = Math.max(0, resumedCount - 1);
                    if (topActivity == a) topActivity = null;
                }
                // 其余生命周期与本逻辑无关，空实现
                @Override public void onActivityCreated(Activity a, android.os.Bundle b) { }
                @Override public void onActivityStarted(Activity a) { }
                @Override public void onActivityStopped(Activity a) { }
                @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle outState) { }
                @Override public void onActivityDestroyed(Activity a) { }
            });
        } catch (Throwable ignored) {
        }
        // 兜底轮询（250ms，成本微乎其微）
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!failed) sweep();
                h.postDelayed(this, 250);
            }
        }, 2000);
    }

    /** 幂等地请求下一帧回调；doFrame 内前台自续，后台停摆 */
    private static void scheduleFrame() {
        if (failed || frameScheduled) return;
        frameScheduled = true;
        Choreographer.getInstance().postFrameCallback(FRAME_LOOP);
    }

    private static final Choreographer.FrameCallback FRAME_LOOP = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameScheduled = false;
            if (failed) return;
            // 窗口级扫描每帧执行：mViews 列表极小、单次微秒级，但只有每帧扫才能保证
            // jar 弹窗（TYPE_TOAST）在首帧渲染前被移除（零闪现）。2026-09-11：随 r 版
            // 节流误降频导致"本接口免费分享"闪现回归，特此拆分恢复。
            sweepWindows();
            // 节流扫描（约 11Hz）：全树递归 O(视图数) 是重活，不必每帧做；
            // 横幅的即时隐藏走 add 监听快速路径，不受此节流影响
            if (frameTimeNanos - lastSweepNanos >= SWEEP_INTERVAL_NANOS) {
                lastSweepNanos = frameTimeNanos;
                sweepDecor();
            }
            if (resumedCount > 0)
                scheduleFrame(); // 前台：持续调度；退后台：循环自然停摆
        }
    };

    private static boolean initReflection() {
        if (inited || failed) return inited;
        try {
            Class<?> cls = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = cls.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            wmGlobal = getInstance.invoke(null);
            fViews = cls.getDeclaredField("mViews");
            fViews.setAccessible(true);
            fParams = cls.getDeclaredField("mParams");
            fParams.setAccessible(true);
            mRemoveView = cls.getDeclaredMethod("removeView", View.class, boolean.class);
            mRemoveView.setAccessible(true);
            // 注意签名是 ViewGroup.LayoutParams（非 WindowManager.LayoutParams），
            // 且为可选能力：查找失败只降级（推屏外不生效），绝不能拖死整个清扫器
            try {
                mUpdateLayout = cls.getDeclaredMethod("updateViewLayout", View.class,
                        ViewGroup.LayoutParams.class);
                mUpdateLayout.setAccessible(true);
            } catch (Throwable t) {
                mUpdateLayout = null;
            }
            inited = true;
        } catch (Throwable th) {
            failed = true; // 反射被 ROM 限制时自动停用, 不影响正常使用
            android.util.Log.d(TAG, "清扫器停用: " + th);
        }
        return inited;
    }

    private static void sweep() {
        sweepDecor(); // 不依赖反射，任何情况下都要跑（也不被窗口清扫反射失败连累）
        sweepWindows();
    }

    /**
     * 窗口级清扫：反射读 WindowManagerGlobal.mViews（列表极小，单次微秒级）。
     * 只处理独立窗口（TYPE_TOAST 弹窗、全局悬浮横幅），每帧调用也不构成负担；
     * 节流会导致弹窗在移除前渲染出帧（闪现），故此处不在节流范围内。
     */
    private static void sweepWindows() {
        if (!initReflection()) return;
        try {
            @SuppressWarnings("unchecked")
            ArrayList<View> views = (ArrayList<View>) fViews.get(wmGlobal);
            @SuppressWarnings("unchecked")
            ArrayList<WindowManager.LayoutParams> params =
                    (ArrayList<WindowManager.LayoutParams>) fParams.get(wmGlobal);
            View toRemove = null;
            // 扫描与移除都在主线程执行, 与窗口显示线程一致, 无并发问题
            for (int i = 0; i < params.size() && i < views.size(); i++) {
                WindowManager.LayoutParams p = params.get(i);
                if (p == null) continue;
                View v = views.get(i);
                if (v == null) continue;

                // ① 源 jar 的初始化横幅（覆盖状态栏的全局悬浮窗）→ 推出屏幕外（方案甲，不 removeView）
                if (isBannerWindow(p, v)) {
                    pushOffScreen(v, p);
                    continue; // 不 break：横幅可能多个，且不阻塞后续 Toast 清扫
                }

                // ② 非宿主 Toast → removeView（原有逻辑）
                if (p.type == WindowManager.LayoutParams.TYPE_TOAST && !isHostToast(v)) {
                    toRemove = v;
                    break; // 一次移一个, 下一帧继续（弹窗为孤立增量, 常规情况每帧至多一个）
                }
            }
            if (toRemove != null) {
                try {
                    mRemoveView.invoke(wmGlobal, toRemove, true);
                    android.util.Log.d(TAG, "已移除 jar 弹窗: " + textOf(toRemove));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
            // 单次异常下一帧重试; 反射结构性失败由 initReflection 的 failed 标记兜底
        }
    }

    /**
     * DecorView 监护：窗口普查证实源横幅不是独立窗口，而是 jar 塞进 Activity
     * DecorView 的子 View（addView/addContentView），故在此逐帧监护：
     * ① decor 直加子 View（index>=1）——宿主从不往 decor 加子 View（全源码 grep 已证），
     *   一律按可疑处理，非白名单类直接隐藏；
     * ② content 容器额外子 View（index>=1，addContentView 塞入）——宿主有合法用途
     *   （PlayFragment 的投屏 WebView），仅隐藏非宿主/系统类的。
     * 隐藏 = alpha 0 + GONE，View 仍挂在树上，jar 的进度更新/自行移除零感知（只隐藏不禁止）。
     */
    private static void sweepDecor() {
        Activity a = topActivity;
        if (a == null) return;
        try {
            ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            for (int i = decor.getChildCount() - 1; i >= 1; i--) {
                hideJarBanner(decor.getChildAt(i));
            }
            View content = decor.findViewById(android.R.id.content);
            if (content instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) content;
                for (int i = g.getChildCount() - 1; i >= 1; i--) {
                    View c = g.getChildAt(i);
                    if (!isHostClass(c)) hideJarBanner(c);
                }
                // 2026-09-10：横幅真身为 jar 塞进 Activity 根布局里的标准控件
                //（LinearLayout + TextView + ProgressBar），递归扫描后代并隐藏。
                scanTreeForBanners(g, 0);
                hookContentHierarchy(g);
            }
            keepHiddenBanners();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 在 Activity content 容器上挂 add 监听：jar 每次 addView 插入横幅的瞬间
     * 就立即隐藏，比 Choreographer 每帧扫描更早，可消除 add→扫描 之间的闪现帧。
     */
    private static void hookContentHierarchy(final ViewGroup content) {
        if (content == null || !hookedContents.add(System.identityHashCode(content))) return;
        try {
            content.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    // addView 回调触发时，子 View 尚未完成 layout/draw；但 jar 已把结构和
                    // ProgressBar 塞好。这里用更激进的「add 瞬间识别」：只要它是 content
                    // 下的标准 LinearLayout、位于顶部、结构简单且含 ProgressBar，就直接隐藏，
                    // 不等文案填充/尺寸确定，从而消灭 add→首帧绘制 之间的闪现。
                    if (DEBUG) {
                        android.util.Log.d(TAG, "hierarchy-added cls=" + child.getClass().getName()
                                + " parent=" + parent.getClass().getName()
                                + " childCount=" + (child instanceof ViewGroup ? ((ViewGroup) child).getChildCount() : 0));
                    }
                    if (child instanceof ViewGroup && isLikelyBannerAtAdd((ViewGroup) child)) {
                        hideTreeBanner(child);
                        android.util.Log.d(TAG, "hierarchy-add 瞬间隐藏 banner: " + child.getClass().getName());
                    } else if (child instanceof ViewGroup) {
                        scanTreeForBanners(child, 0);
                    }
                }

                @Override
                public void onChildViewRemoved(View parent, View child) {
                    // 不需要处理：remove 后自然不可见
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void hideJarBanner(View c) {
        if (c == null) return;
        if (isHostClass(c)) return;
        if (containsHostView(c, 0)) return; // 内部挂着宿主视图 → 是宿主布局，不是 jar 横幅
        if (c.getAlpha() != 0f || c.getVisibility() != View.GONE) {
            c.setAlpha(0f);
            c.setVisibility(View.GONE);
            android.util.Log.d(TAG, "已隐藏源横幅: " + c.getClass().getName());
        }
    }

    // ---- 树内横幅清扫（jar 把标准控件 addView 进 Activity 根布局） ----

    private static final class BannerInfo {
        boolean hasProgressBar;
        boolean hasHintText;
    }

    /**
     * 递归扫描 Activity 视图树，找到 jar 塞入的初始化横幅后隐藏。
     * 横幅特征：ViewGroup，位于屏幕顶部，包含 ProgressBar + 命中 {@link #BANNER_HINT_RE} 的 TextView。
     */
    private static void scanTreeForBanners(View v, int depth) {
        if (depth > 4 || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (!(c instanceof ViewGroup)) continue;
            // 宿主自有布局（最典型＝整块播放器）：只往里找、绝不整体隐藏。
            // 误判一次就会把视频画面一起打成 0 尺寸+INVISIBLE，且被永久保持 → 用户侧就是
            //「黑屏 + 所有按钮消失 + 点不动」，只能杀进程恢复。
            if (containsHostView(c, 0)) {
                scanTreeForBanners(c, depth + 1);
            } else if (isBannerViewGroup((ViewGroup) c)) {
                hideTreeBanner(c);
            } else {
                scanTreeForBanners(c, depth + 1);
            }
        }
    }

    /**
     * addView 瞬间判断：此时 View 还未 layout，width 为 0，不能依赖尺寸和文案。
     * banner 的强特征是：标准 LinearLayout、被直接 add 到 content、结构简单、
     * 位于屏幕顶部、且内部已经塞进 ProgressBar。
     */
    private static boolean isLikelyBannerAtAdd(ViewGroup g) {
        if (g == null) return false;
        String cls = g.getClass().getName();
        if (!"android.widget.LinearLayout".equals(cls)) return false;
        if (containsHostView(g, 0)) return false;   // 宿主自有布局一律放过
        int n = g.getChildCount();
        if (n < 1 || n > 4) return false;          // 宿主根布局子 View 数量远大于此
        int[] loc = new int[2];
        g.getLocationOnScreen(loc);
        if (loc[1] > 300) return false;            // 必须在顶部，适配不同状态栏高度
        BannerInfo info = new BannerInfo();
        collectBannerInfo(g, info, 0);
        return info.hasProgressBar;
    }

    private static boolean isBannerViewGroup(ViewGroup g) {
        if (g == null) return false;
        int[] loc = new int[2];
        g.getLocationOnScreen(loc);
        int w = g.getWidth();
        BannerInfo info = new BannerInfo();
        collectBannerInfo(g, info, 0);
        boolean isBanner = loc[1] <= 400 && w >= 400 && info.hasProgressBar && info.hasHintText;
        if (DEBUG) {
            android.util.Log.d(TAG, "banner-check cls=" + g.getClass().getName()
                    + " loc=" + loc[0] + "," + loc[1] + " w=" + w
                    + " hasPb=" + info.hasProgressBar + " hasHint=" + info.hasHintText
                    + " result=" + isBanner);
        }
        return isBanner;
    }

    private static void collectBannerInfo(View v, BannerInfo info, int depth) {
        if (depth > 5 || info.hasProgressBar && info.hasHintText) return;
        if (v instanceof ProgressBar) info.hasProgressBar = true;
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && BANNER_HINT_RE.matcher(t.toString().trim()).find()) {
                info.hasHintText = true;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectBannerInfo(g.getChildAt(i), info, depth + 1);
            }
        }
    }

    private static void hideTreeBanner(View c) {
        if (c == null) return;
        hiddenBanners.add(c);
        // 四重隐藏：尺寸为 0 + 推离屏幕 + 全透明 + INVISIBLE。
        // 尺寸为 0 在 layout 阶段直接生效，新 banner 诞生的第一帧就不会被画出来。
        boolean changed = false;
        ViewGroup parent = (ViewGroup) c.getParent();
        ViewGroup.LayoutParams lp = c.getLayoutParams();
        if (parent != null) {
            try {
                ViewGroup.LayoutParams zeroLp;
                if (parent instanceof FrameLayout) {
                    zeroLp = new FrameLayout.LayoutParams(0, 0);
                } else {
                    zeroLp = new ViewGroup.LayoutParams(0, 0);
                }
                c.setLayoutParams(zeroLp);
                changed = true;
            } catch (Throwable ignored) {
                // 降级：直接改原 lp
                if (lp != null && (lp.width != 0 || lp.height != 0)) {
                    lp.width = 0;
                    lp.height = 0;
                    c.setLayoutParams(lp);
                    changed = true;
                }
            }
        }
        if (c.getTranslationY() > -8000f || c.getTranslationY() == 0f) {
            c.setTranslationY(-10000f);
            changed = true;
        }
        if (c.getAlpha() != 0f || c.getVisibility() != View.INVISIBLE) {
            c.setAlpha(0f);
            c.setVisibility(View.INVISIBLE);
            changed = true;
        }
        if (changed) {
            android.util.Log.d(TAG, "已隐藏树内横幅: " + textOf(c));
        }
    }

    /** 对已经隐藏过的横幅每帧强制保持「0 尺寸 + 屏外 + 透明 + INVISIBLE」，防止 jar 恢复 */
    private static void keepHiddenBanners() {
        if (hiddenBanners.isEmpty()) return;
        java.util.Iterator<View> it = hiddenBanners.iterator();
        while (it.hasNext()) {
            View c = it.next();
            if (c == null) { it.remove(); continue; }
            try {
                if (c.getParent() == null) { it.remove(); continue; }
                ViewGroup.LayoutParams lp = c.getLayoutParams();
                if (lp != null && (lp.width != 0 || lp.height != 0)) {
                    lp.width = 0;
                    lp.height = 0;
                    c.setLayoutParams(lp);
                }
                if (c.getTranslationY() > -8000f || c.getTranslationY() == 0f) c.setTranslationY(-10000f);
                if (c.getAlpha() != 0f) c.setAlpha(0f);
                if (c.getVisibility() != View.INVISIBLE) c.setVisibility(View.INVISIBLE);
            } catch (Throwable ignored) {
                it.remove();
            }
        }
    }

    /** 是否源 jar 的初始化横幅窗口：视图树内命中已知横幅文案（不限窗口类型，宿主自有窗口放行） */
    private static boolean isBannerWindow(WindowManager.LayoutParams p, View v) {
        if (isHostToast(v)) return false; // 宿主自有窗口放行
        StringBuilder sb = new StringBuilder();
        collectText(v, sb);
        String text = sb.toString();
        for (String b : BANNER_TEXTS) {
            if (text.contains(b)) return true;
        }
        return false;
    }

    /** 方案甲：隐藏横幅窗口（推屏幕外 + 全透明，不 removeView，jar 生命周期零感知） */
    private static void pushOffScreen(View v, WindowManager.LayoutParams p) {
        if (p.y <= -4000 && p.alpha == 0f) return; // 已处理，幂等（jar 若还原，下一帧会再处理）
        if (mUpdateLayout == null) return; // 能力缺失时降级：不影响清扫器整体
        float oldAlpha = p.alpha;
        int oldY = p.y;
        p.y = -6000;
        p.alpha = 0f; // 双保险：透明度不受 gravity/坐标规则影响，必然生效
        try {
            // 必须经 updateViewLayout 让参数生效（仅改 mParams 内对象不会触发重排）
            mUpdateLayout.invoke(wmGlobal, v, p);
            android.util.Log.d(TAG, "已隐藏源横幅: " + textOf(v));
        } catch (Throwable ignored) {
            p.y = oldY; // 更新失败时还原，避免参数与实际窗口状态不一致
            p.alpha = oldAlpha;
        }
    }

    private static String textOf(View v) {
        StringBuilder sb = new StringBuilder();
        collectText(v, sb);
        String t = sb.toString().replace('\n', ' ').trim();
        return t.isEmpty() ? "(无文本)" : (t.length() > 40 ? t.substring(0, 40) : t);
    }

    /** 提取 Toast 视图内全部文本, 命中宿主白名单则放行；无任何文本按 jar 弹窗处理 */
    private static boolean isHostToast(View v) {
        StringBuilder sb = new StringBuilder();
        collectText(v, sb);
        String text = sb.toString();
        for (String host : HOST_TEXTS) {
            if (text.contains(host)) return true;
        }
        return false;
    }

    private static void collectText(View v, StringBuilder sb) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) sb.append(t).append('\n');
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectText(g.getChildAt(i), sb);
        }
    }
}
