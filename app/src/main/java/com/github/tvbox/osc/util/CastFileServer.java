package com.github.tvbox.osc.util;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * 极简局域网 HTTP 服务：把本机视频文件以 http://<局域网IP>:<port>/cast.xxx 暴露给投屏设备。
 * 支持 Range 请求（视频拖动/续传所需）。纯标准库实现，无第三方依赖。
 * 同一时刻只服务一个当前文件，端口随机（ephemeral），Activity 销毁时 stop。
 */
public class CastFileServer {

    private static final CastFileServer INSTANCE = new CastFileServer();
    public static CastFileServer get() { return INSTANCE; }

    private static final String TAG = "CastFileServer";

    /**
     * 连接处理线程池：原来每条连接 new 一个线程，局域网里被反复扫描/重连时线程数无上限，
     * 且这些线程非 daemon。投屏场景并发极低（一个播放器），2~4 个足够。
     */
    private static final java.util.concurrent.ExecutorService CAST_POOL =
            L1Executors.fixed("l1box-cast", 4);

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile String currentPath;
    private final Object lock = new Object();

    public boolean isRunning() {
        return serverSocket != null && !serverSocket.isClosed();
    }

    /**
     * 启动（若未运行）并指向要投屏的文件，返回可供局域网设备访问的 http 地址；失败返回 null。
     */
    public String start(File file) {
        if (file == null || !file.exists()) return null;
        synchronized (lock) {
            if (!isRunning()) {
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(0));
                    acceptThread = new Thread(this::acceptLoop);
                    acceptThread.setDaemon(true);
                    acceptThread.start();
                } catch (IOException e) {
                    Log.w(TAG, "start failed", e);
                    return null;
                }
            }
            currentPath = file.getAbsolutePath();
        }
        String ip = getLanIp();
        if (ip == null) return null;
        int port = serverSocket.getLocalPort();
        String ext = getExt(file.getName());
        return "http://" + ip + ":" + port + "/cast" + ext;
    }

    public void stop() {
        synchronized (lock) {
            currentPath = null;
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (IOException ignore) { }
                serverSocket = null;
            }
        }
    }

    private void acceptLoop() {
        while (!Thread.currentThread().isInterrupted() && isRunning()) {
            try {
                Socket socket = serverSocket.accept();
                try {
                    CAST_POOL.execute(() -> handle(socket));
                } catch (Throwable th) {
                    // 池已满/已关闭时也不能让 accept 线程崩掉，直接关掉这条连接
                    try { socket.close(); } catch (IOException ignore) { }
                }
            } catch (IOException e) {
                if (isRunning()) Log.w(TAG, "accept error", e);
                break;
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket; OutputStream os = socket.getOutputStream()) {
            byte[] buf = new byte[8192];
            int r = socket.getInputStream().read(buf);
            if (r <= 0) return;
            String request = new String(buf, 0, r, "ISO-8859-1");
            String[] lines = request.split("\r\n");
            if (lines.length == 0) return;
            String[] parts = lines[0].split(" ");
            if (parts.length < 2) return;
            String method = parts[0];

            String range = null;
            for (String line : lines) {
                if (line.toLowerCase().startsWith("range:")) {
                    range = line.substring(line.indexOf(':') + 1).trim();
                    break;
                }
            }

            File file = currentPath != null ? new File(currentPath) : null;
            if (file == null || !file.exists()) {
                sendStatus(os, "404 Not Found", "text/plain", "not found");
                return;
            }
            long total = file.length();
            String mime = guessMime(file.getName());

            long start = 0;
            long end = total - 1;
            if (range != null && range.startsWith("bytes=")) {
                String rg = range.substring(6).trim();
                int dash = rg.indexOf('-');
                try {
                    if (dash == 0) {
                        long suffix = Long.parseLong(rg.substring(1));
                        start = Math.max(0, total - suffix);
                    } else {
                        start = Long.parseLong(rg.substring(0, dash));
                        end = (dash < rg.length() - 1) ? Long.parseLong(rg.substring(dash + 1)) : total - 1;
                    }
                } catch (NumberFormatException ignore) { }
                if (end >= total) end = total - 1;
                if (start > end || start < 0) start = 0;
            }
            long len = end - start + 1;

            StringBuilder headers = new StringBuilder();
            if (range != null) {
                headers.append("HTTP/1.1 206 Partial Content\r\n");
                headers.append("Content-Range: bytes ").append(start).append("-").append(end)
                        .append("/").append(total).append("\r\n");
            } else {
                headers.append("HTTP/1.1 200 OK\r\n");
            }
            headers.append("Content-Type: ").append(mime).append("\r\n");
            headers.append("Accept-Ranges: bytes\r\n");
            headers.append("Content-Length: ").append(len).append("\r\n");
            headers.append("Connection: close\r\n");
            headers.append("\r\n");

            if (method.equalsIgnoreCase("HEAD")) {
                os.write(headers.toString().getBytes("UTF-8"));
                os.flush();
                return;
            }

            os.write(headers.toString().getBytes("UTF-8"));
            os.flush();

            try (FileInputStream fis = new FileInputStream(file)) {
                fis.skip(start);
                long remaining = len;
                byte[] data = new byte[16384];
                int n;
                while (remaining > 0 && (n = fis.read(data)) > 0) {
                    int toWrite = (int) Math.min(n, remaining);
                    os.write(data, 0, toWrite);
                    remaining -= toWrite;
                }
                os.flush();
            }
        } catch (Exception e) {
            Log.w(TAG, "handle error", e);
        }
    }

    private void sendStatus(OutputStream os, String status, String type, String body) throws IOException {
        String resp = "HTTP/1.1 " + status + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.getBytes("UTF-8").length
                + "\r\nConnection: close\r\n\r\n" + body;
        os.write(resp.getBytes("UTF-8"));
        os.flush();
    }

    private String guessMime(String name) {
        name = name.toLowerCase();
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".mkv")) return "video/x-matroska";
        if (name.endsWith(".avi")) return "video/x-msvideo";
        if (name.endsWith(".mov")) return "video/quicktime";
        if (name.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (name.endsWith(".ts")) return "video/mp2t";
        if (name.endsWith(".flv")) return "video/x-flv";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".wmv")) return "video/x-ms-wmv";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".m4a") || name.endsWith(".aac")) return "audio/mp4";
        return "application/octet-stream";
    }

    private String getExt(String name) {
        int i = name.lastIndexOf('.');
        if (i >= 0 && i < name.length() - 1) return name.substring(i);
        return "";
    }

    private String getLanIp() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress() || !(addr instanceof Inet4Address)) continue;
                    String host = addr.getHostAddress();
                    if (host.startsWith("192.168.") || host.startsWith("10.")
                            || (host.startsWith("172.") && isPrivate172(host))) {
                        return host;
                    }
                }
            }
            // 回退：任意非回环 IPv4
            Enumeration<NetworkInterface> en2 = NetworkInterface.getNetworkInterfaces();
            while (en2.hasMoreElements()) {
                NetworkInterface ni = en2.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress() || !(addr instanceof Inet4Address)) continue;
                    return addr.getHostAddress();
                }
            }
        } catch (SocketException e) {
            Log.w(TAG, "getLanIp", e);
        }
        return null;
    }

    private boolean isPrivate172(String host) {
        // 172.16.0.0 - 172.31.255.255
        String[] p = host.split("\\.");
        if (p.length < 2) return false;
        try {
            int b = Integer.parseInt(p[1]);
            return b >= 16 && b <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
