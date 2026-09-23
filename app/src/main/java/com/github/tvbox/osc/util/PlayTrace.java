package com.github.tvbox.osc.util;

import android.util.Log;

/**
 * 播放链路埋点。
 *
 * 只做一件事：把「取链 / 净化 / 起播 / 兜底 / 出链」几个阶段的耗时与结果打一行日志，
 * 否则「播放成功率提高了多少」只能靠感觉，出问题也无从定位是哪一段。
 *
 * 三条铁律（本类存在的前提）：
 *  ① 绝不抛异常 —— 所有方法内部全 try-catch，埋点自身出问题也不许影响播放；
 *  ② 绝不做网络/IO/加锁 —— 只是字符串拼接 + Log，可在主线程安全调用；
 *  ③ 绝不改行为 —— 只有读参数、没有副作用，删掉本类不影响任何功能。
 *
 * 日志 tag 固定 L1Play，抓取：adb logcat -s L1Play
 */
public final class PlayTrace {

    private static final String TAG = "L1Play";
    /** 诊断用的独立 tag：与播放心跳分开，免得污染成功率统计（抓取：adb logcat -s L1Diag） */
    private static final String TAG_DIAG = "L1Diag";

    private PlayTrace() {
    }

    /**
     * 打一行诊断日志，用于排查"这份数据从哪来、写到哪去"（历史记录的去重键、播放器配置的来源等）。
     * 与 stage 分 tag，避免把诊断行混进取链/起播的统计里。
     */
    public static void diag(String msg) {
        try {
            Log.i(TAG_DIAG, "诊断：" + (msg == null ? "" : msg));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 打一行阶段日志。stage 用固定词表（取链/净化/起播/兜底/看门狗/出链），便于按阶段统计。
     */
    public static void stage(String stage, String detail) {
        try {
            Log.i(TAG, "播放心跳：" + stage + " " + (detail == null ? "" : detail));
        } catch (Throwable ignored) {
            // 埋点失败绝不影响播放
        }
    }

    /**
     * 打一行失败日志（原因 + 耗时）。与 stage 分tag是为了能单独筛出所有失败样本。
     */
    public static void fail(String stage, String reason, long ms) {
        try {
            Log.i(TAG, "播放失败：" + stage + " reason=" + (reason == null ? "" : reason) + " ms=" + ms);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 地址压缩：只留「主机 + 末段」，避免整条带鉴权参数的地址刷屏，
     * 同时保留辨认能力（同一站点不同集数看末段就够）。
     */
    public static String brief(String url) {
        try {
            if (url == null || url.isEmpty()) return "";
            String s = url;
            int q = s.indexOf('?');
            if (q >= 0) s = s.substring(0, q) + "?…";
            if (s.length() <= 72) return s;
            int slash = s.lastIndexOf('/');
            if (slash > 0 && s.length() - slash < 40) {
                int hostEnd = s.indexOf("://");
                hostEnd = hostEnd < 0 ? 0 : hostEnd + 3;
                int hostSlash = s.indexOf('/', hostEnd);
                if (hostSlash > 0 && s.length() - hostSlash < 56) return s;
                return s.substring(0, hostEnd + 24) + "…" + s.substring(slash);
            }
            return s.substring(0, 60) + "…";
        } catch (Throwable th) {
            return "";
        }
    }
}
