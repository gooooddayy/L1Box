package com.github.tvbox.osc.util;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;

/**
 * 图片"在途请求"登记与取消（2026-09-28 · 用户口径版）。
 *
 * 背景：一次搜索命中 250+ 条结果 ⇒ 250+ 张图，Picasso 的下载**不可取消**
 * （它用的是老 `Downloader` 接口，只有 load/shutdown），于是滑动时大量"已经划过去的图"
 * 仍在下载、占满并发槽，用户真正要看的图排在后面 —— 表现为"滑完才慢慢加载"。
 *
 * ⛔ 上一版的做法（"可见优先门闸"：不在窗口内就**不发请求**）已被撤除：
 *    它按 position 判、下载线程按 URL 判，两套判据不一致，导致**点左侧特定站点时图片全不加载**
 *    （cb 实测 `图片队列：取出=16 跳过=15`）。用户明确要求**不要另加判错（防止误判）**。
 *
 * ✅ 现在改成"**请求照发、越界即取消**"：
 *    - adapter 绑定即请求（恢复原行为，不判断可见性、不跳过任何一张图）；
 *    - 下载器把每个请求的 {@link Call} 登记在这里；
 *    - 列表**停稳**或**切换站点**时，用"可见 ± 预取"的 URL 集合调用 {@link #cancelOutside}，
 *      把**已经滚出可见范围、正在下载**的请求取消掉 → 下载线程秒级释放，让位给可见的图；
 *    - 取消只影响当前看不到的图；item 再回到可见区会重新绑定并重新请求（Picasso 不缓存失败）
 *      ⇒ **不会漏图**。
 */
public final class L1ImageInflight {

    /** 规范化 URL → 正在执行的 Call */
    private static final Map<String, Call> INFLIGHT = new ConcurrentHashMap<>();

    private static final AtomicInteger TAKE = new AtomicInteger();      // 进入下载的请求数
    private static final AtomicInteger CANCELLED = new AtomicInteger(); // 被取消的越界请求数

    public static void reset() {
        INFLIGHT.clear();
        TAKE.set(0);
        CANCELLED.set(0);
    }

    public static void put(String url, Call call) {
        String n = norm(url);
        if (n != null && call != null) INFLIGHT.put(n, call);
    }

    public static void remove(String url) {
        String n = norm(url);
        if (n != null) INFLIGHT.remove(n);
    }

    public static void countTake() {
        TAKE.incrementAndGet();
    }

    /**
     * 取消"已经不在可见（含预取）范围内"的在途下载。
     *
     * @param keep 当前需要保留的 URL 集合（可见 ± 预取）；为 null/空表示"拿不到可见范围"⇒ **一个都不取消**（保守）
     * @return 本次取消的请求数
     */
    public static int cancelOutside(Collection<String> keep) {
        if (keep == null || keep.isEmpty()) return 0;
        Set<String> keepSet = new HashSet<>();
        for (String u : keep) {
            String n = norm(u);
            if (n != null) keepSet.add(n);
        }
        int n = 0;
        for (Map.Entry<String, Call> e : INFLIGHT.entrySet()) {
            if (keepSet.contains(e.getKey())) continue;
            Call c = e.getValue();
            if (c != null && !c.isCanceled()) {
                c.cancel();
                n++;
            }
            INFLIGHT.remove(e.getKey());
        }
        if (n > 0) CANCELLED.addAndGet(n);
        return n;
    }

    /** 收尾埋点：`取出=N 取消=K 在途=S`（纯日志） */
    public static String summary() {
        return "取出=" + TAKE.get() + " 取消=" + CANCELLED.get() + " 在途=" + INFLIGHT.size();
    }

    /** 与下载器口径一致：地址可能带 `@Headers=…` 后缀，统一截掉并 trim */
    private static String norm(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isEmpty()) return null;
        int at = u.indexOf('@');
        if (at > 0) u = u.substring(0, at);
        return u;
    }

    private L1ImageInflight() {
    }
}
