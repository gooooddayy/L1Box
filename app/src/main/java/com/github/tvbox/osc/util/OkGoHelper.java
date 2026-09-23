package com.github.tvbox.osc.util;

import static okhttp3.ConnectionSpec.CLEARTEXT;
import static okhttp3.ConnectionSpec.COMPATIBLE_TLS;
import static okhttp3.ConnectionSpec.MODERN_TLS;
import static okhttp3.ConnectionSpec.RESTRICTED_TLS;

import android.graphics.Bitmap;

import com.github.catvod.net.SSLCompat;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.picasso.MyOkhttpDownLoader;
import com.github.tvbox.osc.util.urlhttp.BrotliInterceptor;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.https.HttpsUtils;
import com.lzy.okgo.interceptor.HttpLoggingInterceptor;
import com.lzy.okgo.model.HttpHeaders;
import com.orhanobut.hawk.Hawk;
import com.squareup.picasso.Picasso;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.net.ssl.SSLSocketFactory;

import okhttp3.Cache;
import okhttp3.ConnectionSpec;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;
import okhttp3.internal.Util;
import okhttp3.internal.Version;
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper;

public class OkGoHelper {
    public static final long DEFAULT_MILLISECONDS = 10000;      //默认的超时时间

    static void initExoOkHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor("OkExoPlayer");

        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.BODY);
            loggingInterceptor.setColorLevel(Level.INFO);
        } else {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.NONE);
            loggingInterceptor.setColorLevel(Level.OFF);
        }
        builder.connectionSpecs(getConnectionSpec());
        builder.addInterceptor(new BrotliInterceptor());
        builder.retryOnConnectionFailure(true);
        builder.followRedirects(true);
        builder.followSslRedirects(true);

        try {
            setOkHttpSsl(builder);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        builder.dns(fallbackDns);

        ExoMediaSourceHelper.getInstance(App.getInstance()).setOkClient(builder.build());
    }

    public static DnsOverHttps dnsOverHttps = null;

    /**
     * 挂在所有 OkHttp 客户端上的 DNS 入口：DoH 优先、失败自动回退系统 DNS（见 {@link L1FallbackDns}）。
     * **所有注入点都必须用它，不许再裸挂 dnsOverHttps** —— 裸挂会让本地 127.0.0.1 链路解析失败
     * （内嵌 DoH 对私有地址是直接抛异常的），回归闸门有对应断言。
     * 初值就是"未启用 DoH"的实例（直接系统解析），因此它永不为 null —— 客户端构建时无需判空，
     * 最坏情况也只是回到改造前的系统 DNS 行为。
     */
    public static L1FallbackDns fallbackDns = new L1FallbackDns(null);

    /** 安全DNS 的出厂默认：开启（腾讯）。配合 fallbackDns 的回退与熔断，开启才是安全的 */
    public static final int DEFAULT_DOH = 1;

    /**
     * DoH 查询超时。
     * DoH 正常在百毫秒级返回，2 秒还没结果说明这条链路不通；而 DoH 不通时**每个未命中的域名**
     * 都要等满一次超时才能回退系统 DNS，10 秒（客户端默认）会让整个 App 的网络一起变慢。
     * 超时收紧是回退与熔断能真正起作用的前提。
     */
    private static final long DOH_TIMEOUT_MS = 2000;

    public static ArrayList<String> dnsHttpsList = new ArrayList<>();

    public static List<ConnectionSpec> getConnectionSpec() {
        return Util.immutableList(RESTRICTED_TLS, MODERN_TLS, COMPATIBLE_TLS, CLEARTEXT);
    }

    public static String getDohUrl(int type) {
        switch (type) {
            case 1: {
                return "https://doh.pub/dns-query";
            }
            case 2: {
                return "https://dns.alidns.com/dns-query";
            }
            case 3: {
                return "https://doh.360.cn/dns-query";
            }
        }
        return "";
    }

    /**
     * 订阅抓取请求头：添加订阅与启用拉配置**两段共用同一套**。
     * 值的定义在 {@link L1SubHeaders}（纯 Java、有桌面用例表可验），这里只做"塞进 OkGo 头表"的胶水，
     * 避免两段各自拼装又走回"同一地址添加失败、启用成功"的老路。
     */
    public static HttpHeaders subHeaders(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("User-Agent", L1SubHeaders.UA);
        headers.put("Accept", L1SubHeaders.ACCEPT);
        headers.put("Accept-Language", L1SubHeaders.ACCEPT_LANGUAGE);
        headers.put("Cache-Control", L1SubHeaders.CACHE_CONTROL);
        String referer = L1SubHeaders.referer(url);
        if (referer != null) headers.put("Referer", referer);
        return headers;
    }

    static void initDnsOverHttps() {
        dnsHttpsList.add("关闭");
        dnsHttpsList.add("腾讯");
        dnsHttpsList.add("阿里");
        dnsHttpsList.add("360");
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor("OkExoPlayer");
        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.BODY);
            loggingInterceptor.setColorLevel(Level.INFO);
        } else {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.NONE);
            loggingInterceptor.setColorLevel(Level.OFF);
        }
        builder.addInterceptor(new BrotliInterceptor());
        try {
            setOkHttpSsl(builder);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        builder.connectionSpecs(getConnectionSpec());
        builder.cache(new Cache(new File(App.getInstance().getCacheDir().getAbsolutePath(), "dohcache"), 10 * 1024 * 1024));
        // DoH 自己的请求也要短超时：它的失败是本层要感知并熔断的对象，不能被 10 秒默认值拖住
        builder.connectTimeout(DOH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(DOH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(DOH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        OkHttpClient dohClient = builder.build();
        String dohUrl = getDohUrl(Hawk.get(HawkConfig.DOH_URL, DEFAULT_DOH));
        dnsOverHttps = new DnsOverHttps.Builder().client(dohClient).url(dohUrl.isEmpty() ? null : HttpUrl.get(dohUrl)).build();
        fallbackDns = new L1FallbackDns(dnsOverHttps);
    }
    static OkHttpClient defaultClient = null;
    static OkHttpClient noRedirectClient = null;

    public static OkHttpClient getDefaultClient() {
        return defaultClient;
    }

    public static OkHttpClient getNoRedirectClient() {
        return noRedirectClient;
    }

    /** 去广告预取的超时（毫秒）：用户拍板「起播优先」，净化不值得让起播白等 */
    public static final long PURIFY_TIMEOUT_MS = 2500;

    private static OkHttpClient purifyClient = null;

    /**
     * 净化预取专用 client：连接/读取各 2.5 秒。
     *
     * 去广告必须先把 m3u8 完整拉一遍，而默认 client 是 10 秒超时，遇到跳转还要再拉一遍 ——
     * 合起来最坏 20 秒纯等待，全压在起播前，这是「起播慢」的最大来源。
     * 净化只是锦上添花的一步：超时就退回原始地址直连起播（调用处的 onError 分支已具备该回退），
     * 代价是个别源不再过滤片头广告，换来起播最坏等待从 20 秒砍到 2.5 秒。
     *
     * 惰性构建 + 双重兜底：构建失败、或 init() 尚未跑过（defaultClient 还没有）都退回默认 client，
     * 最坏情况＝回到改动前的行为，不会让净化这一步彻底失效。
     */
    public static OkHttpClient getPurifyClient() {
        if (purifyClient == null) {
            try {
                OkHttpClient base = getDefaultClient();
                if (base != null) {
                    purifyClient = base.newBuilder()
                            .connectTimeout(PURIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(PURIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .writeTimeout(PURIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .build();
                }
            } catch (Throwable th) {
                th.printStackTrace();
                purifyClient = null;
            }
        }
        return purifyClient;
    }

    public static void init() {
        initDnsOverHttps();

        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor("OkGo");

        if (Hawk.get(HawkConfig.DEBUG_OPEN, false)) {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.BODY);
            loggingInterceptor.setColorLevel(Level.INFO);
        } else {
            loggingInterceptor.setPrintLevel(HttpLoggingInterceptor.Level.NONE);
            loggingInterceptor.setColorLevel(Level.OFF);
        }

        //builder.retryOnConnectionFailure(false);
        builder.connectionSpecs(getConnectionSpec());
        builder.addInterceptor(new BrotliInterceptor());
        builder.readTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                .writeTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                .connectTimeout(DEFAULT_MILLISECONDS, TimeUnit.MILLISECONDS)
                .dns(fallbackDns);
        try {
            setOkHttpSsl(builder);
        } catch (Throwable th) {
            th.printStackTrace();
        }

        HttpHeaders.setUserAgent(Version.userAgent());

        OkHttpClient okHttpClient = builder.build();
        OkGo.getInstance().setOkHttpClient(okHttpClient);

        defaultClient = okHttpClient;

        builder.followRedirects(false);
        builder.followSslRedirects(false);
        noRedirectClient = builder.build();

        initExoOkHttpClient();
        initPicasso(okHttpClient);
    }

    static void initPicasso(OkHttpClient client) {
        client.dispatcher().setMaxRequestsPerHost(32);
        // 图片使用独立的带磁盘缓存的 client: 海报可复用, 重复进入秒开并节省流量。
        // 缓存只作用于图片, 接口请求仍走无缓存的 defaultClient, 避免数据被缓存后不刷新。
        OkHttpClient imageClient = client.newBuilder()
                .cache(new Cache(new File(App.getInstance().getCacheDir(), "img_cache"), 64 * 1024 * 1024))
                .build();
        MyOkhttpDownLoader downloader = new MyOkhttpDownLoader(imageClient);
        Picasso picasso = new Picasso.Builder(App.getInstance())
                .downloader(downloader)
                .executor(HeavyTaskUtil.getBigTaskExecutorService())
                .defaultBitmapConfig(Bitmap.Config.RGB_565)
                .build();
        Picasso.setSingletonInstance(picasso);
    }

    private static synchronized void setOkHttpSsl(OkHttpClient.Builder builder) {
        try {

            final SSLSocketFactory sslSocketFactory = new SSLCompat();
            builder.sslSocketFactory(sslSocketFactory, SSLCompat.TM);
            builder.hostnameVerifier(HttpsUtils.UnSafeHostnameVerifier);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


}
