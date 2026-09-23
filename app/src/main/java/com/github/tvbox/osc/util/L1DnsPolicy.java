package com.github.tvbox.osc.util;

/**
 * 安全 DNS（DoH）的启用策略：**决定某个 host 该不该交给 DoH，以及 DoH 连续失联时的熔断**。
 *
 * 为什么要单独一层：裸开 DoH 有两个确定性坑，都是"换台设备就会中"的坑，不能靠实测侥幸。
 *
 * ① **私有地址 / IP 字面量必须绕开 DoH**。
 *    本工程内嵌的 DoH 实现里 {@code Builder.resolvePrivateAddresses} 默认 false，
 *    而 private 的判据是"没有有效 TLD+1"，即 {@code 127.0.0.1}、{@code 192.168.x.x}、{@code localhost}
 *    全部命中 —— 命中后**直接抛 UnknownHostException**，不会回退。
 *    而本 App 的核心链路恰好全在 127.0.0.1 上（净化路由 /l1.m3u8、网盘 /proxy、首页 /l1home）。
 *    ⇒ 若不做这层前置判断，一旦打开 DoH，**这些本地请求会全部解析失败**，表现为
 *      "开了安全DNS之后播放就不行了"，而且是必现。
 *    对 IP 字面量做 DNS 查询本身也没有任何意义（查 "1.2.3.4" 的 A 记录只会白等一次超时）。
 *
 * ② **DoH 失联要熔断**。
 *    DoH 被阻断时，每个未命中的域名都要等满一次 HTTP 超时才回退系统 DNS；请求一多就是全局变慢。
 *    连续失败若干次即进入冷却期，冷却期内直接走系统 DNS，过冷却期再放一次探测放行。
 *
 * 纯逻辑、不依赖 Android 也不依赖 okhttp —— 可用桌面 JVM 跑用例表验证。
 */
public class L1DnsPolicy {

    /** 冷却时长：DoH 恢复通常是网络环境变化，5 分钟足够短到能自愈、又长到不反复白等 */
    public static final long COOL_MS = 5 * 60 * 1000L;

    /** 连续失败达到这个次数即熔断。取 3：一次失败可能是偶发，连三说明是链路问题 */
    public static final int FAIL_LIMIT = 3;

    private volatile int fails = 0;
    private volatile long coolUntil = 0L;

    /**
     * 该 host 是否必须直接走系统 DNS（= 不要碰 DoH）。
     * 只拦"DoH 无意义或必然被拒"的形态；公网域名一律放行给 DoH。
     */
    public static boolean directHost(String host) {
        if (host == null) return true;
        String h = host.trim();
        if (h.isEmpty()) return true;
        // 方括号包裹的 IPv6 字面量（http://[::1]:8080 的 host 就是 [::1]）
        if (h.charAt(0) == '[') return true;
        // 域名不含冒号；含冒号即可判定为 IPv6 字面量
        if (h.indexOf(':') >= 0) return true;
        if (isIpv4Literal(h)) return true;
        // 尾点归一后再看层级（"example.com." 与 "example.com" 同义）
        String low = h;
        while (low.endsWith(".")) low = low.substring(0, low.length() - 1);
        if (low.isEmpty()) return true;
        // 单段名（localhost、路由器名、容器名）：没有有效 TLD，DoH 必然判为 private 并抛异常
        if (low.indexOf('.') < 0) return true;
        low = low.toLowerCase();
        // 保留域 / 局域网常用后缀：DoH 解析不到，交给系统 DNS 才是对的
        return low.endsWith(".local") || low.endsWith(".lan") || low.endsWith(".internal")
                || low.endsWith(".home") || low.endsWith(".localdomain");
    }

    /** a.b.c.d 且四段都在 0~255：这是 IP 字面量，不是域名 */
    private static boolean isIpv4Literal(String h) {
        int seg = 0, num = 0, digits = 0;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (c == '.') {
                if (digits == 0 || digits > 3) return false;
                seg++;
                if (seg > 3) return false;
                num = 0;
                digits = 0;
                continue;
            }
            if (c < '0' || c > '9') return false;
            num = num * 10 + (c - '0');
            digits++;
            if (num > 255) return false;
        }
        return seg == 3 && digits > 0 && digits <= 3;
    }

    /** 是否处于熔断冷却期 */
    public boolean cooling(long now) {
        return now < coolUntil;
    }

    /** DoH 查询成功：立即恢复（哪怕刚熔断，只要探测成功就放行） */
    public void onOk() {
        fails = 0;
        coolUntil = 0L;
    }

    /** DoH 查询失败：累计到阈值后进入冷却期 */
    public void onFail(long now) {
        fails++;
        if (fails >= FAIL_LIMIT) {
            fails = 0;
            coolUntil = now + COOL_MS;
        }
    }

    /** 连续失败计数（供日志/用例表断言） */
    public int fails() {
        return fails;
    }
}
