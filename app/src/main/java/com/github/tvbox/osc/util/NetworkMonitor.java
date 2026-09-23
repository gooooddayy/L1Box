package com.github.tvbox.osc.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.event.NetworkEvent;

import org.greenrobot.eventbus.EventBus;

/**
 * 网络状态监听。
 *
 * 用途：
 * 1) 断网时让首页/搜索立即可判断，避免干等 10 秒超时；
 * 2) 网络恢复（WiFi 重连、流量切回）时发出事件，让失败态页面自动重试一次。
 *
 * 实现说明：
 * - registerDefaultNetworkCallback 为 API 24 起支持，与 minSdk 24 完全匹配，无需版本守卫；
 * - 判定「在线」只看 NET_CAPABILITY_INTERNET（有联网能力），不要求 VALIDATED——
 *   需要网页认证的公共 WiFi 下 VALIDATED 为 false，若据此判定离线会误伤正常使用；
 * - 初始状态与变化检测都回读系统当前状态，避免切换网络时 onLost/onAvailable 的瞬时抖动误报。
 */
public final class NetworkMonitor {

    private static volatile boolean online = true;
    private static volatile boolean started = false;

    private NetworkMonitor() {
    }

    /** 当前是否有可用网络。未知时按「在线」处理，宁可让请求去超时，也不误拦正常网络 */
    public static boolean isOnline() {
        return online;
    }

    public static synchronized void start() {
        if (started) return;
        started = true;
        try {
            ConnectivityManager cm = (ConnectivityManager) App.getInstance()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            online = queryOnline(cm);
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    refresh(cm);
                }

                @Override
                public void onLost(Network network) {
                    refresh(cm);
                }
            });
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 回读系统真实状态后再更新，过滤网络切换过程中的瞬时抖动 */
    private static void refresh(ConnectivityManager cm) {
        try {
            setOnline(queryOnline(cm));
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private static boolean queryOnline(ConnectivityManager cm) {
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static void setOnline(boolean now) {
        if (online == now) return;
        online = now;
        try {
            // 事件在系统回调线程发出，订阅方用 ThreadMode.MAIN 接收
            EventBus.getDefault().post(new NetworkEvent(now));
        } catch (Throwable ignored) {
        }
    }
}
