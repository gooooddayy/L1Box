package com.github.catvod.crawler;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.tvbox.osc.base.App;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import dalvik.system.DexClassLoader;

class ProtectedInitJar {

    private static final byte[] DEX_MAGIC = new byte[]{'d', 'e', 'x'};
    private static final int BUFFER_SIZE = 8192;

    /**
     * 家族特征常量（2026-09-14 an 批次一）。
     *
     * 全部预编码为 UTF-8 字节，扫描时做**字节比对**而不是 new String —— 一个 dex 的 typeIds
     * 可能上万条，逐条构造字符串会在加载期产生大量垃圾。
     */
    private static final String SPIDER = "com/github/catvod/spider/";
    /** Init 类前缀（含 InitOrigin 等变体） */
    private static final byte[] P_INIT = bytes("L" + SPIDER + "Init");
    /** 精确的 Init 类描述符 */
    private static final byte[] T_INIT = bytes("L" + SPIDER + "Init;");
    private static final byte[] T_DEXNATIVE = bytes("L" + SPIDER + "DexNative;");
    /** 同族入口类：任一出现即该加固 SDK 家族（与是否带 killProcess 无关） */
    private static final byte[][] FAMILY_TYPES = {
            T_DEXNATIVE,
            bytes("L" + SPIDER + "ProxyOrigin;"),
            bytes("L" + SPIDER + "InitOrigin;"),
            bytes("L" + SPIDER + "GoProxyManager;"),
    };
    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 加固 jar 原生初始化的进程级串行锁（2026-09-13）。
     *
     * 事故现象（真机 logcat 实证）：多个线程池线程（pool-19/29/40/48）**同时**进入 jar 内部的
     * `GoProxyManager.startBlockingForInit`，各自 exec 出一份 `new_go_proxy_wex` 监听不同随机端口
     * （38781、21132…），而 jar 内部记录的目标端口被互相覆盖 —— `waitHealth` 连续 12 次去查
     * 127.0.0.1:31830（一个从未被监听的端口），最终原生代理对象为 null，
     * 此后任意一个 /proxy 请求进来即在 `DexNative.proxyInvoke` 触发
     * `JNI DETECTED ERROR … obj == null` → SIGABRT，整个进程被系统杀掉。
     *
     * 为什么必须是进程级一把锁：DexNative 是该加固方案的原生库，进程内只有一份，
     * 被并发写入的正是这份共享状态（端口记录 / 当前 loader），与"是哪个 jar"无关；
     * 原有的按 jarKey 分锁（JarLoader.lockFor）只能串行同一 jar，管不住 jar 之间。
     *
     * 为什么不能在 Java 层 catch：abort 由 native 发起，是进程级死亡不是异常，
     * `catch (Throwable)` 拿不到控制权。所以唯一的防线是**事前的串行与门禁**。
     */
    private static final Object NATIVE_INIT_LOCK = new Object();

    /**
     * 进程级单飞闸门（2026-09-13 ai 版真机 3 次崩溃的决定性证据）。
     *
     * 加固 SDK 把 Go 代理当**进程内独占资源**：二进制固定为 files/AiWex/new_go_proxy_wex 一条路径
     * （全场 17 次启动全同一路径），且每次启动前先 killall 掉在跑的实例（17 次启动 10 次
     * exitCode=137 SIGKILL）。多个加固 jar 各带一份 SDK 副本、各自启动 → 后启动的杀掉先启动的
     * → 被杀 jar 的健康检查循环盯着一个永不被监听的端口 → 代理对象保持 null
     * → 任何 /proxy 打到它 → DexNative.proxyInvoke obj==null → SIGABRT 整个进程。
     *
     * 旧实现 GO_PROXY_STARTED 按 jar 的 Class 记账（WeakHashMap），每 jar 一份，跨 jar 零去重，
     * 管不住互杀链。改为进程级一把闸：**整个进程只允许第一个成功 init 的加固 jar 启动代理**，
     * 后续加固 jar 只绑定 Context + 内部 loader、跳过 startGoProxy。
     * 读写都在 NATIVE_INIT_LOCK 内（init 全程持锁），check-then-act 无竞态。
     */
    private static volatile boolean GO_PROXY_STARTED = false;

    /** 本进程代理持有者：成功执行 startGoProxy 的那个 Init 类。/proxy 只准调用它的 Proxy（见 JarLoader 路由） */
    private static volatile Class<?> PROXY_HOLDER = null;

    /**
     * 持有者的 jar key（MD5(jarUrl)）。与 PROXY_HOLDER **同锁更新**，两者永远同一份真相（2026-09-14 批次三）。
     *
     * 此前 JarLoader 自己另存一个 proxyHolderKey，与 PROXY_HOLDER 分处两地、各自过期 ——
     * am 会话"账本上没有、方法还能调"的空洞就是这类双份状态的产物。身份与 key 只有合在一处，
     * 才不可能对不上。
     */
    private static volatile String PROXY_HOLDER_KEY = "";

    /**
     * 原生初始化进行中（2026-09-14 aq）。**进程级**，跨 jar、跨实例。
     *
     * 为什么必须进程级、而不是按 key：加固 jar 初始化时会把在跑的共享 Go 代理 killall 掉再启动
     * 自己的，而 /proxy 是按 recentJarKey 路由 —— 若此刻进来的请求属于**另一个** jar，按 key 的
     * 门禁对它完全无效，它会打到正被 killall 的原生对象上 → obj==null → SIGABRT。
     * 门禁只能是"全进程任一 jar 在初始化 → 所有 /proxy 一律拒绝"。
     */
    private static volatile boolean NATIVE_INIT_IN_PROGRESS = false;

    /** 是否有加固 jar 正在做原生初始化（/proxy 入站门禁用，见 JarLoader.proxyInvoke） */
    static boolean nativeInitInProgress() {
        return NATIVE_INIT_IN_PROGRESS;
    }

    /** 持有者的 ClassLoader（/proxy 身份终审用）。无持有者时返回 null */
    static ClassLoader proxyHolderLoader() {
        Class<?> holder = PROXY_HOLDER;
        return holder == null ? null : holder.getClassLoader();
    }

    /** 持有者的 jar key；空串表示本进程尚无持有者 */
    static String proxyHolderKey() {
        return PROXY_HOLDER_KEY;
    }

    /**
     * 家族 jar 的 ClassLoader 账本（2026-09-14，am）。init 成功即登记，**永不随配置重载清空**。
     *
     * al 会话 5 次 SIGABRT 的根因：homeJarLoader.load() 把静态路由账本（protectedJarKeys/proxyHolderKey）
     * 清了，却清不掉主实例缓存的 proxyMethods —— 于是"账本上没有、方法还能调"的家族 jar 出现；
     * 随后新 holder 启动时 killall 杀掉它的代理（exitCode=137 实证），/proxy 按 recentJarKey 打到它
     * 就 obj==null abort。key 型账本可被清空/过期，**ClassLoader 身份不会**：init 成功过的 loader
     * 记在这里，/proxy 调用前按它做最终裁决，从数学上封死"打到非 holder 家族 jar"的路径。
     */
    private static final Set<ClassLoader> FAMILY_LOADERS = ConcurrentHashMap.newKeySet();

    /** 该 ClassLoader 是否家族 jar（init 成功过）。null 恒否。 */
    static boolean isFamilyClassLoader(ClassLoader cl) {
        return cl != null && FAMILY_LOADERS.contains(cl);
    }

    /** 该 ClassLoader 是否当前代理持有者。holder 未定或为 null 时恒否。 */
    static boolean isHolderClassLoader(ClassLoader cl) {
        return cl != null && PROXY_HOLDER != null && PROXY_HOLDER.getClassLoader() == cl;
    }

    /**
     * 运行时家族探测缓存（loader → 是否含 DexNative），**含 false 结论**。
     *
     * 与 FAMILY_LOADERS 的区别：那张表记的是"init 成功过的身份"，本表记的是"探测结论"，
     * 普通 jar 也会被记一条 false，因此不能混用。
     */
    private static final java.util.Map<ClassLoader, Boolean> PROBE_CACHE = new ConcurrentHashMap<>();

    /**
     * 运行时权威探测：该 ClassLoader 里是否存在 `com.github.catvod.spider.DexNative`。
     *
     * 这是本方案里**唯一不受 dex 结构影响**的判据。静态扫描要自己解析 dex 的
     * classDefs/typeIds，壳 + payload 多 dex 结构会让"Init 与 DexNative 分处不同 dex"，
     * 逐 dex 判定必然漏（2026-09-14 am 真机 3 次 SIGABRT 的根因）。
     * 而 `loadClass` 是 ART 自己的解析结果 —— 跨 dex、跨壳、抗混淆，全覆盖。
     *
     * `ClassLoader.loadClass` 只链接不初始化（等价于 `Class.forName(name, false, loader)`），
     * 不执行静态块，因此**零副作用**：不会碰到任何原生状态。
     */
    static boolean probeFamily(ClassLoader cl) {
        if (cl == null) return false;
        Boolean cached = PROBE_CACHE.get(cl);
        if (cached != null) return cached;
        boolean result;
        try {
            cl.loadClass("com.github.catvod.spider.DexNative");
            result = true;
        } catch (Throwable notFamily) {
            result = false;
        }
        PROBE_CACHE.put(cl, result);
        return result;
    }

    /** 某个 loader 已被永久丢弃（md5 换版）：清掉按它记账的探测结论，不留死引用 */
    static void forgetLoader(ClassLoader cl) {
        if (cl != null) PROBE_CACHE.remove(cl);
    }

    private final ConcurrentHashMap<String, Boolean> jars = new ConcurrentHashMap<>();

    void clear() {
        jars.clear();
    }

    boolean check(String jar) {
        Boolean cached = jars.get(jar);
        if (cached != null) return cached;
        boolean result = scan(jar, App.getInstance().getPackageName());
        jars.put(jar, result);
        return result;
    }

    /**
     * 加固 jar 初始化：**全过程持进程级锁**，并全程置 NATIVE_INIT_IN_PROGRESS。
     *
     * 串行范围故意包含 bindDexLoader —— `DexNative.getLoader` 同样落在那份进程级原生状态上，
     * 并发调用会互相覆盖"当前 loader"，使后进入者拿到 null 或半成品。
     * 只串行 startGoProxy 是不够的（实测两次崩溃的 waitHealth 错乱就出现在 loader 与端口两处）。
     *
     * 代价：多 jar 场景下初始化排队，每个 jar 首次加载多等上一份的启动时间；
     * 换来的是"绝不因并发触发原生 abort"。加载发生在工作线程，不影响 UI 响应。
     *
     * == 两条路径（2026-09-14 aq，真机日志实证后的定稿） ==
     *
     * 事实一：这批「壳 + payload 多 dex」加固 jar 在 ab→am 全线都是被**漏判成普通 jar**、
     * 走 `Init.init(Context)` 活下来的（ak 13843 次 / am 14678 次，且那 28521 次里 0 崩溃）。
     * jar 自己的入口里，次序是"先起 Go 代理、再取 loader"，天生自洽。
     *
     * 事实二：ap 把判据修好之后，它们第一次进入我们的手写受控路径，而手写路径**跳过了"起代理"**，
     * 于是 `getLoader → InitOrigin.init → ProxyOrigin.init` 去连 127.0.0.1:8964~9031 全部
     * ECONNREFUSED（7802 次），拿不到 loader → 458 次拒载 → 站点成片消失（用户实测"搜索为空"）。
     *
     * 所以定稿为：
     *  - **本进程已有代理在跑** → 先试手绑（`getLoader` 能连上它）：省掉一次 killall+重启，是纯提速；
     *  - **没有代理可承接 / 手绑失败** → 交还 jar 自己的 `Init.init`：它是唯一能把代理起起来的入口；
     *    自杀调用已由 JarKillNeutralizer 消除，且全程在 NATIVE_INIT_LOCK 内串行。
     *  - **谁真正启动了代理，谁就是持有者**（见 occupyProxy）。走 jar 自身入口 = jar 内部重启了共享
     *    代理 = 旧持有者的代理已死，必须换届，否则旧持有者的 /proxy 会打在死掉的原生对象上。
     */
    boolean init(Class<?> clz, String key) {
        Object init = null;
        try {
            Method get = clz.getMethod("get");
            init = get.invoke(null);
        } catch (Throwable ignored) {
        }
        synchronized (NATIVE_INIT_LOCK) {
            NATIVE_INIT_IN_PROGRESS = true;
            try {
                bindContext(clz, init);
                boolean bound = GO_PROXY_STARTED && bindDexLoader(clz, init);
                boolean tookProxy = false;
                if (!bound) {
                    bound = invokeSelfInit(clz);
                    tookProxy = bound;
                }
                if (!bound) {
                    System.out.println("加固 jar 初始化失败（手绑与 jar 自身入口都不可用），已降级其站点：" + key);
                    return false;
                }
                if (tookProxy) {
                    // jar 内部重启了共享代理：本进程唯一的活代理现在归它
                    occupyProxy(clz, key);
                    System.out.println("加固 jar 交还自身入口初始化成功，本地代理已由它接管：" + key);
                } else {
                    invokeNoArg(clz, "replaceCloudDiskNames");
                    System.out.println("加固 jar 手绑成功（承接已有的本地代理，未重启）：" + key);
                }
                FAMILY_LOADERS.add(clz.getClassLoader()); // 身份账本：随 loader 生命周期存活，不受配置重载影响
                return true;
            } finally {
                NATIVE_INIT_IN_PROGRESS = false;
            }
        }
    }

    /** 本 jar 现在持有代理：登记身份与 key（同锁更新，两者永远同一份真相），并关掉旧持有者的路由 */
    private static void occupyProxy(Class<?> clz, String key) {
        GO_PROXY_STARTED = true;
        PROXY_HOLDER = clz;
        PROXY_HOLDER_KEY = key;
    }

    /**
     * 交还 jar 自己的初始化入口 `Init.init(Context)`。
     *
     * 为什么可以走它：加固 jar 的 `Init.init` 内含宿主白名单校验，校验不过会 `Process.killProcess`
     * 自杀 —— 但那些调用已被 JarKillNeutralizer 在 dex 层改成合法方法名（运行时抛可捕获的
     * NoSuchMethodError），拿不到控制权的进程级自杀已经不存在。实证：ab→am 全线 28521 次执行 0 崩溃。
     */
    private boolean invokeSelfInit(Class<?> clz) {
        try {
            Method method = clz.getMethod("init", Context.class);
            method.invoke(null, new JarContextBridge(App.getInstance(),
                    JarToastBlockLoader.create(App.getInstance().getClassLoader())));
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    private void bindContext(Class<?> clz, Object init) {
        if (init == null) return;
        // Context 桥接：加固 jar 内部经 context.getClassLoader() 建内层加载器，
        // 桥接后内层 payload 的 Toast/Process 同样走拦截链
        Context bridged = new JarContextBridge(App.getInstance(),
                JarToastBlockLoader.create(App.getInstance().getClassLoader()));
        try {
            Field context = clz.getDeclaredField("c");
            context.setAccessible(true);
            context.set(init, bridged);
            return;
        } catch (Throwable ignored) {
        }
        for (Field field : clz.getDeclaredFields()) {
            try {
                if (Modifier.isStatic(field.getModifiers()) || !Context.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                field.set(init, bridged);
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean bindDexLoader(Class<?> clz, Object init) {
        if (init == null) return false;
        try {
            Class<?> nativeClass = clz.getClassLoader().loadClass("com.github.catvod.spider.DexNative");
            Method getLoader = nativeClass.getMethod("getLoader", Object.class);
            Object loader = getLoader.invoke(null, new JarContextBridge(App.getInstance(),
                    JarToastBlockLoader.create(App.getInstance().getClassLoader())));
            if (!(loader instanceof DexClassLoader)) return false;
            boolean bound = false;
            for (Class<?> type = clz; type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !DexClassLoader.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    field.set(init, loader);
                    bound = true;
                }
            }
            return bound;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void invokeNoArg(Class<?> clz, String methodName) {
        try {
            Method method = clz.getMethod(methodName);
            method.invoke(null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 家族判据（2026-09-14 an 批次一）：**jar 级汇总**，不再是逐 dex 独立判定。
     *
     * 旧写法对每个 dex 单独调 isProtected()、任一命中即返回 true；而 isProtected 内部要求
     * Init 与 DexNative 出现在**同一个 dex**。壳 + payload 多 dex 结构的加固 jar
     * （真机实例 files/0b9565d6a6b5ecd989a7de9e1d50677e.jar）必然两边都不满足 → 恒判普通
     * → 被当普通 jar 直接调 Init.init → 内部先 killall 共享 Go 代理再启动自己的 → native abort。
     *
     * 现在改为：逐个 dex **只采集特征**，全部采完再统一判定（见 Signals.family()）。
     */
    private static boolean scan(String jar, String packageName) {
        try {
            File file = new File(jar);
            if (!file.exists()) return false;
            Signals signals = new Signals();
            if (isDexFile(file)) {
                InputStream is = null;
                try {
                    is = new FileInputStream(file);
                    signals.or(collectDex(readBytes(is), packageName));
                } finally {
                    close(is);
                }
                return signals.family();
            }
            ZipFile zip = null;
            try {
                zip = new ZipFile(file);
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(".dex")) continue;
                    InputStream is = null;
                    try {
                        is = zip.getInputStream(entry);
                        signals.or(collectDex(readBytes(is), packageName));
                    } finally {
                        close(is);
                    }
                    if (signals.familyType) return true; // 家族入口类一出现即可定案，不必读完剩余 dex
                }
            } finally {
                close(zip);
            }
            return signals.family();
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Signals collectDex(byte[] data, String packageName) {
        if (data == null || data.length < 112 || !startsWith(data, DEX_MAGIC)) return new Signals();
        try {
            return new Dex(data, packageName).collect();
        } catch (Throwable ignored) {
            return new Signals();
        }
    }

    private static boolean isDexFile(File file) {
        InputStream is = null;
        try {
            is = new FileInputStream(file);
            byte[] magic = new byte[DEX_MAGIC.length];
            int len = is.read(magic);
            return len == DEX_MAGIC.length && startsWith(magic, DEX_MAGIC);
        } catch (Throwable ignored) {
            return false;
        } finally {
            close(is);
        }
    }

    private static byte[] readBytes(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int len;
        while ((len = is.read(buffer)) != -1) baos.write(buffer, 0, len);
        close(is);
        return baos.toByteArray();
    }

    private static boolean startsWith(byte[] data, byte[] pattern) {
        if (data == null || pattern == null || data.length < pattern.length) return false;
        for (int i = 0; i < pattern.length; i++) {
            if (data[i] != pattern[i]) return false;
        }
        return true;
    }

    private static void close(Closeable closeable) {
        try {
            if (closeable != null) closeable.close();
        } catch (Throwable ignored) {
        }
    }

    private static class Dex {

        private final byte[] data;
        private final int stringIdsSize;
        private final int stringIdsOff;
        private final int typeIdsSize;
        private final int typeIdsOff;
        private final int methodIdsSize;
        private final int methodIdsOff;
        private final int classDefsSize;
        private final int classDefsOff;
        private final Whitelist whitelist;

        Dex(byte[] data, String packageName) {
            this.data = data;
            this.stringIdsSize = uint(0x38);
            this.stringIdsOff = uint(0x3c);
            this.typeIdsSize = uint(0x40);
            this.typeIdsOff = uint(0x44);
            this.methodIdsSize = uint(0x58);
            this.methodIdsOff = uint(0x5c);
            this.classDefsSize = uint(0x60);
            this.classDefsOff = uint(0x64);
            this.whitelist = new Whitelist(packageName);
        }

        /**
         * 采集本 dex 的家族特征（**不判定**）。判定统一在 jar 级 Signals.family() 里做，
         * 这样"Init 在壳 dex、DexNative 在 payload dex"也能被判出来。
         *
         * 信号源按"便宜 → 贵"排序，命中即早退。
         */
        Signals collect() {
            Signals s = new Signals();
            for (int i = 0; i < typeIdsSize; i++) {
                if (hitsFamilyType(uint(typeIdsOff + i * 4))) {
                    s.familyType = true;
                    return s; // 最精确的信号已定案，无需再扫
                }
            }
            scanClasses(s);
            return s;
        }

        /** ① 家族入口类：任一出现即该加固 SDK 家族 —— 与是否带 killProcess 无关，也与所在 dex 无关 */
        private boolean hitsFamilyType(int stringIdx) {
            for (byte[] type : FAMILY_TYPES) {
                if (stringEquals(stringIdx, type)) return true;
            }
            return false;
        }

        /** ② Init 类、宿主白名单、killProcess 自杀调用 */
        private void scanClasses(Signals s) {
            for (int i = 0; i < classDefsSize; i++) {
                int off = classDefsOff + i * 32;
                int descIdx = uint(off);
                if (!stringStartsWith(descIdx, P_INIT)) continue;
                boolean exactInit = stringEquals(descIdx, T_INIT);
                if (exactInit) s.initClass = true;
                int classDataOff = uint(off + 24);
                if (classDataOff <= 0) continue;
                // 白名单只对精确的 Init 类查：命中说明该 jar 认这个宿主包名，可正常走 Init.init
                if (exactInit && scanInitWhitelist(classDataOff)) s.whitelisted = true;
                if (!s.killProcess && scanKillProcess(classDataOff)) s.killProcess = true;
                if (s.whitelisted && s.killProcess) return;
            }
        }

        /** 字符串表项与常量做字节比对，避免 typeIds 上万条时逐条 new String */
        private boolean stringEquals(int stringIdx, byte[] expect) {
            int len = bytesLen(stringIdx, expect.length);
            return len == expect.length && matchAt(stringIdx, expect, expect.length);
        }

        private boolean stringStartsWith(int stringIdx, byte[] prefix) {
            return bytesLen(stringIdx, prefix.length) >= prefix.length
                    && matchAt(stringIdx, prefix, prefix.length);
        }

        /** 该字符串表项的 UTF-8 字节长度；越界或短于 min 时返回 -1 */
        private int bytesLen(int stringIdx, int min) {
            if (stringIdx < 0 || stringIdx >= stringIdsSize) return -1;
            int[] cursor = new int[]{uint(stringIdsOff + stringIdx * 4)};
            int len = uleb(cursor);
            if (len < min || cursor[0] < 0 || cursor[0] + min > data.length) return -1;
            return len;
        }

        private boolean matchAt(int stringIdx, byte[] expect, int count) {
            int[] cursor = new int[]{uint(stringIdsOff + stringIdx * 4)};
            uleb(cursor);
            int start = cursor[0];
            for (int i = 0; i < count; i++) {
                if (data[start + i] != expect[i]) return false;
            }
            return true;
        }

        private boolean scanInitWhitelist(int off) {
            int[] cursor = new int[]{off};
            int staticFields = uleb(cursor);
            int instanceFields = uleb(cursor);
            int directMethods = uleb(cursor);
            int virtualMethods = uleb(cursor);
            skipFields(cursor, staticFields + instanceFields);
            return scanInitMethods(cursor, directMethods) || scanInitMethods(cursor, virtualMethods);
        }

        private boolean scanInitMethods(int[] cursor, int count) {
            int methodIdx = 0;
            for (int i = 0; i < count; i++) {
                methodIdx += uleb(cursor);
                uleb(cursor);
                int codeOff = uleb(cursor);
                if (codeOff <= 0 || methodIdx >= methodIdsSize) continue;
                if ("init".equals(methodName(methodIdx)) && scanWhitelist(codeOff)) return true;
            }
            return false;
        }

        private boolean scanKillProcess(int off) {
            int[] cursor = new int[]{off};
            int staticFields = uleb(cursor);
            int instanceFields = uleb(cursor);
            int directMethods = uleb(cursor);
            int virtualMethods = uleb(cursor);
            skipFields(cursor, staticFields + instanceFields);
            return scanKillMethods(cursor, directMethods) || scanKillMethods(cursor, virtualMethods);
        }

        private boolean scanKillMethods(int[] cursor, int count) {
            int methodIdx = 0;
            for (int i = 0; i < count; i++) {
                methodIdx += uleb(cursor);
                uleb(cursor);
                int codeOff = uleb(cursor);
                if (codeOff > 0 && scanKillProcessCode(codeOff)) return true;
            }
            return false;
        }

        private boolean scanWhitelist(int codeOff) {
            int insnsSize = uint(codeOff + 12);
            int insnsOff = codeOff + 16;
            int end = insnsOff + insnsSize * 2;
            for (int off = insnsOff; off + 1 < end && off + 1 < data.length; off += 2) {
                int op = data[off] & 0xff;
                if (op == 0x1a && off + 3 < end) {
                    if (whitelist.contains(string(ushort(off + 2)))) return true;
                } else if (op == 0x1b && off + 5 < end) {
                    if (whitelist.contains(string(uint(off + 2)))) return true;
                }
            }
            return false;
        }

        private boolean scanKillProcessCode(int codeOff) {
            int insnsSize = uint(codeOff + 12);
            int insnsOff = codeOff + 16;
            int end = insnsOff + insnsSize * 2;
            for (int off = insnsOff; off + 3 < end && off + 3 < data.length; off += 2) {
                int op = data[off] & 0xff;
                if (op >= 0x6e && op <= 0x72 && isKillProcess(ushort(off + 2))) return true;
            }
            return false;
        }

        private void skipFields(int[] cursor, int count) {
            for (int i = 0; i < count; i++) {
                uleb(cursor);
                uleb(cursor);
            }
        }

        private boolean isKillProcess(int methodIdx) {
            if (methodIdx < 0 || methodIdx >= methodIdsSize) return false;
            return "Landroid/os/Process;".equals(methodClass(methodIdx)) && "killProcess".equals(methodName(methodIdx));
        }

        private String methodClass(int methodIdx) {
            return typeDesc(ushort(methodIdsOff + methodIdx * 8));
        }

        private String methodName(int methodIdx) {
            return string(uint(methodIdsOff + methodIdx * 8 + 4));
        }

        private String typeDesc(int typeIdx) {
            if (typeIdx < 0 || typeIdx >= typeIdsSize) return "";
            return string(uint(typeIdsOff + typeIdx * 4));
        }

        private String string(int stringIdx) {
            if (stringIdx < 0 || stringIdx >= stringIdsSize) return "";
            int[] cursor = new int[]{uint(stringIdsOff + stringIdx * 4)};
            uleb(cursor);
            int start = cursor[0];
            int end = start;
            while (end < data.length && data[end] != 0) end++;
            try {
                return new String(data, start, end - start, "UTF-8");
            } catch (Throwable ignored) {
                return "";
            }
        }

        private int uleb(int[] cursor) {
            int result = 0;
            int shift = 0;
            while (cursor[0] < data.length) {
                int b = data[cursor[0]++] & 0xff;
                result |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return result;
        }

        private int ushort(int off) {
            if (off < 0 || off + 1 >= data.length) return 0;
            return (data[off] & 0xff) | ((data[off + 1] & 0xff) << 8);
        }

        private int uint(int off) {
            if (off < 0 || off + 3 >= data.length) return 0;
            return (data[off] & 0xff)
                    | ((data[off + 1] & 0xff) << 8)
                    | ((data[off + 2] & 0xff) << 16)
                    | ((data[off + 3] & 0xff) << 24);
        }
    }

    /**
     * 单个 jar 的家族特征汇总（跨 dex 累积）。判定见 family()。
     *
     * 判据只保留**确定性特征**：家族入口类（DexNative / ProxyOrigin / InitOrigin / GoProxyManager）、
     * 或「Init + killProcess」这套自毁签名。另有运行时探测 probeFamily 作为最终裁决。
     *
     * 曾经有过一条启发式兜底「同时引用 System.load* 与 Runtime.exec」——**已于 09-14 删除**：
     * 它把大量普通 jar 误判进受控路径（ap 真机：458 次误拒载、站点成片不可用），
     * 而它想兜住的"未知变体"本就由 probeFamily 零盲区覆盖，属于纯粹的误伤源。
     */
    private static class Signals {

        /** 精确的 Lcom/github/catvod/spider/Init; 类 */
        boolean initClass;
        /** 家族入口类（DexNative / ProxyOrigin / InitOrigin / GoProxyManager） */
        boolean familyType;
        /** Init.init 命中宿主包名白名单 → 该 jar 允许正常走 Init.init */
        boolean whitelisted;
        /** Init* 类里出现 Process.killProcess（自杀逻辑） */
        boolean killProcess;

        void or(Signals other) {
            if (other == null) return;
            initClass |= other.initClass;
            familyType |= other.familyType;
            whitelisted |= other.whitelisted;
            killProcess |= other.killProcess;
        }

        boolean family() {
            // ① 家族入口类：最精确，跨 dex 汇总后即本次盲区的正解
            if (familyType) return true;
            // ② 宿主白名单：该 jar 认这个包名，可正常 init。排在 ① 之后 —— 白名单只保证不 killProcess，
            //    防不了 killall 互杀（ak 版注释里的同一条结论）
            if (whitelisted) return false;
            // ③ 旧判据（跨 dex 放宽）：Init + killProcess
            return initClass && killProcess;
        }
    }

    private static class Whitelist {

        private static final String AES_KEY = "1234123412341234";
        private final String packageName;

        Whitelist(String packageName) {
            this.packageName = packageName;
        }

        boolean contains(String value) {
            if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(value)) return false;
            if (packageName.equals(value) || containsItem(value)) return true;
            return containsItem(decrypt(value));
        }

        private boolean containsItem(String text) {
            if (TextUtils.isEmpty(text)) return false;
            int start = 0;
            while (start <= text.length()) {
                int end = text.indexOf(',', start);
                if (end < 0) end = text.length();
                if (packageName.equals(text.substring(start, end).trim())) return true;
                start = end + 1;
            }
            return false;
        }

        private String decrypt(String value) {
            if (!isCipherText(value)) return "";
            try {
                byte[] key = AES_KEY.getBytes("UTF-8");
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
                return new String(cipher.doFinal(Base64.decode(value, Base64.DEFAULT)), "UTF-8");
            } catch (Throwable ignored) {
                return "";
            }
        }

        private boolean isCipherText(String value) {
            int len = value.length();
            if (len < 24 || len % 4 != 0) return false;
            int padding = 0;
            for (int i = 0; i < len; i++) {
                char c = value.charAt(i);
                if (c == '=') {
                    padding++;
                    if (i < len - 2) return false;
                } else if (!isBase64(c)) {
                    return false;
                }
            }
            int rawLen = len * 3 / 4 - padding;
            return rawLen > 0 && rawLen % 16 == 0;
        }

        private boolean isBase64(char c) {
            return (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '+'
                    || c == '/';
        }
    }
}
