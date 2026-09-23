package com.github.catvod.crawler;

import android.content.Context;
import android.os.SystemClock;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.MD5;
import com.lzy.okgo.OkGo;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import okhttp3.Response;

public class JarLoader {
    private ConcurrentHashMap<String, DexClassLoader> classLoaders = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Method> proxyMethods = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    /** Guard 类 jar 的就绪检查缓存：true=内部原生 loader 未就绪（创建实例必原生崩溃），false=可安全创建 */
    private ConcurrentHashMap<DexClassLoader, Boolean> guardBrokenCache = new ConcurrentHashMap<>();
    /** 正在做原生初始化的加固 jar（key）。此窗口内其原生状态未建立，任何 proxy 调用都会 abort 杀进程 */
    private final Set<String> initializingJarKeys = ConcurrentHashMap.newKeySet();
    /** 加固 jar 初始化刚完成后的静默截止时刻（elapsedRealtime）。jar 的 Go 代理在 init 返回后才真正 ready */
    private final ConcurrentHashMap<String, Long> proxyQuietUntil = new ConcurrentHashMap<>();
    /**
     * 静默期长度。真机日志逐条配对实测（binary start begin → health started=true）：
     * 544 / 564 / 573 / 560 / 545 / 566 ms，仅首次解压那次 1753ms。
     * 取 800ms 覆盖常规收尾；早先按 1.4~1.6 秒的估算定 2000ms 属过度保护，会让首轮多丢结果。
     */
    private static final long PROXY_QUIET_MS = 800L;
    /** 门禁命中时返回的响应（每次新建，避免同一个流实例被多个响应共用） */
    private static Object[] proxyNotReady() {
        return new Object[]{503, "text/plain; charset=utf-8", new ByteArrayInputStream(new byte[0])};
    }

    /**
     * 进程级单飞的路由账本（2026-09-13）。static：主 jarLoader 与 homeJarLoader 两个实例
     * 都可能装载加固 jar，而 /proxy 只经主实例的 proxyInvoke 路由，账本必须跨实例共享。
     * key = MD5(jarUrl)，全局唯一，无歧义。
     */
    /** 本进程已成功装载的加固 jar key（含持有者）。与装载复用表同生命周期，不再随配置重载清空 */
    private static final Set<String> protectedJarKeys = ConcurrentHashMap.newKeySet();

    /**
     * 装载事件序号（2026-09-14 冷启动首搜空源·第二根因）。
     *
     * 每一次 jar 装载**有结论**（命中复用 / 缓存命中 / 下载装载成功 / 失败）自增。
     * 搜索侧在发起搜索时快照一次，收尾时再读一次：
     *   "序号变了" ⇒ 本轮搜索与某次 jar 初始化**重叠**了 ⇒ 本轮的 0 结果不可信
     *   （实测：jar 自己的 searchContent 在代理/配置未就绪时抛 NPE 或返回空串，
     *    站点就被静默记成 0 条，页面直接"暂无数据"；再搜一次 jar 已就绪 → 结果正常）。
     * 于是搜索侧据此**自动重搜**，而不是把这个假空态摆给用户看。
     * static：主 loader 与 home loader 两个实例的装载都要记进同一本账。
     */
    private static final java.util.concurrent.atomic.AtomicLong LOAD_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /** 装载序号现值（单调不减；只用于"变没变"的比较，不解释绝对值） */
    public static long loadSeq() {
        return LOAD_SEQ.get();
    }

    /**
     * 进程内 jar 装载复用表（2026-09-14 批次二）。key = jar 绝对路径（唯一、稳定、跨实例共享）。
     *
     * **为什么必须复用**：`DexClassLoader` 每重建一代，jar 里的类就是新一代的 Class 对象，
     * 而进程内已被 dlopen 的原生库不随之卸载 —— native 侧记着的 jmethodID 指向上一代，
     * 下一次调用即 `mid == null` → SIGABRT。这正是真机"退出→重进→立刻搜索"大概率崩溃的机制
     * （"退出"不杀进程，"重进"复用进程重新 loadConfig）。复用同一个 loader，类不换代，
     * native 与 Java 侧身份始终一致，这条崩溃路径从机制上消失。
     *
     * **顺带收益（这才是提速的那部分）**：不再重复抽解原生库（libdecjni.so 每代随机名，会话内
     * 已见 15+ 个）、不再重复 `killall` 已有代理进程（降到 0~1 次）、不再重复下载与全量 dex 扫描。
     * 首搜与"重进后的首搜"都直接命中，没有现场排队。
     *
     * **复用条件是路径 + 指纹（md5）双匹配**：服务端换了 jar（md5 变）就丢弃重建，
     * 「下拉刷新能让 jar 更新」这条铁律不被破坏。
     *
     * static 且按 jar 路径索引：主 jarLoader 与 homeJarLoader 共享同一份账本。al 会话 5 次崩溃的
     * 教训就是"两个实例各自记账必然对不上"，所以账本只留一份。
     *
     * 容量上限 + LRU：让低端机的类内存有界（首轮搜索实测会创建 26 个 loader 量级）。
     * 淘汰时跳过当前持有者 —— 它的代理进程还在跑，丢了它就再没人能接管那个代理。
     */
    private static final int REUSE_CAPACITY = 20;
    private static final java.util.LinkedHashMap<String, LoadedJar> REUSED =
            new java.util.LinkedHashMap<>(16, 0.75f, true);

    /** 一次成功装载的全部可复用产物 */
    private static class LoadedJar {
        /** 装载时的内容指纹（订阅给的 md5；订阅没给就用磁盘文件 md5） */
        final String fingerprint;
        final DexClassLoader loader;
        /** 是否家族（加固）jar —— 复用时据此恢复路由账本登记 */
        final boolean family;
        /** 该 jar 的 Proxy.proxy 方法；取不到时为 null（与首次装载时一致） */
        final Method proxyMethod;

        LoadedJar(String fingerprint, DexClassLoader loader, boolean family, Method proxyMethod) {
            this.fingerprint = fingerprint;
            this.loader = loader;
            this.family = family;
            this.proxyMethod = proxyMethod;
        }
    }

    private final ProtectedInitJar protectedInitJar = new ProtectedInitJar();
    /** 同一 jar 的加载锁：防止多线程并发重复下载/创建 classloader/初始化（参考上游 TVBoxOS） */
    private final ConcurrentHashMap<String, Object> jarLocks = new ConcurrentHashMap<>();
    private volatile String recentJarKey = "";

    public JarLoader() {
    }

    private Object lockFor(String key) {
        Object lock = jarLocks.get(key);
        if (lock != null)
            return lock;
        Object created = new Object();
        Object old = jarLocks.putIfAbsent(key, created);
        return old == null ? created : old;
    }

    /**
     * 不要在主线程调用我
     *
     * @param cache
     */
    public boolean load(String cache) {
        spiders.clear();
        recentJarKey = "main";
        proxyMethods.clear();
        classLoaders.clear();
        // 注意这里**不动**任何进程级状态（单飞闸门 / 持有者 / 路由账本）。jar 装载自批次二起
        // 跨配置重载复用同一个 DexClassLoader，而 native 状态的生命周期正是那个 ClassLoader 的
        // 生命周期 —— 配置重载时重置它们会造出"账本没了、代理还在跑、又没人接手"的空窗。
        // 换届只在 ProtectedInitJar.init 成功、且该 jar 确实重启了共享代理时发生（occupyProxy）。
        return loadClassLoader(cache, "main");
    }

    private boolean loadClassLoader(String jar, String key) {
        boolean success = false;
        try {
            // 中和 jar 内 System.exit/Process.killProcess 等自杀调用（加固 jar 校验宿主失败时
            // 会静默 exit 杀死整个进程，SecurityManager 在 Android 14+ 已被系统禁用，
            // 只能在 dex 层把自杀调用变成可捕获的 NoSuchMethodError），并消除 W^X 可写 dex 告警
            File jarFile = new File(jar);
            JarKillNeutralizer.patch(jarFile);
            jarFile.setReadOnly();
            // 指纹必须在 patch 之后取：它要代表"这个 loader 装进去的到底是哪份内容"，
            // 与 loadJarInternal 的复用查询同一口径（中和会改写文件，patch 前取指纹会永远对不上，
            // 复用一次都命中不了）。
            String fingerprint = fileFingerprint(jarFile);
            LoadedJar reused = reuse(jar, fingerprint);
            if (reused != null) {
                adopt(key, reused);
                return true;
            }
            File cacheDir = new File(App.getInstance().getCacheDir().getAbsolutePath() + "/catvod_csp");
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            ClassLoader bridgeLoader = JarToastBlockLoader.create(App.getInstance().getClassLoader());
            DexClassLoader classLoader = new DexClassLoader(jar, cacheDir.getAbsolutePath(), null, bridgeLoader);
            // 等待部分设备的异步 dex 加载完成，仅拿到 Init 类（loadClass 只链接不执行代码）
            Class classInit = null;
            int count = 0;
            while (count < 5) {
                try {
                    classInit = classLoader.loadClass("com.github.catvod.spider.Init");
                    if (classInit != null)
                        break;
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                count++;
            }
            boolean family = false;
            if (classInit != null) {
                // 家族判定 = 静态判据 ‖ 运行时权威探测（2026-09-14 an 批次一，见 isFamily）
                family = isFamily(classLoader, jar, key);
                if (family) {
                    // 加固 jar：其 Init.init 内含 killProcess 自杀逻辑，不能无条件直接调用，
                    // 交 ProtectedInitJar 走受控路径（手绑优先，失败则交还 jar 自身入口并在锁内串行）。
                    // 详细取舍见 ProtectedInitJar.init 的注释 —— 那里有 ap 版 458 次误拒载的行号级实证。
                    initializingJarKeys.add(key);
                    try {
                        success = protectedInitJar.init(classInit, key);
                    } finally {
                        initializingJarKeys.remove(key);
                        // init 返回 ≠ 代理可用：Go 代理是 init 之后才真正监听端口并写端口记录的，
                        // 这段时间内进来的 /proxy 会打到尚未就绪的原生对象上（obj==null → SIGABRT）。
                        // 记一段静默期，期间由 proxyInvoke 提前挡掉，不用 try-catch 兜（兜不住）。
                        if (success) proxyQuietUntil.put(key, SystemClock.elapsedRealtime() + PROXY_QUIET_MS);
                    }
                } else {
                    Method method = classInit.getMethod("init", Context.class);
                    // Context 桥接：加固 jar 用 context.getClassLoader() 建内层加载器，
                    // 桥接后内层 payload 的 Toast/Process 同样走拦截链
                    method.invoke(null, new JarContextBridge(App.getInstance(), bridgeLoader));
                    success = true;
                }
            }
            if (success) {
                System.out.println("自定义爬虫代码加载成功!");
                classLoaders.put(key, classLoader);
                if (family) {
                    // 进程级单飞路由账本：登记加固 jar。非持有者的加固 jar 其 Proxy 类绝不可被调用
                    // （原生代理对象恒为 null，打进去就是 DexNative obj==null → SIGABRT），由 proxyInvoke 事前拦截。
                    // 注意这里不再记"持有者 key"—— 那份身份由 ProtectedInitJar 托管，避免双份状态各自过期。
                    protectedJarKeys.add(key);
                }
                Method proxyMethod = null;
                try {
                    Class proxy = classLoader.loadClass("com.github.catvod.spider.Proxy");
                    proxyMethod = proxy.getMethod("proxy", Map.class);
                    proxyMethods.put(key, proxyMethod);
                } catch (Throwable th) {

                }
                // 记入复用表：本进程内下一次需要这个 jar 时直接沿用同一个 loader，不再换代
                remember(jar, new LoadedJar(fingerprint, classLoader, family, proxyMethod));
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return success;
    }

    /**
     * 家族 jar 判定（2026-09-14 an 批次一）：**静态判据 ‖ 运行时权威探测**，任一命中即家族。
     *
     * 为什么必须加运行时那一路：静态判据（ProtectedInitJar.check）得自己解析 dex 结构，
     * 遇到「壳 + payload 多 dex」的加固 jar 必然漏判 —— 真机实证
     * `files/0b9565d6a6b5ecd989a7de9e1d50677e.jar` 的 Init 与 DexNative 分处不同 dex，
     * 逐 dex 判定恒为 false，于是走了普通分支**直接调用 Init.init**：内部先 killall 掉已在跑的
     * 共享 Go 代理、再启动自己的 → 原生 abort，整进程被杀（Java 层 catch 拿不到控制权）。
     * 崩溃栈里 JarLoader.loadClassLoader 的行号，正是普通分支那一次 invoke。
     *
     * 运行时探测是权威判据：`loadClass` 是 ART 自己的解析结果，跨 dex、抗壳、抗混淆，
     * 且只链接不初始化（零副作用）。
     *
     * 判定为家族之后**不再有"拒载站点"这个结局**（ap 版 458 次误拒载的教训）：受控路径手绑失败时
     * 会交还 jar 自身入口重试，两条都不成才降级。
     */
    private boolean isFamily(DexClassLoader classLoader, String jar, String key) {
        if (protectedInitJar.check(jar)) return true;
        if (ProtectedInitJar.probeFamily(classLoader)) {
            System.out.println("运行时探测命中家族 jar（静态判据漏判，已改走受控路径）：" + key);
            return true;
        }
        return false;
    }

    /**
     * 装载入口：无论最终是命中复用、缓存命中、下载成功还是失败，出去时都记一次"有结论"，
     * 供搜索侧判断本轮 0 结果是否可信（见 LOAD_SEQ 注释）。用 finally 是为了失败也计数 ——
     * 失败同样意味着"本轮和初始化撞上了"，那次 0 结果一样不可信。
     */
    private DexClassLoader loadJarInternal(String jar, String md5, String key) {
        try {
            return doLoadJarInternal(jar, md5, key);
        } finally {
            LOAD_SEQ.incrementAndGet();
        }
    }

    private DexClassLoader doLoadJarInternal(String jar, String md5, String key) {
        synchronized (lockFor(key)) {
            if (classLoaders.contains(key))
                return classLoaders.get(key);
            File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + key + ".jar");
            String path = cache.getAbsolutePath();
            // 复用优先（批次二）：先做中和（幂等）再取指纹，指纹才等于"真正会被加载的那个文件"。
            // 内容没变就沿用同一个 DexClassLoader —— 不重建＝类不换代＝native 侧 jmethodID 不失配
            // （治 mid==null），同时省掉重新下载、全量 dex 扫描、抽解原生库与 killall 代理。
            // 放在 md5 缓存校验之前是必须的：中和会改写缓存文件，订阅声明的 md5 与它天然对不上，
            // 不先查复用就会每次都走"重新下载"。
            JarKillNeutralizer.patch(cache);
            LoadedJar reused = reuse(path, fileFingerprint(cache));
            if (reused != null) {
                adopt(key, reused);
                return reused.loader;
            }
            if (!md5.isEmpty()) {
                if (cache.exists() && MD5.getFileMd5(cache).equalsIgnoreCase(md5)) {
                    loadClassLoader(path, key);
                    return classLoaders.get(key);
                }
            }
            try {
                cache.delete(); // 缓存文件可能被 setReadOnly 标记，先删再写保证版本更新可覆盖
                Response response = OkGo.<File>get(jar).execute();
                InputStream is = response.body().byteStream();
                OutputStream os = new FileOutputStream(cache);
                try {
                    byte[] buffer = new byte[2048];
                    int length;
                    while ((length = is.read(buffer)) > 0) {
                        os.write(buffer, 0, length);
                    }
                } finally {
                    try {
                        is.close();
                        os.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                loadClassLoader(path, key);
                return classLoaders.get(key);
            } catch (Throwable e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    // ============ 装载复用（2026-09-14 批次二）：一次装载，整进程服役 ============

    /**
     * 磁盘文件内容指纹（md5，小写）。文件不存在或算不出时返回空串。
     *
     * 口径固定为"文件内容"而不是"订阅声明的 md5"：显式声明的 md5 在中和器改写缓存文件之后
     * 就再也对不上了，用它当复用依据等于永远不复用。文件内容才是"这个 loader 装的是什么"的
     * 唯一可靠描述，也天然满足"服务端换版必须重建"（换版＝内容变＝指纹变）。
     */
    private static String fileFingerprint(File file) {
        if (file == null || !file.exists() || file.length() <= 0) return "";
        String value = MD5.getFileMd5(file);
        return value == null ? "" : value.toLowerCase();
    }

    /** 复用命中查询；指纹变了（jar 换版）即作废旧代，保证 jar 更新能落地 */
    private LoadedJar reuse(String path, String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) return null;
        LoadedJar entry;
        synchronized (REUSED) {
            entry = REUSED.get(path);
            if (entry == null) return null;
            if (fingerprint.equals(entry.fingerprint)) return entry;
            REUSED.remove(path);
        }
        ProtectedInitJar.forgetLoader(entry.loader);
        return null;
    }

    /** 复用命中：把已装载的 loader 挂回本实例的路由表（不重新装载、不碰 native） */
    private void adopt(String key, LoadedJar entry) {
        classLoaders.put(key, entry.loader);
        if (entry.proxyMethod != null) proxyMethods.put(key, entry.proxyMethod);
        if (entry.family) protectedJarKeys.add(key);
    }

    /** 装载成功：记入复用表，并按 LRU 上限回收最久未用的条目 */
    private static void remember(String path, LoadedJar entry) {
        synchronized (REUSED) {
            REUSED.put(path, entry);
            if (REUSED.size() <= REUSE_CAPACITY) return;
            ClassLoader holder = ProtectedInitJar.proxyHolderLoader();
            java.util.Iterator<Map.Entry<String, LoadedJar>> it = REUSED.entrySet().iterator();
            while (it.hasNext() && REUSED.size() > REUSE_CAPACITY) {
                LoadedJar candidate = it.next().getValue();
                // 持有者的 loader 不能丢：它的代理进程还在跑，丢了它就再没人能接管那个代理
                if (candidate.loader == holder) continue;
                it.remove();
                ProtectedInitJar.forgetLoader(candidate.loader);
            }
        }
    }

    public Spider getSpider(String key, String cls, String ext, String jar) {
        String clsKey = cls.replace("csp_", "");
        String jarUrl = "";
        String jarMd5 = "";
        String jarKey = "";
        if (jar.isEmpty()) {
            jarKey = "main";
        } else {
            String[] urls = jar.split(";md5;");
            jarUrl = urls[0];
            jarKey = MD5.string2MD5(jarUrl);
            jarMd5 = urls.length > 1 ? urls[1].trim() : "";
        }
        recentJarKey = jarKey;
        if (spiders.containsKey(key))
            return spiders.get(key);
        DexClassLoader classLoader = null;
        if (jarKey.equals("main"))
            classLoader = classLoaders.get("main");
        else {
            classLoader = loadJarInternal(jarUrl, jarMd5, jarKey);
        }
        if (classLoader == null)
            return new SpiderNull();
        try {
            // Guard 类加固 jar 防护：此类 jar 的实例构造会走原生 DexNative 引导，
            // 若其内部 loader 未就绪（如宿主签名不在 jar 白名单），原生层会直接 abort 杀死进程
            // （SIGABRT 无法被 Java try-catch 捕获）。创建前先检查，未就绪则降级为空站点跳过。
            if (isGuardJarBroken(classLoader))
                return new SpiderNull();
            Spider sp = (Spider) classLoader.loadClass("com.github.catvod.spider." + clsKey).newInstance();
            sp.init(App.getInstance(), ext);
//            if (!jar.isEmpty()) {
//                sp.homeContent(false); // 增加此行 应该可以解决部分写的有问题源的历史记录问题 但会增加这个源的首次加载时间 不需要可以已删掉
//            }
            spiders.put(key, sp);
            return sp;
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return new SpiderNull();
    }

    /**
     * Guard 类加固 jar 检测：jar 内含 DexNative 类即为原生引导型 jar。
     * 其 Init 单例持有 DexClassLoader 字段（由 DexNative.getLoader 初始化），
     * 该字段为 null 时创建任何 Guard spider 都会在原生层 abort 杀进程，必须跳过。
     * 注意字段可能是实例字段（Init.get() 单例持有）也可能是静态字段，两者都要查。
     * 普通 jar（无 DexNative 类）不受影响，直接放行。
     */
    private boolean isGuardJarBroken(DexClassLoader classLoader) {
        Boolean cached = guardBrokenCache.get(classLoader);
        if (cached != null)
            return cached;
        boolean broken = false;
        try {
            classLoader.loadClass("com.github.catvod.spider.DexNative");
            // 能加载 DexNative → 是 Guard 型 jar，检查 Init 的 DexClassLoader 是否就绪
            Class<?> initCls = classLoader.loadClass("com.github.catvod.spider.Init");
            Object singleton = initCls.getMethod("get").invoke(null);
            for (java.lang.reflect.Field f : initCls.getDeclaredFields()) {
                if (!"dalvik.system.DexClassLoader".equals(f.getType().getName()))
                    continue;
                f.setAccessible(true);
                Object value = java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        ? f.get(null) : (singleton != null ? f.get(singleton) : null);
                if (value == null) {
                    broken = true;
                    break;
                }
            }
        } catch (ClassNotFoundException notGuard) {
            broken = false; // 无 DexNative 类 → 普通 jar
        } catch (Throwable th) {
            th.printStackTrace();
            broken = false;
        }
        guardBrokenCache.put(classLoader, broken);
        if (broken)
            System.out.println("Guard jar 内部 loader 未就绪，本会话内跳过其全部站点");
        return broken;
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {        try {
            DexClassLoader classLoader = classLoaders.get("main");
            String clsKey = "Json" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            Class jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        try {
            DexClassLoader classLoader = classLoaders.get("main");
            String clsKey = "Mix" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            Class jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, name, flag, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    public Object[] proxyInvoke(Map params) {
        String key = recentJarKey;
        // 原生就绪门禁（2026-09-13）：加固 jar 的本地代理尚未就绪时，jar 内部会把 null 对象
        // 交给 DexNative.proxyInvoke，native 直接 abort 整个进程（JNI DETECTED ERROR，Java 层 catch 不到）。
        // 所以这里只能"事前拒绝"：初始化窗口内、以及初始化刚完成的一小段静默期内一律不碰 native，
        // 让这一次 /proxy 失败即可 —— 少一次响应可以忍，进程被杀不能忍。
        //
        // **进程级**那一半（2026-09-14 aq 补上）：加固 jar 初始化时会把在跑的共享 Go 代理 killall 掉
        // 再启动自己的，而 /proxy 是按 recentJarKey 路由 —— 若此刻进来的请求属于**另一个** jar，
        // 下面按 key 的判断对它完全无效，它会打到正被 killall 的原生对象上 → obj==null → SIGABRT。
        // 所以门禁必须是"全进程任何一个 jar 在初始化 → 所有 /proxy 一律拒绝"。
        if (ProtectedInitJar.nativeInitInProgress()) return proxyNotReady();
        if (isNativeWindow(key)) return proxyNotReady();
        // 进程级单飞路由（2026-09-13）：/proxy 只准调用"代理持有者"的 Proxy 类。
        // 非 holder 的加固 jar 跳过了 startGoProxy，其原生代理对象恒为 null，打进去必 abort；
        // 一律事前 503（普通 jar 不经 startGoProxy，无此风险，行为与从前完全一致）。
        // 持有者身份（含它的 key）自批次三起由 ProtectedInitJar 在原生锁内单一维护，
        // 不存在"身份换了、key 还没换"这类双份状态各自的过期窗口。
        if (protectedJarKeys.contains(key) && !key.equals(ProtectedInitJar.proxyHolderKey())) return proxyNotReady();
        try {
            Method proxyFun = proxyMethods.get(key);
            if (proxyFun != null) {
                // 身份终审（2026-09-14，am）+ fail-safe 探测（an 批次一）：按 Method 的 ClassLoader
                // 身份做最终裁决，独立于一切可被 load() 清空/过期的 key 账本。家族 jar 中只有当前
                // 持有者的 Proxy 可以碰 native —— 其余一律事前 503（其原生代理对象要么恒 null、
                // 要么已被新 holder 的 killall 杀掉，打进去就是 SIGABRT）。
                //
                // 这里比 am 多加了一条 **运行时探测**：这是全部防线里唯一不依赖"静态判据正确"的一层。
                // 即使某个未知变体骗过了静态判据、走了普通路径装载，只要它不是 holder，就依然打不到 native。
                ClassLoader mcl = proxyFun.getDeclaringClass().getClassLoader();
                if ((ProtectedInitJar.isFamilyClassLoader(mcl) || ProtectedInitJar.probeFamily(mcl))
                        && !ProtectedInitJar.isHolderClassLoader(mcl))
                    return proxyNotReady();
                return (Object[]) proxyFun.invoke(null, params);
            }
        } catch (Throwable th) {

        }
        return null;
    }

    /**
     * 是否处于"加固 jar 原生状态未稳"的窗口内。
     * 只有加固 jar 才会进这两个集合，普通 jar 恒返回 false → 行为与改造前完全一致。
     */
    private boolean isNativeWindow(String key) {
        if (key == null || key.isEmpty()) return false;
        if (initializingJarKeys.contains(key)) return true;
        Long until = proxyQuietUntil.get(key);
        if (until == null) return false;
        if (SystemClock.elapsedRealtime() >= until) {
            proxyQuietUntil.remove(key, until);   // 过期即摘除，之后零开销
            return false;
        }
        return true;
    }
}
