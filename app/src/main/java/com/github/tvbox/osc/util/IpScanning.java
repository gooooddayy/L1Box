package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.IpScanningVo;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class IpScanning {

    private List<IpScanningVo> ipScanningVos = new ArrayList<>();

    /**
     * 线程数
     */

    int corePoolSize = 5;

    /**
     * 最大线程数
     */
    int maximumPoolSize = 10;

    // 走统一入口：命名线程 + daemon + 空闲回收 + 拒绝时不向调用方抛异常。
    // 原实现为裸 ThreadPoolExecutor + CallerRunsPolicy：饱和时会在调用线程里执行 ping，
    // 若调用方是主线程，等于让主线程做网络 IO（低配机上直接卡顿/ANR）。
    private ThreadPoolExecutor threadPool =
            L1Executors.pool("l1box-ipscan", corePoolSize, maximumPoolSize,
                    new LinkedBlockingQueue<>(10));

    /**
     * 通过IP扫描对应网段中可以使用的网段
     * @param ips 输入的IP
     */
    public List<IpScanningVo> search(String ips, boolean all) {
        //清空缓存
        ipScanningVos.clear();
        int divisionIp = ips.lastIndexOf(".");
        String substring = ips.substring(0, divisionIp + 1);

        String last = ips.substring(divisionIp + 1);
        int end = Integer.parseInt(last) + 30; //搜索范围不是全部，缩小范围
        end = end > 255 ? 255 : end;
        if (all) end = 255;
        int total = end;

        // 扫描对应网段中的所有Ip
        BlockingQueue<IpScanningVo> queue = new ArrayBlockingQueue<>(total);
        for (int i = 1; i < total; i++) {
            String iip = substring + i;
            threadPool.submit(new PingIp(iip, queue));
        }
        threadPool.shutdown();
        // 等所有 ping 收尾后再返回，但不能用「while(!isTerminated()){}」空转：
        // 那是 100% CPU 忙等，24/30 个 IP 逐个超时需要数秒，在低配机/主线程调用方上
        // 直接表现为整机卡顿甚至 ANR。这里改成阻塞等待 + 30s 硬上限（超时放弃未回结果）。
        long deadline = System.currentTimeMillis() + 30_000L;
        try {
            while (!threadPool.isTerminated() && System.currentTimeMillis() < deadline) {
                if (threadPool.awaitTermination(500, TimeUnit.MILLISECONDS)) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!threadPool.isTerminated()) threadPool.shutdownNow();
        ipScanningVos = new ArrayList<>(queue);
        return ipScanningVos;
    }

    private static class PingIp implements Runnable {
        private String ip;
        private Queue<IpScanningVo> array;

        public PingIp(String ip, Queue<IpScanningVo> array) {
            this.array = array;
            this.ip = ip;
        }

        @Override
        public void run() {
            //遍历IP地址
            InetAddress addip = null;
            try {
                addip = InetAddress.getByName(ip);
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
            //检查设备是否在线，其中1000ms指定的是超时时间
            // 当返回值是true时，说明host是可用的，false则不可。
            boolean status = false;
            try {
                if (addip != null) {
                    status = addip.isReachable(1000);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (status) {
                IpScanningVo ipScanning = new IpScanningVo(addip.getHostName(), ip);
                LOG.i("IP地址为:" + ip + "\t\t设备名称为: " + addip.getHostName() + "\t\t是否可用: " + (status ? "可用" : "不可用"));
                array.add(ipScanning);
            }
        }
    }
}
