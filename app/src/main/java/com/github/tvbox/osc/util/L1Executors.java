package com.github.tvbox.osc.util;

import android.os.Looper;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一的线程池构造入口 —— 面向"所有设备"的兜底，而不是针对某台机器调参。
 *
 * 为什么必须统一：
 * 1) Executors 默认线程工厂产生的线程名是 pool-NN-thread-M，一旦卡住无法定位来源；
 * 2) Executors.newFixedThreadPool / newSingleThreadExecutor 的 core 线程默认**永不超时**
 *    （allowCoreThreadTimeOut=false）。只要某处漏了 shutdown，或任务卡在不响应 interrupt
 *    的同步网络调用里，线程就会永久留在进程内。高配机毫无感觉，2G 内存老机上却是
 *    调度争抢 → 卡顿 → ANR → 被系统杀的直接来源。
 * 3) 因此这里统一做四件事：命名、空闲自动回收、并发上限、**绝不向调用方抛异常的拒绝策略**。
 *    即使将来新增代码忘了回收，空闲线程也会在 KEEP_ALIVE_SECONDS 后自行退出。
 *
 * 任何异常都退回保守默认值，不改变原有功能行为。
 */
public final class L1Executors {

    /** 空闲线程回收时限。这是"即使漏了回收也不会无限堆积"的兜底。 */
    public static final long KEEP_ALIVE_SECONDS = 30L;

    private static volatile ThreadPoolExecutor timeoutRunner;

    private L1Executors() {
    }

    /**
     * 全局统一拒绝策略：**永不向调用方抛异常**。
     *
     * 调用点（Activity/Fragment 的异步任务）基本都没有 try/catch 兜底，一旦
     * RejectedExecutionException 冒泡出去就是一次闪退 —— 而它恰恰最容易发生在
     * 页面正在销毁、任务迟到提交的时刻，属于典型的"偶发闪退、无法复现"。
     * 两种情形分别处理：
     *   - 池已关闭：任务已无接收方，静默丢弃；
     *   - 池活跃但饱和（有界队列满）：非主线程退化为直接执行（功能不丢），
     *     主线程绝不在此执行（可能在池里跑的是长时间阻塞任务，会直接 ANR），
     *     改交给应急 daemon 线程执行。
     */
    private static final RejectedExecutionHandler SAFE_REJECT = new RejectedExecutionHandler() {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) return;
            if (Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
                Thread t = new Thread(r, "l1box-overflow");
                t.setDaemon(true);
                t.start();
                return;
            }
            r.run();
        }
    };

    /** 命名线程工厂：线程名可辨识，且为 daemon（卡住的线程不拖住进程退出路径） */
    public static ThreadFactory named(final String prefix) {
        final AtomicInteger seq = new AtomicInteger(0);
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }

    /**
     * 通用池构造：命名 + 空闲回收（core 线程也参与）+ 安全拒绝策略。
     * 语义与 Executors 的对应池一致（任务串行/并发行为不变），
     * 差别只在"空闲会回收 + 拒绝时不抛异常 + 线程可辨识"。
     */
    public static ThreadPoolExecutor pool(String name, int core, int max, BlockingQueue<Runnable> queue) {
        int c = core < 1 ? 1 : core;
        int m = max < c ? c : max;
        ThreadPoolExecutor p = new ThreadPoolExecutor(
                c, m, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, queue, named(name), SAFE_REJECT);
        p.allowCoreThreadTimeOut(true);
        return p;
    }

    /** 固定并发池：等价于 Executors.newFixedThreadPool + 命名 + 空闲回收 + 安全拒绝 */
    public static ThreadPoolExecutor fixed(String name, int parallelism) {
        return pool(name, parallelism, parallelism, new LinkedBlockingQueue<Runnable>());
    }

    /**
     * 进程级共享的"单次任务隔离池"：用于给同步阻塞调用加超时的场景
     * （如 getSort 中 future.get(15s) 包住 homeContent）。
     *
     * 语义与"每次 new 一个单线程池"一致：一个任务独占一个线程；
     * 但线程会复用、空闲 30s 自动回收，且总量有硬上限 —— 无论哪台设备、
     * 哪一处漏了回收，同时被卡住的线程数都不会超过该上限。
     *
     * 饱和时走 {@link #SAFE_REJECT}：后台线程退化为直接执行（功能不丢，只是失去超时保护），
     * 主线程转交应急线程，不抛 RejectedExecutionException，调用方无需额外兜底。
     * 注意：这是进程级共享池，**任何调用方都不得 shutdown 它**。
     */
    public static ExecutorService timeoutRunner() {
        if (timeoutRunner == null) {
            synchronized (L1Executors.class) {
                if (timeoutRunner == null) {
                    ThreadPoolExecutor pool = new ThreadPoolExecutor(
                            0, DeviceProfile.timeoutRunnerMax(),
                            KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                            new SynchronousQueue<Runnable>(),
                            named("l1box-timeout"),
                            SAFE_REJECT);
                    pool.allowCoreThreadTimeOut(true);
                    timeoutRunner = pool;
                }
            }
        }
        return timeoutRunner;
    }
}
