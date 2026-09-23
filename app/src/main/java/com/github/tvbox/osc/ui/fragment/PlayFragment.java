package com.github.tvbox.osc.ui.fragment;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;

import androidx.core.content.ContextCompat;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.util.ArrayList;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;

import com.blankj.utilcode.util.ColorUtils;
import com.blankj.utilcode.util.LogUtils;
import com.blankj.utilcode.util.RegexUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.blankj.utilcode.util.SpanUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.Subtitle;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.EXOmPlayer;
import com.github.tvbox.osc.player.IjkMediaPlayer;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.TrackInfo;
import com.github.tvbox.osc.player.TrackInfoBean;
import com.github.tvbox.osc.player.controller.VodController;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.adapter.ParseAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.PlayingControlDialog;
import com.github.tvbox.osc.ui.dialog.PlayingControlRightDialog;
import com.github.tvbox.osc.ui.dialog.SearchSubtitleDialog;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.CastDialog;
import com.github.tvbox.osc.ui.dialog.SubtitleDialog;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.ProgressStore;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.PlayTrace;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.android.exoplayer2.Player;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.google.android.exoplayer2.text.Cue;
import com.gyf.immersionbar.BarHide;
import com.gyf.immersionbar.ImmersionBar;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.enums.PopupPosition;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.HttpHeaders;
import com.lzy.okgo.model.Response;
import com.lzy.okgo.request.GetRequest;
import com.obsez.android.lib.filechooser.ChooserDialog;
import com.orhanobut.hawk.Hawk;

import org.apache.commons.lang3.StringUtils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import me.jessyan.autosize.AutoSize;
import okhttp3.OkHttpClient;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkTimedText;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.ProgressManager;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

public class PlayFragment extends BaseLazyFragment {
    private MyVideoView mVideoView;
    private TextView mPlayLoadTip;
    private ImageView mPlayLoadErr;
    private View mPlayLoading;
    private VodController mController;
    private CastDialog mCastDialog;
    private SourceViewModel sourceViewModel;
    private Handler mHandler;

    private final long videoDuration = -1;
    /**
     * 记录当前播放url
     */
    private String mCurrentUrl;
    private boolean mFullWindows;
    /**
     * 非全屏下的设置弹窗
     */
    private BasePopupView mPlayingControlDialog;
    /**
     * 全屏下的设置弹窗
     */
    private BasePopupView mPlayingControlRightDialog;
    /**
     * 真正交给播放器的地址与请求头：兼容性兜底时按同一地址重播（不重新解析源）。
     * 请求头存副本：改造后播放器侧对请求头只读不改（不再从表里摘 UA），
     * 但存副本能让「兜底重播」拿到的头一定与首次起播一致，不去依赖下游是否改动过调用方的表。
     */
    private String mPlayingUrl = null;
    private HashMap<String, String> mPlayingHeaders = null;
    /**
     * 兼容性兜底状态。fbKey 是"本集"标识，换集才重新计数，保证同一集不会被反复重试拖成假死；
     * fbPlan/fbPlanIdx 是兜底阶梯（每集只算一次，按顺序取用）；fbOverride 是兜底期间的临时配置副本。
     */
    private String fbKey = null;
    private ArrayList<JSONObject> fbPlan = null;
    private int fbPlanIdx = 0;
    private JSONObject fbOverride = null;
    /**
     * 净化前的原始播放地址。净化命中后播放器拿到的是本地代理地址，一旦这层代理出问题
     * （清单被误删 / 本地服务异常），拿着代理地址怎么重试都救不回来 —— 先回退到这里直连一次。
     * 只回退一次，回退后置空。
     */
    private String mFallbackRawUrl = null;
    /** 本集是否已试过"本地去 BOM"重试（清单带 BOM 时解析直接失败，剥掉即可救回） */
    private boolean mBomRetried = false;
    /** 本集是否已试过"同址换协议（http↔https）"重试 */
    private boolean mVariantTried = false;
    /** 变形前的地址：变形失败后降级必须回到它，不能拿被改坏的协议地址再试（见 compatFallback） */
    private String mVariantBaseUrl = null;
    /** 最近一次"已处理过失败"的起播时刻：同一次起播的重复 error 回调不再重复消耗降级额度 */
    private long mFailHandledAt = 0;
    /** 本集首次起播的时刻：自动重播的总预算从它算起（换集 / 用户手动重试才归零） */
    private long mEpisodeStartAt = 0;
    /**
     * 净化请求代次。切集/换解析接口/重播都会自增；回调先比对代次，不一致说明这次净化已经被
     * 更新的播放请求取代 —— 丢弃它，避免旧请求的 onError 用旧地址把新一集覆盖掉。
     */
    private int mPurifyGen = 0;
    /**
     * 起播看门狗。黑屏转圈（状态永远停在 STATE_PREPARING）在改动前不算失败，也就永远等不到兜底。
     * 判据从严：只有"仍停在准备中 + 播放位置为 0"才判死（**不看网速**，理由见 watchdog 内注释），
     * 且先延长一次再动手，宁可多等一会儿也不误杀"只是慢"的源。
     * 实测正常源就绪耗时最大 2.8 秒 ⇒ 20+12 秒的窗口对慢源极其宽松。
     */
    private static final long START_WATCHDOG_MS = 20000;
    /**
     * 首次判死后唯一的一次延长窗口（20+12=32 秒仍无画面才真的动作）。
     * 改动前这里分"无数据复核 10 秒 / 有数据延长 12 秒"两条路，本质是同一个判断被拆成三种轮次。
     */
    private static final long START_WATCHDOG_STALL_MS = 12000;
    /**
     * 本集自动重播的总预算（从本集首次起播算起）。
     * 「判死 → 降级 → 再判死 → 再降级」如果每一档都空转，没有预算的话用户要盯着转圈两分多钟
     * 才等到提示 —— 那比直接给提示更糟。只在"判死"这条路上检查，正在缓冲且位置已推进的
     * 播放不会被它打断。换集（或用户手动点重试）才归零。
     *
     * 判据是"**再跑一个完整的看门狗窗口会不会超预算**"，不是"当前是否已经超预算"：
     * 判死点分别在 32 / 64 / 96 秒，只有前者才能在 64 秒那一档停住手；后者（旧写法）
     * 不管填 65 还是 90 都卡在 64 与 96 之间，一次降级都挡不住，最坏仍是 96 秒。
     *
     * 取值 70 秒 = 「首次 32 秒窗口 + 一次降级重播的 32 秒窗口」= 64 秒，再留 6 秒给
     * 取链与起播本身的开销（预算起点 mEpisodeStartAt 早于首次起播时刻，这段开销会算进来）。
     */
    private static final long EPISODE_RETRY_BUDGET_MS = 70000;
    private boolean mWatchdogFired = false;
    /**
     * 看门狗轮次：0 = 首次观察，1 = 已经延长过一次。
     * 只有"再给一次机会"这一级，不再按有无数据分流（见 mStartWatchdog 内注释）。
     */
    private int mWatchdogRound = 0;
    /** 最近一次起播的时刻，用于把"起播到出画面"的耗时打进埋点 */
    private long mPlayStartedAt = 0;
    /** 最近一次播放的集标识（playFlag#playIndex），换集时把各路的计数与额度归零 */
    private String mLastPlayKey = null;

    /** 本集标识：换集＝重新给满兜底额度，且不让上一集的回退地址/看门狗状态串到下一集 */
    private String curEpisodeKey() {
        try {
            return mVodInfo.playFlag + "#" + mVodInfo.playIndex;
        } catch (Throwable th) {
            return null;
        }
    }

    private final Runnable mStartWatchdog = new Runnable() {
        @Override
        public void run() {
            if (mVideoView == null || mWatchdogFired) return;
            int st;
            long pos;
            long speed;
            try {
                st = mVideoView.getCurrentPlayState();
                pos = mVideoView.getCurrentPosition();
                speed = mVideoView.getTcpSpeed();
            } catch (Throwable th) {
                return; // 取不到状态就不判 —— 宁可不作为，也绝不误杀
            }
            // 真进展只有一个判据：**播放器已离开「准备中」，或者播放位置真的动过**。
            // 这里刻意不看网速：
            //  ① 不同内核的 getTcpSpeed 语义不一致（Exo 取不到时恒为 0），拿它当判据会把
            //     "慢但能播"的源提前判死 —— 与本工程"宁可多等，绝不误杀"的取向相悖；
            //  ② 第三批实测已经证明"有数据在流"并不等于"快出画面"，它只会让分支变多、判据变模糊。
            if (st != VideoView.STATE_PREPARING || pos > 0) {
                PlayTrace.stage("看门狗", "起播有进展，解除 st=" + st + " pos=" + pos);
                return;
            }
            // 只给一次延长，之后不再区分"有没有数据"：判死窗口固定为 20+12=32 秒。
            // （改动前是"无数据 30 秒 / 有数据 42 秒"两条路，本质是同一个判断被拆成了三种轮次。）
            if (mWatchdogRound == 0) {
                mWatchdogRound = 1;
                PlayTrace.stage("看门狗", "起播 " + (START_WATCHDOG_MS / 1000) + " 秒仍无画面，再给 "
                        + (START_WATCHDOG_STALL_MS / 1000) + " 秒（宁可不作为，也不误杀）");
                postWatchdog(START_WATCHDOG_STALL_MS);
                return;
            }
            mWatchdogFired = true;
            long ms = mPlayStartedAt > 0 ? (System.currentTimeMillis() - mPlayStartedAt) : -1;
            PlayTrace.fail("起播", "看门狗判定无画面 st=" + st + " pos=" + pos + " speed=" + speed, ms);
            // 本集的重播预算用尽：不再降级，直接给明确提示。
            // 判据＝"再跑一个完整窗口（20+12 秒）会超预算" —— 用"当前是否已超"是挡不住的：
            // 判死点在 32/64/96 秒，65 与 90 都卡在 64 与 96 之间 ⇒ 一次降级都拦不下。
            long elapsed = System.currentTimeMillis() - mEpisodeStartAt;
            if (mEpisodeStartAt > 0
                    && elapsed + START_WATCHDOG_MS + START_WATCHDOG_STALL_MS > EPISODE_RETRY_BUDGET_MS) {
                PlayTrace.fail("出链", "本集预算 " + (EPISODE_RETRY_BUDGET_MS / 1000)
                        + "s 不够再跑一个看门狗窗口，直接给结论", ms);
                errorFinal("视频播放出错");
                return;
            }
            if (fallbackToRawUrl()) return;
            if (compatFallback()) return;
            errorFinal("视频播放出错");
        }
    };

    private void armStartWatchdog() {
        try {
            mHandler.removeCallbacks(mStartWatchdog);
            // 每次起播（首播、换集、重播、兜底降级）都是一次新的观察：判死标记与轮次必须归零，
            // 否则"判死 → 降级重播 → 又不出画面"这一轮就没人接管，等于回到永久转圈。
            // 不会无限循环：降级阶梯自带额度（回退直连 1 次 / 变形 1 次 / 内置降级最多 2 档），
            // 额度用尽后 errorWithRetry 走到明确提示即终止。
            mWatchdogFired = false;
            mWatchdogRound = 0;
            mHandler.postDelayed(mStartWatchdog, START_WATCHDOG_MS);
        } catch (Throwable ignored) {
        }
    }

    /** 看门狗延后一次（异常一律吞掉：定时失败绝不能把播放主流程带崩） */
    private void postWatchdog(long delayMs) {
        try {
            mHandler.postDelayed(mStartWatchdog, delayMs);
        } catch (Throwable ignored) {
        }
    }

    private void cancelStartWatchdog() {
        try {
            mHandler.removeCallbacks(mStartWatchdog);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 取链看门狗。
     *
     * 「取链」＝问站点要播放地址（含解析接口）。这一步卡住时进程既没有报错、也没有超时，
     * 界面会永远停在「正在获取播放信息」，连兜底链都进不去 —— 用户只能看着转圈。
     * 同时取链回调里那条 catch 是静默的（只写日志），异常时同样不会给任何反馈。
     * 这里统一兜住：15 秒没结论就当本次取链失败。
     *
     * 误判代价可控：提示走 errorWithRetry → 先试自动重取一次（弱网下多半就成功了），
     * 真的取不到才提示「获取播放信息错误」，不会直接判死。
     */
    private static final long FETCH_WATCHDOG_MS = 15000;
    private boolean mFetchDone = false;
    private long mFetchStartedAt = 0;
    private final Runnable mFetchWatchdog = new Runnable() {
        @Override
        public void run() {
            if (mFetchDone) return;
            mFetchDone = true;
            PlayTrace.fail("取链", "看门狗判定超时无结论",
                    mFetchStartedAt > 0 ? (System.currentTimeMillis() - mFetchStartedAt) : -1);
            errorWithRetry("获取播放信息错误");
        }
    };

    private void armFetchWatchdog() {
        try {
            mHandler.removeCallbacks(mFetchWatchdog);
            mFetchDone = false;
            mFetchStartedAt = System.currentTimeMillis();
            mHandler.postDelayed(mFetchWatchdog, FETCH_WATCHDOG_MS);
        } catch (Throwable ignored) {
        }
    }

    private void finishFetchWatchdog() {
        try {
            mFetchDone = true;
            mHandler.removeCallbacks(mFetchWatchdog);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 净化命中的本地 HLS 端点。
     * 与 /m3u8 返回同一份内容，区别只在**带 .m3u8 后缀**：播放器按地址扩展名推断容器类型，
     * 无后缀时会把 HLS 清单先当普通视频试一次，失败了才补救 —— 观感就是"第一下播不出、切一下就好"。
     * 加后缀后能一次判对，且不需要额外请求（内容本来就已下载好）。
     */
    private String localHlsUrl() {
        return "http://127.0.0.1:" + RemoteServer.serverPort + "/l1.m3u8";
    }

    private static boolean isLocalHlsUrl(String url) {
        return url != null && url.contains("/l1.m3u8");
    }

    /**
     * 地址是否指向本机（净化端点 / 网盘源经加固 jar 暴露出来的 /proxy）。
     *
     * 这类地址做 http↔https 变形没有任何意义 —— 本机同一个端口上不存在另一套协议。
     * 实测把 `http://127.0.0.1:9978/proxy?...` 变形出 `https://127.0.0.1:9978/proxy?...`，
     * TLS 握手必失败，白白多耗一次尝试。原来的守卫只认 `/l1.m3u8`，把 /proxy 漏掉了。
     */
    private static boolean isLoopbackUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("://127.0.0.1") || u.contains("://localhost") || u.contains("://[::1]");
    }

    /**
     * 请求头兜底补全：只在站点**没给** Referer 时，按播放地址补一个同源 Referer。
     *
     * 为什么只补 Referer、不补 UA：工程里没有"固定 UA"的公共出口（只有一组随机浏览器 UA 的工具），
     * 而给同一个源每次请求随机 UA 会把不确定性带进播放链路，与"稳定优先"相悖 ——
     * UA 缺失时播放器用自带默认值，行为与改动前一致；站点显式给了 UA 的照旧原样使用。
     *
     * 补 Referer 的收益是明确的：防盗链校验 Referer，缺了就 403；补的是地址自身的同源前缀，
     * 对不需要 Referer 的源无影响。只补缺失项，绝不覆盖站点已给的值。任何异常都退回原请求头。
     */
    private static HashMap<String, String> fillMissingHeaders(String url, HashMap<String, String> headers) {
        try {
            if (url == null || !url.toLowerCase().startsWith("http")) return headers;
            if (url.contains("://127.0.0.1") || url.contains("://localhost")) return headers; // 本地地址没有防盗链
            if (headers != null) {
                for (String k : headers.keySet()) {
                    if ("Referer".equalsIgnoreCase(k)) return headers;
                }
            }
            int p = url.indexOf("://");
            int slash = url.indexOf('/', p + 3);
            String origin = (slash > 0) ? url.substring(0, slash + 1) : url;
            HashMap<String, String> out = (headers == null) ? new HashMap<>() : new HashMap<>(headers);
            out.put("Referer", origin);
            PlayTrace.stage("请求头", "补同源 Referer=" + origin);
            return out;
        } catch (Throwable th) {
            return headers;
        }
    }

    /**
     * 剥掉 UTF-8 BOM。清单首个字符是 BOM 时，播放器按文本解析会直接失败；
     * BOM 可能以 \uFEFF 呈现，也可能被按 latin1 读成"ï»¿"三个字符，两种都要处理。
     */
    private static String stripBom(String s) {
        if (s == null) return null;
        if (s.startsWith("\uFEFF")) return s.substring(1);
        if (s.startsWith("ï»¿")) return s.substring(3);
        return s;
    }

    /**
     * 给净化预取接上"2.5 秒超时"的 client。拿不到就原样返回（退回默认 10 秒超时），
     * 净化这一步宁可慢一点，也不能因为埋点式优化反而整体失效。
     */
    private static GetRequest<String> withPurifyClient(GetRequest<String> req) {
        try {
            OkHttpClient pc = OkGoHelper.getPurifyClient();
            if (pc != null) return req.client(pc);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return req;
    }
    @Override
    protected int getLayoutResID() {
        return R.layout.activity_play;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE) {
            mController.mSubtitleView.setTextSize((int) event.obj);
        } else if (event.type == RefreshEvent.TYPE_BATTERY_CHANGE && mController.mMyBatteryView!=null){
            mController.mMyBatteryView.updateBattery((int) event.obj);
        }
    }

    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
    }

    /** 本次起播的落点(毫秒)，供 prepared() 给出一次性的续播/片头提示 */
    private long mResumeTipPos = 0;
    /** 本次起播配置的片头跳过秒数 */
    private int mResumeTipSkipSec = 0;

    public long getSavedProgress(String url) {
        int st = 0;
        try {
            st = mVodPlayerCfg.getInt("st");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        long skip = st * 1000L;
        // 进度自成一档存储（不走 cache 表），换解析地址后同一集仍能续播
        long rec = ProgressStore.get(url);
        mResumeTipPos = Math.max(rec, skip);
        mResumeTipSkipSec = st;
        return mResumeTipPos;
    }

    /**
     * 起播后一次性提示本次落点。续播与片头跳过只会命中一个，故不担心两条提示叠加；
     * 位置不足 10 秒不提示——刚开头就弹提示是打扰，用户自己也能感知。
     */
    private void showResumeTip() {
        long pos = mResumeTipPos;
        int skipSec = mResumeTipSkipSec;
        mResumeTipPos = 0; // 消费掉，避免解析重试等重复 prepared 时再次提示
        mResumeTipSkipSec = 0;
        if (pos <= 0 || getContext() == null) return;
        if (pos > skipSec * 1000L) {
            if (pos < 10000) return;
            ToastUtils.showShort("已从 " + PlayerUtils.stringForTime((int) pos) + " 继续播放");
        } else if (skipSec > 0) {
            ToastUtils.showShort("已跳过片头 " + skipSec + " 秒");
        }
    }

    private void initView() {
        EventBus.getDefault().register(this);
        mHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case 100:
                        stopParse();
                        errorWithRetry("嗅探错误");
                        break;
                }
                return false;
            }
        });
        mVideoView = findViewById(R.id.mVideoView);
        mPlayLoadTip = findViewById(R.id.play_load_tip);
        mPlayLoading = findViewById(R.id.play_loading);
        mPlayLoadErr = findViewById(R.id.play_load_error);
        mController = new VodController(requireContext());
        mController.showParse(false);
        mController.setCanChangePosition(true);
        mController.setEnableInNormal(true);
        mController.setGestureEnabled(true);
        ProgressManager progressManager = new ProgressManager() {
            @Override
            public void saveProgress(String url, long progress) {
                // 进度已到片尾(≥95%)说明这集实际已经看完：清除记录而不是留一条接近片尾的进度，
                // 否则下次续播会直接跳到片尾、等于"一进去就播完"
                long dur = 0;
                try {
                    dur = mVideoView.getDuration();
                } catch (Throwable ignored) {
                }
                if (dur > 0 && progress >= dur * 95 / 100) {
                    ProgressStore.remove(url);
                    return;
                }
                ProgressStore.save(url, progress);
            }

            @Override
            public long getSavedProgress(String url) {
                return PlayFragment.this.getSavedProgress(url);
            }
        };
        mVideoView.setProgressManager(progressManager);
        mController.setListener(new VodController.VodControlListener() {
            final DetailActivity activity = (DetailActivity) mActivity;
            @Override
            public void chooseSeries() {
                //activity中已处理
                activity.showAllSeriesDialog();
            }

            @Override
            public void playNext(boolean rmProgress) {
                String preProgressKey = progressKey;
                PlayFragment.this.playNext(rmProgress);
                // 正常播完/切下一集：清掉上一集的进度，重进该集从头播放
                if (rmProgress && preProgressKey != null)
                    ProgressStore.remove(preProgressKey);
            }

            @Override
            public boolean hasNext() {
                // 供控制器判断后面还有没有剧集：最后一集播完停在最后一帧，不弹连播倒计时
                return nextSeriesIndex(+1) >= 0;
            }

            @Override
            public void playPre() {
                PlayFragment.this.playPrevious();
            }

            @Override
            public void changeParse(ParseBean pb) {
                autoRetryCount = 0;
                // 换了解析接口＝换了内容来源，兜底额度重新给满
                mRefetchTried = false; // P1：新来源，自动重取的 once 护栏重新给满
                mPlaybackHadStarted = false;
                fbKey = null;
                fbPlan = null;
                fbPlanIdx = 0;
                fbOverride = null;
                doParse(pb);
            }

            @Override
            public void updatePlayerCfg() {
                // 写回「本片·本线路」存档；比例/速度这类写入不带 plt 标记，不影响"跟默认走"的判定
                savePlayerCfgToLine();
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodPlayerCfg));
            }

            @Override
            public void playExternal(int playerType) {
                // 仅本次吊起：不写配置、不改用户设置（用户 09-18 口径）
                if (!playWithExternalPlayer(playerType)) {
                    ToastUtils.showShort("调用" + PlayerHelper.getPlayerName(playerType) + "失败");
                }
            }

            @Override
            public void replay(boolean replay) {
                autoRetryCount = 0;
                mRefetchTried = false; // P1：用户主动重播，once 护栏重新给满
                mPlaybackHadStarted = false;
                mRetryGen++; // 用户主动重播，与手动「重试」同理：排队中的自动重取一律作废
                play(replay);
            }

            @Override
            public void errReplay() {
                cancelStartWatchdog();
                // 同一次起播只处理一次失败：播放器换源/释放后仍可能滞后再报一次 error，
                // 那一次会把仅剩的降级额度白白吃掉（实测同一集出现过间隔 0.25 秒的两轮降级）。
                if (mPlayStartedAt > 0 && mPlayStartedAt == mFailHandledAt) return;
                mFailHandledAt = mPlayStartedAt;
                // 还有别的解析地址时优先换地址（换地址比换播放器更可能成功），顺序与改动前一致
                if (loadFoundVideoUrls != null && loadFoundVideoUrls.size() > 0) {
                    errorWithRetry("视频播放出错");
                    return;
                }
                // P1（09-22 拍板）：播放已就绪过再死（中途 404/断流）—— 地址本来有效，
                // 换地址/协议/内核都在同一条死链上打转；先重新取链一次（＝用户手动「重试」，
                // 09-22 实测重取后 403ms 起播救回），仍失败再走原有兜底链。
                if (mPlaybackHadStarted && tryRefetchOnce("播放中断", false)) return;
                // 走的是本地净化代理 → 先回退原始直连：代理这一层坏了，拿代理地址怎么重试都没用
                if (fallbackToRawUrl()) return;
                // 同址换协议试一次（零网络成本、不换内核，比换播放器更"轻"）
                if (retryVariantUrl()) return;
                // 再用内置播放器做确定性降级（IJK 软解 → Exo），阶梯按集重置、不重复试同一配置
                if (compatFallback()) return;
                // P1（09-22 拍板）：起播阶段兜底全试尽 —— 同样先重新取链一次
                // （受总时长上限护栏，见 REFETCH_TOTAL_BUDGET_MS；09-22 第二例在 10 秒处报错，
                //  手动换线路 627ms 就能播，说明死的是那条链不是那个源）。
                if (tryRefetchOnce("起播兜底试尽", true)) return;
                // 全试尽：如实告知，只给「重试」这一个出口 —— 不引导换源，也不引导换播放器。
                // 这里用 errorFinal 而不是 errorWithRetry：地址、协议、内核三条路都已经试过，
                // 再排一次"重新取链"只会又开一个 32 秒的观察窗口，把等待白白拖长；
                // 用户看到提示后点「重试」才是重新取链的正确入口（那会重置本集额度）。
                // （09-22 更新：上面两处 tryRefetchOnce 是**带护栏的一次性**重取，errorFinal
                //   仍是终态出口 —— once 护栏保证它最多被绕过一次，不会回到无界的循环重取。）
                PlayTrace.fail("出链", "自动兜底试尽，转为明确提示",
                        mPlayStartedAt > 0 ? (System.currentTimeMillis() - mPlayStartedAt) : -1);
                errorFinal("视频播放出错");
            }

            @Override
            public void selectSubtitle() {
                try {
                    selectMySubtitle();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void selectAudioTrack() {
                selectMyAudioTrack();
            }

            @Override
            public void prepared() {
                cancelStartWatchdog(); // 已经备好了，看门狗失去意义
                mPlaybackHadStarted = true; // P1：就绪过再死走「播放中断」重取护栏（不受取链预算约束）
                if (mPlayStartedAt > 0) {
                    // 带上内核名：水位与起播门槛只对 Exo 生效（IJK 有自己的缓冲逻辑），
                    // 不写清楚就没法判断「门槛有没有生效」该看哪条日志
                    PlayTrace.stage("起播", "已就绪，耗时 " + (System.currentTimeMillis() - mPlayStartedAt)
                            + "ms 内核=" + getCurrentPlayerItemName());
                }
                initSubtitleView();
                showResumeTip();
            }

            @Override
            public void toggleFullScreen() {
                activity.toggleFullPreview();
            }

            @Override
            public void exit() {
                activity.onBackPressed();
            }

            @Override
            public void cast() {
                if (activity == null) return;
                String url = getFinalUrl();
                if (TextUtils.isEmpty(url)) {
                    ToastUtils.showShort("当前没有可投屏的视频");
                    return;
                }
                if (url.contains("127.0.0.1") || url.contains("localhost")) {
                    ToastUtils.showShort("该视频经过本地代理，无法投屏到局域网设备");
                    return;
                }
                String title = "";
                if (mVodInfo != null) {
                    try {
                        VodInfo.VodSeries vs = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
                        title = mVodInfo.name + " " + vs.name;
                    } catch (Exception e) {
                        title = mVodInfo.name;
                    }
                }
                // 防重入：弹窗还在显示时忽略新的点击，避免重复发现设备/重复推送
                if (mCastDialog != null && mCastDialog.isShow()) return;
                try {
                    mCastDialog = new CastDialog(activity, url, title);
                    // 必须经 XPopup.Builder 包装：直接 show() 时 popupInfo 为 null，
                    // XPopup 会抛 IllegalArgumentException("popupInfo is null") → 点投屏即闪退到崩溃页
                    new XPopup.Builder(activity).asCustom(mCastDialog).show();
                } catch (Exception e) {
                    mCastDialog = null;
                    ToastUtils.showShort("投屏暂不可用");
                }
            }

            @Override
            public void onHideBottom() {
                if (mFullWindows){
                    ImmersionBar.with(activity)
                            .hideBar(BarHide.FLAG_HIDE_BAR)
                            .init();
                }
            }

            @Override
            public void showSetting() {
                if (mFullWindows){
                    mPlayingControlRightDialog = new XPopup.Builder(activity)
                            .isViewMode(true)//改为view模式无法自动响应返回键操作,onBackPress时手动dismiss
                            .hasNavigationBar(false)
                            .popupHeight(ScreenUtils.getScreenHeight())
                            .popupPosition(PopupPosition.Right)
                            .asCustom(new PlayingControlRightDialog(activity,mController,mVideoView));
                    mPlayingControlRightDialog.show();
                }else {
                    mPlayingControlDialog = new XPopup.Builder(activity)
                            .isViewMode(true)
                            .hasNavigationBar(false)
                            .asCustom(new PlayingControlDialog(activity,mController,mVideoView));
                    mPlayingControlDialog.show();
                }
            }

            @Override
            public void pip() {
                activity.enterPip();
            }

            @Override
            public void showParseRoot(boolean show, ParseAdapter adapter) {
                DetailActivity activity = (DetailActivity)mActivity;
                activity.showParseRoot(show,adapter);
            }
        });
        mVideoView.setVideoController(mController);
    }

    public boolean hideAllDialogSuccess(){
        if (mPlayingControlRightDialog!=null && mPlayingControlRightDialog.isShow()){
            mPlayingControlRightDialog.dismiss();
            return true;
        }
        if (mPlayingControlDialog!=null && mPlayingControlDialog.isShow()){
            mPlayingControlDialog.dismiss();
            return true;
        }
        return false;
    }

    /**
     * activity返回/点击播放器切换全屏操作等
     */
    public void changedLandscape(boolean fullWindows) {
        mFullWindows = fullWindows;
        if (fullWindows){
            int[] size = mVideoView.getVideoSize();
            int width = size[0];
            int height = size[1];
            if (width>height){//根据视频尺寸判断是否横屏,小视频则只在activity改了预览尺寸(全屏预览)
                //横屏(固定方向,由 VodController 手动控制旋转,禁用陀螺仪)
                mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }

            ImmersionBar.with(mActivity)
                    .hideBar(BarHide.FLAG_HIDE_BAR)
                    .navigationBarColor(R.color.black)//即使隐藏部分时候还是会显示
                    .fitsSystemWindows(false)
                    .init();
        }else {//非全屏统一设置竖屏,activity处理为小的预览尺寸
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

            ImmersionBar.with(mActivity)
                    .hideBar(BarHide.FLAG_SHOW_BAR)
                    .navigationBarColor(R.color.white)
                    .fitsSystemWindows(true)
                    .init();
        }

        mController.changedLandscape(fullWindows);
    }

    //设置字幕
    void setSubtitle(String path) {
        if (path != null && path.length() > 0) {
            // 设置字幕
            mController.mSubtitleView.setVisibility(View.GONE);
            mController.mSubtitleView.setSubtitlePath(path);
            mController.mSubtitleView.setVisibility(View.VISIBLE);
        }
    }

    void selectMySubtitle() throws Exception {
        SubtitleDialog subtitleDialog = new SubtitleDialog(getActivity());
        subtitleDialog.setSubtitleViewListener(new SubtitleDialog.SubtitleViewListener() {
            @Override
            public void setTextSize(int size) {
                mController.mSubtitleView.setTextSize(size);
            }

            @Override
            public void setSubtitleDelay(int milliseconds) {
                mController.mSubtitleView.setSubtitleDelay(milliseconds);
            }

            @Override
            public void selectInternalSubtitle() {
                selectMyInternalSubtitle();
            }

            @Override
            public void setTextStyle(int style) {
                setSubtitleViewTextStyle(style);
            }

            @Override
            public void subtitleOpen(boolean b) {
                mController.openSubtitle(b);
            }
        });
        subtitleDialog.setSearchSubtitleListener(new SubtitleDialog.SearchSubtitleListener() {
            @Override
            public void openSearchSubtitleDialog() {
                SearchSubtitleDialog searchSubtitleDialog = new SearchSubtitleDialog(getActivity());
                searchSubtitleDialog.setSubtitleLoader(new SearchSubtitleDialog.SubtitleLoader() {
                    @Override
                    public void loadSubtitle(Subtitle subtitle) {
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                String zimuUrl = subtitle.getUrl();
                                LOG.i("Remote Subtitle Url: " + zimuUrl);
                                setSubtitle(zimuUrl);//设置字幕
                                searchSubtitleDialog.dismiss();
                            }
                        });
                    }
                });
                if (mVodInfo.playFlag.contains("Ali") || mVodInfo.playFlag.contains("parse")) {
                    searchSubtitleDialog.setSearchWord(mVodInfo.playNote);
                } else {
                    searchSubtitleDialog.setSearchWord(mVodInfo.name);
                }
                searchSubtitleDialog.show();
            }
        });
        subtitleDialog.setLocalFileChooserListener(new SubtitleDialog.LocalFileChooserListener() {
            @Override
            public void openLocalFileChooserDialog() {
                // Android 11+ 必须先拿到文件访问权限，否则选中文件后读取一定失败
                if (!XXPermissions.isGranted(getActivity(), Permission.MANAGE_EXTERNAL_STORAGE)) {
                    ToastUtils.showShort("需要文件访问权限才能加载本地字幕");
                    requestStoragePermission();
                    return;
                }
                openSubtitleFileChooser();
            }
        });
        subtitleDialog.show();
    }

    private void requestStoragePermission() {
        XXPermissions.with(this)
                .permission(Permission.MANAGE_EXTERNAL_STORAGE)
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(List<String> permissions, boolean all) {
                        if (all) {
                            openSubtitleFileChooser();
                        } else {
                            ToastUtils.showLong("部分权限未正常授予，请授权");
                        }
                    }

                    @Override
                    public void onDenied(List<String> permissions, boolean never) {
                        if (never) {
                            ToastUtils.showLong("文件权限被永久拒绝，请手动授权");
                            XXPermissions.startPermissionActivity(getActivity(), permissions);
                        } else {
                            ToastUtils.showShort("获取权限失败");
                        }
                    }
                });
    }

    private void openSubtitleFileChooser() {
        new ChooserDialog(getActivity(), R.style.FileChooser)
                .withFilter(false, false, "srt", "ass", "scc", "stl", "ttml")
                .withStartFile(Environment.getExternalStorageDirectory().getAbsolutePath())
                .withChosenListener(new ChooserDialog.Result() {
                    @Override
                    public void onChoosePath(String path, File pathFile) {
                        LOG.i("Local Subtitle Path: " + path);
                        setSubtitle(path);//设置字幕
                    }
                })
                .build()
                .show();
    }

    void setSubtitleViewTextStyle(int style) {
        if (style == 0) {
            mController.mSubtitleView.setTextColor(ContextCompat.getColorStateList(getContext(), R.color.color_FFFFFF));
        } else if (style == 1) {
            mController.mSubtitleView.setTextColor(ContextCompat.getColorStateList(getContext(), R.color.color_FFB6C1));
        }
    }

    void selectMyAudioTrack() {
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();

        TrackInfo trackInfo = null;
        if (mediaPlayer instanceof IjkMediaPlayer) {
            trackInfo = ((IjkMediaPlayer) mediaPlayer).getTrackInfo();
        }
        if (mediaPlayer instanceof EXOmPlayer) {
            trackInfo = ((EXOmPlayer) mediaPlayer).getTrackInfo();
        }

        if (trackInfo == null) {
            Toast.makeText(mContext, "没有音轨", Toast.LENGTH_SHORT).show();
            return;
        }
        List<TrackInfoBean> bean = trackInfo.getAudio();
        if (bean == null) bean = new ArrayList<>();
        final List<TrackInfoBean> beanList = bean;
        if (bean.isEmpty()) {
            Toast.makeText(mContext, "没有可切换的音轨", Toast.LENGTH_SHORT).show();
            return;
        }
        int defaultSel = 0;
        SelectDialog<TrackInfoBean> dialog = new SelectDialog<>(getActivity());
        dialog.setTip("切换音轨");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<TrackInfoBean>() {
            @Override
            public void click(TrackInfoBean value, int pos) {
                try {
                    for (TrackInfoBean audio : beanList) {
                        audio.selected = audio.trackId == value.trackId;
                    }
                    mediaPlayer.pause();
                    long progress = mediaPlayer.getCurrentPosition();//保存当前进度，ijk 切换轨道 会有快进几秒
                    if (mediaPlayer instanceof IjkMediaPlayer) {
                        ((IjkMediaPlayer) mediaPlayer).setTrack(value.trackId);
                    }
                    if (mediaPlayer instanceof EXOmPlayer) {
                        ((EXOmPlayer) mediaPlayer).selectExoTrack(value);
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mediaPlayer.seekTo(progress);
                            mediaPlayer.start();
                        }
                    }, 800);
                    dialog.dismiss();
                } catch (Exception e) {
                    LOG.e("切换音轨出错");
                }
            }

            @Override
            public String getDisplay(TrackInfoBean val) {
                String name = val.name.replace("AUDIO,", "");
                name = name.replace("N/A,", "");
                name = name.replace(" ", "");
                return name + (TextUtils.isEmpty(val.language) ? "" : " " + val.language);
            }
        }, new DiffUtil.ItemCallback<TrackInfoBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }

            @Override
            public boolean areContentsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }
        }, bean, defaultSel);
        dialog.show();
    }

    void selectMyInternalSubtitle() {
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = null;
        if (mediaPlayer instanceof EXOmPlayer) {
            trackInfo = ((EXOmPlayer)mediaPlayer).getTrackInfo();
        }
        if (mediaPlayer instanceof IjkMediaPlayer) {
            trackInfo = ((IjkMediaPlayer)mediaPlayer).getTrackInfo();
        }

        if (trackInfo == null) {
            Toast.makeText(mContext, "没有内置字幕", Toast.LENGTH_SHORT).show();
            return;
        }
        List<TrackInfoBean> bean = trackInfo.getSubtitle();
        if (bean.size() < 1) return;
        SelectDialog<TrackInfoBean> dialog = new SelectDialog<>(mActivity);
        dialog.setTip("切换内置字幕");
        dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<TrackInfoBean>() {
            @Override
            public void click(TrackInfoBean value, int pos) {
                mController.mSubtitleView.setVisibility(View.VISIBLE);
                try {
                    for (TrackInfoBean subtitle : bean) {
                        subtitle.selected =subtitle.trackGroupId == value.trackGroupId && subtitle.trackId == value.trackId;
                    }
                    mediaPlayer.pause();
                    long progress = mediaPlayer.getCurrentPosition();//保存当前进度，ijk 切换轨道 会有快进几秒
                    mController.mSubtitleView.destroy();
                    mController.mSubtitleView.clearSubtitleCache();
                    mController.mSubtitleView.isInternal = true;

                    if (mediaPlayer instanceof IjkMediaPlayer) {
                        ((IjkMediaPlayer)mediaPlayer).setTrack(value.trackId);
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                mediaPlayer.seekTo(progress);
                                mediaPlayer.start();
                            }
                        }, 800);
                    }
                    if (mediaPlayer instanceof EXOmPlayer) {
                        ((EXOmPlayer)mediaPlayer).selectExoTrack(value);
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                mediaPlayer.seekTo(progress);
                                mediaPlayer.start();
                                mController.startProgress();
                            }
                        }, 800);
                    }
                    dialog.dismiss();
                } catch (Exception e) {
                    LOG.e("切换内置字幕出错");
                }
            }

            @Override
            public String getDisplay(TrackInfoBean val) {
                return val.name + (TextUtils.isEmpty(val.language)? "": " " + val.language);
            }
        }, new DiffUtil.ItemCallback<TrackInfoBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }

            @Override
            public boolean areContentsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }
        }, bean, trackInfo.getSubtitleSelected(false));
        dialog.show();
    }

    void setTip(String msg, boolean loading, boolean err) {
        if (!isAdded()) return;
        //影魔
        requireActivity().runOnUiThread(() -> {
            mPlayLoadTip.setText(msg);
            mPlayLoadTip.setVisibility(View.VISIBLE);
            mPlayLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
            mPlayLoadErr.setVisibility(err ? View.VISIBLE : View.GONE);

            if (err && needRetryLink(msg)) {
                // 错误态一律带一个可点的「重试」，但**只给这一个出口**：
                //  - 不引导换源：本来就是多源，节目页自己有换源入口，程序不替用户重复提醒；
                //  - 不引导换播放器：自动降级（errReplay 那条链）已经把内置播放器逐档试过了，
                //    手动切播放器是操作栏「播放器」按钮的事（短按内置循环、长按完整列表）。
                // 点「重试」＝把本集所有"已试过"的标记清干净，从取链开始重新来一遍。
                SpanUtils.with(mPlayLoadTip)
                        .append(msg + "，")
                        .append("重试")
                        .setClickSpan(ColorUtils.getColor(R.color.orange), false, view -> {
                            retryFromScratch();
                        }).create();
            }
        });
    }

    /**
     * 哪些错误提示要带「重试」。判据＝"再试一次有可能成功"：
     * 取链空返回、解析/嗅探失败、兜底试尽、地址不可播都是源侧偶发，重试有意义；
     * 「站点已失效，请返回重试」给的动作是"返回"，文案里已经写清，不在这里重复引导。
     */
    private static boolean needRetryLink(String msg) {
        return !"站点已失效，请返回重试".equals(msg);
    }

    /**
     * 用户手动点「重试」：把本集所有"已试过"的标记清干净，从取链开始重来一遍。
     * 与自动兜底的关键区别是它**重置额度** —— 自动链把额度用尽才会显示这句提示，
     * 不重置的话用户点下去只会立刻再看到同一句提示，等于按钮没用。
     */
    private void retryFromScratch() {
        try {
            autoRetryCount = 0;
            mWatchdogFired = false;
            mWatchdogRound = 0;
            mFallbackRawUrl = null;
            mBomRetried = false;
            mVariantTried = false;
            mVariantBaseUrl = null;
            mFailHandledAt = 0;
            mRefetchTried = false; // P1：用户手动重试，自动重取的 once 护栏重新给满
            mPlaybackHadStarted = false;
            mRetryGen++; // 作废排队中的那次自动重取：用户已经自己发起了一次，不能并发取链
            mEpisodeStartAt = System.currentTimeMillis(); // 用户明确要求再试一次，预算重新给满
            fbKey = null;
            fbPlan = null;
            fbPlanIdx = 0;
            fbOverride = null;
            PlayTrace.stage("重试", "用户手动重试本集");
            play(false);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    void hideTip() {
        mPlayLoadTip.setVisibility(View.GONE);
        mPlayLoading.setVisibility(View.GONE);
        mPlayLoadErr.setVisibility(View.GONE);
    }

    /**
     * 失败出口（全应用唯一一个）。
     *
     * 旧实现分两支：`finish=true` 只弹 2 秒 Toast、**不关**「正在获取播放信息」那个 loading 浮层。
     * 取链空返回（站点偶发不给地址）正好走这一支 —— 实测表现是"提示一闪而过 + 界面永久转圈"，
     * 用户看到的是卡死而不是出错。现在两支合并：关掉转圈、留错误态与可点的「重试」。
     * 出口之前仍然会静默自动重试一次（autoRetry），弱网下这次提示根本不会出现。
     */
    /**
     * 终态失败出口：**不再触发任何自动重取**，直接给错误态 + 可点「重试」。
     *
     * 只用在"确定没有别的手段可试"的两处：① 起播失败且兜底链已全试尽 ② 起播预算用尽。
     * 与 errorWithRetry 的唯一区别就是跳过 autoRetry —— 但这一层很重要：autoRetry 会排一次
     * "重新取链"，而那意味着**又开一个 20+12 秒的看门狗窗口**，会把"每一步都空转"时的
     * 最坏等待从预算内的 64 秒拖回 96 秒以上（实测就是这样绕过了旧预算）。
     *
     * 显示形态与 errorWithRetry 完全一致（同一个 setTip），不存在"两个提示长得不一样"的问题。
     */
    void errorFinal(String err) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setTip(err, false, true);
            }
        });
    }

    void errorWithRetry(String err) {
        if (!autoRetry() && isAdded()) {
            requireActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setTip(err, false, true);
                }
            });
        }
    }

    /**
     * 净化预取失败的粗归因（只写埋点，不改变任何行为）。
     * 重点是把"本机代理地址"和"远端地址"分开统计：本机代理没起来时，那个地址播多少次都不会成功，
     * 属于另一层面的问题。用真实使用的统计去判断它占多少，而不是靠猜。
     */
    private static String purifyFailKind(String url, Response<String> response) {
        try {
            boolean local = url != null
                    && (url.contains("://127.0.0.1") || url.contains("://localhost"));
            Throwable th = (response == null) ? null : response.getException();
            String type = (th == null) ? "无异常对象" : th.getClass().getSimpleName();
            return (local ? "本地代理地址/" : "远端地址/") + type;
        } catch (Throwable err) {
            return "归因失败";
        }
    }

    private String removeMinorityUrl(String tsUrlPre, String m3u8content) {
        if (!m3u8content.startsWith("#EXTM3U")) return null;
        String linesplit = "\n";
        if (m3u8content.contains("\r\n"))
            linesplit = "\r\n";
        String[] lines = m3u8content.split(linesplit);

        HashMap<String, Integer> preUrlMap = new HashMap<>();
        for (String line : lines) {
            if (line.length() == 0 || line.charAt(0) == '#') {
                continue;
            }
            int ilast = line.lastIndexOf('.');
            if (ilast <= 4) {
                continue;
            }
            String preUrl = line.substring(0, ilast - 4);
            Integer cnt = preUrlMap.get(preUrl);
            if (cnt != null) {
                preUrlMap.put(preUrl, cnt + 1);
            } else {
                preUrlMap.put(preUrl, 1);
            }
        }
        if (preUrlMap.size() <= 1) return null;
        if (preUrlMap.size() > 5) return null;//too many different url, can not identify ads url
        int maxTimes = 0;
        String maxTimesPreUrl = "";
        for (Map.Entry<String, Integer> entry : preUrlMap.entrySet()) {
            if (entry.getValue() > maxTimes) {
                maxTimesPreUrl = entry.getKey();
                maxTimes = entry.getValue();
            }
        }
        if (maxTimes == 0) return null;

        boolean dealedExtXKey = false;
        for (int i = 0; i < lines.length; ++i) {
            if (!dealedExtXKey && lines[i].startsWith("#EXT-X-KEY")) {
                String keyUrl = StringUtils.substringBetween(lines[i], "URI=\"", "\"");
                if (keyUrl != null && !keyUrl.startsWith("http://") && !keyUrl.startsWith("https://")) {
                    String newKeyUrl;
                    if (keyUrl.charAt(0) == '/') {
                        int ifirst = tsUrlPre.indexOf('/', 9);//skip https://, http://
                        newKeyUrl = tsUrlPre.substring(0, ifirst) + keyUrl;
                    } else
                        newKeyUrl = tsUrlPre + keyUrl;
                    lines[i] = lines[i].replace("URI=\"" + keyUrl + "\"", "URI=\"" + newKeyUrl + "\"");
                }
                dealedExtXKey = true;
            }
            if (lines[i].length() == 0 || lines[i].charAt(0) == '#') {
                continue;
            }
            if (lines[i].startsWith(maxTimesPreUrl)) {
                if (!lines[i].startsWith("http://") && !lines[i].startsWith("https://")) {
                    if (lines[i].charAt(0) == '/') {
                        int ifirst = tsUrlPre.indexOf('/', 9);//skip https://, http://
                        lines[i] = tsUrlPre.substring(0, ifirst) + lines[i];
                    } else
                        lines[i] = tsUrlPre + lines[i];
                }
            } else {
                if (i > 0 && lines[i - 1].length() > 0 && lines[i - 1].charAt(0) == '#') {
                    lines[i - 1] = "";
                }
                lines[i] = "";
            }
        }
        return StringUtils.join(lines, linesplit);
    }

    void playUrl(String url, HashMap<String, String> rawHeaders) {
        // 请求头兜底补全（补同源 Referer）只用于净化预取那一次请求，绝不交给播放器。
        // 原因：补出来的是「源站地址」作 Referer，而清单里的分片常托管在第三方 CDN 上，CDN 拒绝
        // 外站 Referer（实测小红书 CDN：空 Referer 放行、外站 Referer 返回 403）。一旦把它写进
        // 播放器的默认请求头，分片会集体 403 —— 症状正是「清单取到了、却始终播不出来」。
        // 播放侧（下面所有 startPlayUrl / startPurified）一律用站点原始请求头，与改动前一致。
        // raw 用新变量而不是重新赋值参数 —— 参数一旦被赋值就不再是 effectively final，
        // 而下面的净化回调（匿名类）要引用它。
        final HashMap<String, String> raw = rawHeaders;
        final HashMap<String, String> headers = fillMissingHeaders(url, rawHeaders);
        mCurrentUrl = url;
        // 代次在任何一条出口之前自增：新一集的地址未必需要净化（不走净化就不该有净化请求），
        // 但上一集"在飞"的净化回调必须一律作废，否则它会用旧地址把新一集覆盖掉。
        final int gen = ++mPurifyGen;
        if (!Hawk.get(HawkConfig.VIDEO_PURIFY, true)) {
            startPlayUrl(url, raw);
            return;
        }
        if (!url.contains("://127.0.0.1/") && !url.contains(".m3u8")) {
            startPlayUrl(url, raw);
            return;
        }
        OkGo.getInstance().cancelTag("m3u8-1");
        OkGo.getInstance().cancelTag("m3u8-2");
        final long t0 = System.currentTimeMillis();
        PlayTrace.stage("净化", "开始 url=" + PlayTrace.brief(url));
        //remove ads in m3u8
        HttpHeaders hheaders = new HttpHeaders();
        if(headers != null){
            for (Map.Entry<String, String> s : headers.entrySet()) {
                hheaders.put(s.getKey(), s.getValue());
            }
        }

        // 净化用 2.5 秒超时的独立 client：这一步只是加分项，不值得让起播白等（超时即直连起播）
        withPurifyClient(OkGo.<String>get(url)
                .tag("m3u8-1")
                .headers(hheaders))
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        if (gen != mPurifyGen) return; // 已被更新的播放请求取代，丢弃
                        String content = response.body();
                        if (content == null || !content.startsWith("#EXTM3U")) {
                            PlayTrace.stage("净化", "不是 HLS 清单，直连 ms=" + (System.currentTimeMillis() - t0));
                            startPlayUrl(url, raw);
                            return;
                        }

                        String[] lines = null;
                        if (content.contains("\r\n"))
                            lines = content.split("\r\n", 10);
                        else
                            lines = content.split("\n", 10);
                        String forwardurl = "";
                        boolean dealedFirst = false;
                        for (String line : lines) {
                            if (!"".equals(line) && line.charAt(0) != '#') {
                                if (dealedFirst) {
                                    //跳转行后还有内容，说明不需要跳转
                                    forwardurl = "";
                                    break;
                                }
                                if (line.endsWith(".m3u8") || line.contains(".m3u8?")) {
                                    if (line.startsWith("http://") || line.startsWith("https://")) {
                                        forwardurl = line;
                                    } else if (line.charAt(0)=='/' ) {
                                        int ifirst = url.indexOf('/', 9);//skip https://, http://
                                        forwardurl = url.substring(0, ifirst) + line;
                                    } else {
                                        int ilast = url.lastIndexOf('/');
                                        forwardurl = url.substring(0, ilast + 1) + line;
                                    }
                                }
                                dealedFirst = true;
                            }
                        }
                        if ("".equals(forwardurl)) {
                            startPurified(url, url, content, raw, t0);
                            return;
                        }
                        final String finalforwardurl = forwardurl;
                        withPurifyClient(OkGo.<String>get(forwardurl)
                                .tag("m3u8-2")
                                .headers(hheaders))
                                .execute(new AbsCallback<String>() {
                                    @Override
                                    public void onSuccess(Response<String> response) {
                                        if (gen != mPurifyGen) return; // 已被更新的播放请求取代，丢弃
                                        String content = response.body();
                                        if (content == null || !content.startsWith("#EXTM3U")) {
                                            PlayTrace.stage("净化", "跳转层不是 HLS 清单，直连 ms=" + (System.currentTimeMillis() - t0));
                                            startPlayUrl(finalforwardurl, raw);
                                            return;
                                        }
                                        startPurified(url, finalforwardurl, content, raw, t0);
                                    }

                                    @Override
                                    public String convertResponse(okhttp3.Response response) throws Throwable {
                                        return response.body().string();
                                    }

                                    @Override
                                    public void onError(Response<String> response) {
                                        super.onError(response);
                                        if (gen != mPurifyGen) return;
                                        PlayTrace.stage("净化", "跳转层失败，改用原始地址 ms=" + (System.currentTimeMillis() - t0));
                                        startPlayUrl(url, raw);
                                    }
                                });
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        if (gen != mPurifyGen) return;
                        PlayTrace.stage("净化", "预取失败或超时(" + purifyFailKind(url, response)
                                + ")，直连起播 ms=" + (System.currentTimeMillis() - t0));
                        startPlayUrl(url, raw);
                    }
                });
    }

    /**
     * 净化内容就绪后的收尾。
     *
     * 能识别出"少数派分片"（＝广告）就改走本地 HLS 端点，顺带把"这是 HLS"这件事明确告诉播放器
     * （端点带 .m3u8 后缀，播放器就不会先把清单当普通视频试一次 = 消除"第一下播不出、切一下就好"）；
     * 识别不出就老老实实直连原地址 —— 净化是加分项，不能因为改不动内容就播不了。
     *
     * @param rawUrl  净化前的原始地址，供"本地代理这条路播不出"时回退
     * @param listUrl 真正取到清单的地址（可能是跳转后的那一层），分片相对路径要按它补全
     */
    private void startPurified(String rawUrl, String listUrl, String content,
                               HashMap<String, String> headers, long t0) {
        String purified;
        try {
            int ilast = listUrl.lastIndexOf('/');
            purified = removeMinorityUrl(listUrl.substring(0, ilast + 1), content);
        } catch (Throwable th) {
            th.printStackTrace();
            purified = null;
        }
        if (purified == null) {
            PlayTrace.stage("净化", "无需处理，直连 ms=" + (System.currentTimeMillis() - t0));
            startPlayUrl(listUrl, headers);
            return;
        }
        RemoteServer.m3u8Content = purified;
        mFallbackRawUrl = rawUrl; // 本地代理这条路播不出时回退到它
        PlayTrace.stage("净化", "命中，走本地 HLS（免一次容器误判）ms=" + (System.currentTimeMillis() - t0));
        startPlayUrl(localHlsUrl(), headers);
    }

    /**
     * 去 BOM 重试（本地实现）。自己拉一份清单、剥掉 BOM，再经内置代理喂给播放器，
     * 不依赖任何外部服务（改造前是把地址转发给第三方去 BOM 接口）。只在"同一地址已经失败过一次"
     * 时才会走到（autoRetryCount>0），不影响首次起播耗时。
     * 本地代理不可用、拉取失败、或内容根本不是清单时，一律退回按原地址播 —— 兜底不能变成阻碍。
     */
    private void retryWithoutBom(final String url, final HashMap<String, String> headers) {
        mBomRetried = true;
        if (RemoteServer.serverPort <= 0) {
            startPlayUrl(url, headers);
            return;
        }
        PlayTrace.stage("去BOM", "本地重试 url=" + PlayTrace.brief(url));
        HttpHeaders hheaders = new HttpHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> s : headers.entrySet()) {
                hheaders.put(s.getKey(), s.getValue());
            }
        }
        withPurifyClient(OkGo.<String>get(url)
                .tag("bom-1")
                .headers(hheaders))
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String content = stripBom(response.body());
                        if (content == null || !content.startsWith("#EXTM3U")) {
                            PlayTrace.stage("去BOM", "不是清单，按原地址播");
                            startPlayUrl(url, headers);
                            return;
                        }
                        RemoteServer.m3u8Content = content;
                        mFallbackRawUrl = url; // 本地代理这条路播不出时回退到它
                        PlayTrace.stage("去BOM", "命中，改走本地 HLS");
                        startPlayUrl(localHlsUrl(), headers);
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        PlayTrace.stage("去BOM", "拉取失败，按原地址播");
                        startPlayUrl(url, headers);
                    }
                });
    }

    /**
     * 地址是否能交给播放内核。
     *
     * 判据只有一条：必须带 scheme（含 "://"）。内核（Exo/IJK）都按 URL 解析，没有 scheme 的字符串必然失败，
     * 而且失败得很贵 —— 会走完整条兜底链。实测：某站点同一集两分钟内一次给 https 直链（能播）、
     * 一次给 `Ksvideo-<hex>`（全工程没有这个协议），后者让界面空转约 12 秒才给提示。
     *
     * magnet:/thunder/ed2k/.torrent 同样不带 "://"，但它们有专用通路（play() 里先经 Thunder.play 处理），
     * 所以交给那个模块自己判，不在这里拦。
     */
    private static boolean isPlayableUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return url.contains("://") || Thunder.isSupportUrl(url);
    }

    void startPlayUrl(String url, HashMap<String, String> rawHeaders) {
        LOG.i("playUrl:" + url);
        // 不是 URL 的地址直接判掉，不进播放器、也不进兜底链（那些步骤对它一律无效）。
        // 出口仍走 errorWithRetry，所以"静默自动重取一次"保留着：源侧轮换出可用地址时能自动救回来。
        if (!isPlayableUrl(url)) {
            PlayTrace.fail("地址", "站点下发的地址不是 URL（不含 ://），跳过播放器与兜底链", -1);
            cancelStartWatchdog();
            errorWithRetry("站点返回的地址无法播放");
            return;
        }
        // 这一层不再补 Referer：它会把「源站 Referer」写进播放器的默认请求头，使跨域 CDN 上的
        // 分片请求被 403（详见 playUrl 顶部说明）。请求头一律按调用方给的用。
        final HashMap<String, String> headers = rawHeaders;
        // 记录真正交给播放器的地址与请求头，供兼容性兜底按同一地址重播
        mPlayingUrl = url;
        mPlayingHeaders = (headers == null) ? null : new HashMap<>(headers);
        mPlayStartedAt = System.currentTimeMillis();
        // 起播的不是本地净化地址：作废上一次的净化内容，免得换集时被读到上一份残留；
        // 同时"回退直连"也就无从谈起了（已经在用真实地址）
        if (!isLocalHlsUrl(url)) {
            RemoteServer.m3u8Content = null;
            mFallbackRawUrl = null;
        }
        PlayTrace.stage("起播", "url=" + PlayTrace.brief(url)
                + (fbOverride != null ? " 兜底第" + fbPlanIdx + "档" : ""));
        // 去 BOM 兜底（本地实现）：同一地址已经失败过一次、且是远端 m3u8 时，本地拉一份清单、
        // 剥掉 BOM 后经内置代理播。原实现是把地址转发给第三方去 BOM 接口 ——
        // 那本身就是个新的失败点（第三方异常＝凭空多一次失败），也会把整份清单发到外部。
        // 守卫用 isLoopbackUrl：旧写法 `!url.contains("://127.0.0.1/")` 带了斜杠，
        // 而真实本地地址是 `http://127.0.0.1:9978/proxy?...`（端口插在中间）⇒ 匹配不上、守卫失效，
        // 实测对网盘源的本地代理死地址白做了一次拉取（必然失败），这里一并修正。
        if (autoRetryCount > 0 && !mBomRetried && url.toLowerCase().contains(".m3u8")
                && !isLocalHlsUrl(url)
                && !isLoopbackUrl(url)) {
            retryWithoutBom(url, headers);
            return;
        }
        String finalUrl = url;
        if (mActivity == null || !isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            stopParse();
            // 兜底期间用临时配置副本，用户设置里的播放器/解码不受影响
            JSONObject playCfg = (fbOverride != null) ? fbOverride : mVodPlayerCfg;
            if (mVideoView != null) {
                mVideoView.release();

                if (finalUrl != null) {
                    int playerType = playCfg.optInt("pl", PlayerHelper.BUILTIN_EXO);
                    // 判据统一走 isExternalPlayer（配置里的 pl 只可能是 0/1/2/10~14，
                    // 用 `>= 10` 眼下也不会出错，但把"哪些编号算外部"这件事收口到一个地方，
                    // 免得以后再出现 100/101 那种被粗判误伤的内置项）
                    if (PlayerHelper.isExternalPlayer(playerType)) {
                        String extName = PlayerHelper.getPlayerName(playerType);
                        String playTitle = mVodInfo.name;
                        long progress = 0;
                        try {
                            VodInfo.VodSeries vs = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
                            playTitle = mVodInfo.name + " " + vs.name;
                            progress = getSavedProgress(progressKey);
                        } catch (Throwable th) {
                            // 取标题失败不该让这一集播不了：标题退化成剧名，进度按 0
                            th.printStackTrace();
                        }
                        setTip("调用外部播放器" + extName + "进行播放", true, false);
                        boolean callResult = PlayerHelper.runExternalPlayer(playerType, requireActivity(), finalUrl, playTitle, playSubtitle, headers, progress);
                        if (callResult) {
                            setTip("调用外部播放器" + extName + "成功", true, false);
                            return;
                        }
                        // 吊起失败（没装 / 被系统拦下）不能就此算这一集播不了：退回内置接着播。
                        // 只改本次播放的临时配置副本，用户设置不动 —— 下次手动选外部仍会先尝试吊起。
                        JSONObject builtinCfg = PlayerHelper.copyWithBuiltinFallback(playCfg);
                        if (builtinCfg == null) {
                            PlayTrace.fail("外部播放器", extName + " 调用失败，且回退内置的配置副本构造失败", -1);
                            setTip("调用外部播放器" + extName + "失败", false, true);
                            return;
                        }
                        playCfg = builtinCfg;
                        PlayTrace.stage("外部播放器", extName + " 调用失败，回退内置 pl=" + PlayerHelper.fallbackBuiltinType());
                        ToastUtils.showShort("外部播放器调用失败，已改用内置播放器播放");
                    }
                    hideTip();
                    PlayerHelper.updateCfg(mVideoView, playCfg);
                    mVideoView.setProgressKey(progressKey);
                    if (headers != null) {
                        mVideoView.setUrl(finalUrl, headers);
                    } else {
                        mVideoView.setUrl(finalUrl);
                    }
                    mVideoView.start();
                    armStartWatchdog(); // 黑屏转圈也要有个尽头：起播无进展时进兜底链
                    mController.resetSpeed();
                }
            }
        });
    }

    private void initSubtitleView() {
        TrackInfo trackInfo = null;
        if (mVideoView.getMediaPlayer() instanceof IjkMediaPlayer) {
            trackInfo = ((IjkMediaPlayer) (mVideoView.getMediaPlayer())).getTrackInfo();
            if (trackInfo != null && trackInfo.getSubtitle().size() > 0) {//如有则设置内置字幕
                mController.mSubtitleView.hasInternal = true;
            }
            ((IjkMediaPlayer) (mVideoView.getMediaPlayer())).setOnTimedTextListener(new IMediaPlayer.OnTimedTextListener() {
                @Override
                public void onTimedText(IMediaPlayer mp, IjkTimedText text) {
                    if (mController.mSubtitleView.isInternal) {
                        com.github.tvbox.osc.subtitle.model.Subtitle subtitle = new com.github.tvbox.osc.subtitle.model.Subtitle();
                        subtitle.content = text.getText();
                        mController.mSubtitleView.onSubtitleChanged(subtitle);
                    }
                }
            });
        }

        if (mVideoView.getMediaPlayer() instanceof EXOmPlayer) {
            trackInfo = ((EXOmPlayer) (mVideoView.getMediaPlayer())).getTrackInfo();
            if (trackInfo != null && trackInfo.getSubtitle().size() > 0) {
                mController.mSubtitleView.hasInternal = true;
            }
            ((EXOmPlayer) (mVideoView.getMediaPlayer())).setOnTimedTextListener(new Player.Listener() {
                @Override
                public void onCues(@NonNull List<Cue> cues) {
                    if (cues.size() > 0) {
                        CharSequence ss = cues.get(0).text;
                        if (ss != null && mController.mSubtitleView.isInternal) {
                            com.github.tvbox.osc.subtitle.model.Subtitle subtitle = new com.github.tvbox.osc.subtitle.model.Subtitle();
                            subtitle.content = ss.toString();
                            mController.mSubtitleView.onSubtitleChanged(subtitle);
                        }
                    } else{
                        mController.mSubtitleView.onSubtitleChanged(null);
                    }
                }
            });
        }

        mController.mSubtitleView.bindToMediaPlayer(mVideoView.getMediaPlayer());
        mController.mSubtitleView.setPlaySubtitleCacheKey(subtitleCacheKey);
        String subtitlePathCache = (String) CacheManager.getCache(MD5.string2MD5(subtitleCacheKey));
        if (subtitlePathCache != null && !subtitlePathCache.isEmpty()) {
            mController.mSubtitleView.setSubtitlePath(subtitlePathCache);
        } else {
            if (playSubtitle != null && playSubtitle.length() > 0) {
                mController.mSubtitleView.setSubtitlePath(playSubtitle);
            } else {
                if (mController.mSubtitleView.hasInternal) {//有则使用内置字幕
                    mController.mSubtitleView.isInternal = true;
                    if (trackInfo != null && !trackInfo.getSubtitle().isEmpty()) {
                        List<TrackInfoBean> subtitleTrackList = trackInfo.getSubtitle();
                        int selectedIndex = trackInfo.getSubtitleSelected(true);
                        boolean hasCh =false;
                        for(TrackInfoBean subtitleTrackInfoBean : subtitleTrackList) {
                            String lowerLang = subtitleTrackInfoBean.language.toLowerCase();
                            if (lowerLang.contains("zh") || lowerLang.contains("ch")) {
                                hasCh=true;
                                if (selectedIndex != subtitleTrackInfoBean.trackId) {
                                    if (mVideoView.getMediaPlayer() instanceof IjkMediaPlayer){
                                        ((IjkMediaPlayer)(mVideoView.getMediaPlayer())).setTrack(subtitleTrackInfoBean.trackId);
                                    }else if (mVideoView.getMediaPlayer() instanceof EXOmPlayer){
                                        ((EXOmPlayer)(mVideoView.getMediaPlayer())).selectExoTrack(subtitleTrackInfoBean);
                                    }
                                    break;
                                }
                            }
                        }
                        if(!hasCh){
                            if (mVideoView.getMediaPlayer() instanceof IjkMediaPlayer){
                                ((IjkMediaPlayer)(mVideoView.getMediaPlayer())).setTrack(subtitleTrackList.get(0).trackId);
                            }else if (mVideoView.getMediaPlayer() instanceof EXOmPlayer){
                                ((EXOmPlayer)(mVideoView.getMediaPlayer())).selectExoTrack(subtitleTrackList.get(0));
                            }
                        }
                    }
                }
            }
        }
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.playResult.observeForever(mObserverPlayResult);
    }

    private final Observer<JSONObject> mObserverPlayResult= new Observer<JSONObject>() {
        @Override
        public void onChanged(JSONObject info) {
            finishFetchWatchdog(); // 有结论了（哪怕是空），取链看门狗收工
            if (info != null) {
                try {
                    progressKey = info.optString("proKey", null);
                    boolean parse = info.optString("parse", "1").equals("1");
                    boolean jx = info.optString("jx", "0").equals("1");
                    playSubtitle = info.optString("subt", /*"https://dash.akamaized.net/akamai/test/caption_test/ElephantsDream/ElephantsDream_en.vtt"*/"");
                    subtitleCacheKey = info.optString("subtKey", null);
                    String playUrl = info.optString("playUrl", "");
                    String flag = info.optString("flag");
                    String url = info.getString("url");
                    HashMap<String, String> headers = null;
                    webUserAgent = null;
                    webHeaderMap = null;
                    if (info.has("header")) {
                        try {
                            JSONObject hds = new JSONObject(info.getString("header"));
                            Iterator<String> keys = hds.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                if (headers == null) {
                                    headers = new HashMap<>();
                                }
                                headers.put(key, hds.getString(key));
                                if (key.equalsIgnoreCase("user-agent")) {
                                    webUserAgent = hds.getString(key).trim();
                                }
                            }
                            webHeaderMap = headers;
                        } catch (Throwable th) {

                        }
                    }
                    if (parse || jx) {
                        boolean userJxList = (playUrl.isEmpty() && ApiConfig.get().getVipParseFlags().contains(flag)) || jx;
                        PlayTrace.stage("取链", "走解析 flag=" + flag + " jx=" + jx + " 用户解析=" + userJxList);
                        initParse(flag, userJxList, playUrl, url);
                    } else {
                        mController.showParse(false);
                        PlayTrace.stage("取链", "拿到直链 url=" + PlayTrace.brief(playUrl + url));
                        playUrl(playUrl + url, headers);
                    }
                } catch (Throwable th) {
                    // 这里刻意不直接报错：旧版把 errorWithRetry 注释掉了（怕正常流程也命中该分支），
                    // 后果是异常时界面永远停在「正在获取播放信息」，用户只能看转圈。
                    // 改由取链看门狗统一兜底：15 秒没结论才处理，不会因单次异常误报。
                    PlayTrace.fail("取链", "回调内异常 " + th, -1);
                    LogUtils.e(th.toString());
//                        errorWithRetry("获取播放信息错误");
//                        Toast.makeText(mContext, "获取播放信息错误1", Toast.LENGTH_SHORT).show();
                }
            } else {
                PlayTrace.fail("取链", "站点未返回播放信息", -1);
                errorWithRetry("获取播放信息错误");
//                    Toast.makeText(mContext, "获取播放信息错误", Toast.LENGTH_SHORT).show();
            }
        }
    };

    public void setData(Bundle bundle) {
//        mVodInfo = (VodInfo) bundle.getSerializable("VodInfo");
        mVodInfo = App.getInstance().getVodInfo();
        sourceKey = bundle.getString("sourceKey");
        sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) { // 换源后旧 sourceKey 会失效，继续初始化播放器配置会闪退
            errorWithRetry("站点已失效，请返回重试");
            return;
        }
        initPlayerCfg();
        play(false);
    }

    private void initData() {
        /*Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {

        }*/
    }

    void initPlayerCfg() {
        try {
            mVodPlayerCfg = new JSONObject(mVodInfo.playerCfg);
        } catch (Throwable th) {
            mVodPlayerCfg = new JSONObject();
        }
        // 诊断用：这次播放器配置是从哪儿来的（看日志就能判断"跟默认走"有没有被绕过）
        String cfgFrom = "影视级快照";
        try {
            // 换线路会重新走这里（setData 只有 jumpToPlay 一个调用点）：优先取「本片·本线路」的存档，
            // 换线路互不影响（用户 09-18 口径：切播放器只作用于同线路本片的所有集）。
            String lineCfg = mVodInfo.getLinePlayerCfg(mVodInfo.playFlag);
            if (!TextUtils.isEmpty(lineCfg)) {
                try {
                    mVodPlayerCfg = new JSONObject(lineCfg);
                    cfgFrom = "本线路存档";
                } catch (Throwable ignored) {
                }
            } else if (isLegacyOnlyCfg()) {
                // 只有「整张线路表都是空的 + 有影视级配置」才按升级前的旧数据认领给当前线路。
                // 不能只看"当前线路没存档"：换了条新线路时 playerCfg 还是上一条线路的快照，
                // 那样会把上一条线路的播放器选择搬到新线路上（串味）。
                adoptLegacyPlayerCfg();
                cfgFrom = "旧数据认领";
            } else {
                // 本线路没有存档。此刻 mVodPlayerCfg 还是上面从「影视级快照」读进来的那份，
                // 而它记的是**上一条线路**最后写下的配置（含那条线路的 pl 与 plt 标记）——
                // 直接沿用会让新线路"继承"上一条线路的播放器选择（实测：线路1 切 IJK 软解后，
                // 线路2/3 跟着全变 IJK 软解）。这里显式重建成空白配置，往下一路按设置页默认补齐。
                mVodPlayerCfg = new JSONObject();
                cfgFrom = "无存档重建";
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        try {
            // 没手动选过播放器（没有 plt 标记）时，播放器与解码档每次都跟设置页默认走。
            // 判据不能用"配置里有没有 pl"：改比例/速度/起播跳转也会把整份配置（含 pl）写回，
            // 那不代表用户选过播放器，旧写法会让这些片子永久跟死当时的默认值。
            // 全局默认设成外部播放器也照用（吊不起来会在 startPlayUrl 里自动退回内置接着播）。
            if (!PlayerHelper.isUserPicked(mVodPlayerCfg)) {
                mVodPlayerCfg.put("pl", PlayerHelper.defaultPlayerType(sourceBean));
                mVodPlayerCfg.put("ijk", Hawk.get(HawkConfig.IJK_CODEC, ""));
            }
            if (!mVodPlayerCfg.has("pr")) {
                mVodPlayerCfg.put("pr", Hawk.get(HawkConfig.PLAY_RENDER, 0));
            }
            if (!mVodPlayerCfg.has("ijk")) {
                mVodPlayerCfg.put("ijk", Hawk.get(HawkConfig.IJK_CODEC, ""));
            }
            if (!mVodPlayerCfg.has("sc")) {
                mVodPlayerCfg.put("sc", Hawk.get(HawkConfig.PLAY_SCALE, 0));
            }
            if (!mVodPlayerCfg.has("sp")) {
                mVodPlayerCfg.put("sp", 1.0f);
            }
            if (!mVodPlayerCfg.has("st")) {
                mVodPlayerCfg.put("st", 0);
            }
            if (!mVodPlayerCfg.has("et")) {
                mVodPlayerCfg.put("et", 0);
            }
        } catch (Throwable th) {

        }
        PlayTrace.diag("播放器配置 片=" + mVodInfo.name + " 线路=" + mVodInfo.playFlag + " 来源=" + cfgFrom
                + " pl=" + mVodPlayerCfg.optInt("pl", -1) + " plt=" + mVodPlayerCfg.optInt(PlayerHelper.KEY_USER_PICKED, 0)
                + " 解码档=" + mVodPlayerCfg.optString("ijk", ""));
        savePlayerCfgToLine();
        mController.setPlayerConfig(mVodPlayerCfg);
    }

    /**
     * 是不是"升级前的老记录"：整张线路表都是空的，但有影视级那份配置。
     * 一起看这两条才不会把上一条线路的配置搬到新线路上。
     */
    private boolean isLegacyOnlyCfg() {
        try {
            boolean noLineCfg = (mVodInfo.flagPlayerCfgMap == null) || mVodInfo.flagPlayerCfgMap.isEmpty();
            return noLineCfg && !TextUtils.isEmpty(mVodInfo.playerCfg);
        } catch (Throwable th) {
            th.printStackTrace();
            return false;
        }
    }

    /**
     * 旧数据（升级前影视级一份配置）的认领：记到当前线路名下，并判断当年有没有手动切过播放器。
     *
     * 判据是"配置里的 pl 与当前默认是否一致"：不一致说明是当年手动选的，认领为手动（保持原样、不冲掉）；
     * 一致则当作"只是调过比例/速度带出去的值"，不加标记，之后继续跟设置页默认走。
     * 判不出来时按手动处理（保守，宁可不改用户的既成选择）。
     */
    private void adoptLegacyPlayerCfg() {
        try {
            int pl = mVodPlayerCfg.optInt("pl", -1);
            if (pl < 0) return;
            int def = PlayerHelper.defaultPlayerType(sourceBean);
            int siteDef = -1;
            try {
                if (sourceBean != null) siteDef = sourceBean.getPlayerType();
            } catch (Throwable ignored) {
            }
            boolean looksDefault = (pl == def) || (pl >= 0 && pl == siteDef);
            if (!looksDefault) PlayerHelper.markUserPicked(mVodPlayerCfg);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 写回「本片·本线路」存档，并同步 playerCfg 快照（历史记录随后由 EventBus 那条链路落盘）。 */
    void savePlayerCfgToLine() {
        try {
            if (mVodInfo == null || mVodPlayerCfg == null) return;
            mVodInfo.playerCfg = mVodPlayerCfg.toString();
            mVodInfo.setLinePlayerCfg(mVodInfo.playFlag, mVodInfo.playerCfg);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null) {
            if (mController.onKeyEvent(event)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mVideoView != null) {
            mVideoView.pause();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mVideoView != null) {
            mVideoView.resume();
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) {
            if (mVideoView != null) {
                mVideoView.pause();
            }
        } else {
            if (mVideoView != null) {
                mVideoView.resume();
            }
        }
        super.onHiddenChanged(hidden);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 视图销毁后看门狗必须停：否则它们会在十几秒后对着已释放的播放器做兜底
        cancelStartWatchdog();
        finishFetchWatchdog();
        //手动注销
        sourceViewModel.playResult.removeObserver(mObserverPlayResult);

        EventBus.getDefault().unregister(this);
        if (mVideoView != null) {
            mVideoView.release();
            mVideoView = null;
        }
        stopLoadWebView(true);
        stopParse();
        Thunder.stop(true);//停止磁力下载
        Jianpian.finish();//停止p2p下载
    }

    private VodInfo mVodInfo;
    private JSONObject mVodPlayerCfg;
    private String sourceKey;
    private SourceBean sourceBean;

    public void playNext(boolean isProgress) {
        // 正序:列表下一项即下一集;倒序:列表已反序,下一集(集数+1)在列表的上一项。
        // 不做方向判断的话,倒序时点下一集会沿反序列表往后走,集数反而变小(第8集跳到第7集/第22集)。
        int next = nextSeriesIndex(+1);
        if (next < 0) {
            Toast.makeText(requireContext(), "已经是最后一集了!", Toast.LENGTH_SHORT).show();
            return;
        }
        mVodInfo.playIndex = next;
        // 先在选集里确定集数(更新高亮)，再跳集播放，保证选集与在播集一致
        markSeriesSelected(next);
        play(false);
    }

    public void playPrevious() {
        // 与 playNext 同理:倒序时上一集(集数-1)在列表的下一项
        int prev = nextSeriesIndex(-1);
        if (prev < 0) {
            Toast.makeText(requireContext(), "已经是第一集了!", Toast.LENGTH_SHORT).show();
            return;
        }
        mVodInfo.playIndex = prev;
        // 先在选集里确定集数(更新高亮)，再跳集播放，保证选集与在播集一致
        markSeriesSelected(prev);
        play(false);
    }

    /**
     * 按集数方向(target 步长为 +1 表示"集数+1")算出目标集在选集列表中的下标；越界返回 -1。
     * 倒序时列表已反序，列表下标方向与集数方向相反，故步长取反。
     * 基准取"当前高亮的那一集"，比 playIndex 可靠——切换正倒序后 playIndex 可能与列表错位。
     */
    private int nextSeriesIndex(int targetStep) {
        if (mVodInfo == null) return -1;
        List<VodInfo.VodSeries> list = mVodInfo.seriesMap.get(mVodInfo.playFlag);
        if (list == null || list.isEmpty()) return -1;

        int cur = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).selected) { cur = i; break; }
        }
        if (cur < 0) {
            cur = mVodInfo.playIndex;
            if (cur < 0) cur = 0;
            if (cur >= list.size()) cur = list.size() - 1;
        }

        int step = mVodInfo.reverseSort ? -targetStep : targetStep;
        int idx = cur + step;
        return (idx < 0 || idx >= list.size()) ? -1 : idx;
    }

    /** 把指定 playIndex 的集在选集列表里标记为选中(清除其它)，使"选集高亮"与"在播集"始终保持一致 */
    private void markSeriesSelected(int index) {
        if (mVodInfo == null) return;
        List<VodInfo.VodSeries> list = mVodInfo.seriesMap.get(mVodInfo.playFlag);
        if (list == null || index < 0 || index >= list.size()) return;
        for (int i = 0; i < list.size(); i++) list.get(i).selected = (i == index);
        // 同步刷新详情页的选集高亮：播放器内跳集(下一集/上一集/自动连播)改的是同一份 VodInfo 数据，
        // 不通知 adapter 的话,退出全屏或再次打开选集面板时高亮仍停在跳集前的旧集上。
        if (mActivity instanceof DetailActivity) {
            DetailActivity da = (DetailActivity) mActivity;
            if (da.seriesAdapter != null) {
                mActivity.runOnUiThread(() -> da.seriesAdapter.notifyDataSetChanged());
            }
        }
    }

    /**
     * 播放失败的确定性降级兜底：按"兼容性从高到低"的兜底阶梯（IJK 软解码 → Exo）重播同一地址。
     * 只在内置播放器之间切换，绝不调用外部播放器；同一集最多兜底 2 次，换集/换解析接口自动重置。
     * 兜底用独立的配置副本，不写回 mVodPlayerCfg，也不改 Hawk 里的用户默认播放器。
     */
    private boolean compatFallback() {
        if (mVideoView == null || mVodPlayerCfg == null || mVodInfo == null) return false;
        if (TextUtils.isEmpty(mPlayingUrl)) return false;
        String key = mVodInfo.playFlag + "#" + mVodInfo.playIndex;
        if (!key.equals(fbKey)) { // 换集：重新给满兜底额度
            fbKey = key;
            fbPlan = null;
            fbPlanIdx = 0;
            fbOverride = null;
        }
        if (fbPlan == null) fbPlan = PlayerHelper.buildCompatFallbackPlan(mVodPlayerCfg);
        if (fbPlanIdx >= fbPlan.size()) return false;
        fbOverride = fbPlan.get(fbPlanIdx++);
        // 变形重试会把 mPlayingUrl 换成"同址改协议"的地址，而那个协议对不少源根本不存在
        // （例如 http 端口硬写成 https）。降级再拿这个死地址去播，等于把仅剩的额度浪费掉，
        // 所以只要变形试过，降级一律回到变形前的原地址。
        String useUrl = (mVariantTried && !TextUtils.isEmpty(mVariantBaseUrl)) ? mVariantBaseUrl : mPlayingUrl;
        PlayTrace.stage("兜底", "降级到 " + PlayerHelper.getPlayerName(fbOverride.optInt("pl", 1))
                + "（第 " + fbPlanIdx + "/" + fbPlan.size() + " 档）url=" + PlayTrace.brief(useUrl));
        setTip("播放失败，已切为" + PlayerHelper.getPlayerName(fbOverride.optInt("pl", 1)) + "重试", true, false);
        startPlayUrl(useUrl, mPlayingHeaders);
        return true;
    }

    /**
     * 回退原始直连地址。
     *
     * 净化命中后，播放器拿到的是 127.0.0.1 上的本地清单，分片地址虽已被改写成源站绝对地址，
     * 但清单本身依赖本地服务与那份被改写过的内容：一旦改写误删了关键分片行、或本地服务异常，
     * 拿着这个地址重试多少次都是同一个坏结果。所以失败后先换回净化前的原始地址直连一次 ——
     * 代价是这一集可能带片头广告，换来的是"能播"。
     *
     * 只回退一次（回退后立刻置空），失败后继续走内核降级阶梯。
     */
    private boolean fallbackToRawUrl() {
        if (TextUtils.isEmpty(mFallbackRawUrl)) return false;
        String raw = mFallbackRawUrl;
        mFallbackRawUrl = null;
        PlayTrace.stage("兜底", "回退原始直连 url=" + PlayTrace.brief(raw));
        startPlayUrl(raw, mPlayingHeaders);
        return true;
    }

    /**
     * 同址换协议重试：只在 http↔https 之间换，地址其余部分一字不改。
     * 有些源的两种协议由不同节点承载，一种不通另一种仍然可用；成本只有一次重播，
     * 既不动播放器也不动解码档，所以排在"内置降级"之前（比换内核更轻）。
     */
    private boolean retryVariantUrl() {
        if (mVariantTried || TextUtils.isEmpty(mPlayingUrl)) return false;
        if (isLoopbackUrl(mPlayingUrl)) return false; // 本机代理地址换协议必失败（/l1.m3u8 与 /proxy 都算）
        // 只在标准端口（http 80 / https 443 / 不写端口）上试。
        // 实测历史触发的 4 次全部失败，其中 3 次是 `http://...:9090/...` 这类私有端口 ——
        // 同一个私有端口上不可能同时存在两套协议的服务，换过去必然连不上，只是白耗一次起播。
        if (!hasStandardPort(mPlayingUrl)) {
            PlayTrace.stage("变形", "非标准端口，跳过 http↔https 重试");
            return false;
        }
        String u = mPlayingUrl.toLowerCase();
        String variant;
        if (u.startsWith("https://")) {
            variant = "http://" + mPlayingUrl.substring(8);
        } else if (u.startsWith("http://")) {
            variant = "https://" + mPlayingUrl.substring(7);
        } else {
            return false; // rtmp/rtsp 等不在此列
        }
        mVariantTried = true;
        mVariantBaseUrl = mPlayingUrl; // 降级时必须回到这个地址（见 compatFallback）
        PlayTrace.stage("变形", "http↔https 重试 url=" + PlayTrace.brief(variant));
        setTip("正在尝试备用协议重连", true, false);
        startPlayUrl(variant, mPlayingHeaders);
        return true;
    }

    /** 地址是否走标准端口（不写端口 / :80 / :443）——只有这种才可能同时存在 http 与 https 两套服务 */
    private static boolean hasStandardPort(String url) {
        try {
            String u = url.toLowerCase();
            int i = u.indexOf("://");
            if (i < 0) return false;
            u = u.substring(i + 3);
            int slash = u.indexOf('/');
            if (slash >= 0) u = u.substring(0, slash);
            int colon = u.indexOf(':');
            if (colon < 0) return true; // 没写端口＝默认端口
            String port = u.substring(colon + 1);
            return "80".equals(port) || "443".equals(port);
        } catch (Throwable th) {
            return false; // 解析不了就不试（不试只是少一次机会，试错会多一次失败）
        }
    }

    private int autoRetryCount = 0;
    /**
     * 重取代次。换集、用户手动点「重试」时自增，排队中的那次退避重取发现代次变了就直接作废。
     * 只比对"集标识"是不够的：用户在同一集上点「重试」时集标识并没有变，队列里那次重取
     * 仍会照跑，于是和用户刚发起的那次撞成两个并发取链请求。
     */
    private int mRetryGen = 0;

    /**
     * 自动重取的退避间隔。
     *
     * 改动前这里是"立刻再取一次"：`errorWithRetry → autoRetry → play(false)` 全程同步调用，
     * 实测两次取链请求之间只隔 **2~8 毫秒** —— 等于把同一个请求连打两遍，源侧还没轮到换数据
     * 就又被打了一次，结果必然是同一个空返回。用户看到的就是"一进去就报错、根本没试"（全程 271ms）。
     * 退避到秒级才有意义：实测源侧抖动的恢复窗口正是秒级（同站同集失败后 2.4 秒就拿到了直链）。
     */
    private static final long[] AUTO_RETRY_DELAY_MS = {1200, 2500};
    /**
     * 取链阶段的重取总预算（从本集开始算）。
     * 用户口径：从点播到看见「重试」约 8 秒。退避重取跑不完两档（比如每次取链本身就很慢）时
     * 就不再硬等，直接在预算内给明确结论 —— 宁可少试一次，也不让等待超过承诺的时长。
     */
    private static final long FETCH_RETRY_BUDGET_MS = 8000;

    // ---- P1（2026-09-22 拍板）：兜底链缺「重新取链」档 ----
    // 当日两例（播放中上游 404、起播兜底试尽）都在同一条死链上打转：换地址/协议/内核全是对着
    // 同一个 URL 重播，唯一有效的动作"重新取链"反而只能靠用户手动点「重试」。两例手动重取
    // 都立刻救回（403ms / 627ms 起播）。这里把那个动作自动化，护栏两道：
    // ① 本集只自动重新取链一次（换集/手动重试/重播/换解析时复位）；
    // ② 从未就绪过的（起播阶段）受总时长上限约束 —— "每一步都空转"的病理在那里
    //    （见 errorFinal 注释），不加界最坏等待会被拖过承诺；就绪过再死的不受此限，
    //    因为地址已被证明有效，中途 404 可发生在任意时刻。
    private static final long REFETCH_TOTAL_BUDGET_MS = 20000;
    /** 本集是否已经自动重新取链过（once 护栏） */
    private boolean mRefetchTried = false;
    /** 本集播放是否就绪过（prepared 过）：区分「播放中断」与「起播就没起来」两条护栏路径 */
    private boolean mPlaybackHadStarted = false;

    /**
     * 自动重取：安排一次"退避之后重新取链"，返回 true 表示已经接管（调用方不要再显示失败提示）。
     *
     * 唯一目的是**别因为试得太快而把本来能播的源判成播不了**：
     * 改动前这里是零间隔重打（实测两次请求只隔 2~8 毫秒）＋只重试 1 次，等于没试就报错
     * （用户原话「不要一下不试就报错」）。退避到秒级、给到 2 次，让源侧的轮换窗口真的过去。
     *
     * ⚠ 不要在这里加"重试 N 次"这类提示文案：重取期间屏幕上原有的 loading 状态会继续显示，
     * 再加一层把内部机制外泄的计数，用户会认为多余（09-17 反馈原话：「这个是不是有点多余」）。
     * 次数用尽或预算用尽才返回 false，由调用方给出可点「重试」的明确提示。
     */
    boolean autoRetry() {
        return autoRetry(FETCH_RETRY_BUDGET_MS);
    }

    /**
     * 带预算参数的重载。budgetMs ≤ 0＝不做总预算检查——仅供 tryRefetchOnce（P1）的
     * 「播放中断」路径使用：那条路有自己的 once 护栏，且就绪过再死的集，失败时刻可能
     * 远超取链预算，若套用 8s 预算会永远轮不到重取。
     */
    boolean autoRetry(long fetchBudgetMs) {
        if (loadFoundVideoUrls != null && loadFoundVideoUrls.size() > 0) {
            autoRetryFromLoadFoundVideoUrls();
            return true;
        }
        if (autoRetryCount >= AUTO_RETRY_DELAY_MS.length) {
            autoRetryCount = 0;
            return false;
        }
        final long delay = AUTO_RETRY_DELAY_MS[autoRetryCount];
        // 预算是"从点播到给出结论"的真上限，不是"还能不能再排一次"。
        // 必须把这次要等的退避也算进去 —— 否则最后一次重取会越过预算：
        // 实测 8 秒预算跑出 10.7 秒才给提示（用户在 14:12:07 点播、14:12:17.7 才看到结论）。
        if (fetchBudgetMs > 0 && mEpisodeStartAt > 0
                && System.currentTimeMillis() + delay - mEpisodeStartAt > fetchBudgetMs) {
            PlayTrace.stage("重试", "取链重取预算 " + (fetchBudgetMs / 1000)
                    + "s 不够下一次退避，直接给结论");
            autoRetryCount = 0;
            return false;
        }
        autoRetryCount++;
        final String keyAtSchedule = curEpisodeKey();
        final int genAtSchedule = mRetryGen;
        PlayTrace.stage("重试", "退避重取 " + autoRetryCount + "/" + AUTO_RETRY_DELAY_MS.length
                + "，延迟 " + delay + "ms");
        // 这里刻意不另加提示文案：重取的本质就是"还在获取播放地址"，屏幕上原有的 loading
        // 状态继续用即可。不引入「重试 N 次」这种把内部机制外泄的说法
        // （用户 09-17 反馈：那个计数看着多余；他要的是"别因为试得太快而误判可播的源"，
        //   不是"把等待拉长给我看"）。
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isAdded() || mVodInfo == null) return;
                    // 排队期间用户换集、或手动点了「重试」：这次重取已经过时，必须作废，
                    // 否则会把用户刚发起的那次取链打断成两个并发请求。
                    if (genAtSchedule != mRetryGen) return;
                    if (keyAtSchedule != null && !keyAtSchedule.equals(curEpisodeKey())) return;
                    play(false);
                } catch (Throwable ignored) {
                }
            }
        }, delay);
        return true;
    }

    /**
     * P1（2026-09-22 拍板）：兜底链的「重新取链」档 —— 本集只试一次。
     *
     * @param where 埋点用：区分「播放中断」（就绪过后死掉）与「起播兜底试尽」
     * @param boundedTotal 起播阶段传 true：受 REFETCH_TOTAL_BUDGET_MS 总时长上限约束，
     *                     防止"每一步都空转"的病理把最坏等待拖过承诺（errorFinal 注释的教训）。
     *                     播放中断传 false：地址已被证明有效，重取不该受取链预算约束。
     */
    private boolean tryRefetchOnce(String where, boolean boundedTotal) {
        if (mRefetchTried) return false;
        mRefetchTried = true;
        PlayTrace.stage("重试", "自动重新取链一次（" + where + "，护栏=once"
                + (boundedTotal ? "+总时长" + (REFETCH_TOTAL_BUDGET_MS / 1000) + "s" : "") + "）");
        return autoRetry(boundedTotal ? REFETCH_TOTAL_BUDGET_MS : 0);
    }

    void autoRetryFromLoadFoundVideoUrls() {
        String videoUrl = loadFoundVideoUrls.poll();
        HashMap<String, String> header = loadFoundVideoUrlsHeader.get(videoUrl);
        playUrl(videoUrl, header);
    }

    void initParseLoadFound() {
        loadFoundCount.set(0);
        loadFoundVideoUrls = new LinkedList<String>();
        loadFoundVideoUrlsHeader = new HashMap<String, HashMap<String, String>>();
    }

    public void play(boolean reset) {
        if (mVodInfo == null) return;
        VodInfo.VodSeries vs = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
        // 换集：兜底额度 / 重播计数 / 看门狗状态 / 回退地址全部归零，保证每一集是独立的一次尝试。
        // 注意不能用"进 play 就归零"——autoRetry() 内部也是调 play(false)，
        // 那样"重新解析一次"的额度会永远用不到（每次进来都被清零 → 无限重试）。
        String epKey = curEpisodeKey();
        if (epKey != null && !epKey.equals(mLastPlayKey)) {
            mLastPlayKey = epKey;
            autoRetryCount = 0;
            mWatchdogFired = false;
            mWatchdogRound = 0;
            mFallbackRawUrl = null;
            mBomRetried = false;
            mVariantTried = false;
            mVariantBaseUrl = null;
            mFailHandledAt = 0;
            mRefetchTried = false; // P1：换集＝新的一集，重新取链的 once 护栏重新给满
            mPlaybackHadStarted = false;
            mRetryGen++; // 换集后上一集排队的重取必须作废（新一集会有自己的取链）
            mEpisodeStartAt = System.currentTimeMillis(); // 换集＝新的一集，重播预算重新给满
            fbKey = null;
            fbPlan = null;
            fbPlanIdx = 0;
            fbOverride = null;
            PlayTrace.stage("取链", "换集 " + epKey + "，额度重置");
        }
        cancelStartWatchdog();
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodInfo.playIndex));
        String playTitleInfo = mVodInfo.name + " " + vs.name;
        setTip("正在获取播放信息", true, false);
        armFetchWatchdog(); // 取链环节卡住时界面上不会有任何反馈，这里兜住
        PlayTrace.stage("取链", "开始 ep=" + mVodInfo.playIndex + " 站点=" + sourceKey);
        mController.setTitle(playTitleInfo);

        stopParse();
        initParseLoadFound();
        if (mVideoView != null) mVideoView.release();
        String subtitleCacheKey = mVodInfo.sourceKey + "-" + mVodInfo.id + "-" + mVodInfo.playFlag + "-" + mVodInfo.playIndex + "-" + vs.name + "-subt";
        String progressKey = mVodInfo.sourceKey + mVodInfo.id + mVodInfo.playFlag + mVodInfo.playIndex + vs.name;
        //重新播放清除现有进度
        if (reset) {
            ProgressStore.remove(progressKey);
            CacheManager.delete(MD5.string2MD5(subtitleCacheKey), 0);
        }
        if (Jianpian.isJpUrl(vs.url)) {//荐片地址特殊判断
            String jp_url = vs.url;
            mController.showParse(false);
            if (vs.url.startsWith("tvbox-xg:")) {
                playUrl(Jianpian.JPUrlDec(jp_url.substring(9)), null);
            } else {
                playUrl(Jianpian.JPUrlDec(jp_url), null);
            }
            return;
        }
        if (Thunder.play(vs.url, new Thunder.ThunderCallback() {
            @Override
            public void status(int code, String info) {
                if (code < 0) {
                    setTip(info, false, true);
                } else {
                    setTip(info, true, false);
                }
            }

            @Override
            public void list(Map<Integer, String> urlMap) {
            }

            @Override
            public void play(String url) {
                playUrl(url, null);
            }
        })) {
            mController.showParse(false);
            return;
        }
        sourceViewModel.getPlay(sourceKey, mVodInfo.playFlag, progressKey, vs.url, subtitleCacheKey);
    }

    private String playSubtitle;
    private String subtitleCacheKey;
    private String progressKey;
    private String parseFlag;
    private String webUrl;
    private String webUserAgent;
    private Map<String, String> webHeaderMap;

    private void initParse(String flag, boolean useParse, String playUrl, final String url) {
        parseFlag = flag;
        webUrl = url;
        ParseBean parseBean = null;
        mController.showParse(useParse);
        if (useParse) {
            parseBean = ApiConfig.get().getDefaultParse();
        } else {
            if (playUrl.startsWith("json:")) {
                parseBean = new ParseBean();
                parseBean.setType(1);
                parseBean.setUrl(playUrl.substring(5));
            } else if (playUrl.startsWith("parse:")) {
                String parseRedirect = playUrl.substring(6);
                for (ParseBean pb : ApiConfig.get().getParseBeanList()) {
                    if (pb.getName().equals(parseRedirect)) {
                        parseBean = pb;
                        break;
                    }
                }
            }
            if (parseBean == null) {
                parseBean = new ParseBean();
                parseBean.setType(0);
                parseBean.setUrl(playUrl);
            }
        }
        doParse(parseBean);
    }

    JSONObject jsonParse(String input, String json) throws JSONException {
        JSONObject jsonPlayData = new JSONObject(json);
        //小窗版解析方法改到这了  之前那个位置data解析无效
        String url;
        if (jsonPlayData.has("data")) {
            url = jsonPlayData.getJSONObject("data").getString("url");
        } else {
            url = jsonPlayData.getString("url");
        }
        if (url.startsWith("//")) {
            url = "http:" + url;
        }
        if (!url.startsWith("http")) {
            return null;
        }
        JSONObject headers = new JSONObject();
        String ua = jsonPlayData.optString("user-agent", "");
        if (ua.trim().length() > 0) {
            headers.put("User-Agent", " " + ua);
        }
        String referer = jsonPlayData.optString("referer", "");
        if (referer.trim().length() > 0) {
            headers.put("Referer", " " + referer);
        }
        JSONObject taskResult = new JSONObject();
        taskResult.put("header", headers);
        taskResult.put("url", url);
        return taskResult;
    }

    void stopParse() {
        mHandler.removeMessages(100);
        stopLoadWebView(false);
        OkGo.getInstance().cancelTag("json_jx");
        if (parseThreadPool != null) {
            try {
                parseThreadPool.shutdown();
                parseThreadPool = null;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    ExecutorService parseThreadPool;

    private void doParse(ParseBean pb) {
        stopParse();
        initParseLoadFound();
        if (pb.getType() == 0) {
            setTip("正在嗅探播放地址", true, false);
            mHandler.removeMessages(100);
            mHandler.sendEmptyMessageDelayed(100, 20 * 1000);
            if (pb.getExt() != null) {
                // 解析ext
                try {
                    HashMap<String, String> reqHeaders = new HashMap<>();
                    JSONObject jsonObject = new JSONObject(pb.getExt());
                    if (jsonObject.has("header")) {
                        JSONObject headerJson = jsonObject.optJSONObject("header");
                        Iterator<String> keys = headerJson.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            if (key.equalsIgnoreCase("user-agent")) {
                                webUserAgent = headerJson.getString(key).trim();
                            } else {
                                reqHeaders.put(key, headerJson.optString(key, ""));
                            }
                        }
                        if (reqHeaders.size() > 0) webHeaderMap = reqHeaders;
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
            loadWebView(pb.getUrl() + webUrl);

        } else if (pb.getType() == 1) { // json 解析
            setTip("正在解析播放地址", true, false);
            // 解析ext
            HttpHeaders reqHeaders = new HttpHeaders();
            try {
                JSONObject jsonObject = new JSONObject(pb.getExt());
                if (jsonObject.has("header")) {
                    JSONObject headerJson = jsonObject.optJSONObject("header");
                    Iterator<String> keys = headerJson.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        reqHeaders.put(key, headerJson.optString(key, ""));
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
            OkGo.<String>get(pb.getUrl() + encodeUrl(webUrl))
                    .tag("json_jx")
                    .headers(reqHeaders)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String json = response.body();
                            try {
                                JSONObject rs = jsonParse(webUrl, json);
                                HashMap<String, String> headers = null;
                                if (rs.has("header")) {
                                    try {
                                        JSONObject hds = rs.getJSONObject("header");
                                        Iterator<String> keys = hds.keys();
                                        while (keys.hasNext()) {
                                            String key = keys.next();
                                            if (headers == null) {
                                                headers = new HashMap<>();
                                            }
                                            headers.put(key, hds.getString(key));
                                        }
                                    } catch (Throwable th) {

                                    }
                                }
                                playUrl(rs.getString("url"), headers);
                            } catch (Throwable e) {
                                e.printStackTrace();
                                errorWithRetry("解析错误");
//                                setTip("解析错误", false, true);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            errorWithRetry("解析错误");
//                            setTip("解析错误", false, true);
                        }
                    });
        } else if (pb.getType() == 2) { // json 扩展
            setTip("正在解析播放地址", true, false);
            parseThreadPool = Executors.newSingleThreadExecutor();
            LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
            for (ParseBean p : ApiConfig.get().getParseBeanList()) {
                if (p.getType() == 1) {
                    jxs.put(p.getName(), p.mixUrl());
                }
            }
            parseThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    JSONObject rs = ApiConfig.get().jsonExt(pb.getUrl(), jxs, webUrl);
                    if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
//                        errorWithRetry("解析错误");
                        setTip("解析错误", false, true);
                    } else {
                        HashMap<String, String> headers = null;
                        if (rs.has("header")) {
                            try {
                                JSONObject hds = rs.getJSONObject("header");
                                Iterator<String> keys = hds.keys();
                                while (keys.hasNext()) {
                                    String key = keys.next();
                                    if (headers == null) {
                                        headers = new HashMap<>();
                                    }
                                    headers.put(key, hds.getString(key));
                                }
                            } catch (Throwable th) {

                            }
                        }
                        if (rs.has("jxFrom")) {
                            ToastUtils.showShort("解析来自:" + rs.optString("jxFrom"));
                        }
                        boolean parseWV = rs.optInt("parse", 0) == 1;
                        if (parseWV) {
                            String wvUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                            loadUrl(wvUrl);
                        } else {
                            playUrl(rs.optString("url", ""), headers);
                        }
                    }
                }
            });
        } else if (pb.getType() == 3) { // json 聚合
            setTip("正在解析播放地址", true, false);
            parseThreadPool = Executors.newSingleThreadExecutor();
            LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
            String extendName = "";
            for (ParseBean p : ApiConfig.get().getParseBeanList()) {
                HashMap data = new HashMap<String, String>();
                data.put("url", p.getUrl());
                if (p.getUrl().equals(pb.getUrl())) {
                    extendName = p.getName();
                }
                data.put("type", p.getType() + "");
                data.put("ext", p.getExt());
                jxs.put(p.getName(), data);
            }
            String finalExtendName = extendName;
            parseThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    JSONObject rs = ApiConfig.get().jsonExtMix(parseFlag + "111", pb.getUrl(), finalExtendName, jxs, webUrl);
                    if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
//                        errorWithRetry("解析错误");
                        setTip("解析错误", false, true);
                    } else {
                        if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                            if (rs.has("ua")) {
                                webUserAgent = rs.optString("ua").trim();
                            }
                            if (!isAdded()) return;
                            requireActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    String mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                                    stopParse();
                                    setTip("正在嗅探播放地址", true, false);
                                    mHandler.removeMessages(100);
                                    mHandler.sendEmptyMessageDelayed(100, 20 * 1000);
                                    loadWebView(mixParseUrl);
                                }
                            });
                        } else {
                            HashMap<String, String> headers = null;
                            if (rs.has("header")) {
                                try {
                                    JSONObject hds = rs.getJSONObject("header");
                                    Iterator<String> keys = hds.keys();
                                    while (keys.hasNext()) {
                                        String key = keys.next();
                                        if (headers == null) {
                                            headers = new HashMap<>();
                                        }
                                        headers.put(key, hds.getString(key));
                                    }
                                } catch (Throwable th) {
                                    th.printStackTrace();
                                }
                            }
                            if (rs.has("jxFrom")) {
                                ToastUtils.showShort("解析来自:" + rs.optString("jxFrom"));
                            }
                            playUrl(rs.optString("url", ""), headers);
                        }
                    }
                }
            });
        }
    }

    private String encodeUrl(String url) {
        try {
            return URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }

    private WebView mSysWebView;
    private final Map<String, Boolean> loadedUrls = new HashMap<>();
    private LinkedList<String> loadFoundVideoUrls = new LinkedList<>();
    private HashMap<String, HashMap<String, String>> loadFoundVideoUrlsHeader = new HashMap<>();
    private final AtomicInteger loadFoundCount = new AtomicInteger(0);

    void loadWebView(String url) {
        if (mSysWebView == null) {
            mSysWebView = new MyWebView(mContext);
            configWebViewSys(mSysWebView);
            loadUrl(url);
        } else {
            loadUrl(url);
        }
    }

    void loadUrl(String url) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (mSysWebView != null) {
                mSysWebView.stopLoading();
                if (webUserAgent != null) {
                    mSysWebView.getSettings().setUserAgentString(webUserAgent);
                }
                //mSysWebView.clearCache(true);
                if (webHeaderMap != null) {
                    mSysWebView.loadUrl(url, webHeaderMap);
                } else {
                    mSysWebView.loadUrl(url);
                }
            }
        });
    }

    void stopLoadWebView(boolean destroy) {
        if (mActivity == null || !isAdded()) return;
        requireActivity().runOnUiThread(() -> {

            if (mSysWebView != null) {
                mSysWebView.stopLoading();
                mSysWebView.loadUrl("about:blank");
                if (destroy) {
//                        mSysWebView.clearCache(true);
                    mSysWebView.removeAllViews();
                    mSysWebView.destroy();
                    mSysWebView = null;
                }
            }
        });
    }

    public String getFinalUrl(){
        return TextUtils.isEmpty(mCurrentUrl) || !RegexUtils.isURL(mCurrentUrl) ?"":mCurrentUrl;
    }

    /**
     * 详情页「外部播放」选到内置项（目前只有系统播放器 0）时调用：切内核并重播。
     * 与播放页走同一份配置对象和同一条重播路径，不新增任何网络请求。
     * 返回 false 表示当前没有在播的地址或配置尚未建立，调用方按"还没开始播"提示即可。
     */
    public boolean switchPlayerItem(int item) {
        if (mVodPlayerCfg == null || TextUtils.isEmpty(getFinalUrl())) return false;
        // 外部播放器不走这里：只吊起、不写配置（见 playWithExternalPlayer）。
        // 判据必须用 isExternalPlayer，不能用 `item >= 10`：内置的 IJK 硬/软解编码是 100/101，
        // 也在 10 以上，粗判会把它们也挡掉 —— 详情页选「IJK播放器(硬解码/软解码)」会直接判失败。
        if (PlayerHelper.isExternalPlayer(item)) return false;
        if (!PlayerHelper.applyPlayerItem(mVodPlayerCfg, item)) return false;
        // 用户手动选的播放器：打上标记并存进本线路，之后不再被设置页默认覆盖
        PlayerHelper.markUserPicked(mVodPlayerCfg);
        savePlayerCfgToLine();
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodPlayerCfg));
        if (mController != null) mController.updatePlayerCfgView();
        play(false);
        return true;
    }

    /**
     * 手动用外部播放器打开当前这一集：**仅本次**（用户 09-18 拍板）。
     * 不写任何配置、不改用户设置 —— 本线路配置原样保留，下一集回到该配置。
     * 地址/请求头沿用播放中那一份，进度取当前集已存进度，全程零新增网络请求。
     */
    public boolean playWithExternalPlayer(int playerType) {
        try {
            if (mVodInfo == null || TextUtils.isEmpty(getFinalUrl())) return false;
            String title = mVodInfo.name;
            try {
                VodInfo.VodSeries vs = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
                title = mVodInfo.name + " " + vs.name;
            } catch (Throwable ignored) {
                // 取不到集名就退化成剧名，不影响吊起
            }
            long progress = getSavedProgressOfCurrent();
            boolean ok = PlayerHelper.runExternalPlayer(playerType, requireActivity(), getFinalUrl(), title, playSubtitle, getPlayingHeaders(), progress);
            if (ok) pausePlayback(); // 吊起成功：暂停内置，避免两个播放器同时出声
            return ok;
        } catch (Throwable th) {
            th.printStackTrace();
            return false;
        }
    }

    /** 当前生效的播放器候选项编码（详情页弹窗靠它把勾选落在"正在用的那个"上）。 */
    public int getCurrentPlayerItem() {
        if (mVodPlayerCfg == null) return PlayerHelper.BUILTIN_EXO;
        return PlayerHelper.currentPlayerItem(mVodPlayerCfg);
    }

    /** 当前生效的播放器候选项名（播放页/详情页弹窗回显"当前：XXX"用）。 */
    public String getCurrentPlayerItemName() {
        if (mVodPlayerCfg == null) return "";
        return PlayerHelper.getPlayerItemName(PlayerHelper.currentPlayerItem(mVodPlayerCfg));
    }

    /** 详情页「外部播放」吊起第三方 App 时要用的请求头，与当前播放中那一份完全相同。 */
    public HashMap<String, String> getPlayingHeaders() {
        return mPlayingHeaders;
    }

    /** 当前剧集已保存的播放进度（毫秒），供详情页「外部播放」吊起时续播。 */
    public long getSavedProgressOfCurrent() {
        return (progressKey == null) ? 0 : getSavedProgress(progressKey);
    }

    /** 详情页「外部播放」吊起成功后暂停内置播放，避免与第三方播放器同时出声。 */
    public void pausePlayback() {
        try {
            if (mVideoView != null && mVideoView.isPlaying()) mVideoView.pause();
        } catch (Throwable ignored) {
        }
    }

    boolean checkVideoFormat(String url) {
        try {
            if (url.contains("url=http") || url.contains(".html")) {
                return false;
            }
            if (sourceBean.getType() == 3) {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                if (sp != null && sp.manualVideoCheck()) {
                    return sp.isVideoFormat(url);
                }
            }
            return VideoParseRuler.checkIsVideoForParse(webUrl, url);
        } catch (Exception e) {
            return false;
        }
    }

    class MyWebView extends WebView {
        public MyWebView(@NonNull Context context) {
            super(context);
        }

        @Override
        public void setOverScrollMode(int mode) {
            super.setOverScrollMode(mode);
            if (mContext instanceof Activity)
                AutoSize.autoConvertDensityOfCustomAdapt((Activity) mContext, PlayFragment.this);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return false;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configWebViewSys(WebView webView) {
        if (webView == null) {
            return;
        }
        ViewGroup.LayoutParams layoutParams = Hawk.get(HawkConfig.DEBUG_OPEN, false)
                ? new ViewGroup.LayoutParams(800, 400) :
                new ViewGroup.LayoutParams(1, 1);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.clearFocus();
        webView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        requireActivity().addContentView(webView, layoutParams);
        /* 添加webView配置 */
        final WebSettings settings = webView.getSettings();
        settings.setNeedInitialFocus(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        // 安全加固：远程嗅探页面无需 file 域跨访问，关闭后恶意网页无法读本地文件
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            settings.setBlockNetworkImage(false);
        } else {
            settings.setBlockNetworkImage(true);
        }
        settings.setUseWideViewPort(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setSupportZoom(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
//        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        /* 添加webView配置 */
        //设置编码
        settings.setDefaultTextEncodingName("utf-8");
        settings.setUserAgentString(webView.getSettings().getUserAgentString());
//         settings.setUserAgentString(ANDROID_UA);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return false;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                return true;
            }
        });
        SysWebClient mSysWebClient = new SysWebClient();
        webView.setWebViewClient(mSysWebClient);
        webView.setBackgroundColor(Color.BLACK);
    }

    private class SysWebClient extends WebViewClient {

        @SuppressLint("WebViewClientOnReceivedSslError")
        @Override
        public void onReceivedSslError(WebView webView, SslErrorHandler sslErrorHandler, SslError sslError) {
            sslErrorHandler.proceed();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            String click = sourceBean.getClickSelector();
            LOG.i("onPageFinished url:" + url);

            if (!click.isEmpty()) {
                String selector;
                if (click.contains(";")) {
                    if (!url.contains(click.split(";")[0])) return;
                    selector = click.split(";")[1];
                } else {
                    selector = click.trim();
                }
                String js = "$(\"" + selector + "\").click();";
                LOG.i("javascript:" + js);
                mSysWebView.loadUrl("javascript:" + js);
            }
        }

        WebResourceResponse checkIsVideo(String url, HashMap<String, String> headers) {
            if (url.endsWith("/favicon.ico")) {
                if (url.startsWith("http://127.0.0.1")) {
                    return new WebResourceResponse("image/x-icon", "UTF-8", null);
                }
                return null;
            }

            boolean isFilter = VideoParseRuler.isFilter(webUrl, url);
            if (isFilter) {
                LOG.i("shouldInterceptLoadRequest filter:" + url);
                return null;
            }

            boolean ad;
            if (!loadedUrls.containsKey(url)) {
                ad = AdBlocker.isAd(url);
                loadedUrls.put(url, ad);
            } else {
                ad = Boolean.TRUE.equals(loadedUrls.get(url));
            }

            if (!ad) {
                if (checkVideoFormat(url)) {
                    loadFoundVideoUrls.add(url);
                    loadFoundVideoUrlsHeader.put(url, headers);
                    LOG.i("loadFoundVideoUrl:" + url);
                    if (loadFoundCount.incrementAndGet() == 1) {
                        url = loadFoundVideoUrls.poll();
                        mHandler.removeMessages(100);
                        String cookie = CookieManager.getInstance().getCookie(url);
                        if (!TextUtils.isEmpty(cookie))
                            headers.put("Cookie", " " + cookie);//携带cookie
                        playUrl(url, headers);
                        stopLoadWebView(false);
                    }
                }
            }

            return ad || loadFoundCount.get() > 0 ?
                    AdBlocker.createEmptyResource() :
                    null;
        }

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
//            WebResourceResponse response = checkIsVideo(url, new HashMap<>());
            return null;
        }

        @Nullable
        @Override
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            LOG.i("shouldInterceptRequest url:" + url);
            HashMap<String, String> webHeaders = new HashMap<>();
            Map<String, String> hds = request.getRequestHeaders();
            if (hds != null && hds.keySet().size() > 0) {
                for (String k : hds.keySet()) {
                    if (k.equalsIgnoreCase("user-agent")
                            || k.equalsIgnoreCase("referer")
                            || k.equalsIgnoreCase("origin")) {
                        webHeaders.put(k, " " + hds.get(k));
                    }
                }
            }
            return checkIsVideo(url, webHeaders);
        }

        @Override
        public void onLoadResource(WebView webView, String url) {
            super.onLoadResource(webView, url);
        }
    }

    public MyVideoView getPlayer() {
        return mVideoView;
    }
    public VodController getController() {
        return mController;
    }

}