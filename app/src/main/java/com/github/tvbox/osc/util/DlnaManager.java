package com.github.tvbox.osc.util;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.bean.DlnaDevice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 DLNA / UPnP 投屏模块（零第三方依赖）。
 * 通过 SSDP M-SEARCH 发现局域网内的 MediaRenderer，再用 AVTransport SOAP 接口推送视频。
 */
public class DlnaManager {

    private static final DlnaManager INSTANCE = new DlnaManager();
    public static DlnaManager get() { return INSTANCE; }

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String AV_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1";

    /**
     * 投屏任务的执行线程池。
     *
     * 原实现是 Executors.newCachedThreadPool()：线程名匿名（pool-NN，出问题无法定位）、
     * 无并发上限（理论上可无限建线程）、且不认识主线程语义。
     * 改用统一入口后语义保持一致——仍是"按需建线程、空闲自动回收"：
     * core=1 常驻、SynchronousQueue 直接交接、线程不够才建新线程（上限 8 兜底）、
     * 空闲 30s 全部回收，饱和时走 SAFE_REJECT（不抛异常，主线程转应急线程，绝不 ANR）。
     */
    private final ExecutorService executor =
            L1Executors.pool("l1box-dlna", 1, 8, new SynchronousQueue<Runnable>());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface DiscoverCallback {
        void onResult(List<DlnaDevice> devices, String error);
    }

    public interface ActionCallback {
        void onResult(boolean success, String message);
    }

    public interface PositionCallback {
        void onResult(int positionSec, int durationSec, String error);
    }

    private DlnaManager() {
    }

    /**
     * 搜索局域网内的可投屏设备。结果在主线程回调。
     */
    public void discover(Context context, long timeoutMs, DiscoverCallback cb) {
        executor.execute(() -> {
            List<DlnaDevice> devices = new ArrayList<>();
            String error = null;
            WifiManager.MulticastLock lock = null;
            MulticastSocket socket = null;
            try {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    lock = wm.createMulticastLock("L1BoxDlna");
                    lock.setReferenceCounted(true);
                    lock.acquire();
                }

                socket = new MulticastSocket(0);
                socket.setSoTimeout((int) Math.min(timeoutMs, 5000));
                socket.setTimeToLive(4);
                InetAddress group = InetAddress.getByName(SSDP_ADDR);
                socket.joinGroup(group);

                byte[] sendData = buildSearchMessage().getBytes(StandardCharsets.UTF_8);
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, group, SSDP_PORT);
                for (int i = 0; i < 2; i++) {
                    socket.send(sendPacket);
                    try { Thread.sleep(200); } catch (InterruptedException ignored) { }
                }

                Set<String> seen = new HashSet<>();
                byte[] buf = new byte[2048];
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < timeoutMs) {
                    try {
                        DatagramPacket receivePacket = new DatagramPacket(buf, buf.length);
                        socket.receive(receivePacket);
                        String response = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8);
                        String location = parseHeader(response, "LOCATION");
                        if (location != null && seen.add(location)) {
                            DlnaDevice d = fetchDevice(location);
                            if (d != null && d.controlUrl != null) {
                                devices.add(d);
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                }
            } catch (Throwable t) {
                error = t.getMessage();
            } finally {
                if (socket != null) {
                    try { socket.leaveGroup(InetAddress.getByName(SSDP_ADDR)); } catch (Exception ignore) { }
                    try { socket.close(); } catch (Exception ignore) { }
                }
                if (lock != null && lock.isHeld()) {
                    try { lock.release(); } catch (Exception ignore) { }
                }
            }
            final String fError = error;
            final List<DlnaDevice> fDevices = devices;
            mainHandler.post(() -> cb.onResult(fDevices, fError));
        });
    }

    /**
     * 把视频推送到指定设备并开始播放。
     */
    public void cast(DlnaDevice device, String videoUrl, String title, ActionCallback cb) {
        executor.execute(() -> {
            String err = null;
            try {
                String meta = buildMetadata(videoUrl, title);
                soap(device.controlUrl, "SetAVTransportURI",
                        "<u:SetAVTransportURI xmlns:u=\"" + AV_SERVICE + "\">"
                                + "<InstanceID>0</InstanceID>"
                                + "<CurrentURI>" + escapeXml(videoUrl) + "</CurrentURI>"
                                + "<CurrentURIMetaData>" + escapeXml(meta) + "</CurrentURIMetaData>"
                                + "</u:SetAVTransportURI>");
                soap(device.controlUrl, "Play",
                        "<u:Play xmlns:u=\"" + AV_SERVICE + "\">"
                                + "<InstanceID>0</InstanceID><Speed>1</Speed></u:Play>");
            } catch (Throwable t) {
                err = t.getMessage();
            }
            final String fErr = err;
            mainHandler.post(() -> cb.onResult(fErr == null, fErr));
        });
    }

    /**
     * 暂停当前播放。
     */
    public void pause(DlnaDevice device, ActionCallback cb) {
        soapAsync(device, "Pause",
                "<u:Pause xmlns:u=\"" + AV_SERVICE + "\"><InstanceID>0</InstanceID></u:Pause>", cb);
    }

    /**
     * 从暂停恢复播放。
     */
    public void resume(DlnaDevice device, ActionCallback cb) {
        soapAsync(device, "Play",
                "<u:Play xmlns:u=\"" + AV_SERVICE + "\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>", cb);
    }

    /**
     * 停止播放。
     */
    public void stop(DlnaDevice device, ActionCallback cb) {
        soapAsync(device, "Stop",
                "<u:Stop xmlns:u=\"" + AV_SERVICE + "\"><InstanceID>0</InstanceID></u:Stop>", cb);
    }

    /**
     * 查询当前播放进度（秒）。durationSec<=0 表示直播或未知时长。
     */
    public void getPositionInfo(DlnaDevice device, PositionCallback cb) {
        executor.execute(() -> {
            int pos = -1, dur = -1;
            String err = null;
            try {
                String resp = soapResp(device.controlUrl, "GetPositionInfo",
                        "<u:GetPositionInfo xmlns:u=\"" + AV_SERVICE + "\">"
                                + "<InstanceID>0</InstanceID></u:GetPositionInfo>");
                pos = parseTime(getTag(resp, "RelTime"));
                dur = parseTime(getTag(resp, "TrackDuration"));
            } catch (Throwable t) {
                err = t.getMessage();
            }
            final int fPos = pos, fDur = dur;
            final String fErr = err;
            mainHandler.post(() -> cb.onResult(fPos, fDur, fErr));
        });
    }

    /**
     * 跳转到指定进度（秒）。
     */
    public void seek(DlnaDevice device, int positionSeconds, ActionCallback cb) {
        String target = String.format("%02d:%02d:%02d",
                positionSeconds / 3600, (positionSeconds % 3600) / 60, positionSeconds % 60);
        soapAsync(device, "Seek",
                "<u:Seek xmlns:u=\"" + AV_SERVICE + "\">"
                        + "<InstanceID>0</InstanceID>"
                        + "<Unit>REL_TIME</Unit>"
                        + "<Target>" + target + "</Target></u:Seek>", cb);
    }

    private static int parseTime(String t) {
        if (t == null) return -1;
        int dot = t.indexOf('.');
        if (dot >= 0) t = t.substring(0, dot);
        String[] p = t.split(":");
        if (p.length != 3) return -1;
        try {
            int h = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            int s = Integer.parseInt(p[2]);
            if (h < 0 || m < 0 || m > 59 || s < 0 || s > 59) return -1;
            return h * 3600 + m * 60 + s;
        } catch (Exception e) {
            return -1;
        }
    }

    private void soapAsync(DlnaDevice device, String action, String body, ActionCallback cb) {
        executor.execute(() -> {
            String err = null;
            try {
                soap(device.controlUrl, action, body);
            } catch (Throwable t) {
                err = t.getMessage();
            }
            final String fErr = err;
            mainHandler.post(() -> cb.onResult(fErr == null, fErr));
        });
    }

    // ---------- 内部实现 ----------

    private String buildSearchMessage() {
        return "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 3\r\n"
                + "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
                + "\r\n";
    }

    private String parseHeader(String resp, String key) {
        String[] lines = resp.split("\r\n|\n");
        String k = key.toLowerCase();
        for (String line : lines) {
            int idx = line.indexOf(':');
            if (idx > 0 && line.substring(0, idx).trim().toLowerCase().equals(k)) {
                return line.substring(idx + 1).trim();
            }
        }
        return null;
    }

    private DlnaDevice fetchDevice(String location) {
        try {
            String xml = getXml(location);
            if (xml == null) return null;
            String name = getTag(xml, "friendlyName");
            if (name == null || name.isEmpty()) name = "未知设备";
            String control = getServiceControlUrl(xml, "AVTransport");
            if (control == null) return null;
            String controlAbs = control;
            try {
                controlAbs = new URL(new URL(location), control).toString();
            } catch (Exception ignore) { }
            String ip = null;
            try { ip = new URL(location).getHost(); } catch (Exception ignore) { }
            return new DlnaDevice(name, location, controlAbs, ip);
        } catch (Exception e) {
            return null;
        }
    }

    private String getServiceControlUrl(String xml, String serviceName) {
        Pattern p = Pattern.compile("<service[^>]*>(.*?)</service>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        String lower = serviceName.toLowerCase();
        while (m.find()) {
            String block = m.group(1);
            if (block.toLowerCase().contains(lower)) {
                return getTag(block, "controlURL");
            }
        }
        return null;
    }

    private String getTag(String xml, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int s = xml.indexOf(open);
        if (s >= 0) {
            int e = xml.indexOf(close, s + open.length());
            if (e >= 0) return xml.substring(s + open.length(), e).trim();
        }
        Pattern p = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    private String getXml(String addr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(addr).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            String s = new String(readFully(is), StandardCharsets.UTF_8);
            is.close();
            return s;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void soap(String controlUrl, String action, String body) throws IOException {
        soapResp(controlUrl, action, body);
    }

    private String soapResp(String controlUrl, String action, String body) throws IOException {
        String envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body>" + body + "</s:Body></s:Envelope>";
        byte[] data = envelope.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(controlUrl).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION", "\"" + AV_SERVICE + "#" + action + "\"");
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = null;
            if (is != null) {
                resp = new String(readFully(is), StandardCharsets.UTF_8);
                is.close();
            }
            if (code >= 400) throw new IOException("HTTP " + code + " (" + action + ")");
            return resp;
        } finally {
            conn.disconnect();
        }
    }

    private String buildMetadata(String url, String title) {
        String mime = guessMime(url);
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + escapeXml(title) + "</dc:title>"
                + "<upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"http-get:*:" + mime + ":*\">" + escapeXml(url) + "</res>"
                + "</item></DIDL-Lite>";
    }

    private String guessMime(String url) {
        if (url == null) return "video/*";
        String u = url.toLowerCase();
        if (u.contains(".m3u8")) return "application/vnd.apple.mpegurl";
        if (u.contains(".mp4")) return "video/mp4";
        if (u.contains(".mkv")) return "video/x-matroska";
        if (u.contains(".flv")) return "video/x-flv";
        if (u.contains(".ts")) return "video/mp2t";
        if (u.contains(".mov")) return "video/quicktime";
        if (u.contains(".webm")) return "video/webm";
        if (u.contains(".wmv")) return "video/x-ms-wmv";
        if (u.contains(".avi")) return "video/x-msvideo";
        if (u.contains(".mp3")) return "audio/mpeg";
        return "video/*";
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static byte[] readFully(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toByteArray();
    }
}
