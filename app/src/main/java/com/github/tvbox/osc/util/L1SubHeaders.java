package com.github.tvbox.osc.util;

/**
 * 订阅抓取的请求头：**两段链路（添加订阅 / 启用拉配置）共用同一套**。
 *
 * 历史上两段各写各的，添加阶段只带 UA、启用阶段多带 Accept，于是出现
 * "同一地址添加时失败、启用时反而成功"这种自相矛盾的表现。这里收敛成一份常量表，
 * 调用方只取用、不再各自拼装。
 *
 * 其中 Referer 是这次新加的一项：部分源站开了防盗链，缺 Referer 直接 403。
 * 拿不到可靠的 Referer 时宁可不带这个头，也不要硬填一个错的（错的比没有更容易被拒）。
 *
 * 纯字符串处理，不依赖 Android —— 可用桌面 JVM 跑用例表验证。
 */
public class L1SubHeaders {

    public static final String UA = "okhttp/3.15";

    /** 与改造前 ApiConfig.requestAccept 逐字一致：源站按 Accept 分流的不能因为这次改动换一种返回 */
    public static final String ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,"
            + "image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9";

    public static final String ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8";

    /** 订阅是配置不是资源：中间层不许给缓存的旧版本，否则"改了源地址还在读旧配置" */
    public static final String CACHE_CONTROL = "no-cache";

    /**
     * 以源站根地址作 Referer（防盗链最常见的判据就是"Referer 得是我自己的域名"）。
     * 只取 scheme://host[:port]，丢弃路径与查询：路径里常带 token，透传出去既无必要也多余。
     * 拿不到 scheme 就返回 null —— 调用方不加这个头。
     */
    public static String referer(String url) {
        if (url == null) return null;
        int p = url.indexOf("://");
        if (p <= 0) return null;
        int start = p + 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        if (end <= start) return null;
        return url.substring(0, end) + "/";
    }
}
