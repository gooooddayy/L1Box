package com.github.catvod.crawler;

import android.content.Context;

import com.github.tvbox.osc.util.OkGoHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;

public abstract class Spider {

    public void init(Context context) throws Exception {}

    public void init(Context context, String extend) throws Exception {
        init(context);
    }

    public String homeContent(boolean filter) throws Exception {
        return "";
    }

    public String homeVideoContent() throws Exception {
        return "";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap < String, String > extend) throws Exception {
        return "";
    }

    public String detailContent(List < String > ids) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return "";
    }

    public String playerContent(String flag, String id, List < String > vipFlags) throws Exception {
        return "";
    }

    public boolean manualVideoCheck() throws Exception {
        return false;
    }

    public boolean isVideoFormat(String url) throws Exception {
        return false;
    }

    public Object[] proxyLocal(Map < String, String > params) throws Exception {
        return null;
    }

    public void cancelByTag() {

    }

    public void destroy() {}

    /**
     * jar 侧的 DNS 入口（2026-09-22 修正）。
     *
     * ⚠ 必须返回**包装类**，不能返回裸的 dnsOverHttps：
     *  · 裸 DoH 对私网地址/IP 字面量是**直接抛** UnknownHostException（`isPrivateHost` 命中且
     *    `resolvePrivateAddresses` 默认 false），127.0.0.1 / 192.168.x / localhost 全中 —— jar 只要
     *    走本机净化路由或 IP 直连站点，解析就必失败；
     *  · DnsOverHttps 已把"查询无结果"从**静默回退系统 DNS** 改成**如实抛出**，裸挂会让 jar 的请求
     *    直接失败（改动前是"慢一点但能通"）。
     * 包装类把这两件事都兜住：私网/字面量绕开 DoH、DoH 失败回退系统 DNS、连续失败熔断、
     * DoH 客户端 2s 超时。DoH 正常时行为与裸挂完全一致。
     */
    public static Dns safeDns() {
        return OkGoHelper.fallbackDns;
    }
}
