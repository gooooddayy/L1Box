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
            if (!local && (fileName.startsWith("/localfile/") || fileName.equals("/dns-query"))) {
                return denied("该接口仅供本机使用。");
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
                        Object[] rs = ApiConfig.get().proxyLocal(params);
                        //if (rs[0] instanceof Response) {
                        //    return (Response) rs[0];
                        //}
                        int code = (int) rs[0];
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