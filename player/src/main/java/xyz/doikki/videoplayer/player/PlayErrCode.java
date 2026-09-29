package xyz.doikki.videoplayer.player;

/**
 * I1（2026-09-28）：内核错误码透传的最小通道。
 *
 * 背景：播放错误码在此前被全工程丢弃 —— Exo 只打日志、Ijk 丢弃 what/extra，
 * 内核报什么错全都无差别走同一条兜底链，"很多源直接播放失败"到底是 403 防盗链、
 * 404、超时还是解码不支持，从日志完全无法分类（二期定向修的盲区）。
 *
 * 做法：各内核在回调 {@code PlayerEventListener.onError()} **之前**把错误描述写入
 * {@link #set}；PlayFragment.errReplay() 入口 {@link #take} 取走归因（take 即清空，
 * 保证一次错误只归因一次、不串染下一轮）。只加可见性，不改任何判定行为。
 *
 * 放在本模块（player）而非 app：ExoMediaPlayer/IjkPlayer 在这里，app 反向依赖本模块。
 */
public final class PlayErrCode {

    private static volatile String sDesc = "";

    /** 内核在 onError() 前调用；desc 形如 "Exo:2004 ERROR_CODE_IO_BAD_HTTP_STATUS" / "Ijk:-10000/-1010" */
    public static void set(String desc) {
        sDesc = desc == null ? "" : desc;
    }

    /** 取走并清空：一次错误只归因一次 */
    public static String take() {
        String v = sDesc;
        sDesc = "";
        return v;
    }

    /**
     * 失败粗归因（**只用于日志文字，不参与任何跳过/判死决策** —— 用户 09-28 口径：不另加判错）。
     * 返回 null ＝ 没有拿到内核错误码（取链失败等路径）。
     * Exo 数字段：1xxx=Source 错误、2xxx=IO/网络（2004=HTTP 4xx/5xx）、**3xxx=解析/容器**、4xxx=解码。
     * （2026-09-28 修正：原来把 3xxx 标成"解码"是错的 —— 真机 cb 里 3002/3003 全是**解析类**。）
     */
    public static String classify(String desc) {
        if (desc == null || desc.isEmpty()) return null;
        if (desc.startsWith("Exo:")) {
            try {
                int code = Integer.parseInt(desc.substring(4).trim());
                if (code >= 4000 && code < 5000) return "解码";
                if (code >= 3000 && code < 4000) return "解析/容器";
                if (code >= 1000 && code < 3000) return "网络/HTTP";
            } catch (NumberFormatException ignored) {
            }
        } else if (desc.startsWith("Ijk:")) {
            // Ijk：-10000=MEDIA_ERROR_UNKNOWN（多为内容打不开/无法解析），-1010=MEDIA_ERROR_UNSUPPORTED
            if (desc.contains("-1010")) return "解码";
            return "内容无法打开";
        } else if (desc.startsWith("exc:")) {
            return "内容无法打开"; // setDataSource/prepareAsync 抛异常
        }
        return "其他";
    }

    private PlayErrCode() {
    }
}
