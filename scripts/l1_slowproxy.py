# -*- coding: utf-8 -*-
"""L1Box 播放测试用「限速正向代理」（PC 端，零依赖，标准库）。

为什么需要它：
    这台设备不能 root，没法在设备侧做流量整形。但 Android 的「全局 HTTP 代理」
    (settings put global http_proxy) 会让走 Java 网络栈的请求（OkHttp / Exo 的数据源）
    经过指定代理 —— 于是"把网速压到某个值"就有了一个可控、可复现的做法。

    注意：ffmpeg(IJK) 不读系统代理，所以它只影响 Exo 路径 —— 而 Exo 正是默认播放器，
    这恰好就是要测的那条路径。

用法：
    python l1_slowproxy.py 8899 8      # 限到 8 KB/s  → 必然「有网速、无画面」（验看门狗会响）
    python l1_slowproxy.py 8899 60     # 限到 60 KB/s → 「慢但可能能播」（验不误杀）
    python l1_slowproxy.py 8899 0      # 不限速（只做连通性对照）

设备侧开关：
    设代理： adb shell settings put global http_proxy <PC_IP>:8899
    清代理： adb shell settings put global http_proxy :0

已知边界：本机回环地址（127.0.0.1 / localhost）无法经代理转发 —— 那台"本地 HLS 服务"
    跑在设备自己身上。遇到这种请求会直接返回 502 并在日志里标出来，便于区分「代理造成
    的影响」与「被测行为」。
"""

import socket
import sys
import threading
import time

BUF = 4096


class Bucket(object):
    """简易令牌桶：把下行速率压到 rate 字节/秒。rate<=0 表示不限速。"""

    def __init__(self, rate):
        self.rate = float(rate)
        self.allow = self.rate if self.rate > 0 else 0.0
        self.ts = time.time()

    def take(self, n):
        if self.rate <= 0:
            return
        while True:
            now = time.time()
            self.allow += (now - self.ts) * self.rate
            self.ts = now
            if self.allow > self.rate:
                self.allow = self.rate
            if self.allow >= n:
                self.allow -= n
                return
            time.sleep(min((n - self.allow) / self.rate, 0.2))


def log(msg):
    sys.stdout.write('[%s] %s\n' % (time.strftime('%H:%M:%S'), msg))
    sys.stdout.flush()


def pipe(src, dst, bucket):
    """把 src 的字节带限速地送到 dst。"""
    try:
        while True:
            data = src.recv(BUF)
            if not data:
                break
            bucket.take(len(data))
            dst.sendall(data)
    except Exception:
        pass


def read_head(conn):
    """读到请求头结束（\r\n\r\n），返回原始头字节与剩余体。"""
    buf = b''
    while b'\r\n\r\n' not in buf:
        chunk = conn.recv(BUF)
        if not chunk:
            return None, b''
        buf += chunk
        if len(buf) > 65536:
            break
    head, _, rest = buf.partition(b'\r\n\r\n')
    return head, rest


def handle(conn, rate):
    try:
        conn.settimeout(30)
        head, rest = read_head(conn)
        if head is None:
            return
        lines = head.decode('latin1').split('\r\n')
        parts = lines[0].split()
        if len(parts) < 3:
            return
        method, target, _ = parts[0], parts[1], parts[2]

        if method.upper() == 'CONNECT':
            host, _, port = target.partition(':')
            port = int(port or 443)
            if host in ('127.0.0.1', 'localhost', '::1'):
                conn.sendall(b'HTTP/1.1 502 Bad Gateway\r\n\r\n')
                log('本机回环 %s:%d —— 代理到不了设备自己的回环地址，直接 502' % (host, port))
                return
            up = socket.create_connection((host, port), timeout=20)
            conn.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n')
            log('CONNECT %s:%d  限速 %s KB/s' % (host, port, rate / 1024.0 if rate else 'off'))
            bucket = Bucket(rate)
            t = threading.Thread(target=pipe, args=(up, conn, bucket))
            t.daemon = True
            t.start()
            pipe(conn, up, Bucket(0))   # 上行不限速（请求体很小）
            up.close()
            return

        # 普通 HTTP：target 可能是绝对 URL，也可能是 path（用 Host 头补主机）
        if target.startswith('http://'):
            rest_url = target[7:]
            hostport, _, path = rest_url.partition('/')
            path = '/' + path
        else:
            hostport = None
            path = target
            for ln in lines[1:]:
                if ln.lower().startswith('host:'):
                    hostport = ln.split(':', 1)[1].strip()
                    break
            if not hostport:
                conn.sendall(b'HTTP/1.1 400 Bad Request\r\n\r\n')
                return
        host, _, port = hostport.partition(':')
        port = int(port or 80)

        if host in ('127.0.0.1', 'localhost', '::1'):
            conn.sendall(b'HTTP/1.1 502 Bad Gateway\r\n\r\n')
            log('本机回环 %s:%d%s —— 代理到不了设备自己的回环地址，直接 502' % (host, port, path))
            return

        up = socket.create_connection((host, port), timeout=20)
        forward = ['%s %s HTTP/1.1' % (method, path)]
        for ln in lines[1:]:
            low = ln.lower()
            if low.startswith('proxy-connection:') or low.startswith('proxy-authorization:'):
                continue
            forward.append(ln)
        up.sendall(('\r\n'.join(forward) + '\r\n\r\n').encode('latin1'))
        if rest:
            up.sendall(rest)

        log('%s %s:%d%s  限速 %s KB/s' % (method, host, port, path[:80],
                                          rate / 1024.0 if rate else 'off'))
        pipe(up, conn, Bucket(rate))
        up.close()
    except Exception as e:
        log('连接异常：%s' % e)
    finally:
        try:
            conn.close()
        except Exception:
            pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8899
    kbps = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    rate = kbps * 1024
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(('0.0.0.0', port))
    srv.listen(64)
    log('限速代理已启动：端口 %d，下行 %s' % (port, ('%d KB/s' % kbps) if kbps else '不限速'))
    log('设备侧设置：adb shell settings put global http_proxy <本机IP>:%d' % port)
    while True:
        try:
            conn, addr = srv.accept()
            t = threading.Thread(target=handle, args=(conn, rate))
            t.daemon = True
            t.start()
        except KeyboardInterrupt:
            break
        except Exception as e:
            log('accept 异常：%s' % e)


if __name__ == '__main__':
    main()
