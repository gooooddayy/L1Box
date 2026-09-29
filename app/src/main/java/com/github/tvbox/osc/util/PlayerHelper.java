package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.player.EXOmPlayer;
import com.github.tvbox.osc.player.IjkMediaPlayer;
import com.github.tvbox.osc.player.render.SurfaceRenderViewFactory;
import com.github.tvbox.osc.player.thirdparty.Kodi;
import com.github.tvbox.osc.player.thirdparty.MXPlayer;
import com.github.tvbox.osc.player.thirdparty.ReexPlayer;
import com.github.tvbox.osc.player.thirdparty.RemoteTVBox;
import com.github.tvbox.osc.player.thirdparty.VlcPlayer;
import com.github.tvbox.osc.server.RemoteServer;
import com.orhanobut.hawk.Hawk;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

import tv.danmaku.ijk.media.player.IjkLibLoader;
import xyz.doikki.videoplayer.exo.ExoCacheConfig;
import xyz.doikki.videoplayer.exo.ExoMediaPlayerFactory;
import xyz.doikki.videoplayer.player.AndroidMediaPlayerFactory;
import xyz.doikki.videoplayer.player.PlayerFactory;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.render.RenderViewFactory;
import xyz.doikki.videoplayer.render.TextureRenderViewFactory;

public class PlayerHelper {
    public static void updateCfg(VideoView videoView, JSONObject playerCfg) {
        int playerType = Hawk.get(HawkConfig.PLAY_TYPE, 2);
        int renderType = Hawk.get(HawkConfig.PLAY_RENDER, 0);
        String ijkCode = Hawk.get(HawkConfig.IJK_CODEC, "软解码");
        int scale = Hawk.get(HawkConfig.PLAY_SCALE, 0);
        try {
            playerType = playerCfg.getInt("pl");
            renderType = playerCfg.getInt("pr");
            ijkCode = playerCfg.getString("ijk");
            scale = playerCfg.getInt("sc");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        IJKCode codec = ApiConfig.get().getIJKCodec(ijkCode);
        PlayerFactory playerFactory;
        if (playerType == 1) {
            playerFactory = new PlayerFactory<IjkMediaPlayer>() {
                @Override
                public IjkMediaPlayer createPlayer(Context context) {
                    return new IjkMediaPlayer(context, codec);
                }
            };
            try {
                tv.danmaku.ijk.media.player.IjkMediaPlayer.loadLibrariesOnce(new IjkLibLoader() {
                    @Override
                    public void loadLibrary(String s) throws UnsatisfiedLinkError, SecurityException {
                        try {
                            System.loadLibrary(s);
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                });
            } catch (Throwable th) {
                th.printStackTrace();
            }
        } else if (playerType == 2) {
            playerFactory = new PlayerFactory<EXOmPlayer>() {
                @Override
                public EXOmPlayer createPlayer(Context context) {
                    return new EXOmPlayer(context);
                }
            };
        } else {
            playerFactory = AndroidMediaPlayerFactory.create();
        }
        RenderViewFactory renderViewFactory = null;
        switch (renderType) {
            case 0:
            default:
                renderViewFactory = TextureRenderViewFactory.create();
                break;
            case 1:
                renderViewFactory = SurfaceRenderViewFactory.create();
                break;
        }
        videoView.setPlayerFactory(playerFactory);
        videoView.setRenderViewFactory(renderViewFactory);
        videoView.setScreenScaleType(scale);
    }

    public static void updateCfg(VideoView videoView) {
        int playType = Hawk.get(HawkConfig.PLAY_TYPE, 2);
        PlayerFactory playerFactory;
        if (playType == 1) {
            playerFactory = new PlayerFactory<IjkMediaPlayer>() {
                @Override
                public IjkMediaPlayer createPlayer(Context context) {
                    return new IjkMediaPlayer(context, null);
                }
            };
            try {
                tv.danmaku.ijk.media.player.IjkMediaPlayer.loadLibrariesOnce(new IjkLibLoader() {
                    @Override
                    public void loadLibrary(String s) throws UnsatisfiedLinkError, SecurityException {
                        try {
                            System.loadLibrary(s);
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                });
            } catch (Throwable th) {
                th.printStackTrace();
            }
        } else if (playType == 2) {
            playerFactory = new PlayerFactory<EXOmPlayer>() {
                @Override
                public EXOmPlayer createPlayer(Context context) {
                    return new EXOmPlayer(context);
                }
            };
        } else {
            playerFactory = AndroidMediaPlayerFactory.create();
        }
        int renderType = Hawk.get(HawkConfig.PLAY_RENDER, 0);
        RenderViewFactory renderViewFactory = null;
        switch (renderType) {
            case 0:
            default:
                renderViewFactory = TextureRenderViewFactory.create();
                break;
            case 1:
                renderViewFactory = SurfaceRenderViewFactory.create();
                break;
        }
        videoView.setPlayerFactory(playerFactory);
        videoView.setRenderViewFactory(renderViewFactory);
    }


    public static void init() {
        // 把设置页的「磁盘缓存」开关同步给 player 模块 —— 它读不到 Hawk，只能由 App 层推过去。
        // init() 是应用启动与切换播放器类型都会走的入口，放这里能覆盖"关掉缓存后重启应用"这条路径
        // （否则静态开关会回到默认值 true，用户的关闭动作会被静默丢弃）。
        try {
            ExoCacheConfig.setEnabled(Hawk.get(HawkConfig.EXO_DISK_CACHE, true));
        } catch (Throwable ignored) {
        }
        try {
            tv.danmaku.ijk.media.player.IjkMediaPlayer.loadLibrariesOnce(new IjkLibLoader() {
                @Override
                public void loadLibrary(String s) throws UnsatisfiedLinkError, SecurityException {
                    try {
                        System.loadLibrary(s);
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
            });
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    public static String getPlayerName(int playType) {
        HashMap<Integer, String> playersInfo = getPlayersInfo();
        if (playersInfo.containsKey(playType)) {
            return playersInfo.get(playType);
        } else {
            return "系统播放器";
        }
    }

    private static HashMap<Integer, String> mPlayersInfo = null;
    public static HashMap<Integer, String> getPlayersInfo() {
        if (mPlayersInfo == null) {
            HashMap<Integer, String> playersInfo = new HashMap<>();
            playersInfo.put(0, "系统播放器");
            playersInfo.put(1, "IJK播放器");
            playersInfo.put(2, "Exo播放器");
            playersInfo.put(10, "MX播放器");
            playersInfo.put(11, "Reex播放器");
            playersInfo.put(12, "Kodi播放器");
            playersInfo.put(13, "附近TVBox");
            playersInfo.put(14, "VLC播放器");
            mPlayersInfo = playersInfo;
        }
        return mPlayersInfo;
    }

    /**
     * 各播放器"本机是否可用"。**不做静态缓存**：用户装/卸了外部播放器后无需重启 App 就能反映到
     * 设置页与长按菜单里（每项只是一次 PackageManager 查询或一次 Hawk 读取，开销可忽略）。
     *
     * 系统播放器（0）恒为 true：它是 App 自带的 Android MediaPlayer，不存在"装没装"的问题；
     * 原实现写死 false，使它既进不了设置页的默认播放器列表、也进不了长按菜单。
     */
    public static HashMap<Integer, Boolean> getPlayersExistInfo() {
        HashMap<Integer, Boolean> playersExist = new HashMap<>();
        playersExist.put(0, true);
        playersExist.put(1, true);
        playersExist.put(2, true);
        playersExist.put(10, MXPlayer.getPackageInfo() != null);
        playersExist.put(11, ReexPlayer.getPackageInfo() != null);
        playersExist.put(12, Kodi.getPackageInfo() != null);
        playersExist.put(13, RemoteTVBox.getAvalible() != null);
        playersExist.put(14, VlcPlayer.getPackageInfo() != null);
        return playersExist;
    }

    public static Boolean getPlayerExist(int playType) {
        HashMap<Integer, Boolean> playersExistInfo = getPlayersExistInfo();
        if (playersExistInfo.containsKey(playType)) {
            return playersExistInfo.get(playType);
        } else {
            return false;
        }
    }

    /**
     * 本地播放页的候选＝内置内核（系统 / Exo / IJK）。
     *
     * 为什么本地页不列外部播放器：本地播放页**没有吊起外部播放器的通路**
     * （全工程 runExternalPlayer 只有点播页一个调用点），列出来就是"点了也播不了"的无效项，
     * 而本地页对外的列表在短按循环里还会被按到。判据与"是否外部"无关，是"这里能不能用"。
     */
    public static ArrayList<Integer> getBuiltinPlayerTypes() {
        ArrayList<Integer> types = new ArrayList<>();
        types.add(0);
        types.add(BUILTIN_EXO);
        types.add(1);
        return types;
    }

    /**
     * 设置页「默认播放器」的候选项＝**本机确实可用的播放器**（原始编号）：
     * 系统(0) → Exo(2) → IJK(1) → 已安装的外部播放器(10~14)。
     *
     * 清单由"存不存在"决定，装了就显示、没装就不显示 —— 不列没装的外部，
     * 是为了避免用户点到"点了没反应/吊不起来"的项。顺序固定，不依赖 HashMap 遍历。
     */
    public static ArrayList<Integer> getAvailablePlayerTypes() {
        ArrayList<Integer> types = getBuiltinPlayerTypes();
        HashMap<Integer, Boolean> exist = getPlayersExistInfo();
        for (int ext = 10; ext <= 14; ext++) {
            if (Boolean.TRUE.equals(exist.get(ext))) types.add(ext);
        }
        return types;
    }

    /**
     * 配置里的播放器本机没有（外部被卸载、换设备后配置跟着同步过来等）时落回 Exo，避免
     * 界面显示一个已经点不动的项。判据仍只是"存不存在"——装了的外部播放器照样可以作默认。
     */
    public static int normalizeAvailablePlayerType(int type) {
        return Boolean.TRUE.equals(getPlayerExist(type)) ? type : BUILTIN_EXO;
    }

    /**
     * 外部播放器吊起失败后要退回的内置内核：全局默认本身是内置就沿用它，否则用 Exo。
     * 与 normalizeAvailablePlayerType 的区别：这里问的是"退回哪个内置"，不看存不存在。
     */
    public static int fallbackBuiltinType() {
        int type = Hawk.get(HawkConfig.PLAY_TYPE, BUILTIN_EXO);
        return (type == 0 || type == 1 || type == 2) ? type : BUILTIN_EXO;
    }

    /**
     * 外部播放器吊起失败时的回退副本：只把 pl 换回内置默认，其余键（解码档/渲染/比例…）原样保留。
     * 副本构造失败返回 null，调用方按原配置继续即可（不额外制造一次失败）。
     * 不改用户设置：手动选了外部播放器，下次仍然会先尝试吊起。
     */
    public static JSONObject copyWithBuiltinFallback(JSONObject cfg) {
        if (cfg == null) return null;
        try {
            JSONObject copy = new JSONObject(cfg.toString());
            copy.put("pl", fallbackBuiltinType());
            return copy;
        } catch (Throwable th) {
            th.printStackTrace();
            return null;
        }
    }

    /**
     * 6 参重载（不带进度）：转交给 7 参重载，进度按 0（从头播）。
     *
     * 原实现是 `return runExternalPlayer(..., headers);` —— 6 个实参再次命中它自己，
     * 是**无限递归**，一旦有调用点必然 StackOverflowError。当前全工程只有 7 参那一版被调用
     * （播放页 startPlayUrl），所以它一直没炸；属于"埋着的地雷"，按既定口径（减少崩溃必须）一并修掉。
     */
    public static Boolean runExternalPlayer(int playerType, Activity activity, String url, String title, String subtitle, HashMap<String, String> headers) {
        return runExternalPlayer(playerType, activity, url, title, subtitle, headers, 0);
    }

    public static Boolean runExternalPlayer(int playerType, Activity activity, String url, String title, String subtitle, HashMap<String, String> headers, long progress) {
        // #8（09-23 定稿）：外部播放器改喂**本机转发端点**（见 wrapForExternalPlayer）。
        // 不参与的只有两档（RemoteTVBox 是"推给另一台设备"、VLC 不吃请求头），见 isHeaderForwardPlayer。
        String playUrl = isHeaderForwardPlayer(playerType) ? wrapForExternalPlayer(url, headers) : url;
        boolean callResult = false;
        switch (playerType) {
            case 10: {
                callResult = MXPlayer.run(activity, playUrl, title, subtitle, headers);
                break;
            }
            case 11: {
                callResult = ReexPlayer.run(activity, playUrl, title, subtitle, headers);
                break;
            }
            case 12: {
                callResult = Kodi.run(activity, playUrl, title, subtitle, headers);
                break;
            }
            case 13: {
                callResult = RemoteTVBox.run(activity, playUrl, title, subtitle, headers);
                break;
            }
            case 14: {
                callResult = VlcPlayer.run(activity, url, title, subtitle, progress);
                break;
            }
        }
        return callResult;
    }

    /**
     * 哪些外部播放器该走本机转发：**必须同时满足"在本机播放"＋"接收请求头"**。
     *
     *  - 10 MX / 12 Kodi：请求头是**拼在地址字符串后面**交给它们的（`地址|k=v&k=v`），
     *    这正是"约 10 秒结束"的机理，是本条改动的正主；
     *  - 11 Reex：请求头走 intent 附加项，属于"能不能每次都带头"未知的一档，一并纳入
     *    （对本机转发而言，多覆盖一个只会更稳，不会改变上游拿到的东西）；
     *  - **13 RemoteTVBox 必须排除**：它是把地址 **POST 给局域网里另一台设备**去播的
     *    （`RemoteTVBox.run` → `http://<那台设备的IP>:9978/action`）。给它塞 127.0.0.1，
     *    那台设备解析出来就是"它自己"，必然播不了 —— 这一档保持原样。
     *  - **14 VLC 排除**：它的 run 压根不吃请求头，包装只会多一层往返。
     */
    private static boolean isHeaderForwardPlayer(int playerType) {
        return playerType == 10 || playerType == 11 || playerType == 12;
    }

    /**
     * 请求头兜底补全（从 PlayFragment 搬来，两边共用一份实现）：只在**没给** Referer 时，
     * 按播放地址补一个同源 Referer。
     *
     * 为什么只补 Referer、不补 UA：工程里没有"固定 UA"的公共出口（只有一组随机浏览器 UA 的工具），
     * 而给同一个源每次请求随机 UA 会把不确定性带进播放链路，与"稳定优先"相悖 ——
     * UA 缺失时播放器用自带默认值，行为与改动前一致；站点显式给了 UA 的照旧原样使用。
     *
     * 补 Referer 的收益是明确的：防盗链校验 Referer，缺了就 403；补的是地址自身的同源前缀，
     * 对不需要 Referer 的源无影响。只补缺失项，绝不覆盖站点已给的值。任何异常都退回原请求头。
     *
     * **使用边界（沿用既有口径）**：结果只喂**本机转发链路**（/l1play 与投屏码那条）和净化预取，
     * 绝不写进播放器的默认请求头 —— 源站 Referer 会让跨域 CDN 上的分片 403。
     */
    public static HashMap<String, String> fillMissingHeaders(String url, HashMap<String, String> headers) {
        try {
            if (url == null || !url.toLowerCase().startsWith("http")) return headers;
            if (url.contains("://127.0.0.1") || url.contains("://localhost")) return headers; // 本地地址没有防盗链
            if (headers != null) {
                for (String k : headers.keySet()) {
                    if ("Referer".equalsIgnoreCase(k)) return headers;
                }
            }
            int p = url.indexOf("://");
            int slash = url.indexOf('/', p + 3);
            String origin = (slash > 0) ? url.substring(0, slash + 1) : url;
            HashMap<String, String> out = (headers == null) ? new HashMap<>() : new HashMap<>(headers);
            out.put("Referer", origin);
            PlayTrace.stage("请求头", "补同源 Referer=" + origin);
            return out;
        } catch (Throwable th) {
            return headers;
        }
    }

    /**
     * **投屏**（bt，2026-09-24）：把交给 DLNA 设备去拉的地址，换成**局域网可达**的本机转发端点。
     *
     * 与外部播放器的区别只有一处：DLNA 设备是**另一台机器**，所以端点必须是本机的局域网 IP，
     * 而不是 127.0.0.1。换来的一次性随机码里**不含**上游地址与请求头，电视只看到"本机地址＋码"。
     *
     * 三条退回（都退回原地址，与改动前行为完全一致，绝不制造新的失败）：
     *  - 取不到局域网 IP（没连网 / 只开热点且网卡没地址）；
     *  - 地址本来就是本机地址（调用方另有守卫，这里再兜一层）；
     *  - 登记失败。
     */
    public static String wrapForCast(String url, HashMap<String, String> headers) {
        try {
            if (url == null || url.isEmpty()) return url;
            if (isLoopbackUrl(url)) return url;
            String lan = RemoteServer.lanIp();
            if (lan == null || lan.isEmpty()) return url;
            HashMap<String, String> h = headers;
            if (h == null || h.isEmpty()) h = fillMissingHeaders(url, null);
            String token = RemoteServer.registerCastForward(url, h);
            if (token == null || token.isEmpty()) return url;
            PlayTrace.stage("投屏", "改喂局域网转发端点 " + lan + " " + PlayTrace.brief(url));
            return "http://" + lan + ":" + RemoteServer.serverPort + "/l1play?t=" + token;
        } catch (Throwable th) {
            th.printStackTrace();
            return url;
        }
    }

    /** 转发端点 URL 的长度上限：本机服务的请求行/请求头有 8KB 级上限，留足余量 */
    private static final int EXT_FORWARD_MAX_LEN = 3500;

    /**
     * #8（2026-09-23 定稿）：把交给外部播放器的地址换成**本机转发端点**。
     *
     * **要解决的是"外部播放器约 10 秒就结束"**：非 m3u8 直链是原样交给 MX 的，而 MX 只把请求头
     * **拼在地址字符串后面**（`MXPlayer.java`：`地址|k=v&k=v`）。首请求带头 → 起播成功并预读
     * 8~12 秒；**后续续传/重连丢掉请求头 → 上游断流 → 缓冲放完即"结束"**，时间点正好对上。
     * （m3u8 之所以没事，是因为它拿到的是本机地址，本来就不校验防盗链。）
     *
     * 换成 `http://127.0.0.1:<port>/l1play?u=<原地址>&h=<请求头>` 之后：请求头由 App 在服务端
     * 统一带上，播放器**每一个**请求（首播 / 续传 / 拖动）都必然带齐；`Range` 由本机透传、
     * `Content-Range / Content-Length / Content-Type` 原样回传（少了这步只能播、不能拖）。
     *
     * 三条边界都是"不多做一分"：
     *  - **站点没下发请求头时按同源 Referer 兜底后再包**（2026-09-24 bt 改，见下）——
     *    原来这里是"原样返回"，真机实测证明那是 D1 的根因：直链普遍不带 headers，于是这一条
     *    把绝大多数外部播放都静默退回了；
     *  - **已经是本机地址的原样返回**（净化后的清单 / 本地代理）—— 它本来就被外部播放器正常播；
     *  - **拼出来超过 {@link #EXT_FORWARD_MAX_LEN} 就退回直给** —— 本机服务的请求头有长度上限，
     *    超了连播都播不了，那比"10 秒结束"更糟。
     *
     * 只作用于外部播放器：内置 Exo/IJK 路径一个字不动（它们本来就走内核数据源，每次请求都带头）。
     */
    public static String wrapForExternalPlayer(String url, HashMap<String, String> headers) {
        try {
            if (url == null || url.isEmpty()) return url;
            if (isLoopbackUrl(url)) return url;
            // 站点**没下发请求头**时不能直接退回直给（D1 真机实测的根因）：大量站点的直链不带 headers，
            // 原来第一条判据就把它静默退回了，MX 拿到裸地址 → 上游 403 → 约 12 秒结束，
            // 日志里连"改喂本机转发端点"都不会出现。这里按播放地址自身的同源前缀补一个 Referer，
            // 结果只喂**转发链路**（由本机在服务端统一带上），绝不交给播放器 —— 后者会把源站 Referer
            // 写进播放器的默认请求头，使跨域 CDN 上的分片集体 403（见 PlayFragment 的既有说明）。
            // 补不出（非 http 地址等）才退回直给，行为与改动前一致。
            HashMap<String, String> h = headers;
            if (h == null || h.isEmpty()) {
                h = fillMissingHeaders(url, null);
                if (h == null || h.isEmpty()) return url;
            }
            String full = "http://127.0.0.1:" + RemoteServer.serverPort + "/l1play"
                    + "?u=" + URLEncoder.encode(url, "UTF-8")
                    + "&h=" + URLEncoder.encode(new JSONObject(h).toString(), "UTF-8");
            if (full.length() > EXT_FORWARD_MAX_LEN) {
                PlayTrace.stage("外部", "转发地址过长(" + full.length() + ")，退回直给");
                return url;
            }
            PlayTrace.stage("外部", "改喂本机转发端点 " + PlayTrace.brief(url));
            return full;
        } catch (Throwable th) {
            // 包装失败绝不能让这一集播不了：退回原地址，行为与改动前一致
            th.printStackTrace();
            return url;
        }
    }

    /** 是否已经是本机地址（127.0.0.1 / localhost / ::1） */
    private static boolean isLoopbackUrl(String url) {
        String u = url.toLowerCase();
        return u.startsWith("http://127.0.0.1") || u.startsWith("https://127.0.0.1")
                || u.startsWith("http://localhost") || u.startsWith("http://[::1]");
    }

    /**
     * 复制链接用的**兜底解包**（bw，2026-09-24）：万一带上了本机转发端点，就还原成上游原地址。
     *
     * 为什么要有它：下载按钮的「复制链接」要给用户**站点原始直链**（能直接粘到任意下载器）。
     * 正常路径上 `PlayFragment.getFinalUrl()` 返回的就是原始直链（`mCurrentUrl` 在净化之前赋值，
     * 全文件唯一赋值点），转发包装只是 `wrapForExternalPlayer` 里的**局部变量**、不回流。
     * 但"外部播放器/投屏正在用的时候点复制"这类时序无法穷举 —— 与其赌，不如在这里再解一层。
     *
     * 命中 `.../l1play?u=<urlencoded>[&h=...]` ⇒ 取 `u` 解码后返回；**其他任何形态（含空）⇒ 一字不改**。
     * 整体 try/catch：解包失败也原样返回，绝不因为"复制"这个动作影响到别的什么。
     * 正常路径上 url 里不含 `/l1play?` ⇒ 直接原样返回，与改动前逐字节相同（副作用为零）。
     */
    public static String unwrapForward(String url) {
        try {
            if (url == null || url.isEmpty()) return url;
            int p = url.indexOf("/l1play?");
            if (p < 0) return url;
            String q = url.substring(p + "/l1play?".length());
            for (String kv : q.split("&")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                if (!"u".equals(kv.substring(0, eq))) continue;
                String v = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                if (v != null && !v.isEmpty()) return v;
            }
            return url;
        } catch (Throwable th) {
            return url;
        }
    }

    public static String getRenderName(int renderType) {
        if (renderType == 1) {
            return "SurfaceView";
        } else {
            return "TextureView";
        }
    }

    /**
     * 播放失败时的兼容性兜底阶梯：按候选顺序（兼容性从高到低）返回"播放器+解码"配置副本。
     * 顺序说明：IJK 软解码对封装/编码最宽容放第一档，Exo 换内核覆盖 IJK 失败的情况；
     * 与 base 当前组合完全相同的候选会被跳过，避免空跑一次。
     * 只涉及内置播放器（1=IJK、2=Exo），绝不包含外部播放器；返回的是副本，不会改动 base。
     */
    public static ArrayList<JSONObject> buildCompatFallbackPlan(JSONObject base) {
        ArrayList<JSONObject> plan = new ArrayList<>();
        if (base == null) return plan;
        int curPl = base.optInt("pl", 1);
        String curIjk = base.optString("ijk", "");
        String softCodec = findSoftDecodeCodecName();
        if (softCodec != null && !(curPl == 1 && softCodec.equals(curIjk))) {
            JSONObject cfg = copyPlayerCfg(base, 1, softCodec);
            if (cfg != null) plan.add(cfg);
        }
        if (curPl != 2) {
            JSONObject cfg = copyPlayerCfg(base, 2, null);
            if (cfg != null) plan.add(cfg);
        }
        return plan;
    }

    private static JSONObject copyPlayerCfg(JSONObject base, int pl, String ijk) {
        try {
            JSONObject cfg = new JSONObject(base.toString());
            cfg.put("pl", pl);
            if (ijk != null) cfg.put("ijk", ijk);
            return cfg;
        } catch (Throwable th) {
            return null;
        }
    }

    /**
     * 找"软解码"分组名。用 IJK 选项 4|mediacodec 判断而非比对分组文案——
     * 分组名是订阅下发的，各家叫法不一致（有的叫"软解码"有的叫别的）。
     */
    public static String findSoftDecodeCodecName() {
        try {
            for (IJKCode code : ApiConfig.get().getIjkCodes()) {
                LinkedHashMap<String, String> opt = code.getOption();
                if (opt != null && !"1".equals(opt.get("4|mediacodec"))) {
                    return code.getName();
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    // ================= 播放器候选项（手动切换用） =================
    //
    // 为什么不直接用 getExistPlayerTypes()：那份列表的顺序来自 HashMap（不确定），
    // 且把外部播放器（10~14）混在里面 —— 手动短按"下一个"会误唤起 MX/VLC/Kodi。
    // 这里把候选分成"内置含解码档"和"外部"两类，短按只在内置里循环，外部只出现在长按列表。
    //
    // 编码说明：2/100/101 是内置项（IJK 的硬解/软解拆成两项），10~14 是外部播放器（沿用原编号）。

    /** Exo（无需解码档） */
    public static final int BUILTIN_EXO = 2;
    /** IJK + 硬解码档 */
    public static final int BUILTIN_IJK_HARD = 100;
    /** IJK + 软解码档 */
    public static final int BUILTIN_IJK_SOFT = 101;

    /**
     * 候选项是不是"外部播放器"。
     *
     * 必须用这个判据，不能用 `item >= 10`：内置的 IJK 硬/软解编码是 100/101，也在 10 以上，
     * 粗判会把它们当成外部播放器去吊起（吊不起来、还静默丢掉了"切内核"这件事）。
     */
    public static boolean isExternalPlayer(int item) {
        return item >= 10 && item <= 14;
    }

    /**
     * 找"硬解码"分组名。判据是 IJK 选项 4|mediacodec == "1"，
     * 与 findSoftDecodeCodecName 互补，同样不依赖订阅给分组起的名字。
     */
    public static String findHardDecodeCodecName() {
        try {
            for (IJKCode code : ApiConfig.get().getIjkCodes()) {
                LinkedHashMap<String, String> opt = code.getOption();
                if (opt != null && "1".equals(opt.get("4|mediacodec"))) {
                    return code.getName();
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    /**
     * 内置候选（短按循环用）。顺序即短按顺序：Exo → IJK硬解 → IJK软解 → Exo。
     * 订阅没下发某一档时该项不出现，避免选中一个实际不存在的解码档。
     */
    public static ArrayList<Integer> getBuiltinItems() {
        ArrayList<Integer> items = new ArrayList<>();
        items.add(BUILTIN_EXO);
        if (findHardDecodeCodecName() != null) items.add(BUILTIN_IJK_HARD);
        if (findSoftDecodeCodecName() != null) items.add(BUILTIN_IJK_SOFT);
        return items;
    }

    /**
     * 「播放器」选择清单（播放页控制条与详情页共用同一份）：
     * 系统 + Exo + IJK硬解 + IJK软解 + 已安装的外部播放器。
     *
     * 为什么把内置内核也列进来：详情页没有控制条，选内核只能靠这份清单；
     * 播放页虽然能短按循环内置档，但循环只在内置里转、也看不出"当前是哪一个"，
     * 两处用同一份清单才能保证"两个页面选播放器的口径完全一致"。
     */
    public static ArrayList<Integer> getPlayerPickItems() {
        ArrayList<Integer> items = new ArrayList<>();
        items.add(0);
        items.add(BUILTIN_EXO);
        if (findHardDecodeCodecName() != null) items.add(BUILTIN_IJK_HARD);
        if (findSoftDecodeCodecName() != null) items.add(BUILTIN_IJK_SOFT);
        HashMap<Integer, Boolean> exist = getPlayersExistInfo();
        for (int ext = 10; ext <= 14; ext++) {
            if (Boolean.TRUE.equals(exist.get(ext))) items.add(ext);
        }
        return items;
    }

    /** 配置里标记"用户手动选过播放器"的键 */
    public static final String KEY_USER_PICKED = "plt";

    /**
     * 用户有没有手动选过播放器。没选过（false）时，播放器与解码档每次都跟设置页默认走。
     *
     * 判据必须是这个显式标记，不能用"配置里有没有 pl"：控制条上改比例/速度/起播跳转
     * 都会把整份配置（含 pl）写回历史，那不代表用户选过播放器。
     */
    public static boolean isUserPicked(JSONObject cfg) {
        return cfg != null && cfg.optInt(KEY_USER_PICKED, 0) == 1;
    }

    /** 标记"这次是用户手动选的播放器"，之后不再被设置页默认覆盖。自动兜底换内核不得调用。 */
    public static void markUserPicked(JSONObject cfg) {
        if (cfg == null) return;
        try {
            cfg.put(KEY_USER_PICKED, 1);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /**
     * 配置里标记"用户在播放器里手动调过画面缩放"的键。
     *
     * 与播放器标记（plt）同构：没有它时，"sc"每次起播都实时跟设置页的全局值走；
     * 有它则这个片+这条线路永远用存档值、设置页再改也不动。
     * 原实现只在"配置里没有 sc 键"时把全局值播一次种并立刻落盘，于是那个片此后
     * 永久跟死当时的全局值 —— 设置页怎么改都不生效（正是"默认缩放不生效"的成因）。
     */
    public static final String KEY_SCALE_PICKED = "sct";

    /** 用户在播放器里手动调过这部片（这条线路）的缩放没有。 */
    public static boolean isScalePicked(JSONObject cfg) {
        return cfg != null && cfg.optInt(KEY_SCALE_PICKED, 0) == 1;
    }

    /** 标记"用户手动调过这一片这一线路的缩放"，此后不再被设置页的全局缩放覆盖。 */
    public static void markScalePicked(JSONObject cfg) {
        if (cfg == null) return;
        try {
            cfg.put(KEY_SCALE_PICKED, 1);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /**
     * 缺省播放器：订阅给该站点指定了就用站点的，否则用设置页的全局默认。
     * 只回答"没有用户选择时该用谁"，与 applyPlayerItem 无关。
     */
    public static int defaultPlayerType(SourceBean sourceBean) {
        try {
            if (sourceBean != null && sourceBean.getPlayerType() != -1) return sourceBean.getPlayerType();
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return Hawk.get(HawkConfig.PLAY_TYPE, BUILTIN_EXO);
    }

    public static String getPlayerItemName(int item) {
        switch (item) {
            case BUILTIN_IJK_HARD:
                return "IJK播放器 (硬解码)";
            case BUILTIN_IJK_SOFT:
                return "IJK播放器 (软解码)";
            case BUILTIN_EXO:
                return "Exo播放器";
            default:
                return getPlayerName(item);
        }
    }

    /**
     * 当前配置对应哪个候选项。IJK 的硬/软按 4|mediacodec 判定归属 ——
     * 只有当配置里的档名正好等于软解档名时才算软解，其余 IJK 情形记作硬解档，
     * 这样订阅把档名叫成什么都不影响判定；外部播放器原样返回自己的编号。
     */
    public static int currentPlayerItem(JSONObject cfg) {
        if (cfg == null) return BUILTIN_EXO;
        int pl = cfg.optInt("pl", BUILTIN_EXO);
        if (pl == 1) {
            String soft = findSoftDecodeCodecName();
            return (soft != null && soft.equals(cfg.optString("ijk", ""))) ? BUILTIN_IJK_SOFT : BUILTIN_IJK_HARD;
        }
        return pl;
    }

    /** 把候选项写进播放器配置（内置项会同时决定解码档）。写成功返回 true。 */
    public static boolean applyPlayerItem(JSONObject cfg, int item) {
        if (cfg == null) return false;
        try {
            switch (item) {
                case BUILTIN_IJK_HARD: {
                    String hard = findHardDecodeCodecName();
                    if (hard == null) return false;
                    cfg.put("pl", 1);
                    cfg.put("ijk", hard);
                    return true;
                }
                case BUILTIN_IJK_SOFT: {
                    String soft = findSoftDecodeCodecName();
                    if (soft == null) return false;
                    cfg.put("pl", 1);
                    cfg.put("ijk", soft);
                    return true;
                }
                case BUILTIN_EXO: {
                    cfg.put("pl", 2);
                    return true;
                }
                default: {
                    cfg.put("pl", item); // 外部播放器：编号即类型
                    return true;
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
            return false;
        }
    }

    /**
     * 手动「播放器」短按的下一档：只在内置候选里循环（不含外部播放器）。
     * 当前是外部播放器时回到 Exo —— 手上有列表可选，短按只做"快速回内置"。
     */
    public static int nextBuiltinItem(JSONObject cfg) {
        ArrayList<Integer> items = getBuiltinItems();
        int idx = items.indexOf(currentPlayerItem(cfg));
        if (idx < 0) return items.get(0);
        return items.get((idx + 1) % items.size());
    }

    public static String getScaleName(int screenScaleType) {
        String scaleText = "默认";
        switch (screenScaleType) {
            case VideoView.SCREEN_SCALE_DEFAULT:
                scaleText = "默认";
                break;
            case VideoView.SCREEN_SCALE_16_9:
                scaleText = "16:9";
                break;
            case VideoView.SCREEN_SCALE_4_3:
                scaleText = "4:3";
                break;
            case VideoView.SCREEN_SCALE_MATCH_PARENT:
                scaleText = "填充";
                break;
            case VideoView.SCREEN_SCALE_ORIGINAL:
                scaleText = "原始";
                break;
            case VideoView.SCREEN_SCALE_CENTER_CROP:
                scaleText = "裁剪";
                break;
        }
        return scaleText;
    }

    public static String getDisplaySpeed(long speed) {
        if(speed > 1048576)
            return new DecimalFormat("#.00").format(speed / 1048576d) + "Mb/s";
        else if(speed > 1024)
            return (speed / 1024) + "Kb/s";
        else
            return speed > 0?speed + "B/s":"";
    }
}
