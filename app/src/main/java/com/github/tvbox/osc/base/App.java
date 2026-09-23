package com.github.tvbox.osc.base;

import android.content.ComponentCallbacks2;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.multidex.MultiDexApplication;

import com.github.catvod.crawler.JsLoader;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.activity.MainActivity;
import com.github.tvbox.osc.util.DeviceProfile;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HomeConfigLoader;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MemoryGuard;
import com.github.tvbox.osc.util.NetworkMonitor;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.StartupGuard;
import com.github.tvbox.osc.util.Utils;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;

import java.util.ArrayList;
import java.util.List;

import cat.ereza.customactivityoncrash.config.CaocConfig;
import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;
import xyz.doikki.videoplayer.exo.ExoCacheConfig;
import xyz.doikki.videoplayer.exo.L1LoadControl;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static App instance;

    private static P2PClass p;
    public static String burl;

    public boolean isNormalStart;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // 崩溃后系统会直接重启到非启动页 Activity（跳过 SplashActivity），
        // 此时 BaseActivity 旧逻辑检测 isNormalStart=false 会 AppUtils.relaunchApp(true)
        // 杀死自己重启（日志铁证: Sending signal SIG:9 发生在启动后 150ms 内），
        // 造成"闪退后连杀两次、第三次才活"。Application 每次启动必然先跑，
        // 在此标记正常启动即可彻底移除该自杀重启链路。
        isNormalStart = true;
        // 崩溃循环自恢复：必须放在一切业务初始化之前 —— 它要记录的正是"这次启动有没有走完"。
        // 连续多次没走完说明启动阶段存在（很可能是本地数据引起的）反复崩溃，届时自动清缓存放行。
        StartupGuard.begin();
        // 设备能力分级：并发等参数按设备档位取值，先于一切业务初始化
        DeviceProfile.init(this);
        Log.i("DeviceProfile", "设备分级: " + DeviceProfile.describe());
        initParams();
        // OKGo
        OkGoHelper.init(); //台标获取
        // 网络状态监听：断网/切网可感知，用于弱网快速失败与联网后自动重试
        NetworkMonitor.start();
        EpgUtil.init();
        // 初始化Web服务器
        ControlManager.init(this);
        //初始化数据库
        AppDataManager.init();
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();
        AutoSizeConfig.getInstance()
                .setExcludeFontScale(true)
                .setCustomFragment(true)
                .getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        PlayerHelper.init();
        // 进程冷启动 ⇒ 上一次会话已完全退出 ⇒ 把遗留的磁盘缓存清掉，避免长期占用用户存储。
        // 口径：**软件内退出再进入保留缓存（能命中），完全退出软件才清**。Android 没有"应用退出"
        // 回调，但"进程启动"是精确信号 —— 退播放页/切集/切后台进程都还在，onCreate 不跑，缓存自然保留。
        // 位置：此刻所有播放器都还没实例化，改名不会与 SimpleCache 的创建争同一目录。
        ExoCacheConfig.purgeStaleOnProcessStart(this);
        QuickJSLoader.init();
        // 冷启动提速：cleanPlayerCache 是递归删缓存目录，缓存大时可达秒级，放后台不阻塞主线程。
        // 延迟 5s 再清，避免极端情况下与用户秒进播放产生缓存文件读写竞态
        new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
            }
            FileUtils.cleanPlayerCache();
        }, "cache-clean").start();
        initCrashConfig();
        initExitGuard();
        // Toast 兜底清扫器：拦截 jar 经原生 JNI 弹的真 Toast（类加载器拦截链之外的保险层）
        com.github.tvbox.osc.util.ToastSweeper.start();
        Utils.initTheme();
        initBackgroundGuard();
        // 启动平稳判定：首屏出现后仍持续运行 10s 未崩溃，才算本次启动走完，崩溃循环计数归零。
        // 用"延迟判定"而不是"Activity 一可见就算成功"，是为了让 Splash→首页 阶段的崩溃也能被数到。
        try {
            new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    StartupGuard.success();
                }
            }, 10000L);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 前后台主动释放图片缓存——不把预防措施交给系统的脾气。
     *
     * 实测部分 ROM（OPPO/ColorOS）在应用退到后台时并不下发 TRIM_MEMORY_UI_HIDDEN，
     * 只靠 onTrimMemory 等于把预防措施交给系统的脾气。这里改用生命周期库的标准
     * 前后台判定（ON_STOP 自带约 700ms 去抖），任何设备退后台都能生效；
     * 与 onTrimMemory 互为补充，MemoryGuard 内部有节流，不会重复释放。
     */
    private void initBackgroundGuard() {
        try {
            ProcessLifecycleOwner.get().getLifecycle().addObserver(new LifecycleObserver() {
                @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
                public void onAppBackground() {
                    MemoryGuard.releaseImageCache();
                    Log.i("MemoryGuard", "应用退到后台，已释放图片内存缓存");
                }
            });
        } catch (Throwable th) {
            // 安装失败不影响启动
            Log.e("MemoryGuard", "前后台监听安装失败(不影响运行): " + th);
        }
    }

    /**
     * 内存压力响应：跨设备预防的关键一环。
     *
     * 高配机上这个回调几乎不会带严重等级触发，所以"本机压测通过"代表不了
     * 小内存设备——那些机器切到后台后，正是被系统按内存占用挑出来杀掉的。
     * 这里只做零体验损失的清理：UI 已不可见时放掉图片缓存，丢了下次重新
     * 解码即可，却能明显降低整个进程被回收的概率。
     *
     * 刻意不在前台等级（TRIM_MEMORY_RUNNING_*）清理：前台清空会让列表滚动
     * 时图片反复重新解码而闪烁，那是拿一个问题换另一个问题。
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // 内存已紧张（RUNNING_LOW / CRITICAL 及以上）：把并发档位降到低档，只影响之后新建的池，
        // 正在进行的播放与列表完全不受影响。刻意不清图片缓存 —— 前台清空会让列表滚动时
        // 反复重新解码而闪烁，那是拿一个问题换另一个问题。
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            DeviceProfile.markMemoryPressure();
            // 播放缓冲同步回落：把空闲缓冲块还给堆，并进入冷却期不再加深水位
            L1LoadControl.onMemoryPressure();
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            MemoryGuard.releaseImageCache();
            Log.i("MemoryGuard", "UI 不可见，已释放图片内存缓存 level=" + level);
        }
    }

    /**
     * 系统内存紧张的通用回调（ComponentCallbacks 的基础契约，全平台都会调）。
     *
     * 为什么不能只靠 onTrimMemory：实测部分 ROM 在退后台时并不下发 TRIM_MEMORY_UI_HIDDEN，
     * 只靠它等于把预防措施交给系统的脾气。onLowMemory 覆盖面更广，两者互为补充。
     * 这里做两件零体验损失的事：并发档位降到低档（只影响之后新建的池）+ 放掉图片缓存。
     * 回调在主线程，MemoryGuard 内部有节流，不会反复抖动。
     */
    @Override
    public void onLowMemory() {
        super.onLowMemory();
        DeviceProfile.markMemoryPressure();
        L1LoadControl.onMemoryPressure();
        MemoryGuard.releaseImageCache();
        Log.i("MemoryGuard", "系统内存紧张(onLowMemory)：已降并发档位并释放图片缓存");
    }

    /**
     * 第三层自杀防线：订阅 jar 加固层除 killProcess 外还可能走 System.exit / Runtime.exit
     * （不经类加载器拦截，但会经过 SecurityManager.checkExit）。安装只覆写 checkExit 的
     * SecurityManager：调用栈里存在 jar 加载器帧（非 boot、非宿主 PathClassLoader）时抛
     * SecurityException 拦截退出，宿主自身退出不受影响。安装失败只记日志，不影响正常运行。
     */
    private void initExitGuard() {
        try {
            if (System.getSecurityManager() != null) return;
            ClassLoader hostLoader = getClassLoader();
            System.setSecurityManager(new SecurityManager() {
                @Override
                public void checkExit(int status) {
                    for (Class<?> c : getClassContext()) {
                        ClassLoader l = c.getClassLoader();
                        if (l != null && l != hostLoader && !(l instanceof dalvik.system.PathClassLoader)) {
                            Log.i("ExitGuard", "已拦截 jar 自杀式退出 status=" + status + " from " + c.getName());
                            throw new SecurityException("jar exit blocked");
                        }
                    }
                }
            });
        } catch (Throwable th) {
            Log.e("ExitGuard", "安装失败(不影响运行): " + th);
        }
    }

    private void initParams() {
        // Hawk
        Hawk.init(this).build();
        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            Hawk.put(HawkConfig.DEBUG_OPEN, false); // 仅在开启过时才写，避免每次启动一次存储写
        }

        putDefault(HawkConfig.HOME_REC, 0);                  //推荐: 0=豆瓣热播, 1=站点推荐(已移除)
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 1) {        //旧版选过站点推荐,回退到豆瓣热播
            Hawk.put(HawkConfig.HOME_REC, 0);
        }
        putDefault(HawkConfig.PLAY_TYPE, 2);                 //播放器: 0=系统, 1=IJK, 2=Exo
        putDefault(HawkConfig.IJK_CODEC, "硬解码");           //IJK解码: 软解码, 硬解码
        putDefault(HawkConfig.BACKGROUND_PLAY_TYPE,2);           //后台播放: 0 关闭,1 开启,2 画中画
        //安全DNS: 0=关闭, 1=腾讯, 2=阿里, 3=360, 4=Google, 5=AdGuard, 6=Quad9
        // 出厂默认**开启**：默认关闭时，"DNS 被污染"的整类源在默认状态下完全没有可用性。
        // 敢默认开启的前提是"DoH 失败自动回退系统 DNS + 本地/私有地址一律绕开 DoH + 连续失败熔断"
        // （util/L1FallbackDns 与 util/L1DnsPolicy，均有桌面用例表），三条缺一不可。
        putDefault(HawkConfig.DOH_URL, OkGoHelper.DEFAULT_DOH);
        upgradeDohDefault();
        putDefault(HawkConfig.PLAY_SCALE, 0);                //画面缩放: 0=默认, 1=16:9, 2=4:3, 3=填充, 4=原始, 5=裁剪
        putDefault(HawkConfig.HISTORY_NUM, 2);                //历史记录数量: 0=30, 1=50, 2=70
        putDefaultApi();
    }

    private void putDefaultApi() {
        String[] apis = getResources().getStringArray(R.array.api);
        if(!Hawk.contains(HawkConfig.API_URL) && !Hawk.contains(HawkConfig.SUBSCRIPTIONS) && !TextUtils.isEmpty(apis[0])){
            List<Subscription> subscriptions = new ArrayList<>();
            for (int i = 0; i < apis.length; i++) {
                if (i==0){
                    subscriptions.add(new Subscription("订阅: 1", apis[0]).setChecked(true));
                    ApiConfig.saveApiUrl(apis[0]);
                }else {
                    subscriptions.add(new Subscription("订阅: "+(i+1), apis[i]));
                }
            }
            Hawk.put(HawkConfig.SUBSCRIPTIONS,subscriptions);
        }
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.load();
    }

    private void putDefault(String key, Object value) {
        if (!Hawk.contains(key)) {
            Hawk.put(key, value);
        }
    }

    /**
     * 安全DNS 默认值迁移（进程内只跑一次）。
     *
     * 老用户 Hawk 里存的还是旧默认值"关闭"，改 {@code putDefault} 的默认值对他们没有任何作用，
     * 所以这里显式抬一次。**只在用户从未手动选择过时才动**：用户在设置页点过之后
     * （DOH_USER_SET 已置位），这里以及将来的任何默认值调整都不再干涉他的选择。
     * 迁移标记 DOH_UPGRADED 保证这个动作只做一次，用户之后关掉它不会再被改回去。
     */
    private void upgradeDohDefault() {
        if (Hawk.get(HawkConfig.DOH_UPGRADED, false)) return;
        Hawk.put(HawkConfig.DOH_UPGRADED, true);
        if (Hawk.get(HawkConfig.DOH_USER_SET, false)) return;
        if (Hawk.get(HawkConfig.DOH_URL, OkGoHelper.DEFAULT_DOH) == 0) {
            Hawk.put(HawkConfig.DOH_URL, OkGoHelper.DEFAULT_DOH);
        }
    }


    private VodInfo vodInfo;
    public void setVodInfo(VodInfo vodinfo){
        this.vodInfo = vodinfo;
    }
    public VodInfo getVodInfo(){
        return this.vodInfo;
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(instance.getExternalCacheDir().getAbsolutePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    private void initCrashConfig(){
        //配置全局异常崩溃操作
        CaocConfig.Builder.create()
                .backgroundMode(CaocConfig.BACKGROUND_MODE_SILENT) //背景模式,开启沉浸式
                .enabled(true) //是否启动全局异常捕获
                .showErrorDetails(true) //是否显示错误详细信息
                .showRestartButton(true) //是否显示重启按钮
                .trackActivities(true) //是否跟踪Activity
                .minTimeBetweenCrashesMs(2000) //崩溃的间隔时间(毫秒)
                .errorDrawable(R.drawable.app_icon) //错误图标
                .restartActivity(MainActivity.class) //重新启动后的activity
                .apply();

        // 后台线程防闪退安全网：spider jar 自起线程抛出的未捕获异常只记日志，不杀整个进程；
        // 主线程异常仍交回 CustomActivityOnCrash 处理（显示错误信息并重启）
        Thread.UncaughtExceptionHandler caocHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread mainThread = Looper.getMainLooper().getThread();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // 崩溃日志先落盘（内部全 try/catch，绝不在处理器内二次抛错）
            com.github.tvbox.osc.util.CrashLog.save(thread, throwable);
            if (thread == mainThread && caocHandler != null) {
                caocHandler.uncaughtException(thread, throwable);
            } else {
                Log.e("App", "后台线程异常(已拦截,不闪退): " + thread.getName(), throwable);
            }
        });
    }

}