package com.github.tvbox.osc.api;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.JsLoader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AES;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.L1Executors;
import com.github.tvbox.osc.util.L1SubContent;
import com.github.tvbox.osc.util.L1SubUrl;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class ApiConfig {
    private static ApiConfig instance;
    private LinkedHashMap<String, SourceBean> sourceBeanList;
    /** 首页源池:来自内置首页.json,与订阅站点池完全隔离,永不被订阅覆盖 */
    private final LinkedHashMap<String, SourceBean> homeSiteList = new LinkedHashMap<>();
    /** 首页是否已被内置配置接管(接管后订阅源不再覆盖 mHomeSource) */
    private boolean homeLocalLoaded = false;
    /** 首页 spider 是否已就绪(避免每次 initData 重复下载) */
    private boolean homeJarReady = false;
    /**
     * 站点池装载是否已有定论（无论最终装载出多少个站点）。
     * 冷启动时订阅还在下载、内置首页源也尚未解析，sourceBeanList 与 homeSiteList 会同时为空，
     * 这与"用户确实没有可用源"在列表上完全无法区分。搜索入口若只凭列表判空，
     * 就会把"还没加载好"误报成空源（表现为"冷启动首搜空源，回首页再搜就正常"）。
     * 故用一个只置位、不复位的定论标志把两种情况分开。
     */
    private volatile boolean sitePoolSettled = false;

    /**
     * 最近一次装载后的实际站点数（-1 = 从未装载过）。
     * 站点池为空在各层都是"正常返回"（装载确实完成了），边界上分不出"加载完了但确实为空"
     * 与"本来就没内容"，只有把结果数量带出去，首页才可能把这种"启用成功却搜不出东西"说清楚。
     */
    private volatile int lastSiteCount = -1;
    /** 首页豆瓣源的原始在线 ext（CDN 数据地址）；override 为 localhost 路由后由路由负责缓存/回放 */
    private String homeDoubanExtUrl = "";
    /** 内置 assets/csp_home.jar 是否已尝试加载一次(避免重复拷贝) */
    private boolean homeBundledTried = false;
    private SourceBean mHomeSource;
    private ParseBean mDefaultParse;
    private List<LiveChannelGroup> liveChannelGroupList;
    private List<ParseBean> parseBeanList;
    private List<String> vipParseFlags;
    private List<IJKCode> ijkCodes;
    private String spider = null;
    public String wallpaper = "";

    private SourceBean emptyHome = new SourceBean();

    private JarLoader jarLoader = new JarLoader();
    /** 首页源专用 spider 加载器:首页.json 与订阅源的 spider jar 可能不同,必须隔离 */
    private JarLoader homeJarLoader = new JarLoader();
    private JsLoader jsLoader = new JsLoader();

    private String userAgent = "okhttp/3.15";

    /** 已加载过的 jar 指纹(fileName -> jarUrl|md5)：同 jar 跳过重复 load，避免 spider jar 每次刷新重复初始化 */
    private final java.util.HashMap<String, String> loadedJarKeys = new java.util.HashMap<>();

    /**
     * jar 加载专用后台线程（串行，保证加载顺序）。
     * DexClassLoader.load() 与文件 MD5 都是重活（几十到几百毫秒），原先跑在主线程的
     * OkGo 回调里，是首页加载/刷新卡顿的直接来源，故收到独立串行后台线程执行。
     * 走统一入口：命名 + daemon + 空闲回收，避免进程里再多一个永不释放的匿名线程。
     */
    private static final ExecutorService jarLoadPool = L1Executors.fixed("l1box-jarload", 1);

    /** 加载结果统一回主线程派发，调用方的线程语义与改造前完全一致 */
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private String requestAccept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9";

    private ApiConfig() {
        sourceBeanList = new LinkedHashMap<>();
        liveChannelGroupList = new ArrayList<>();
        parseBeanList = new ArrayList<>();
    }

    public static ApiConfig get() {
        if (instance == null) {
            synchronized (ApiConfig.class) {
                if (instance == null) {
                    instance = new ApiConfig();
                }
            }
        }
        return instance;
    }

    public static String FindResult(String json, String configKey) {
        String content = json;
        try {
            // 通用净化前置：BOM / 裸 base64 / JSON 前后夹说明文字 / HTML 外壳。
            // 原实现只认 "[A-Za-z0-9]{8}**" 前缀的 base64，**裸 base64 的订阅必然"解析配置失败"**，
            // 而裸 base64 恰是这类源站最常见的返回形态。净化拿不准时返回 null，原逻辑照走。
            String fixed = L1SubContent.toJson(content);
            if (fixed != null) content = fixed;
            if (AES.isJson(content)) return content;
            Pattern pattern = Pattern.compile("[A-Za-z0]{8}\\*\\*");
            Matcher matcher = pattern.matcher(content);
            if(matcher.find()){
                content=content.substring(content.indexOf(matcher.group()) + 10);
                content = new String(Base64.decode(content, Base64.DEFAULT));
            }
            if (content.startsWith("2423")) {
                String data = content.substring(content.indexOf("2324") + 4, content.length() - 26);
                content = new String(AES.toBytes(content)).toLowerCase();
                String key = AES.rightPadding(content.substring(content.indexOf("$#") + 2, content.indexOf("#$")), "0", 16);
                String iv = AES.rightPadding(content.substring(content.length() - 13), "0", 16);
                json = AES.CBC(data, key, iv);
            }else if (configKey !=null && !AES.isJson(content)) {
                json = AES.ECB(content, configKey);
            }
            else{
                json = content;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return json;
    }

    private static byte[] getImgJar(String body){
        Pattern pattern = Pattern.compile("[A-Za-z0]{8}\\*\\*");
        Matcher matcher = pattern.matcher(body);
        if(matcher.find()){
            body = body.substring(body.indexOf(matcher.group()) + 10);
            return Base64.decode(body, Base64.DEFAULT);
        }
        return "".getBytes();
    }

    /**
     * 订阅地址统一写入口：非空时双写(主键+备份)，空时双清(用户主动清空不留备份)。
     * 所有写 API_URL 的地方都必须走这里，保证备份一致。
     */
    public static void saveApiUrl(String url) {
        // 脏地址（BOM/零宽字符/首尾引号/漏写 http://）到此为止：所有写入口都走这里，
        // 收敛一次，后面 loadConfig / 缓存文件名 / 首页缓存比对拿到的就是同一个规范值。
        url = L1SubUrl.normalize(url);
        Hawk.put(HawkConfig.API_URL, url == null ? "" : url);
        if (url == null || url.isEmpty()) {
            Hawk.delete(HawkConfig.API_URL_BACKUP);
        } else {
            Hawk.put(HawkConfig.API_URL_BACKUP, url);
        }
    }

    public void loadConfig(boolean useCache, LoadConfigCallback callback, Activity activity) {
        String apiUrl = Hawk.get(HawkConfig.API_URL, "");
        // 历史数据兼容：早期写进 Hawk 的地址可能带 BOM / 首尾引号 / 漏写协议头，
        // 读出来先收敛一次（normalize 幂等，规范地址原样返回）。
        apiUrl = L1SubUrl.normalize(apiUrl);
        if (apiUrl.isEmpty()) {
            // 主键丢失但备份还在（异常退出/存储损坏）：自动恢复，避免用户重新扫码配置
            String backup = Hawk.get(HawkConfig.API_URL_BACKUP, "");
            if (!backup.isEmpty()) {
                apiUrl = backup;
                Hawk.put(HawkConfig.API_URL, apiUrl);
                try {
                    android.widget.Toast.makeText(activity, "检测到订阅配置异常，已自动恢复", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        }
        if (apiUrl.isEmpty()) {
            // 关闭/删除全部订阅后：立即清空内存里的站点池与相关配置。
            // 之前只回调 error，内存里仍留着上一个源的数据，搜索/播放照常能用，重启才恢复空源。
            synchronized (sourceBeanList) {
                sourceBeanList.clear();
                spider = "";
                parseBeanList.clear();
                vipParseFlags = new ArrayList<>();
            }
            mHomeSource = null;
            // 无订阅是明确结论：站点池为空属正常空态，搜索入口应立即给空态而不是等待
            sitePoolSettled = true;
            callback.error("-1");
            return;
        }
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + MD5.encode(apiUrl));
        if (useCache && cache.exists()) {
            try {
                parseJson(apiUrl, cache);
                callback.success();
                return;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        String TempKey = null, configUrl = "", pk = ";pk;";
        if (apiUrl.contains(pk)) {
            // Java 的 split 会丢掉尾部空串：地址以 ";pk;" 结尾时 a 只有 1 个元素，
            // 原实现直接取 a[1] 就是数组越界；loadConfig 与调用方都没有 try，表现为闪退。
            String[] a = apiUrl.split(pk);
            if (a.length > 1) TempKey = a[1];
            String head = a.length > 0 ? a[0] : "";
            if (apiUrl.startsWith("clan")) {
                configUrl = clanToAddress(head);
            } else {
                configUrl = L1SubUrl.normalize(head);   // 漏写协议头的在这里补上 http://
            }
        } else if (apiUrl.startsWith("clan")) {
            configUrl = clanToAddress(apiUrl);
        } else {
            // 原实现这里是 "http://" + configUrl，而 configUrl 此刻还是空串 —— 漏写协议头的
            // 地址被拼成 "http://" 直接请求失败，正是"某条线路怎么都拉不出来"的确定性原因。
            configUrl = L1SubUrl.normalize(apiUrl);
        }
        String configKey = TempKey;
        doLoadConfigRequest(apiUrl, configUrl, configKey, cache, callback, activity, 0);
    }

    /**
     * 缓存兜底：本地存有这个地址上次成功拉取的配置就直接顶上，返回是否用上了。
     *
     * 缓存文件名是 {@code MD5(完整订阅地址)}，**换源后天然对不上** —— 这正是"回退"与
     * "串用上一个源"之间的分界：能命中的缓存一定来自同一个地址。
     *
     * 缓存自己也坏掉时（历史遗留的坏文件）只如实返回 false，**不做删除**：
     * 删文件是不可逆动作，而它最多只占几 KB，不值得为它冒一次误删的风险。
     */
    private boolean useCacheFallback(String apiUrl, File cache, Activity activity, String tip) {
        if (cache == null || !cache.exists() || cache.length() == 0) return false;
        try {
            parseJson(apiUrl, cache);
            try {
                android.widget.Toast.makeText(activity, tip, android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable th) {
            th.printStackTrace();
            return false;
        }
    }

    /**
     * 拉取订阅配置：弱网自动重试(最多2次，间隔1.5s/3s)，重试用尽仍有缓存走缓存，
     * 最后才报错。retry 期间不打断用户，也不显示失败提示。
     */
    private void doLoadConfigRequest(String apiUrl, String configUrl, String configKey, File cache,
                                     LoadConfigCallback callback, Activity activity, final int attempt) {
        OkGo.<String>get(configUrl)
                // 订阅抓取的请求头统一取自 L1SubHeaders（与"添加订阅"那一段共用同一套）：
                // UA/Accept 之外补了 Accept-Language、Cache-Control 与防盗链用的 Referer。
                .headers(OkGoHelper.subHeaders(configUrl))
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            String json = response.body();
                            parseJson(apiUrl, json);
                            // 顺序很关键：**先解析成功、再写缓存**。这样源站某天把内容改坏时
                            // （返回网页、半截配置、被拦截页），坏内容不会覆盖掉本地那份好缓存 ——
                            // 而那份好缓存正是下面回退路径唯一的依托。
                            try {
                                File cacheDir = cache.getParentFile();
                                if (!cacheDir.exists())
                                    cacheDir.mkdirs();
                                if (cache.exists())
                                    cache.delete();
                                FileOutputStream fos = new FileOutputStream(cache);
                                fos.write(json.getBytes("UTF-8"));
                                fos.flush();
                                fos.close();
                            } catch (Throwable th) {
                                th.printStackTrace();
                            }
                            callback.success();
                        } catch (Throwable th) {
                            th.printStackTrace();
                            // 内容坏了：本地还有这个地址上次成功的配置就先用它顶上，不让一次坏响应
                            // 把首页变成一个可用源都没有。缓存文件名是 MD5(完整地址)，换源天然对不上。
                            if (useCacheFallback(apiUrl, cache, activity, "订阅内容异常，已使用上次的配置")) {
                                callback.success();
                                return;
                            }
                            // 没有可回退的缓存：给出**具体**结论（空响应 / 返回的是网页 / 内容不是订阅配置），
                            // 用户据此能判断该改地址还是该换源，而不是只有一句"解析配置失败"
                            callback.error("订阅内容异常：" + L1SubContent.diagTip(response.body()));
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        // 弱网自动重试：最多 2 次，指数退避 1.5s / 3s
                        if (attempt < 2) {
                            long delay = attempt == 0 ? 1500L : 3000L;
                            try {
                                android.widget.Toast.makeText(activity,
                                        "网络不佳，正在重试(" + (attempt + 1) + "/2)…", android.widget.Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) {
                            }
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                    () -> doLoadConfigRequest(apiUrl, configUrl, configKey, cache, callback, activity, attempt + 1),
                                    delay);
                            return;
                        }
                        if (useCacheFallback(apiUrl, cache, activity, "网络不佳，已使用缓存的订阅配置")) {
                            callback.success();
                            return;
                        }
                        // 重试用尽且无可用缓存：装载到此有定论，避免搜索入口一直等下去
                        sitePoolSettled = true;
                        String err = response.getException() != null ? response.getException().getMessage() : "";
                        System.out.println("L1Sub: 拉取配置失败 地址=" + configUrl + " 异常=" + err);
                        // 提示只说人话；异常原文留给日志（用户既看不懂也不该看到堆栈）
                        callback.error("订阅拉取失败，请检查网络");
                    }

                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        String result = "";
                        try {
                            if (response.body() == null) {
                                result = "";
                            } else {
                                // 取原始字节自己解：body().string() 在 Content-Type 未声明 charset 时一律按
                                // UTF-8 解，GBK 源站的站点名/分类名会烂成 U+FFFD（JSON 结构是 ASCII，照样解析
                                // 得过，所以这种损坏更隐蔽，只表现为"站点名乱码"）
                                result = FindResult(L1SubContent.decodeText(response.body().bytes()), configKey);
                            }

                            if (apiUrl.startsWith("clan")) {
                                result = clanContentFix(clanToAddress(apiUrl), result);
                            }
                            //假相對路徑
                            result = fixContentPath(apiUrl,result);
                        } catch (Throwable th) {
                            th.printStackTrace();
                            result = "";
                        }
                        return result;
                    }
                });
    }


    /** 订阅站点用的 spider jar(csp.jar) */
    public void loadJar(boolean useCache, String spider, LoadConfigCallback callback) {
        loadJar(useCache, spider, callback, "csp.jar", jarLoader);
    }

    /** 首页源用的 spider jar(csp_home.jar,与订阅站点隔离) */
    public void loadHomeJar(boolean useCache, String spider, LoadConfigCallback callback) {
        loadJar(useCache, spider, callback, "csp_home.jar", homeJarLoader);
    }

    private void loadJar(boolean useCache, String spider, LoadConfigCallback callback, String fileName, JarLoader loader) {
        if (spider == null || spider.trim().isEmpty()) {
            callback.error("");
            return;
        }
        String[] urls = spider.split(";md5;");
        String jarUrl = urls[0];
        String md5 = urls.length > 1 ? urls[1].trim() : "";
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + fileName);
        // 同一 jar（URL+md5 都没变）且缓存还在：直接复用已加载的类，不再重新 load——
        // 部分 spider jar 初始化时会弹"正在等待Go服务响应"等自家提示，重复 load 每次刷新首页都会弹一遍
        String jarKey = jarUrl + "|" + md5;
        if (cache.exists() && jarKey.equals(loadedJarKeys.get(fileName))) {
            callback.success();
            return;
        }
        final boolean isJarInImg = jarUrl.startsWith("img+");
        final String downloadUrl = jarUrl.replace("img+", "");
        // MD5 校验与 DexClassLoader 加载都是重活，统一丢到后台线程完成；
        // 只有确认「缓存不可用、确实需要下载」时才回主线程发起请求。
        // 回调一律在主线程发出，调用方的线程语义与改造前完全一致。
        // 与改造前逐字对齐：只有「spider 里写了 md5」或「明确要求用缓存」时才考虑命中缓存；
        // 两者都不满足（订阅未写 md5 + 下拉刷新 useCache=false）必须重新下载，否则 jar 永不更新
        final boolean cacheEligible = useCache || !md5.isEmpty();
        jarLoadPool.execute(() -> {
            boolean matched = false;
            boolean loaded = false;
            try {
                matched = cacheEligible && cache.exists()
                        && (useCache || MD5.getFileMd5(cache).equalsIgnoreCase(md5));
                if (matched) loaded = loader.load(cache.getAbsolutePath());
            } catch (Throwable th) {
                th.printStackTrace();
            }
            final boolean fMatched = matched;
            final boolean fLoaded = loaded;
            MAIN_HANDLER.post(() -> {
                if (fLoaded) {
                    loadedJarKeys.put(fileName, jarKey);
                    callback.success();
                } else if (fMatched) {
                    // 缓存可用却加载失败：与改造前一致，直接报错
                    callback.error("");
                } else {
                    downloadJar(downloadUrl, isJarInImg, cache, fileName, jarKey, loader, callback);
                }
            });
        });
    }

    /**
     * 下载 jar。下载完成后加载仍在后台线程执行——OkGo 回调默认回到主线程，
     * 若在这里直接 load 又会把重活带回主线程，所以交给 loadJarFileAsync。
     */
    private void downloadJar(String jarUrl, boolean isJarInImg, File cache, String fileName, String jarKey,
                             JarLoader loader, LoadConfigCallback callback) {
        OkGo.<File>get(jarUrl)
                .headers("User-Agent", userAgent)
                .headers("Accept", requestAccept)
                .execute(new AbsCallback<File>() {

            @Override
            public File convertResponse(okhttp3.Response response) throws Throwable {
                File cacheDir = cache.getParentFile();
                if (!cacheDir.exists())
                    cacheDir.mkdirs();
                if (cache.exists())
                    cache.delete();
                FileOutputStream fos = new FileOutputStream(cache);
                if (isJarInImg) {
                    String respData = response.body().string();
                    byte[] imgJar = getImgJar(respData);
                    fos.write(imgJar);
                } else {
                    fos.write(response.body().bytes());
                }
                fos.flush();
                fos.close();
                return cache;
            }

            @Override
            public void onSuccess(Response<File> response) {
                if (response.body() != null && response.body().exists()) {
                    loadJarFileAsync(response.body(), fileName, jarKey, loader, callback, null);
                } else {
                    callback.error("");
                }
            }

            @Override
            public void onError(Response<File> response) {
                super.onError(response);
                // 弱网兜底：下载失败但本地还有旧的 jar 缓存 → 先用旧的顶上，别让整个源瘫掉
                if (cache.exists() && cache.length() > 0) {
                    loadJarFileAsync(cache, fileName, jarKey, loader, callback, "网络不佳，已使用缓存数据源");
                    return;
                }
                callback.error("");
            }
        });
    }

    /**
     * 加载本地 jar 文件：仅「加载」这一步放后台线程，完成后回主线程回调。
     * @param successToast 加载成功后的提示，null 表示不提示
     */
    private void loadJarFileAsync(File jar, String fileName, String jarKey, JarLoader loader,
                                  LoadConfigCallback callback, String successToast) {
        jarLoadPool.execute(() -> {
            boolean ok = false;
            try {
                ok = loader.load(jar.getAbsolutePath());
            } catch (Throwable th) {
                th.printStackTrace();
            }
            final boolean success = ok;
            MAIN_HANDLER.post(() -> {
                if (success) {
                    loadedJarKeys.put(fileName, jarKey);
                    if (successToast != null) {
                        try {
                            android.widget.Toast.makeText(App.getInstance(), successToast, android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Throwable ignored) {
                        }
                    }
                    callback.success();
                } else {
                    callback.error("");
                }
            });
        });
    }

    /**
     * 加载内置首页.json：只填充首页源池并设定首页源，绝不触碰订阅站点池。
     * 仅供 HomeFragment 调用（不修改 HawkConfig.API_URL，避免污染订阅逻辑）。
     * @return true 表示首页配置已就绪（调用方不应再让订阅源覆盖首页）
     */
    public boolean loadLocalHomeConfig(LoadConfigCallback callback) {
        try {
            String json = com.github.tvbox.osc.util.HomeConfigLoader.load(App.getInstance());
            if (json == null) {
                return false;
            }
            JsonObject infoJson = new Gson().fromJson(json, JsonObject.class);
            String homeSpider = DefaultConfig.safeJsonString(infoJson, "spider", "");
            parseHomeJson(infoJson);
            // == 这里**只在"本来就没有订阅"时**才算定论（2026-09-14 冷启动首搜空源真根因）==
            // parseHomeJson 只填 homeSiteList / mHomeSource，**不填 sourceBeanList**，
            // 而搜索页取的正是 sourceBeanList（+ 首页源）。内置首页配置是 assets 里的，
            // 冷启动**瞬间**就解析完；订阅则是网络请求，要几百毫秒到几秒。
            // 早先把这里无条件置 true，等于在订阅还在途中就宣布"站点池已定论"，
            // 把搜索侧"未定论就先等"的守卫整个废掉：
            //   首搜时 sourceBeanList 仍为空 → 首页源又不可搜 → 可搜站点为 0 → 立刻"暂无数据"；
            //   再搜一次时订阅已落地 → 结果正常。这就是用户看到的"首搜空、再搜就好"。
            // 有订阅时必须把定论留给 parseJsonInternal（解析完成）或重试用尽那两个出口。
            if (Hawk.get(HawkConfig.API_URL, "").isEmpty()) sitePoolSettled = true;
            // 首页豆瓣数据走本地路由（127.0.0.1:port/l1home）：在线拉取并缓存、离线回放缓存/内置快照，
            // 既保证离线可用又保留在线更新，且不再因订阅/网络不可达而无限转圈
            if (mHomeSource != null && mHomeSource.getExt() != null && !mHomeSource.getExt().isEmpty()
                    && !mHomeSource.getExt().contains("127.0.0.1")) {
                homeDoubanExtUrl = mHomeSource.getExt();
                String local = com.github.tvbox.osc.server.ControlManager.get().getAddress(true) + "l1home";
                mHomeSource.setExt(local);
            }
            // 首页 spider 独立于订阅 spider（csp_home.jar），就绪后才允许首页取数
            if (homeSpider.isEmpty()) {
                // 无远端 spider URL：尝试内置 assets/csp_home.jar（用户放入即生效，零订阅也能看首页）
                if (!homeBundledTried) {
                    homeBundledTried = true;
                    loadBundledHomeJar();
                }
                homeJarReady = true;
                callback.success();
            } else if (homeJarReady) {
                homeJarReady = true;
                callback.success();
            } else {
                loadHomeJar(true, homeSpider, new LoadConfigCallback() {
                    @Override
                    public void retry() {
                        callback.retry();
                    }

                    @Override
                    public void success() {
                        homeJarReady = true;
                        callback.success();
                    }

                    @Override
                    public void error(String msg) {
                        // spider 失败不阻塞首页展示，避免整页空白
                        homeJarReady = true;
                        callback.success();
                    }
                });
            }
            return true;
        } catch (Throwable th) {
            th.printStackTrace();
            callback.error("解析首页配置失败: " + th.getMessage());
            return true;
        }
    }

    /** 首页 spider 是否已就绪（HomeFragment 据此决定能否取首页数据） */
    public boolean isHomeJarReady() {
        return homeJarReady;
    }

    /**
     * 尝试从内置 assets 加载首页 spider jar（csp_home.jar），实现"零订阅也能看首页"。
     * 用户只需把含首页 spider 类（如 csp_NewDouBanGuard）的 csp_home.jar 放进
     * app/src/main/assets/ 后重新构建即可生效；文件不存在时返回 false，走原有兜底逻辑。
     * @return jar 是否成功加载（不代表其中一定有目标 spider 类，调用方靠 getCSP 引用比较分流）
     */
    private boolean loadBundledHomeJar() {
        java.io.InputStream is = null;
        java.io.FileOutputStream fos = null;
        try {
            is = App.getInstance().getAssets().open("csp_home.jar");
            java.io.File cache = new java.io.File(
                    App.getInstance().getFilesDir().getAbsolutePath() + "/csp_home.jar");
            fos = new java.io.FileOutputStream(cache);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            fos.flush();
            return homeJarLoader.load(cache.getAbsolutePath());
        } catch (Throwable th) {
            // 资产缺失或加载失败：静默返回 false，首页回退到订阅 spider jar（兜底空状态 UI）
            return false;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
        }
    }

    /** 解析单个站点节点（订阅站点池与首页源池共用） */
    private SourceBean parseSite(JsonObject obj) {
        SourceBean sb = new SourceBean();
        String siteKey = obj.has("key") ? obj.get("key").getAsString().trim() : "";
        sb.setKey(siteKey);
        sb.setName(obj.has("name") ? obj.get("name").getAsString().trim() : "");
        sb.setType(obj.has("type") ? obj.get("type").getAsInt() : 0);
        sb.setApi(obj.has("api") ? obj.get("api").getAsString().trim() : "");
        sb.setSearchable(DefaultConfig.safeJsonInt(obj, "searchable", 1));
        sb.setQuickSearch(DefaultConfig.safeJsonInt(obj, "quickSearch", 1));
        sb.setFilterable(DefaultConfig.safeJsonInt(obj, "filterable", 1));
        sb.setPlayerUrl(DefaultConfig.safeJsonString(obj, "playUrl", ""));
        if (obj.has("ext") && (obj.get("ext").isJsonObject() || obj.get("ext").isJsonArray())) {
            sb.setExt(obj.get("ext").toString());
        } else {
            sb.setExt(DefaultConfig.safeJsonString(obj, "ext", ""));
        }
        sb.setJar(DefaultConfig.safeJsonString(obj, "jar", ""));
        sb.setPlayerType(DefaultConfig.safeJsonInt(obj, "playerType", -1));
        sb.setCategories(DefaultConfig.safeJsonStringList(obj, "categories"));
        sb.setClickSelector(DefaultConfig.safeJsonString(obj, "click", ""));
        return sb;
    }

    /**
     * 解析内置首页.json：只填充首页源池 + 设定首页源。
     * 绝不动 sourceBeanList（订阅站点池），也绝不动 parses/live/ijk，
     * 保证「首页内容固定」与「线路可切换」互不干扰。
     */
    private void parseHomeJson(JsonObject infoJson) {
        homeSiteList.clear();
        SourceBean firstSite = null;
        if (infoJson.has("sites") && infoJson.get("sites").isJsonArray()) {
            for (JsonElement opt : infoJson.get("sites").getAsJsonArray()) {
                if (!opt.isJsonObject()) continue;
                SourceBean sb = parseSite(opt.getAsJsonObject());
                if (firstSite == null) firstSite = sb;
                if (!sb.getKey().isEmpty()) homeSiteList.put(sb.getKey(), sb);
            }
        }
        if (!homeSiteList.isEmpty()) {
            mHomeSource = firstSite;
            homeLocalLoaded = true;
        }
    }

    private void parseJson(String apiUrl, File f) throws Throwable {
        System.out.println("从本地缓存加载" + f.getAbsolutePath());
        BufferedReader bReader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String s = "";
        while ((s = bReader.readLine()) != null) {
            sb.append(s + "\n");
        }
        bReader.close();
        parseJson(apiUrl, sb.toString());
    }

    private void parseJson(String apiUrl, String jsonStr) {
        // 二次净化：convertResponse 已经净化过一遍，但本地缓存回读、外部推入等路径也走这里。
        // 拿不到 JSON 时把**准确结论**写进日志（空响应 / 返回的是网页 / 内容不是订阅）——
        // 否则用户和我们看到的都只是笼统的"解析配置失败"，无从判断该改地址还是该换源。
        String fixed = L1SubContent.toJson(jsonStr);
        if (fixed == null) {
            System.out.println("L1Sub: 订阅内容不可用 地址=" + apiUrl
                    + " 结论=" + L1SubContent.diagName(L1SubContent.diagnose(jsonStr))
                    + " 样本=" + L1SubContent.head(jsonStr, 80));
            throw new IllegalStateException("订阅内容不是配置");
        }
        jsonStr = fixed;
        // 并发防护：多次 loadConfig 回调可能同时到达（切 tab/返回首页触发的 onResume 与
        // 订阅页 RefreshEvent 叠加），两个线程同时 clear()+put() 会撕裂站点池，导致取站点
        // 时拿到半清空 Map 而 NPE 闪退。这里对整个重填过程串行化。
        synchronized (sourceBeanList) {
            parseJsonInternal(apiUrl, jsonStr);
        }
    }

    private void parseJsonInternal(String apiUrl, String jsonStr) {
        JsonObject infoJson = new Gson().fromJson(jsonStr, JsonObject.class);
        // spider
        spider = DefaultConfig.safeJsonString(infoJson, "spider", "");
        // wallpaper
        wallpaper = DefaultConfig.safeJsonString(infoJson, "wallpaper", "");
        // 远端站点源
        SourceBean firstSite = null;
        if (sourceBeanList!= null)
            sourceBeanList.clear();
        if (infoJson.has("sites") && infoJson.get("sites").isJsonArray()) {
            for (JsonElement opt : infoJson.get("sites").getAsJsonArray()) {
                if (!opt.isJsonObject()) continue;
                SourceBean sb = parseSite(opt.getAsJsonObject());
                if (firstSite == null) firstSite = sb;
                if (!sb.getKey().isEmpty()) sourceBeanList.put(sb.getKey(), sb);
            }
        }
        // 订阅站点池本次解析完成：无论解析出多少个站点，装载都已不再是"在途"状态
        sitePoolSettled = true;
        // 把结果数量带出去：首页据此区分"装载完成但一个站点都没有"（要提示用户检查源地址）
        // 与"装载完成且有内容"（静默继续）。放在这里而不是各调用方，是因为所有路径
        // （在线拉取 / 缓存回读 / 无订阅清空）最终都要经过本方法。
        lastSiteCount = sourceBeanList.size();
        // 站点池为空是**明确结论**，不是"还没加载完"。区分"内容根本不是订阅"与
        // "确实是订阅但没有站点"，让日志能直接告诉我们该换地址还是该换源。
        if (sourceBeanList.isEmpty()) {
            System.out.println("L1Sub: 订阅解析完成 站点0个 地址=" + apiUrl
                    + " 内容里的站点数=" + L1SubContent.countSites(jsonStr)
                    + " 有spider=" + (spider != null && !spider.trim().isEmpty()));
        }
        // 首页已被内置配置接管时，订阅源不覆盖首页源；否则沿用原逻辑挑一个当首页
        if (!homeLocalLoaded && sourceBeanList != null && sourceBeanList.size() > 0) {
            String home = Hawk.get(HawkConfig.HOME_API, "");
            SourceBean sh = getSource(home);
            if (sh == null)
                setSourceBean(firstSite);
            else
                setSourceBean(sh);
        }
        // 需要使用vip解析的flag
        vipParseFlags = DefaultConfig.safeJsonStringList(infoJson, "flags");
        // 解析地址
        // 解析地址（防缺字段/类型不符导致加载订阅源闪退）
        parseBeanList.clear();
        if(infoJson.has("parses") && infoJson.get("parses").isJsonArray()){
            JsonArray parses = infoJson.get("parses").getAsJsonArray();
            for (JsonElement opt : parses) {
                if (!opt.isJsonObject()) continue;
                JsonObject obj = (JsonObject) opt;
                String pName = DefaultConfig.safeJsonString(obj, "name", "");
                String pUrl = DefaultConfig.safeJsonString(obj, "url", "");
                if (pName.isEmpty() || pUrl.isEmpty()) continue;
                ParseBean pb = new ParseBean();
                pb.setName(pName.trim());
                pb.setUrl(pUrl.trim());
                String ext = (obj.has("ext") && obj.get("ext").isJsonObject()) ? obj.get("ext").getAsJsonObject().toString() : "";
                pb.setExt(ext);
                pb.setType(DefaultConfig.safeJsonInt(obj, "type", 0));
                parseBeanList.add(pb);
            }
        }
        // 获取默认解析
        if (parseBeanList != null && parseBeanList.size() > 0) {
            String defaultParse = Hawk.get(HawkConfig.DEFAULT_PARSE, "");
            if (!TextUtils.isEmpty(defaultParse))
                for (ParseBean pb : parseBeanList) {
                    if (pb.getName().equals(defaultParse))
                        setDefaultParse(pb);
                }
            if (mDefaultParse == null)
                setDefaultParse(parseBeanList.get(0));
        }
        // 直播源
        liveChannelGroupList.clear();           //修复从后台切换重复加载频道列表
        String liveURL = Hawk.get(HawkConfig.LIVE_URL, "");
        //String epgURL  = Hawk.get(HawkConfig.EPG_URL, "");

        String liveURL_final = null;
        try {
            if (infoJson.has("lives") && infoJson.get("lives").isJsonArray() && infoJson.get("lives").getAsJsonArray().size() > 0) {
                JsonArray _livesArr = infoJson.get("lives").getAsJsonArray();
                JsonObject livesOBJ = _livesArr.get(0).getAsJsonObject();
                String lives = livesOBJ.toString();
                int index = lives.indexOf("proxy://");
                if (index != -1) {
                    int endIndex = lives.lastIndexOf("\"");
                    String url = lives.substring(index, endIndex);
                    url = DefaultConfig.checkReplaceProxy(url);

                    //clan
                    String extUrl = Uri.parse(url).getQueryParameter("ext");
                    if (extUrl != null && !extUrl.isEmpty()) {
                        String extUrlFix;
                        if (extUrl.startsWith("http") || extUrl.startsWith("clan://")) {
                            extUrlFix = extUrl;
                        } else {
                            extUrlFix = new String(Base64.decode(extUrl, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                        }
                        if (extUrlFix.startsWith("clan://")) {
                            extUrlFix = clanContentFix(clanToAddress(apiUrl), extUrlFix);
                        }

                        // takagen99: Capture Live URL into Config
                        System.out.println("Live URL :" + extUrlFix);
                        putLiveHistory(extUrlFix);
                        // Overwrite with Live URL from Settings
                        if (!StringUtils.isBlank(liveURL)) {
                            extUrlFix = liveURL;
                        }

                        // Final Live URL
                        liveURL_final = extUrlFix;

//                    // Encoding the Live URL
//                    extUrlFix = Base64.encodeToString(extUrlFix.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
//                    url = url.replace(extUrl, extUrlFix);
                    }

                    // takagen99 : Getting EPG URL from File Config & put into Settings
                    if (livesOBJ.has("epg")) {
                        String epg = DefaultConfig.safeJsonString(livesOBJ, "epg", "");
                        System.out.println("EPG URL :" + epg);
                        //putEPGHistory(epg);
                        // Overwrite with EPG URL from Settings
                        //if (StringUtils.isBlank(epgURL)) {
                            Hawk.put(HawkConfig.EPG_URL, epg);
//                        } else {
//                            Hawk.put(HawkConfig.EPG_URL, epgURL);
//                        }
                    }

//                // Populate Live Channel Listing
//                LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
//                liveChannelGroup.setGroupName(url);
//                liveChannelGroupList.add(liveChannelGroup);

                } else {

                    // if FongMi Live URL Formatting exists
                    if (!lives.contains("type")) {
                        loadLives(infoJson.get("lives").getAsJsonArray());
                    } else {
                        JsonObject fengMiLives = _livesArr.get(0).getAsJsonObject();
                        String type = DefaultConfig.safeJsonString(fengMiLives, "type", "");
                        if (type.equals("0")) {
                            String url = DefaultConfig.safeJsonString(fengMiLives, "url", "");

                            // takagen99 : Getting EPG URL from File Config & put into Settings
                            if (fengMiLives.has("epg")) {
                                String epg = DefaultConfig.safeJsonString(fengMiLives, "epg", "");
                                System.out.println("EPG URL :" + epg);
                                //putEPGHistory(epg);
                                // Overwrite with EPG URL from Settings
                                //if (StringUtils.isBlank(epgURL)) {
                                    Hawk.put(HawkConfig.EPG_URL, epg);
//                                } else {
//                                    Hawk.put(HawkConfig.EPG_URL, epgURL);
//                                }
                            }

                            if (url.startsWith("http")) {
                                // takagen99: Capture Live URL into Settings
                                System.out.println("Live URL :" + url);
                                putLiveHistory(url);
                                // Overwrite with Live URL from Settings
                                if (!StringUtils.isBlank(liveURL)) {
                                    url = liveURL;
                                }

                                // Final Live URL
                                liveURL_final = url;

//                            url = Base64.encodeToString(url.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                            }
                        }
                    }
                }

                // takagen99: Load Live Channel from settings URL (WIP)
                if (StringUtils.isBlank(liveURL_final)) {
                    liveURL_final = liveURL;
                }
                liveURL_final = Base64.encodeToString(liveURL_final.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                liveURL_final = "http://127.0.0.1:9978/proxy?do=live&type=txt&ext=" + liveURL_final;
                LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
                liveChannelGroup.setGroupName(liveURL_final);
                liveChannelGroupList.add(liveChannelGroup);
            }


        } catch (Throwable th) {
            th.printStackTrace();
        }


        //video parse rule for host
        if (infoJson.has("rules") && infoJson.get("rules").isJsonArray()) {
            VideoParseRuler.clearRule();
            for(JsonElement oneHostRule : infoJson.getAsJsonArray("rules")) {
                JsonObject obj = (JsonObject) oneHostRule;
                if (obj.has("host") && obj.get("host").isJsonPrimitive()) {
                    String host = obj.get("host").getAsString().trim();
                    if (obj.has("rule") && obj.get("rule").isJsonArray()) {
                        JsonArray ruleJsonArr = obj.getAsJsonArray("rule");
                        ArrayList<String> rule = new ArrayList<>();
                        for (JsonElement one : ruleJsonArr) {
                            if (!one.isJsonPrimitive()) continue;
                            rule.add(one.getAsString());
                        }
                        if (rule.size() > 0) {
                            VideoParseRuler.addHostRule(host, rule);
                        }
                    }
                    if (obj.has("filter") && obj.get("filter").isJsonArray()) {
                        JsonArray filterJsonArr = obj.getAsJsonArray("filter");
                        ArrayList<String> filter = new ArrayList<>();
                        for (JsonElement one : filterJsonArr) {
                            if (!one.isJsonPrimitive()) continue;
                            filter.add(one.getAsString());
                        }
                        if (filter.size() > 0) {
                            VideoParseRuler.addHostFilter(host, filter);
                        }
                    }
                }
                if (obj.has("hosts") && obj.has("regex")
                        && obj.get("hosts").isJsonArray() && obj.get("regex").isJsonArray()) {
                    ArrayList<String> rule = new ArrayList<>();
                    JsonArray regexArray = obj.getAsJsonArray("regex");
                    for (JsonElement one : regexArray) {
                        if (!one.isJsonPrimitive()) continue;
                        rule.add(one.getAsString());
                    }
                    JsonArray array = obj.getAsJsonArray("hosts");
                    for (JsonElement one : array) {
                        if (!one.isJsonPrimitive()) continue;
                        String host = one.getAsString();
                        VideoParseRuler.addHostRule(host, rule);
                    }
                }
            }
        }

        String defaultIJKADS="{\"ijk\":[{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"0\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"软解码\"},{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"1\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"硬解码\"}],\"ads\":[\"mimg.0c1q0l.cn\",\"www.googletagmanager.com\",\"www.google-analytics.com\",\"mc.usihnbcq.cn\",\"mg.g1mm3d.cn\",\"mscs.svaeuzh.cn\",\"cnzz.hhttm.top\",\"tp.vinuxhome.com\",\"cnzz.mmstat.com\",\"www.baihuillq.com\",\"s23.cnzz.com\",\"z3.cnzz.com\",\"c.cnzz.com\",\"stj.v1vo.top\",\"z12.cnzz.com\",\"img.mosflower.cn\",\"tips.gamevvip.com\",\"ehwe.yhdtns.com\",\"xdn.cqqc3.com\",\"www.jixunkyy.cn\",\"sp.chemacid.cn\",\"hm.baidu.com\",\"s9.cnzz.com\",\"z6.cnzz.com\",\"um.cavuc.com\",\"mav.mavuz.com\",\"wofwk.aoidf3.com\",\"z5.cnzz.com\",\"xc.hubeijieshikj.cn\",\"tj.tianwenhu.com\",\"xg.gars57.cn\",\"k.jinxiuzhilv.com\",\"cdn.bootcss.com\",\"ppl.xunzhuo123.com\",\"xomk.jiangjunmh.top\",\"img.xunzhuo123.com\",\"z1.cnzz.com\",\"s13.cnzz.com\",\"xg.huataisangao.cn\",\"z7.cnzz.com\",\"xg.huataisangao.cn\",\"z2.cnzz.com\",\"s96.cnzz.com\",\"q11.cnzz.com\",\"thy.dacedsfa.cn\",\"xg.whsbpw.cn\",\"s19.cnzz.com\",\"z8.cnzz.com\",\"s4.cnzz.com\",\"f5w.as12df.top\",\"ae01.alicdn.com\",\"www.92424.cn\",\"k.wudejia.com\",\"vivovip.mmszxc.top\",\"qiu.xixiqiu.com\",\"cdnjs.hnfenxun.com\",\"cms.qdwght.com\"]}";
        JsonObject defaultJson=new Gson().fromJson(defaultIJKADS, JsonObject.class);
        // 广告地址
        if(AdBlocker.isEmpty()){
            //默认广告拦截
            for (JsonElement host : defaultJson.getAsJsonArray("ads")) {
                AdBlocker.addAdHost(host.getAsString());
            }
            //追加的广告拦截
            if(infoJson.has("ads")){
                for (JsonElement host : infoJson.getAsJsonArray("ads")) {
                    if(!AdBlocker.hasHost(host.getAsString())){
                        AdBlocker.addAdHost(host.getAsString());
                    }
                }
            }
        }
        // IJK解码配置
        if(ijkCodes==null){
            ijkCodes = new ArrayList<>();
            boolean foundOldSelect = false;
            String ijkCodec = Hawk.get(HawkConfig.IJK_CODEC, "");
            JsonArray ijkJsonArray = infoJson.has("ijk")?infoJson.get("ijk").getAsJsonArray():defaultJson.get("ijk").getAsJsonArray();
            for (JsonElement opt : ijkJsonArray) {
                JsonObject obj = (JsonObject) opt;
                String name = obj.get("group").getAsString();
                LinkedHashMap<String, String> baseOpt = new LinkedHashMap<>();
                for (JsonElement cfg : obj.get("options").getAsJsonArray()) {
                    JsonObject cObj = (JsonObject) cfg;
                    String key = cObj.get("category").getAsString() + "|" + cObj.get("name").getAsString();
                    String val = cObj.get("value").getAsString();
                    baseOpt.put(key, val);
                }
                IJKCode codec = new IJKCode();
                codec.setName(name);
                codec.setOption(baseOpt);
                if (name.equals(ijkCodec) || TextUtils.isEmpty(ijkCodec)) {
                    codec.selected(true);
                    ijkCodec = name;
                    foundOldSelect = true;
                } else {
                    codec.selected(false);
                }
                ijkCodes.add(codec);
            }
            if (!foundOldSelect && ijkCodes.size() > 0) {
                ijkCodes.get(0).selected(true);
            }
        }
    }

    private void putLiveHistory(String url) {
        if (!url.isEmpty()) {
            ArrayList<String> liveHistory = Hawk.get(HawkConfig.LIVE_HISTORY, new ArrayList<String>());
            if (!liveHistory.contains(url))
                liveHistory.add(0, url);
            if (liveHistory.size() > 20)
                liveHistory.remove(20);
            Hawk.put(HawkConfig.LIVE_HISTORY, liveHistory);
        }
    }

    public void loadLives(JsonArray livesArray) {
        liveChannelGroupList.clear();
        int groupIndex = 0;
        int channelIndex = 0;
        int channelNum = 0;
        for (JsonElement groupElement : livesArray) {
            LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
            liveChannelGroup.setLiveChannels(new ArrayList<LiveChannelItem>());
            liveChannelGroup.setGroupIndex(groupIndex++);
            String groupName = ((JsonObject) groupElement).get("group").getAsString().trim();
            String[] splitGroupName = groupName.split("_", 2);
            liveChannelGroup.setGroupName(splitGroupName[0]);
            if (splitGroupName.length > 1)
                liveChannelGroup.setGroupPassword(splitGroupName[1]);
            else
                liveChannelGroup.setGroupPassword("");
            channelIndex = 0;
            for (JsonElement channelElement : ((JsonObject) groupElement).get("channels").getAsJsonArray()) {
                JsonObject obj = (JsonObject) channelElement;
                LiveChannelItem liveChannelItem = new LiveChannelItem();
                liveChannelItem.setChannelName(obj.get("name").getAsString().trim());
                liveChannelItem.setChannelIndex(channelIndex++);
                liveChannelItem.setChannelNum(++channelNum);
                ArrayList<String> urls = DefaultConfig.safeJsonStringList(obj, "urls");
                ArrayList<String> sourceNames = new ArrayList<>();
                ArrayList<String> sourceUrls = new ArrayList<>();
                int sourceIndex = 1;
                for (String url : urls) {
                    String[] splitText = url.split("\\$", 2);
                    sourceUrls.add(splitText[0]);
                    if (splitText.length > 1)
                        sourceNames.add(splitText[1]);
                    else
                        sourceNames.add("源" + Integer.toString(sourceIndex));
                    sourceIndex++;
                }
                liveChannelItem.setChannelSourceNames(sourceNames);
                liveChannelItem.setChannelUrls(sourceUrls);
                liveChannelGroup.getLiveChannels().add(liveChannelItem);
            }
            liveChannelGroupList.add(liveChannelGroup);
        }
    }

    public String getSpider() {
        return spider;
    }

    public Spider getCSP(SourceBean sourceBean) {
        boolean js = sourceBean.getApi().endsWith(".js") || sourceBean.getApi().contains(".js?");
        if (js) return jsLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
        // 首页源优先走独立 spider jar（csp_home.jar），避免与订阅站点的 spider 互相覆盖（引用比较，避免同名 key 误判）
        if (sourceBean != null && homeSiteList.get(sourceBean.getKey()) == sourceBean) {
            Spider sp = homeJarLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
            // 首页专属 jar 未就绪（spider 字段 URL 失效/下载失败等）→ 回退到订阅 spider jar（csp.jar），
            // 否则首页永远取不到数据、一直转圈
            if (!(sp instanceof SpiderNull)) return sp;
            return jarLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
        }
        return jarLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
    }

    public Object[] proxyLocal(Map param) {
        return jarLoader.proxyInvoke(param);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        return jarLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    public interface LoadConfigCallback {
        void success();

        void retry();

        void error(String msg);
    }

    public interface FastParseCallback {
        void success(boolean parse, String url, Map<String, String> header);

        void fail(int code, String msg);
    }

    public SourceBean getSource(String key) {
        // 与写入端同一把锁，避免重载中读到半清空 Map
        synchronized (sourceBeanList) {
            if (sourceBeanList.containsKey(key))
                return sourceBeanList.get(key);
        }
        // 首页源池兜底：首页展示源（如内置豆瓣源）只存在于 homeSiteList，
        // 不在此兜底则 getSort/getDetail/getSearch 按 key 查不到会 NPE，导致首页一直转圈
        synchronized (homeSiteList) {
            if (homeSiteList.containsKey(key))
                return homeSiteList.get(key);
        }
        return null;
    }

    /**
     * 站点名称。站点池重载后旧 key 可能查不到，返回空串而不是抛 NPE。
     */
    public String getSourceName(String key) {
        SourceBean sb = getSource(key);
        if (sb == null || sb.getName() == null) return "";
        return sb.getName();
    }

    /**
     * 指定首页展示站点（不写 HawkConfig.HOME_API，避免污染订阅侧的首页源选择）。
     * getSort()/getList() 都取 getHomeSourceBean()，故改指向后分类与列表会一起跟随该站点。
     */
    public void setHomeSite(SourceBean sourceBean) {
        if (sourceBean != null) this.mHomeSource = sourceBean;
    }

    public void setSourceBean(SourceBean sourceBean) {
        this.mHomeSource = sourceBean;
        Hawk.put(HawkConfig.HOME_API, sourceBean.getKey());
    }

    public void setDefaultParse(ParseBean parseBean) {
        if (this.mDefaultParse != null)
            this.mDefaultParse.setDefault(false);
        this.mDefaultParse = parseBean;
        Hawk.put(HawkConfig.DEFAULT_PARSE, parseBean.getName());
        parseBean.setDefault(true);
    }

    public ParseBean getDefaultParse() {
        return mDefaultParse;
    }

    public List<SourceBean> getSourceBeanList() {
        // 与 parseJson 的 clear()+重填共用一把锁：搜索/播放遍历站点池时若正逢切线路重载，
        // 无锁快照会抛 ConcurrentModificationException（"加载源后搜索闪退"的根因）
        List<SourceBean> list;
        synchronized (sourceBeanList) {
            list = new ArrayList<>(sourceBeanList.values());
        }
        // 未配置任何订阅线路时，用内置首页源兜底，避免搜索池为空导致点开影视毫无反应
        if (list.isEmpty() && !homeSiteList.isEmpty()) {
            synchronized (homeSiteList) {
                list.addAll(homeSiteList.values());
            }
        }
        return list;
    }

    /**
     * 是否配置了订阅线路。
     * 与 getSourceBeanList() 不同：后者在无订阅时会用首页源兜底，不能用来判断"有没有订阅"。
     */
    public boolean hasSubscription() {
        synchronized (sourceBeanList) {
            return sourceBeanList != null && !sourceBeanList.isEmpty();
        }
    }

    /**
     * 站点池装载是否已有定论。false 表示"订阅/首页源还在装载途中"，
     * 此时站点池为空属于暂态，调用方应等待而非判定为没有源。
     */
    public boolean isSitePoolSettled() {
        return sitePoolSettled;
    }

    /**
     * 最近一次装载出的站点数（-1 = 从未装载）。
     * 站点数为 0 是**明确结论**而非暂态（{@link #isSitePoolSettled()} 为 true 时），
     * 首页据此提示"这份订阅里没有可用站点"，避免用户看到"启用成功"却搜不出任何东西。
     */
    public int getLastSiteCount() {
        return lastSiteCount;
    }

    public List<ParseBean> getParseBeanList() {
        return parseBeanList;
    }

    public List<String> getVipParseFlags() {
        return vipParseFlags;
    }

    public SourceBean getHomeSourceBean() {
        return mHomeSource == null ? emptyHome : mHomeSource;
    }

    /** 首页豆瓣源原始在线数据地址（供 /l1home 路由做缓存/回放） */
    public String getHomeDoubanExtUrl() {
        return homeDoubanExtUrl;
    }

    public List<LiveChannelGroup> getChannelGroupList() {
        return liveChannelGroupList;
    }

    /**
     * 避免离线(订阅未配置成功时),parseJson未调用,ijkCodes未初始化报空指针
     * @return
     */
    private List<IJKCode> offlineGetIjkCodes() {

        String defaultIJKADS = "{\"ijk\":[{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"0\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"软解码\"},{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"1\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"硬解码\"}],\"ads\":[\"mimg.0c1q0l.cn\",\"www.googletagmanager.com\",\"www.google-analytics.com\",\"mc.usihnbcq.cn\",\"mg.g1mm3d.cn\",\"mscs.svaeuzh.cn\",\"cnzz.hhttm.top\",\"tp.vinuxhome.com\",\"cnzz.mmstat.com\",\"www.baihuillq.com\",\"s23.cnzz.com\",\"z3.cnzz.com\",\"c.cnzz.com\",\"stj.v1vo.top\",\"z12.cnzz.com\",\"img.mosflower.cn\",\"tips.gamevvip.com\",\"ehwe.yhdtns.com\",\"xdn.cqqc3.com\",\"www.jixunkyy.cn\",\"sp.chemacid.cn\",\"hm.baidu.com\",\"s9.cnzz.com\",\"z6.cnzz.com\",\"um.cavuc.com\",\"mav.mavuz.com\",\"wofwk.aoidf3.com\",\"z5.cnzz.com\",\"xc.hubeijieshikj.cn\",\"tj.tianwenhu.com\",\"xg.gars57.cn\",\"k.jinxiuzhilv.com\",\"cdn.bootcss.com\",\"ppl.xunzhuo123.com\",\"xomk.jiangjunmh.top\",\"img.xunzhuo123.com\",\"z1.cnzz.com\",\"s13.cnzz.com\",\"xg.huataisangao.cn\",\"z7.cnzz.com\",\"xg.huataisangao.cn\",\"z2.cnzz.com\",\"s96.cnzz.com\",\"q11.cnzz.com\",\"thy.dacedsfa.cn\",\"xg.whsbpw.cn\",\"s19.cnzz.com\",\"z8.cnzz.com\",\"s4.cnzz.com\",\"f5w.as12df.top\",\"ae01.alicdn.com\",\"www.92424.cn\",\"k.wudejia.com\",\"vivovip.mmszxc.top\",\"qiu.xixiqiu.com\",\"cdnjs.hnfenxun.com\",\"cms.qdwght.com\"]}";
        JsonObject defaultJson = new Gson().fromJson(defaultIJKADS, JsonObject.class);

        List<IJKCode> ijkCodes = new ArrayList<>();
        boolean foundOldSelect = false;
        String ijkCodec = Hawk.get(HawkConfig.IJK_CODEC, "");
        JsonArray ijkJsonArray = defaultJson.get("ijk").getAsJsonArray();
        for (JsonElement opt : ijkJsonArray) {
            JsonObject obj = (JsonObject) opt;
            String name = obj.get("group").getAsString();
            LinkedHashMap<String, String> baseOpt = new LinkedHashMap<>();
            for (JsonElement cfg : obj.get("options").getAsJsonArray()) {
                JsonObject cObj = (JsonObject) cfg;
                String key = cObj.get("category").getAsString() + "|" + cObj.get("name").getAsString();
                String val = cObj.get("value").getAsString();
                baseOpt.put(key, val);
            }
            IJKCode codec = new IJKCode();
            codec.setName(name);
            codec.setOption(baseOpt);
            if (name.equals(ijkCodec) || TextUtils.isEmpty(ijkCodec)) {
                codec.selected(true);
                ijkCodec = name;
                foundOldSelect = true;
            } else {
                codec.selected(false);
            }
            ijkCodes.add(codec);
        }
        if (!foundOldSelect && ijkCodes.size() > 0) {
            ijkCodes.get(0).selected(true);
        }
        return ijkCodes;
    }

    /**
     * 订阅成功还是用拉取的ijk解码配置,未拉取成功时用默认的
     * @return
     */
    public List<IJKCode> getIjkCodes() {
        return ijkCodes==null?offlineGetIjkCodes():ijkCodes;
    }

    public IJKCode getCurrentIJKCode() {
        String codeName = Hawk.get(HawkConfig.IJK_CODEC, "");
        return getIJKCodec(codeName);
    }

    public IJKCode getIJKCodec(String name) {
        for (IJKCode code : getIjkCodes()) {
            if (code.getName().equals(name))
                return code;
        }
        return ijkCodes.get(0);
    }

    String clanToAddress(String lanLink) {
        if (lanLink == null) return "http://127.0.0.1:9978/file/";
        String base = ControlManager.get().getAddress(true);
        if (base == null) base = "http://127.0.0.1:9978/";
        if (lanLink.startsWith("clan://local/")) {
            // SAF 本地导入的订阅：文件已复制到应用私有目录，经内部服务 /localfile/ 路由读取，零存储权限
            return lanLink.replace("clan://local/", base + "localfile/");
        } else if (lanLink.startsWith("clan://localhost/")) {
            return lanLink.replace("clan://localhost/", base + "file/");
        } else if (lanLink.startsWith("clan://")) {
            String link = lanLink.substring(7);
            int end = link.indexOf('/');
            if (end < 0) return base + "file/" + link;
            return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1);
        }
        return lanLink;
    }

    String clanContentFix(String lanLink, String content) {
        String fix = lanLink.substring(0, lanLink.indexOf("/file/") + 6);
        return content.replace("clan://", fix);
    }

    String fixContentPath(String url, String content) {
        if (content.contains("\"./")) {
            if(!url.startsWith("http") && !url.startsWith("clan://")){
                url = "http://" + url;
            }
            if(url.startsWith("clan://"))url=clanToAddress(url);
            content = content.replace("./", url.substring(0,url.lastIndexOf("/") + 1));
        }
        return content;
    }
}
