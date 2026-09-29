package com.github.tvbox.osc.server;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.OkGoHelper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import fi.iki.elonen.NanoHTTPD;

/**
 * @author pj567
 * @date :2021/1/5
 * @description:
 */
public class RemoteServer extends NanoHTTPD {
    private Context mContext;
    public static int serverPort = 9978;
    private boolean isStarted = false;
    private DataReceiver mDataReceiver;
    private ArrayList < RequestProcess > getRequestList = new ArrayList < > ();
    private ArrayList < RequestProcess > postRequestList = new ArrayList < > ();

    /**
     * 净化/去 BOM 后的清单内容。写入方是净化回调线程（OkGo），读取方是 NanoHTTPD 的工作线程，
     * 两边没有任何同步 —— 不加 volatile 时读线程可能长期读到 null（表现为"本地 HLS 端点拿不到内容"）。
     */
    public static volatile String m3u8Content;

    // ================= 局域网访问门禁 =================

    /**
     * 判断请求是否来自本机（App 自身、或本机浏览器开 127.0.0.1）。
     *
     * NanoHTTPD 解析请求时会把对端地址写入 remote-addr / http-client-ip 头，这里用它判断，
     * 避免依赖不同版本的方法签名差异。取不到来源时按"本机"处理——宁可维持改造前的
     * 可用性，也不让播放代理 / 本地订阅 / DNS 这些内部链路因为判定失败而不可用。
     */
    private boolean isLocalRequest(NanoHTTPD.IHTTPSession session) {
        try {
            Map<String, String> h = session.getHeaders();
            String ip = h == null ? null : h.get("remote-addr");
            if (ip == null || ip.isEmpty()) ip = h == null ? null : h.get("http-client-ip");
            if (ip == null || ip.isEmpty()) return true;
            String v = ip.trim();
            int pct = v.indexOf('%');               // IPv6 link-local：fe80::1%wlan0
            if (pct > 0) v = v.substring(0, pct);
            return "127.0.0.1".equals(v) || "::1".equals(v)
                    || "0:0:0:0:0:0:0:1".equals(v) || "localhost".equalsIgnoreCase(v);
        } catch (Throwable th) {
            return true;
        }
    }

    /** 是否允许非本机的"文件管理"操作（上传 / 新建 / 删除 / 目录浏览），默认关闭 */
    private boolean lanManageAllowed() {
        try {
            return Hawk.get(HawkConfig.ALLOW_LAN_FILE_MANAGE, false);
        } catch (Throwable th) {
            return false;
        }
    }

    private static final String DENY_MANAGE =
            "已拦截：局域网文件管理默认关闭（防止同网段设备删改本机文件）。\n"
            + "如确需在电脑上管理本机文件，请在手机 L1Box 的「设置」页打开「允许局域网管理文件」。";

    private Response denied(String reason) {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN,
                "text/plain; charset=utf-8", reason);
    }

    public RemoteServer(int port, Context context) {
        super(port);
        mContext = context;
        addGetRequestProcess();
        addPostRequestProcess();
    }

    private void addGetRequestProcess() {
        getRequestList.add(new RawRequestProcess(this.mContext, "/", R.raw.index, NanoHTTPD.MIME_HTML));
        getRequestList.add(new RawRequestProcess(this.mContext, "/index.html", R.raw.index, NanoHTTPD.MIME_HTML));
        getRequestList.add(new RawRequestProcess(this.mContext, "/style.css", R.raw.style, "text/css"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/ui.css", R.raw.ui, "text/css"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/jquery.js", R.raw.jquery, "application/x-javascript"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/script.js", R.raw.script, "application/x-javascript"));
        getRequestList.add(new RawRequestProcess(this.mContext, "/favicon.ico", R.drawable.app_icon, "image/x-icon"));
    }

    private void addPostRequestProcess() {
        postRequestList.add(new InputRequestProcess(this));
    }

    @Override
    public void start(int timeout, boolean daemon) throws IOException {
        isStarted = true;
        super.start(timeout, daemon);
        EventBus.getDefault().post(new ServerEvent(ServerEvent.SERVER_SUCCESS));
    }

    @Override
    public void stop() {
        super.stop();
        isStarted = false;
    }

    @Override
    public Response serve(IHTTPSession session) {
        EventBus.getDefault().post(new ServerEvent(ServerEvent.SERVER_CONNECTION));
        if (!session.getUri().isEmpty()) {
            String fileName = session.getUri().trim();
            if (fileName.indexOf('?') >= 0) {
                fileName = fileName.substring(0, fileName.indexOf('?'));
            }
            // 门禁①：内部专用端点（都是 App 自己用 127.0.0.1 访问的）不对外服务。
            // /localfile/ 只被 clan://local/ 映射使用，/dns-query 只被内置播放器 DoH 使用。
            boolean local = isLocalRequest(session);
            // /l1play 从这一行移出（bt，2026-09-24）：它多了一种合法调用方（投屏，见 servePlayForward），
            // 门禁改为在 servePlayForward 内按"有没有投屏码"分档。其余内部端点一律只允许本机。
            if (!local && (fileName.startsWith("/localfile/") || fileName.equals("/dns-query"))) {
                return denied("该接口仅供本机使用。");
            }
            // #8（09-23）：外部播放器的转发端点单独认路，**GET 与 HEAD 都收**。
            // 探测请求（HEAD）必须和取流（GET）走同一条路：否则播放器拿到的是默认首页而不是媒体头，
            // 它会据此认定"这个地址不是媒体"。
            if (fileName.equals("/l1play")) {
                if (session.getMethod() != Method.GET && session.getMethod() != Method.HEAD) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
                            NanoHTTPD.MIME_PLAINTEXT, "GET/HEAD only");
                }
                return servePlayForward(session, local);
            }
            if (session.getMethod() == Method.GET) {
                for (RequestProcess process: getRequestList) {
                    if (process.isRequest(session, fileName)) {
                        return process.doResponse(session, fileName, session.getParms(), null);
                    }
                }
                if (fileName.equals("/proxy")) {
                    Map < String, String > params = session.getParms();
                    params.putAll(session.getHeaders());
                    params.put("request-headers", new Gson().toJson(session.getHeaders()));
                    if (params.containsKey("do")) {
                        // 2026-09-28：原来这里直接把返回值的首项强转成 int —— proxyInvoke 返回 null
                        // （该 key 没有代理方法 / jar 侧抛异常）时抛 NPE，被外层兜成 500，
                        // 播放器只看到 Exo 2004「HTTP 状态不对」，日志里什么都没有。
                        // 现在：拿不到结果就明确回 503 + 一行日志，绝不 NPE。
                        Object[] rs = null;
                        try {
                            rs = ApiConfig.get().proxyLocal(params);
                        } catch (Throwable th) {
                            System.out.println("本地代理：/proxy 调用异常 do=" + params.get("do")
                                    + " 异常=" + th);
                        }
                        if (rs == null || rs.length < 3 || !(rs[0] instanceof Integer)) {
                            System.out.println("本地代理：/proxy 无可用响应 do=" + params.get("do")
                                    + " 结果=" + (rs == null ? "null" : "长度" + rs.length + " 首项"
                                    + (rs.length > 0 ? rs[0] : "空")));
                            // ce（2026-09-28）：jar 不接这一单时，按地址里写着的信息自己转发。
                            // 只在"拿不到可用响应"这条分支启用 —— jar 正常时一行都不碰。
                            Response self = proxySelfForward(session, params, local);
                            if (self != null) return self;
                            return NanoHTTPD.newFixedLengthResponse(
                                    NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                                    "text/plain; charset=utf-8",
                                    "proxy unavailable");
                        }
                        int code = (int) (Integer) rs[0];
                        String mime = (String) rs[1];
                        InputStream stream = rs[2] != null ? (InputStream) rs[2] : null;
                        Response response = NanoHTTPD.newChunkedResponse(
                                NanoHTTPD.Response.Status.lookup(code),
                                mime,
                                stream);
                        if (rs.length > 3) {
                            try {
                                HashMap < String, String > headers = (HashMap < String, String > ) rs[3];
                                for (String key: headers.keySet()) {
                                    response.addHeader(key, headers.get(key));
                                }
                            } catch (Throwable th) {
                                th.printStackTrace();
                            }
                        }
                        return response;
                    }
                } else if (fileName.startsWith("/file/")) {
                    try {
                        String f = fileName.substring(6);
                        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
                        String file = root + "/" + f;
                        File localFile = new File(file);
                        if (localFile.exists()) {
                            if (localFile.isFile()) {
                                // 单文件下载保持开放：clan:// 跨设备分享订阅、外部播放器拉流都依赖它，
                                // 且必须知道确切文件名才能访问。被收敛的是"目录浏览"（枚举入口）。
                                return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", new FileInputStream(localFile));
                            } else {
                                // 门禁②：目录浏览＝枚举本机全部文件，非本机默认不允许
                                if (!local && !lanManageAllowed()) {
                                    return denied(DENY_MANAGE);
                                }
                                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, fileList(root, f));
                            }
                        } else {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "File " + file + " not found!");
                        }
                    } catch (Throwable th) {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, th.getMessage());
                    }
                } else if (fileName.startsWith("/localfile/")) {
                    // 本地导入的订阅文件：存于应用私有目录 filesDir/local_subs，读取无需任何存储权限
                    try {
                        String f = fileName.substring(11);
                        if (f.contains("..") || f.contains("/") || f.contains("\\")) {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "Invalid path");
                        }
                        File localFile = new File(mContext.getFilesDir(), "local_subs/" + f);
                        if (localFile.isFile() && localFile.exists()) {
                            return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", new FileInputStream(localFile));
                        } else {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "File " + f + " not found!");
                        }
                    } catch (Throwable th) {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, th.getMessage());
                    }
                } else if (fileName.equals("/dns-query")) {
                    String name = session.getParms().get("name");
                    byte[] rs = null;
                    try {
                        rs = OkGoHelper.dnsOverHttps.lookupHttpsForwardSync(name);
                    } catch (Throwable th) {
                        // 2026-09-22：这里原先静默返回**空应答** —— 对客户端等于"这台 DNS 服务器不回话"，
                        // 而日志上完全看不出是 DoH 那一段挂了（DnsOverHttps 已把失败改成如实抛出，
                        // 就是为了不再有静默失败）。空应答行为保持不变，只补留痕。
                        // 该端点只在"安全DNS 开启"时被 IJK 用起来（见 ControlManager 的 setDotPort），
                        // 即"默认开启安全DNS"之后这条链路才第一次真正投入使用的，必须可观测。
                        Log.w("L1Dns", "本机 /dns-query 上游 DoH 失败，返回空应答：name=" + name
                                + " 原因=" + th.getClass().getSimpleName());
                        rs = new byte[0];
                    }
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/dns-message", new ByteArrayInputStream(rs), rs.length);
                } else if (fileName.equals("/m3u8")) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,  NanoHTTPD.MIME_PLAINTEXT, m3u8Content);
                } else if (fileName.equals("/l1.m3u8")) {
                    // 与 /m3u8 返回同一份净化后的清单，唯一区别是**地址带 .m3u8 后缀**：
                    // 播放器按地址扩展名推断容器类型，无后缀时会把 HLS 清单先当普通视频试一次、
                    // 失败才补救（观感＝「第一下播不出、切一下就好」）。带后缀能一次判对，
                    // 且不增加任何请求 —— 内容在净化那一步就已经下载好了。
                    // 内容为空时回空串而不是 null：NanoHTTPD 对 null 正文的处理不同版本不一致。
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                            "application/vnd.apple.mpegurl", m3u8Content == null ? "" : m3u8Content);
                } else if (fileName.equals("/l1home")) {
                    return serveHomeDouban();
                }
            } else if (session.getMethod() == Method.POST) {
                // 门禁③：写操作（上传 / 新建目录 / 删除）默认仅本机可用。
                // /action 不在其中——它是"推送到局域网其他 TVBox 设备"的既有功能，必须保持开放。
                if (!local && !lanManageAllowed()
                        && (fileName.equals("/upload") || fileName.equals("/newFolder")
                        || fileName.equals("/delFolder") || fileName.equals("/delFile"))) {
                    return denied(DENY_MANAGE);
                }
                Map < String, String > files = new HashMap < String, String > ();
                try {
                    if (session.getHeaders().containsKey("content-type")) {
                        String hd = session.getHeaders().get("content-type");
                        if (hd != null) {
                            // cuke: 修正中文乱码问题
                            if (hd.toLowerCase().contains("multipart/form-data") && !hd.toLowerCase().contains("charset=")) {
                                Matcher matcher = Pattern.compile("[ |\t]*(boundary[ |\t]*=[ |\t]*['|\"]?[^\"^'^;^,]*['|\"]?)", Pattern.CASE_INSENSITIVE).matcher(hd);
                                String boundary = matcher.find() ? matcher.group(1) : null;
                                if (boundary != null) {
                                    session.getHeaders().put("content-type", "multipart/form-data; charset=utf-8; " + boundary);
                                }
                            }
                        }
                    }
                    session.parseBody(files);
                } catch (IOException IOExc) {
                    return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + IOExc.getMessage());
                } catch (NanoHTTPD.ResponseException rex) {
                    return createPlainTextResponse(rex.getStatus(), rex.getMessage());
                }
                for (RequestProcess process: postRequestList) {
                    if (process.isRequest(session, fileName)) {
                        return process.doResponse(session, fileName, session.getParms(), files);
                    }
                }
                try {
                    Map < String, String > params = session.getParms();
                    if (fileName.equals("/upload")) {
                        String path = params.get("path");
                        for (String k: files.keySet()) {
                            if (k.startsWith("files-")) {
                                String fn = params.get(k);
                                String tmpFile = files.get(k);
                                File tmp = new File(tmpFile);
                                String root = Environment.getExternalStorageDirectory().getAbsolutePath();
                                File file = new File(root + "/" + path + "/" + fn);
                                if (file.exists()) file.delete();
                                if (tmp.exists()) {
                                    if (fn.toLowerCase().endsWith(".zip")) {
                                        unzip(tmp, root + "/" + path);
                                    } else {
                                        FileUtils.copyFile(tmp, file);
                                    }
                                }
                                if (tmp.exists()) tmp.delete();
                            }
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                    } else if (fileName.equals("/newFolder")) {
                        String path = params.get("path");
                        String name = params.get("name");
                        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
                        File file = new File(root + "/" + path + "/" + name);
                        if (!file.exists()) {
                            file.mkdirs();
                            File flag = new File(root + "/" + path + "/" + name + "/.tvbox_folder");
                            if (!flag.exists()) flag.createNewFile();
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                    } else if (fileName.equals("/delFolder")) {
                        String path = params.get("path");
                        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
                        File file = new File(root + "/" + path);
                        if (file.exists()) {
                            FileUtils.recursiveDelete(file);
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                    } else if (fileName.equals("/delFile")) {
                        String path = params.get("path");
                        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
                        File file = new File(root + "/" + path);
                        if (file.exists()) {
                            file.delete();
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                    }
                } catch (Throwable th) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "OK");
                }
            }
        }
        //default page: index.html
        return getRequestList.get(0).doResponse(session, "", null, null);
    }

    // ── 投屏转发登记（bt，2026-09-24）────────────────────────────────────────────
    /** 投屏码 → {上游地址, 请求头 JSON, 过期时刻}。只存内存，进程结束即失效。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, String[]> CAST_FORWARD =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 同时在册的投屏码上限：投屏是低频操作，几条足够；超了淘汰最旧的，防长期驻留 */
    private static final int CAST_FORWARD_MAX = 8;
    /** 投屏码有效期：一场电影足够长，过期后电视再请求只会拿到 403，须重新投屏 */
    private static final long CAST_FORWARD_TTL_MS = 12 * 60 * 60 * 1000L;

    /**
     * 登记一次投屏转发，返回**一次性随机码**（32 位十六进制，不可猜）。
     *
     * 为什么用码、而不是让电视直接带上地址与请求头：那样等于把上游地址和站点请求头**明文写进
     * 局域网的请求里**，同网段任何设备抓一次包就拿到了。用码之后，电视只知道"本机的某个地址"。
     * 这也是放开 /l1play 到局域网的安全前提 —— 有码才放行，码只能取登记过的那一条，
     * 且**没有码的请求依旧只允许本机**（门禁一行没松）。
     */
    public static String registerCastForward(String url, HashMap<String, String> headers) {
        try {
            if (url == null || url.isEmpty()) return null;
            while (CAST_FORWARD.size() >= CAST_FORWARD_MAX) {
                String oldest = null;
                long oldestAt = Long.MAX_VALUE;
                for (Map.Entry<String, String[]> e : CAST_FORWARD.entrySet()) {
                    long at = expireOf(e.getValue());
                    if (at < oldestAt) {
                        oldestAt = at;
                        oldest = e.getKey();
                    }
                }
                if (oldest == null) break;
                CAST_FORWARD.remove(oldest);
            }
            byte[] rnd = new byte[16];
            new java.security.SecureRandom().nextBytes(rnd);
            StringBuilder sb = new StringBuilder();
            // 必须 & 0xFF：byte 是带符号的，直接格式化负数会得到 8 位（ffffffxx）而不是 2 位
            for (byte b : rnd) sb.append(String.format("%02x", b & 0xFF));
            String token = sb.toString();
            String hJson = (headers == null || headers.isEmpty()) ? "" : new Gson().toJson(headers);
            CAST_FORWARD.put(token, new String[]{url, hJson,
                    String.valueOf(System.currentTimeMillis() + CAST_FORWARD_TTL_MS)});
            Log.i("L1Play", "投屏码已登记 " + token.substring(0, 6) + "… 地址=" + url);
            return token;
        } catch (Throwable th) {
            th.printStackTrace();
            return null;
        }
    }

    private static long expireOf(String[] reg) {
        try {
            return Long.parseLong(reg[2]);
        } catch (Throwable th) {
            return 0L;
        }
    }

    /** 按码取登记项；过期或不存在返回 null（调用方据此 403 让用户重新投屏） */
    private static String[] castForwardLookup(String token) {
        try {
            String[] reg = CAST_FORWARD.get(token);
            if (reg == null) return null;
            if (expireOf(reg) < System.currentTimeMillis()) {
                CAST_FORWARD.remove(token);
                return null;
            }
            return reg;
        } catch (Throwable th) {
            return null;
        }
    }

    /**
     * 局域网可达地址（投屏用）：遍历网卡找私有网段 IPv4。
     *
     * 不复用 getLocalIPAddress：那个优先读 WifiManager，只开热点、或走有线网时会给出 0.0.0.0
     * —— 而投屏恰恰常在这种环境。这里取不到就返回 null，调用方退回原行为：
     * 宁可投屏缺请求头，也不给电视一个必然连不上的地址。
     */
    public static String lanIp() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return null;
            while (en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress() || !(addr instanceof Inet4Address)) continue;
                    String host = addr.getHostAddress();
                    if (host.startsWith("192.168.") || host.startsWith("10.") || isPrivate172(host)) {
                        return host;
                    }
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    private static boolean isPrivate172(String host) {
        String[] p = host.split("\\.");
        if (p.length < 4) return false;
        try {
            int b = Integer.parseInt(p[1]);
            return b >= 16 && b <= 31;
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * #8（2026-09-23 定稿）：**外部播放器的本机转发端点**。
     *
     * 形如 `http://127.0.0.1:9978/l1play?u=<上游地址>&h=<请求头 JSON>`：由 App 带上站点要求的
     * 请求头去上游取流，再把结果原样转给外部播放器。这样播放器的**每一个**请求（首播 / 续传 /
     * 拖动）都必然带齐请求头 —— 而它自己那套"请求头拼在地址字符串后面"只对首个请求有效，
     * 这正是"外部播放器约 10 秒结束"的机理（首请求带头 → 起播并预读 8~12 秒；续传丢头 → 断流）。
     *
     * 三条不可省的转发纪律：**透传 Range**、**原样回传 Content-Range / Content-Length / Content-Type**、
     * **状态码照抄上游**（206 就是 206）。少任何一条，外部播放器都只能播、不能拖。
     *
     * 只允许本机访问（与 /localfile/、/dns-query 同一道门禁）；只被外部播放器使用，
     * 内置 Exo/IJK 路径不走这里。
     */
    private Response servePlayForward(NanoHTTPD.IHTTPSession session, boolean local) {
        // 两种调用方共用这一段转发：
        //  ① 本机外部播放器（MX/Reex/Kodi）：`?u=<地址>&h=<请求头>`，只允许本机（门禁与改动前一致）；
        //  ② 投屏（bt，2026-09-24）：DLNA 设备是**另一台机器**，它自己去上游取流时天生带不上请求头，
        //     所以地址必须用**局域网 IP**，凭一次性随机码 `?t=` 取流。电视只看到"本机地址＋码"，
        //     上游地址与站点请求头都不出现在局域网的明文里；没有码的请求依旧只允许本机。
        String token = session.getParms().get("t");
        String u;
        if (token != null && !token.isEmpty()) {
            String[] reg = castForwardLookup(token);
            if (reg == null) {
                return denied("投屏链接已失效，请重新投屏。");
            }
            // bv（2026-09-24）：清单里的分片会被重写成"带**同一个码** + 自己的 u"（见 rewriteM3u8），
            // 所以请求自带 u 时以它为准；不带 u 的（电视第一次拉清单）才用登记的那条地址。
            // 放行条件一字未改：仍然必须持有有效码（12h 过期、最多在册 8 条都不动）。
            String reqU = session.getParms().get("u");
            u = (reqU != null && !reqU.isEmpty()) ? reqU : reg[0];
            // 登记里的请求头优先：投屏方根本不带 h，读 session 只会是空
            session.getParms().put("h", reg[1]);
        } else {
            if (!local) {
                return denied("该接口仅供本机使用。");
            }
            u = session.getParms().get("u");
        }
        // 门禁到此为止；转发本体抽到 serveForward（/proxy 的自转发兜底要复用同一份行为）
        return serveForward(session, u, session.getParms().get("h"), token);
    }

    /**
     * 转发本体（ce 2026-09-28 抽出）：按 `h` 的请求头把 `u` 取回来交给调用方。
     *
     * 为什么要抽出来给 `/proxy` 用：站点下发的本地代理地址（`/proxy?do=…&url=…&ck=…`）在
     * jar 的 Proxy 返回 null 时整条链路失败（实测 cd 轮 100%），而那条 URL 里**已经写着**上游地址
     * 与凭据 —— 自转发要的正是这一份**已有的**转发行为（Range 透传、Accept-Encoding: identity、
     * m3u8 分片重写、HEAD 探测），复制一份必然走样。
     *
     * 门禁不在这里做：调用方各自负责（`/l1play` 见 servePlayForward，`/proxy` 见 proxySelfForward）。
     */
    private Response serveForward(NanoHTTPD.IHTTPSession session, String u, String h, String token) {
        if (u == null || u.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST,
                    NanoHTTPD.MIME_PLAINTEXT, "missing u");
        }
        // 探测请求（HEAD）与取流（GET）都走**同一条上游 GET**：CDN 上 HEAD 常被 403，
        // 而"探测能否成功"必须与"真正播放能否成功"一致，所以上游一律用 GET，
        // 只在**回给播放器**这一层把正文去掉（见下面的 head 分支）。
        final boolean head = session.getMethod() == Method.HEAD;
        // 读超时绝不能是默认的 10 秒：转发的是视频流，见 OkGoHelper.getForwardClient 的说明
        okhttp3.OkHttpClient client = OkGoHelper.getForwardClient();
        if (client == null) {
            client = OkGoHelper.getDefaultClient();
        }
        if (client == null) {
            client = new okhttp3.OkHttpClient.Builder().build();
        }
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(u).get();
        // 请求头由本条链路注入：上游只认站点那一套（UA/Referer…），播放器自己带什么与我们无关
        for (Map.Entry<String, String> e : parseForwardHeaders(h).entrySet()) {
            try {
                rb.header(e.getKey(), e.getValue());
            } catch (Throwable ignored) {
                // 单条非法头不能让整次转发失败
            }
        }
        // 显式声明不接受压缩：视频流本就不压缩，同时让 OkHttp **关掉透明 gzip** ——
        // 否则长度/范围会按解压后的字节重算，Range 的字节账就对不上了。
        rb.header("Accept-Encoding", "identity");
        String range = session.getHeaders().get("range");
        if (range != null && !range.isEmpty()) {
            rb.header("Range", range); // 透传 Range（同名字段 header() 是替换，不会与站点头打架）
        }
        okhttp3.Response up = null;
        try {
            up = client.newCall(rb.build()).execute();
            final int code = up.code();
            String mime = up.header("Content-Type");
            if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
            long len = -1;
            String lenStr = up.header("Content-Length");
            if (lenStr != null) {
                try {
                    len = Long.parseLong(lenStr.trim());
                } catch (Throwable ignored) {
                }
            }
            String cr = up.header("Content-Range");
            String ar = up.header("Accept-Ranges");
            if (head) {
                // HEAD 只回头。**必须自己把正文掐掉**：NanoHTTPD 对 HEAD 仍会照发正文，
                // 一次探测就会把整个视频从头往下灌。这里给空正文 + 显式 Content-Length，
                // 播放器据此知道媒体长度与是否可拖（NanoHTTPD 不会重复写 Content-Length）。
                if (up.body() != null) up.body().close();
                // 正文用空流（totalBytes=0），长度靠下面显式写的 Content-Length：
                // NanoHTTPD 的 sendContentLengthHeaderIfNotAlreadyPresent 会读走这个头，
                // 于是头部报出真实长度、正文一个字节不发 —— 这正是 HEAD 该有的样子。
                Response probe = NanoHTTPD.newFixedLengthResponse(statusOf(code), mime,
                        new ByteArrayInputStream(new byte[0]), 0);
                if (len >= 0) probe.addHeader("Content-Length", String.valueOf(len));
                if (cr != null && !cr.isEmpty()) probe.addHeader("Content-Range", cr);
                // bv（2026-09-24）：上游**没给**就不回。原来 ar 为空时硬写 "bytes" ⇒ 对播放器
                // 谎报"这个地址支持拖动"，而实测上游对 Range 请求回 200 + 全量、既无 Content-Range
                // 也无 Accept-Ranges ⇒ 它本来就不支持。谎报只会让播放器按"可拖"去算位置。
                if (ar != null && !ar.isEmpty()) probe.addHeader("Accept-Ranges", ar);
                return probe;
            }
            InputStream body = up.body() != null ? up.body().byteStream() : null;
            if (body == null) {
                up.close();
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                        NanoHTTPD.MIME_PLAINTEXT, "empty upstream body");
            }
            // ── bv（2026-09-24）：m3u8 清单里的**相对分片**就地重写成"经本端点转发"的绝对地址。
            // 真机实测（D1-MX 回归）：播放器/电视拿到清单后，用**清单的 URL**当 base 去补全相对分片，
            // 而清单 URL 就是本机/局域网转发端点 ⇒ 补出来是 `http://127.0.0.1:9978/<分片名>` ⇒
            // 本机没有这个路径 ⇒ NanoHTTPD 回 HTML 首页 ⇒ FFmpeg 在 open 阶段读到 HTML ⇒
            // `AVERROR_INVALIDDATA`（status=-1094995529）⇒ 弹「无法播放」，197ms~1.8s 就死。
            // 重写之后每个分片都是**绝对地址且仍走转发**（请求头不丢）—— 既不猜 base，也不丢防盗链头。
            // 只在 `200 ∧ 无 Content-Range ∧ 长度已知且不超上限` 时做：206 带 Range 语义，改了字节账对不上。
            if (code == 200 && cr == null && len >= 0 && len <= M3U8_MAX_BYTES && isM3u8(mime, u)) {
                byte[] raw = readAll(body, (int) len);
                up.close();
                if (raw == null) {
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                            NanoHTTPD.MIME_PLAINTEXT, "read m3u8 failed");
                }
                byte[] out = rewriteM3u8(raw, u, session, token);
                if (out == null) out = raw;   // 不含相对地址 / 非清单 / 出意外 ⇒ 一字不改
                return NanoHTTPD.newFixedLengthResponse(statusOf(code), mime,
                        new ByteArrayInputStream(out), out.length);
            }
            // 有长度就走定长（NanoHTTPD 会自己写 Content-Length，播放器才能算时长/拖动位置），
            // 没长度（分块上游）才退回 chunked
            Response resp = len >= 0
                    ? NanoHTTPD.newFixedLengthResponse(statusOf(code), mime, body, len)
                    : NanoHTTPD.newChunkedResponse(statusOf(code), mime, body);
            if (cr != null && !cr.isEmpty()) resp.addHeader("Content-Range", cr);
            // 与 HEAD 同一口径（bv）：上游没给 Accept-Ranges 就不回，不谎报可拖动
            if (ar != null && !ar.isEmpty()) resp.addHeader("Accept-Ranges", ar);
            return resp;
        } catch (Throwable th) {
            // 上游失败要如实回一个错误码，而不是空的 200：播放器据此才知道该重试
            if (up != null) {
                try {
                    up.close();
                } catch (Throwable ignored) {
                }
            }
            Log.w("L1Play", "转发失败 url=" + u + " 原因=" + th.getClass().getSimpleName() + ":" + th.getMessage());
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT, "forward failed");
        }
    }

    /** m3u8 清单的重写上限（实测清单 197KB；给足余量，超了就退回原样透传，绝不赌） */
    private static final int M3U8_MAX_BYTES = 8 * 1024 * 1024;

    /** 像不像 m3u8：先看 Content-Type，再看地址后缀（都按小写比） */
    private static boolean isM3u8(String mime, String url) {
        if (mime != null && mime.toLowerCase().contains("mpegurl")) return true;
        if (url != null) {
            String path = url;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (path.toLowerCase().endsWith(".m3u8")) return true;
        }
        return false;
    }

    /** 定长读满（清单必须完整才能重写）；读不满或出错返回 null ⇒ 调用方如实报错 */
    private static byte[] readAll(InputStream in, int len) {
        try {
            byte[] buf = new byte[len];
            int off = 0;
            while (off < len) {
                int r = in.read(buf, off, len - off);
                if (r <= 0) break;
                off += r;
            }
            if (off == len) return buf;
            byte[] out = new byte[off];
            System.arraycopy(buf, 0, out, 0, off);
            return out;
        } catch (Throwable th) {
            return null;
        }
    }

    /**
     * **把 m3u8 清单里的相对地址重写成"经本端点转发"的绝对地址**（bv，2026-09-24）。
     *
     * 为什么不是"把 base 换回上游"：那样分片就由播放器直连上游，站点没下发的 Referer 又丢了 ——
     * 回到 403 那条老路（D1 最初的病根）。所以分片**必须继续走转发**，但地址得是绝对的。
     *
     * 端点前缀取**请求的 Host 头**：本机自动是 `127.0.0.1:9978`、电视自动是 `192.168.x.x:9978`，
     * 不需要这里判断"我是谁"。投屏那条复用**同一个一次性码**（不带 h，请求头从登记里取）。
     *
     * 返回 null 表示"一字未改"（不是清单 / 没有相对地址 / 出意外），调用方照原样回 —— 退化，不是报错。
     */
    private byte[] rewriteM3u8(byte[] raw, String baseUrl, NanoHTTPD.IHTTPSession session, String token) {
        try {
            String text = new String(raw, "UTF-8");
            if (!text.startsWith("#EXTM3U")) return null;
            String host = session.getHeaders().get("host");
            if (host == null || host.isEmpty()) host = "127.0.0.1:" + serverPort;
            String hParam = session.getParms().get("h");
            String prefix = "http://" + host + "/l1play?";
            // 本机（无码）要把请求头一并带上；投屏（有码）由登记提供，不带 h
            String auth = (token != null && !token.isEmpty()) ? ("t=" + token + "&u=") : "u=";
            boolean cast = (token != null && !token.isEmpty());
            String[] lines = text.split("\n", -1);
            StringBuilder out = new StringBuilder(text.length() + 1024);
            int changed = 0;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                String trimmed = line.trim();
                String replaced = null;
                if (!trimmed.isEmpty()) {
                    if (trimmed.charAt(0) == '#') {
                        // EXT-X-KEY / MAP / MEDIA / I-FRAME-STREAM-INF 的 URI="…"
                        replaced = rewriteAttrUri(line, baseUrl, prefix, auth, hParam, cast);
                    } else {
                        String abs = resolveUrl(baseUrl, trimmed);
                        if (abs != null) replaced = wrapForward(abs, prefix, auth, hParam, cast);
                    }
                }
                if (replaced != null) {
                    out.append(replaced);
                    changed++;
                } else {
                    out.append(line);
                }
                if (i < lines.length - 1) out.append("\n");
            }
            if (changed <= 0) return null;
            Log.i("L1Play", "m3u8 分片已重写 n=" + changed + (cast ? " (投屏)" : ""));
            return out.toString().getBytes("UTF-8");
        } catch (Throwable th) {
            Log.w("L1Play", "m3u8 重写失败（按原样转发）：" + th.getClass().getSimpleName() + ":" + th.getMessage());
            return null;
        }
    }

    /** 处理 `URI="…"` 属性（只重写值，其余原样保留；没有就返回 null） */
    private String rewriteAttrUri(String line, String baseUrl, String prefix, String auth, String hParam, boolean cast) {
        int i = line.indexOf("URI=\"");
        if (i < 0) return null;
        int s = i + 5;
        int e = line.indexOf('"', s);
        if (e < 0) return null;
        String abs = resolveUrl(baseUrl, line.substring(s, e));
        if (abs == null) return null;
        String wrapped = wrapForward(abs, prefix, auth, hParam, cast);
        if (wrapped == null) return null;
        return line.substring(0, s) + wrapped + line.substring(e);
    }

    /** 把绝对地址包成本端点的转发地址（本机带 h；投屏只带码） */
    private static String wrapForward(String abs, String prefix, String auth, String hParam, boolean cast) {
        try {
            StringBuilder sb = new StringBuilder(abs.length() + 96);
            sb.append(prefix).append(auth).append(java.net.URLEncoder.encode(abs, "UTF-8"));
            if (!cast && hParam != null && !hParam.isEmpty()) {
                sb.append("&h=").append(java.net.URLEncoder.encode(hParam, "UTF-8"));
            }
            return sb.toString();
        } catch (Throwable th) {
            return null;
        }
    }

    /**
     * 相对地址 → 绝对地址（**纯字符串实现，不用 URI.resolve**）。
     *
     * 为什么不用 `java.net.URI`：清单里的地址常含 `|`、空格、中文（第三方 CDN 的 pkey），
     * `new URI(...)` 会直接抛 URISyntaxException ⇒ 整条重写废掉。字符串拼不会。
     * 已指向本机（127.0.0.1 / /l1play）的一律返回 null（原样保留），防自嵌套。
     */
    private static String resolveUrl(String base, String ref) {
        if (ref == null || ref.isEmpty()) return null;
        String low = ref.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://")) {
            if (low.contains("127.0.0.1") || low.contains("/l1play")) return null;
            return ref;
        }
        try {
            String b = base;
            int q = b.indexOf('?');
            if (q >= 0) b = b.substring(0, q);
            int sp = b.indexOf("://");
            if (sp < 0) return null;
            String scheme = b.substring(0, sp);
            int slash = b.indexOf('/', sp + 3);
            String authority = (slash < 0) ? b.substring(sp + 3) : b.substring(sp + 3, slash);
            if (ref.startsWith("//")) return scheme + ":" + ref;
            if (ref.startsWith("/")) return scheme + "://" + authority + ref;
            String dir = (slash < 0) ? (b + "/") : b.substring(0, b.lastIndexOf('/') + 1);
            return dir + ref;
        } catch (Throwable th) {
            return null;
        }
    }

    /**
     * 解析 /l1play 的 h 参数（请求头 JSON）。解析不出就当"没有请求头"处理：
     * 宁可少带头去试，也不要因为一个坏参数把整次播放判死。
     */
    /**
     * `/proxy` 自转发兜底（ce 2026-09-28）。
     *
     * 背景（cd 轮真机实测）：站点下发 `http://127.0.0.1:9978/proxy?do=<站点key>&type=m3u8&url=<b64>&ck=<b64>&sign=…`，
     * 而 jar 的 `Proxy` 对某些 key **直接返回 null**（同一个 jar 对 `do=ck` 能正常返回 200 ⇒ jar 本身是好的，
     * 只是内部派发表没有这个 key）⇒ 我们只能回 503 ⇒ 播放必然失败。
     *
     * 但那条 URL 是**自描述**的：
     *   · `url`  = base64(http://上游/vod/m3u8/…/index.m3u8)
     *   · `ck`   = base64(k=…&Time=<unix秒>&uid=…)   ← 以 `&` 分隔 ⇒ 更像**查询串**而不是 Cookie 串
     * 所以这里按参数把流自己取回来，交给已有的转发链（m3u8 分片也会被重写成继续走转发）。
     *
     * 三条纪律：
     *  ① **只在 jar 返回 null 时**调用 —— jar 正常时不碰它一行（不覆盖既有行为）；
     *  ② **门禁与 `/l1play` 完全一致**（只允许本机；或持有有效投屏码的局域网设备）——
     *     否则这就成了局域网里人人可用的开放代理；
     *  ③ 先按"查询串"试，非 2xx 再按"Cookie 头"试一次（两种约定都存在，本轮先都试，日志记两份码）。
     */
    private Response proxySelfForward(NanoHTTPD.IHTTPSession session, Map<String, String> params, boolean local) {
        try {
            String castTok = session.getParms().get("t");
            boolean castOk = castTok != null && !castTok.isEmpty() && castForwardLookup(castTok) != null;
            if (!local && !castOk) {
                System.out.println("本地代理：自转发被拒（非本机且无有效投屏码）do=" + params.get("do"));
                return null;
            }
            String u = b64OrPlain(params.get("url"));
            if (u == null || !u.startsWith("http")) {
                System.out.println("本地代理：自转发跳过（url 参数不是可用的 http 地址）do=" + params.get("do"));
                return null;
            }
            String ck = b64OrPlain(params.get("ck"));
            String tk = castOk ? castTok : null;
            // 变体①：ck 作为查询参数附在地址后（`&` 分隔 ⇒ 查询串的写法）
            Response r1 = serveForward(session, appendQuery(u, ck), null, tk);
            int c1 = respCode(r1);
            if (c1 >= 200 && c1 < 300) {
                System.out.println("本地代理：自转发 url=" + briefUrl(u) + " 变体1(查询串)=" + c1);
                return r1;
            }
            if (ck == null || ck.isEmpty()) {
                System.out.println("本地代理：自转发 url=" + briefUrl(u) + " 变体1(查询串)=" + c1 + "（无 ck，不再试变体2）");
                return r1;
            }
            // 变体②：ck 作为 Cookie 头再试一次（另一种常见约定）
            drainQuietly(r1);
            Response r2 = serveForward(session, u, "Cookie: " + ck, tk);
            System.out.println("本地代理：自转发 url=" + briefUrl(u) + " 变体1(查询串)=" + c1
                    + " 变体2(Cookie)=" + respCode(r2));
            return r2;
        } catch (Throwable th) {
            System.out.println("本地代理：自转发异常 do=" + params.get("do") + " " + th.getClass().getSimpleName() + ":" + th.getMessage());
            return null;
        }
    }

    /** base64（标准或 URL 安全）解码；解不出就原样返回（有些站点给的 url 本来就是明文） */
    private static String b64OrPlain(String v) {
        if (v == null || v.isEmpty()) return null;
        if (v.startsWith("http")) return v;
        for (int flags : new int[]{Base64.URL_SAFE | Base64.NO_WRAP, Base64.DEFAULT, Base64.NO_WRAP}) {
            try {
                String d = new String(Base64.decode(v, flags), "UTF-8").trim();
                if (d.startsWith("http")) return d;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 把 ck 作为查询串附到地址后（已在 query 里就用 & 接） */
    private static String appendQuery(String u, String ck) {
        if (ck == null || ck.isEmpty()) return u;
        return u + (u.contains("?") ? "&" : "?") + ck;
    }

    private static int respCode(Response r) {
        try {
            return (r == null || r.getStatus() == null) ? -1 : r.getStatus().getRequestStatus();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 丢掉不用的响应体（不然 okhttp 的连接不会归还） */
    private static void drainQuietly(Response r) {
        try {
            InputStream s = (r == null) ? null : r.getData();
            if (s != null) s.close();
        } catch (Throwable ignored) {
        }
    }

    /** 日志里只留主机+路径（令牌参数不进日志） */
    private static String briefUrl(String u) {
        try {
            int q = u.indexOf('?');
            String s = (q > 0) ? u.substring(0, q) : u;
            return s.length() <= 90 ? s : s.substring(0, 90);
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private HashMap<String, String> parseForwardHeaders(String h) {
        HashMap<String, String> headers = new HashMap<>();
        if (h == null || h.isEmpty()) return headers;
        try {
            JsonObject obj = new Gson().fromJson(h, JsonObject.class);
            if (obj != null) {
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                    String k = e.getKey();
                    if (k == null || k.trim().isEmpty()) continue;
                    // 这几条是连接/报文层的东西，交给 OkHttp 自己算；照抄过来只会互相打架
                    String lk = k.trim().toLowerCase();
                    if (lk.equals("host") || lk.equals("connection") || lk.equals("transfer-encoding")
                            || lk.equals("content-length")) {
                        continue;
                    }
                    if (e.getValue() == null || e.getValue().isJsonNull()) continue;
                    String v = e.getValue().getAsString();
                    if (v == null) continue;
                    headers.put(k.trim(), v);
                }
            }
        } catch (Throwable th) {
            Log.w("L1Play", "请求头解析失败：" + th.getMessage());
        }
        return headers;
    }

    /**
     * 状态码转换：NanoHTTPD 的 Status.lookup 对**表外的码返回 null**（例如 206 之外的
     * 各种 2xx/4xx），直接塞进响应会得到一个坏响应。表外的码自己包一个 IStatus，原样透传。
     */
    private static NanoHTTPD.Response.IStatus statusOf(final int code) {
        NanoHTTPD.Response.IStatus s = NanoHTTPD.Response.Status.lookup(code);
        if (s != null) return s;
        return new NanoHTTPD.Response.IStatus() {
            @Override
            public int getRequestStatus() {
                return code;
            }

            @Override
            public String getDescription() {
                return "" + code;
            }
        };
    }

    /**
     * 首页豆瓣数据路由：在线时拉取并缓存到本地文件，离线时回放缓存；
     * 首次无缓存且离线时回退到内置快照 assets/home_douban_data.txt。
     * 这样首页"永久离线可用"且在线时会自动更新（缓存刷新）。
     */
    private Response serveHomeDouban() {
        File cache = new File(App.getInstance().getFilesDir(), "home_douban_cache.txt");
        // 1) 在线：拉取最新数据并写缓存
        String live = fetchWithTimeout(ApiConfig.get().getHomeDoubanExtUrl());
        if (live != null && !live.isEmpty()) {
            writeFile(cache, live);
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", live);
        }
        // 2) 离线：优先用上次缓存
        String cached = readFile(cache);
        if (cached != null && !cached.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", cached);
        }
        // 3) 首次离线：回退到内置快照
        try {
            InputStream is = App.getInstance().getAssets().open("home_douban_data.txt");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            is.close();
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", new String(baos.toByteArray(), "UTF-8"));
        } catch (Throwable th) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", "");
        }
    }

    /** 带超时的同步 GET，失败/超时返回 null（调用方走缓存兜底） */
    private String fetchWithTimeout(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                br.close();
                return sb.toString();
            }
        } catch (Throwable th) {
            // 离线/超时/解析异常：返回 null，由调用方回放缓存
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private String readFile(File f) {
        if (f == null || !f.exists()) return null;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            br.close();
            return sb.toString();
        } catch (Throwable th) {
            return null;
        }
    }

    private void writeFile(File f, String content) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            fos.write(content.getBytes("UTF-8"));
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public void setDataReceiver(DataReceiver receiver) {
        mDataReceiver = receiver;
    }

    public DataReceiver getDataReceiver() {
        return mDataReceiver;
    }

    public boolean isStarting() {
        return isStarted;
    }

    public String getServerAddress() {
        String ipAddress = getLocalIPAddress(mContext);
        return "http://" + ipAddress + ":" + RemoteServer.serverPort + "/";
    }

    public String getLoadAddress() {
        return "http://127.0.0.1:" + RemoteServer.serverPort + "/";
    }

    public static Response createPlainTextResponse(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, text);
    }

    public static Response createJSONResponse(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, "application/json", text);
    }

    @SuppressLint("DefaultLocale")
    public static String getLocalIPAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        if (ipAddress == 0) {
            try {
                Enumeration < NetworkInterface > enumerationNi = NetworkInterface.getNetworkInterfaces();
                while (enumerationNi.hasMoreElements()) {
                    NetworkInterface networkInterface = enumerationNi.nextElement();
                    String interfaceName = networkInterface.getDisplayName();
                    if (interfaceName.equals("eth0") || interfaceName.equals("wlan0")) {
                        Enumeration < InetAddress > enumIpAddr = networkInterface.getInetAddresses();
                        while (enumIpAddr.hasMoreElements()) {
                            InetAddress inetAddress = enumIpAddr.nextElement();
                            if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                                return inetAddress.getHostAddress();
                            }
                        }
                    }
                }
            } catch (SocketException e) {
                e.printStackTrace();
            }
        } else {
            return String.format("%d.%d.%d.%d", (ipAddress & 0xff), (ipAddress >> 8 & 0xff), (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));
        }
        return "0.0.0.0";
    }

    String fileTime(long time, String fmt) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        Date date = calendar.getTime();
        SimpleDateFormat sdf = new SimpleDateFormat(fmt);
        return sdf.format(date);
    }

    String fileList(String root, String path) {
        File file = new File(root + "/" + path);
        File[] list = file.listFiles();
        JsonObject info = new JsonObject();
        info.addProperty("remote", getServerAddress().replace("http://", "clan://"));
        info.addProperty("del", 0);
        if (path.isEmpty()) {
            info.addProperty("parent", ".");
        } else {
            info.addProperty("parent", file.getParentFile().getAbsolutePath().replace(root + "/", "").replace(root, ""));
        }
        if (list == null || list.length == 0) {
            info.add("files", new JsonArray());
            return info.toString();
        }
        Arrays.sort(list, new Comparator < File > () {@Override
        public int compare(File o1, File o2) {
            if (o1.isDirectory() && o2.isFile()) return -1;
            return o1.isFile() && o2.isDirectory() ? 1 : o1.getName().compareTo(o2.getName());
        }
        });
        JsonArray result = new JsonArray();
        for (File f: list) {
            if (f.getName().startsWith(".")) {
                if (f.getName().equals(".tvbox_folder")) {
                    info.addProperty("del", 1);
                }
                continue;
            }
            JsonObject fileObj = new JsonObject();
            fileObj.addProperty("name", f.getName());
            fileObj.addProperty("path", f.getAbsolutePath().replace(root + "/", ""));
            fileObj.addProperty("time", fileTime(f.lastModified(), "yyyy/MM/dd aHH:mm:ss"));
            fileObj.addProperty("dir", f.isDirectory() ? 1 : 0);
            result.add(fileObj);
        }
        info.add("files", result);
        return info.toString();
    }

    void unzip(File zipFilePath, String destDirectory) throws Throwable {
        File destDir = new File(destDirectory);
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        ZipFile zip = new ZipFile(zipFilePath);
        Enumeration < ZipEntry > iter = (Enumeration < ZipEntry > ) zip.entries();
        while (iter.hasMoreElements()) {
            ZipEntry entry = iter.nextElement();
            InputStream is = zip.getInputStream(entry);
            String filePath = destDirectory + File.separator + entry.getName();
            if (!entry.isDirectory()) {
                extractFile(is, filePath);
            } else {
                File dir = new File(filePath);
                if (!dir.exists()) dir.mkdirs();
                File flag = new File(dir + "/.tvbox_folder");
                if (!flag.exists()) flag.createNewFile();
            }
        }
    }

    void extractFile(InputStream inputStream, String destFilePath) throws Throwable {
        File dst = new File(destFilePath);
        if (dst.exists()) dst.delete();
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(destFilePath));
        byte[] bytesIn = new byte[2048];
        int len = inputStream.read(bytesIn);
        while (len > 0) {
            bos.write(bytesIn, 0, len);
            len = inputStream.read(bytesIn);
        }
        bos.close();
    }

}