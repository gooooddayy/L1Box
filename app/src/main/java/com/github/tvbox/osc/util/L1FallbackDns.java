package com.github.tvbox.osc.util;

import android.util.Log;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import okhttp3.Dns;
import okhttp3.dnsoverhttps.DnsOverHttps;

/**
 * 挂在 OkHttp 上的 DNS 入口：**DoH 优先，DoH 不可用/失联/被拒时回退系统 DNS**。
 *
 * 没有这一层时，"安全DNS"是**开不得的**：
 *  · 内嵌 DoH 实现对私有地址直接抛 UnknownHostException（不回退）⇒ 打开即让本机
 *    127.0.0.1 的净化路由/网盘代理/首页路由全部解析失败；
 *  · DoH 被阻断时每次解析要等满一次 HTTP 超时才得到结果 ⇒ 全局变慢。
 * 上面两条的判断与熔断计数器都在 {@link L1DnsPolicy}（纯逻辑、有桌面用例表），
 * 这里只做"取策略 → 调 DoH → 回退"的胶水，不放任何判断规则。
 *
 * 失败回退的目标是**系统 DNS**，不是"换一个源"：同一个域名重试另一个 DNS 解析器，
 * 始终是同一个地址、同一个源。
 *
 * **2026-09-22 补两件欠账**（都是"让失败看得见"，不改任何解析行为）：
 *  ① 失败时记 Log（tag `L1Dns`）。原先这里是 `catch (Throwable)` 静默吞掉 —— 等于把
 *     DnsOverHttps 特意改成 throw 求来的"失败可见"又吞了回去，日志里根本看不出
 *     DoH 到底有没有生效。因为连续失败 3 次即熔断 5 分钟，日志量天然有界。
 *  ② 累计计数 + 差值输出。回答"安全DNS 到底在不在工作""搜索这一轮里 DNS 有没有拖后腿"，
 *     靠感觉永远说不清，必须落到数字上。计数只有一个写入点是本类，读用来打日志。
 */
public class L1FallbackDns implements Dns {

    private static final String TAG = "L1Dns";

    /** DoH 查询入口；为 null 或 url 未设置时表示"当前未启用 DoH" */
    private final DnsOverHttps doh;
    private final L1DnsPolicy policy = new L1DnsPolicy();

    /** 累计计数：DoH 命中 / DoH 失败 / 熔断冷却期跳过 / 直接走系统 DNS（含 DoH 关闭） */
    private static volatile long cDoh = 0, cFail = 0, cCool = 0, cDirect = 0;

    public L1FallbackDns(DnsOverHttps doh) {
        this.doh = doh;
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        // 三种情况连一次网络都不发：DoH 关闭 / 本地与 IP 字面量 / 熔断冷却期内
        if (doh == null || doh.url() == null || L1DnsPolicy.directHost(hostname)) {
            cDirect++;
            return Dns.SYSTEM.lookup(hostname);
        }
        long now = System.currentTimeMillis();
        if (policy.cooling(now)) {
            cCool++;
            return Dns.SYSTEM.lookup(hostname);
        }
        try {
            List<InetAddress> r = doh.lookup(hostname);
            if (r != null && !r.isEmpty()) {
                cDoh++;
                policy.onOk();
                return r;
            }
            cFail++;
        } catch (Throwable th) {
            // DoH 抛错（含私有地址拒答、超时、TLS 失败）：下面统一回退，不让它冒到请求层
            cFail++;
            policy.onFail(now);
            logFail(hostname, th.getClass().getSimpleName());
            return Dns.SYSTEM.lookup(hostname);
        }
        // 没抛异常但没有结果：同样是失败，同样要留痕
        policy.onFail(now);
        logFail(hostname, "no-result");
        return Dns.SYSTEM.lookup(hostname);
    }

    /** 失败留痕；熔断刚触发时额外说明，便于判断"DoH 是不是整条链路都不可用" */
    private void logFail(String hostname, String why) {
        boolean tripped = policy.cooling(System.currentTimeMillis());
        Log.w(TAG, "DoH 失败，已回退系统DNS：" + hostname + " 原因=" + why
                + (tripped ? "（连续失败达 " + L1DnsPolicy.FAIL_LIMIT + " 次，熔断 "
                        + (L1DnsPolicy.COOL_MS / 60000) + " 分钟内不再尝试 DoH）" : ""));
    }

    /** 计数快照，配合 {@link #delta(long[])} 看"某一段窗口内"的 DNS 活动 */
    public static long[] counters() {
        return new long[]{cDoh, cFail, cCool, cDirect};
    }

    /** 相对某次快照的增量文本（base 为空数组时退化为累计值） */
    public static String delta(long[] base) {
        long[] n = counters();
        long b0 = 0, b1 = 0, b2 = 0, b3 = 0;
        if (base != null && base.length == 4) {
            b0 = base[0];
            b1 = base[1];
            b2 = base[2];
            b3 = base[3];
        }
        return "DNS(窗口内):DoH命中=" + (n[0] - b0) + " 失败=" + (n[1] - b1)
                + " 熔断跳过=" + (n[2] - b2) + " 直连=" + (n[3] - b3);
    }
}
