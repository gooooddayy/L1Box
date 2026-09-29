/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.tvbox.osc.picasso;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.github.tvbox.osc.util.L1ImageInflight;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.squareup.picasso.Downloader;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * A {@link Downloader} which uses OkHttp to download images.
 */
public final class MyOkhttpDownLoader implements Downloader {

    // ── G2（2026-09-28，用户口径修订）：图片加载**成功率**＋失败原因分类 ──────────
    // 只统计走网络的下载（内存/磁盘缓存命中不经过这里，也不算失败）。
    // 用户关心的是"图片到底能不能加载出来"，并怀疑是 DNS 拦截 —— 所以账本必须分开记：
    //   ①未知主机（UnknownHostException，就是 DNS 解析失败/被拦截）
    //   ②连接/读取超时 ③连接失败 ④TLS/证书 ⑤HTTP 状态码（403 防盗链等）
    // 有了分类才能定刀：DNS 类要查域名解析，HTTP 类才轮到补 Referer/UA。
    private static final AtomicInteger IMG_OK = new AtomicInteger();
    private static final ConcurrentHashMap<String, AtomicInteger> IMG_FAIL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicInteger> IMG_REASON = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> IMG_HOST_REASON = new ConcurrentHashMap<>();

    /** 一轮搜索开始时清零（FastSearchActivity.search() 调用） */
    public static void imgStatsReset() {
        IMG_OK.set(0);
        IMG_FAIL.clear();
        IMG_REASON.clear();
        IMG_HOST_REASON.clear();
    }

    /**
     * 收尾汇总（一行）：
     * `成功=N 失败=M 成功率=xx.x% 原因=[未知主机/DNS:5, 连接超时:2, HTTP403:3] 失败域名=[a.com:3(未知主机/DNS), …]`
     * 成功率＝成功/(成功+失败)，分母只含**真的走了一次网络**的请求。
     */
    public static String imgStatsSummary() {
        int ok = IMG_OK.get();
        int fail = 0;
        for (AtomicInteger v : IMG_FAIL.values()) fail += v.get();
        int total = ok + fail;
        StringBuilder sb = new StringBuilder("成功=").append(ok).append(" 失败=").append(fail)
                .append(" 成功率=").append(total == 0 ? "无请求" :
                        String.format(java.util.Locale.US, "%.1f%%", ok * 100.0 / total));
        if (!IMG_REASON.isEmpty()) {
            ArrayList<Map.Entry<String, AtomicInteger>> rs = new ArrayList<>(IMG_REASON.entrySet());
            Collections.sort(rs, (a, b) -> b.getValue().get() - a.getValue().get());
            sb.append(" 原因=[");
            for (int i = 0; i < rs.size() && i < 6; i++) {
                if (i > 0) sb.append(", ");
                sb.append(rs.get(i).getKey()).append(":").append(rs.get(i).getValue().get());
            }
            sb.append("]");
        }
        if (!IMG_FAIL.isEmpty()) {
            ArrayList<Map.Entry<String, AtomicInteger>> list = new ArrayList<>(IMG_FAIL.entrySet());
            Collections.sort(list, (a, b) -> b.getValue().get() - a.getValue().get());
            sb.append(" 失败域名=[");
            for (int i = 0; i < list.size() && i < 6; i++) {
                if (i > 0) sb.append(", ");
                String host = list.get(i).getKey();
                sb.append(host).append(":").append(list.get(i).getValue().get())
                        .append("(").append(IMG_HOST_REASON.getOrDefault(host, "?")).append(")");
            }
            sb.append("]");
        }
        return sb.toString();
    }

    /** 记录一次网络失败：host 计数 ＋ 原因分类计数 ＋ host→原因（便于直接看出"哪个域名是被拦的"） */
    private static void countFail(String host, String reason) {
        if (host == null || host.isEmpty()) host = "?";
        IMG_FAIL.computeIfAbsent(host, k -> new AtomicInteger()).incrementAndGet();
        IMG_REASON.computeIfAbsent(reason, k -> new AtomicInteger()).incrementAndGet();
        IMG_HOST_REASON.putIfAbsent(host, reason);
    }

    /** 异常 → 失败原因分类（用户最关心的"是不是 DNS 拦截了"＝未知主机这一类） */
    private static String reasonOf(Throwable e) {
        if (e instanceof java.net.UnknownHostException) return "未知主机/DNS";
        if (e instanceof java.net.SocketTimeoutException) {
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            return m.contains("connect") ? "连接超时" : "读取超时";
        }
        if (e instanceof java.net.ConnectException) return "连接失败";
        if (e instanceof javax.net.ssl.SSLException) return "TLS/证书";
        String n = e.getClass().getSimpleName();
        return (n == null || n.isEmpty()) ? "其他异常" : n;
    }

    // ── P1（2026-09-28）：图床请求头策略 ────────────────────────────────
    /** 常见浏览器 UA：图床普遍对它放行（原来写死的 Dalvik UA 实测被豆瓣图床 418 挡） */
    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 13; M2102J2SC) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /**
     * 按域名补 Referer（**只图片链、只这几个已知需要的域名**）。
     * 依据：by/ca 实测失败域名集中在 `img1.doubanio.com`（HTTP418/连接失败）；
     * 播放链"乱补 Referer 导致 403"的教训不适用于图片，但同样不能全局乱加 —— 所以按域名白名单。
     */
    private static String refererFor(String url) {
        try {
            String u = url.toLowerCase();
            if (u.contains("doubanio.com") || u.contains("douban.com")) {
                return "https://movie.douban.com/";
            }
            return null;
        } catch (Throwable th) {
            return null;
        }
    }


    @VisibleForTesting
    final Call.Factory client;
    private final Cache cache;
    private boolean sharedClient = true;

    /**
     * Create a new downloader that uses the specified OkHttp instance. A response cache will not be
     * automatically configured.
     */
    public MyOkhttpDownLoader(OkHttpClient client) {
        this.client = client;
        this.cache = client.cache();
    }

    /**
     * Create a new downloader that uses the specified {@link Call.Factory} instance.
     */
    public MyOkhttpDownLoader(Call.Factory client) {
        this.client = client;
        this.cache = null;
    }

    @NonNull
    @Override
    public Response load(@NonNull Request request) throws IOException {
        String url = request.url().toString();
        String header = null;
        String cookie = null;
        String ua = null;
        String referer = null;

        //检查链接里面是否有自定义header
        if (url.contains("@Headers=")){
            header =url.split("@Headers=")[1].split("@")[0];
            header =URLDecoder.decode(header,"UTF-8");
        }
        if (url.contains("@Cookie=")) cookie= url.split("@Cookie=")[1].split("@")[0];
        if (url.contains("@User-Agent=")) ua =url.split("@User-Agent=")[1].split("@")[0];
        if (url.contains("@Referer=")) referer= url.split("@Referer=")[1].split("@")[0];

        url = url.split("@")[0];
        // 2026-09-28（用户口径）：**请求照发，不做任何"该不该加载"的预判**。
        // 上一版在这里按"可见窗口"跳过 → 点左侧特定站点时整页图都不加载（误判）。
        // 现在只做一件事：把在途 Call 登记下来，供列表停稳/切站点时取消"已经滚出可见范围"的下载，
        // 让并发槽及时让给用户真正要看的图（见 L1ImageInflight）。
        Request.Builder mRequestBuilder = request.newBuilder().url(url);
        if(!TextUtils.isEmpty(header)) {
            JsonObject jsonInfo = new Gson().fromJson(header, JsonObject.class);
            for (String key : jsonInfo.keySet()) {
                String val = jsonInfo.get(key).getAsString();
                mRequestBuilder.addHeader(key.toUpperCase(), removeDuplicateSlashes(val));
            }
        }else {
            if(!TextUtils.isEmpty(cookie)) {
                assert cookie != null;
                mRequestBuilder.addHeader("Cookie", cookie);
            }
            if(!TextUtils.isEmpty(ua)){
                assert ua != null;
                mRequestBuilder.addHeader("User-Agent", ua);
            }else {
                // P1（2026-09-28）：原来写死 Dalvik UA —— 实测豆瓣图床对非浏览器 UA 直接 418。
                // 换成常见浏览器 UA（图床认的就是这个），这一层与并发无关，纯粹是成功率。
                mRequestBuilder.addHeader("User-Agent", BROWSER_UA);
            }
            if(!TextUtils.isEmpty(referer)){
                assert referer != null;
                mRequestBuilder.addHeader("Referer", referer);
            } else {
                // P1：按域名补 Referer（只针对已知需要它的图床，只作用于图片链）
                String ref = refererFor(url);
                if (ref != null) mRequestBuilder.addHeader("Referer", ref);
            }
        }
        okhttp3.Request counted = mRequestBuilder.build();
        L1ImageInflight.countTake();
        okhttp3.Call call = client.newCall(counted);
        L1ImageInflight.put(url, call);
        Response response;
        try {
            response = call.execute();
        } catch (IOException e) {
            // 2026-09-28：**我们自己取消的请求不算失败**。
            // cc 实测：取消数（3~8）与失败里的 SocketException 数（2~8）完全吻合 ——
            // 那是"滚出可见范围被主动掐掉"的下载，如果记成失败，账本会把"正常行为"伪装成"图站坏了"。
            if (!call.isCanceled()) {
                countFail(counted.url().host(), reasonOf(e));
            }
            throw e;
        } finally {
            L1ImageInflight.remove(url);
        }
        // 非 2xx（403 防盗链 / 404 等）Picasso 按失败处理，这里按 HTTP 状态码记账
        if (!response.isSuccessful()) {
            countFail(counted.url().host(), "HTTP" + response.code());
        } else {
            IMG_OK.incrementAndGet();
        }
        return response;
    }

    private static String removeDuplicateSlashes(String paramValue) {
        return paramValue.replaceAll("//", "/");
    }
    @Override
    public void shutdown() {
        if (!sharedClient && cache != null) {
            try {
                cache.close();
            } catch (IOException ignored) {
            }
        }
    }
}
