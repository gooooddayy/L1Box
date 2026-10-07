package com.github.tvbox.osc.util;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.google.gson.Gson;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 首页冷启动缓存：把最近一次成功渲染的首页（分类 tab + 各分类第一页影片列表）
 * 存到本地 JSON，下次启动先秒显缓存内容，订阅/jar/站点池照常在后台刷新。
 * 缓存绑定线路地址（apiUrl），换线路自动失效；每次拉到新数据覆盖式保存，
 * 空源存空标记。全部走主线程调用 + 单线程异步落盘，读写零阻塞。
 */
public class HomeCache {
    /** 每个 tab 最多缓存的条目数（第一页通常 20~30 条，留余量） */
    private static final int MAX_PER_TAB = 60;
    private static final long MAX_FILE = 4L * 1024 * 1024;
    /** 落盘合并窗口：窗口内的多次变更合并为一次序列化+写入 */
    private static final long WRITE_MERGE_MS = 600L;

    public static class Tab {
        public String id;
        public String name;
        public String flag;
        public List<Movie.Video> videos;
    }

    public static class Data {
        public String apiUrl = "";
        public String homeSiteKey = "";
        public boolean empty;
        public List<Tab> tabs = new ArrayList<>();
    }

    /** 驻留内存态：首次读盘后常驻，后续读写均基于它，避免重复 IO */
    private static Data mem;

    /**
     * 落盘兜底快照（tab id → 影片列表）。cz（2026-10-07 实证修复）新增。
     *
     * 为什么需要它：刷新时 {@link #clearVideos()} 会清掉内存里的影片列表 —— 这是**必须的**，
     * 否则各分类会拿旧数据秒显后就认为"已有数据"，不再重新拉取，下拉刷新等于没刷。
     * 但原实现顺手把"清空"也落了盘，于是「清空已写盘、新数据还没回来」这个中间态被持久化：
     * 刷新途中被杀进程，磁盘上就留下「骨架有、影片全空」的半截缓存，下次冷启动误判缓存可用，
     * 首页永久停在"暂无数据"（真机日志 17:52:34.719 清空 → 17:52:36.047 被杀 → 17:52:37.059 有影片的=0）。
     *
     * 所以清空时先把影片**备份到这里**，落盘时再补回快照：内存照样是空的（刷新行为不变），
     * 磁盘上则始终是「上一次完整缓存」。换线路（apiUrl 变化）或空源时整体作废，绝不串线路数据。
     */
    private static final Map<String, List<Movie.Video>> keepVideos = new LinkedHashMap<>();

    private static final Gson GSON = new Gson();
    // 统一线程池入口：命名 + daemon + 空闲回收，避免进程里再多一个永不释放的匿名线程池
    private static final ExecutorService POOL = L1Executors.fixed("l1box-homecache", 1);
    /** 待落盘快照与"已排一次写"标记，用于合并写入 */
    private static final AtomicReference<Data> pending = new AtomicReference<Data>();
    private static final AtomicBoolean scheduled = new AtomicBoolean(false);

    private static File file() {
        return new File(App.getInstance().getFilesDir(), "home_cache.json");
    }

    /** 读缓存（首次读盘，之后驻留内存；损坏/缺失返回空 Data，不抛错） */
    public static synchronized Data get() {
        if (mem != null) return mem;
        File f = file();
        try {
            if (f.exists() && f.length() > 0 && f.length() < MAX_FILE) {
                FileReader r = new FileReader(f);
                mem = GSON.fromJson(r, Data.class);
                r.close();
            }
        } catch (Throwable ignored) {
            // 缓存损坏（写盘被中断/存储异常/文件被改）时删掉它：否则每次冷启动都要白解析一遍。
            // 删除失败也不影响功能，只是下次再试一次。
            try {
                f.delete();
            } catch (Throwable ignore) {
            }
        }
        if (mem == null) mem = new Data();
        return mem;
    }

    /** 分类骨架渲染成功后保存（合并已归档的各 tab 影片列表）；空分类不覆盖缓存 */
    public static synchronized void saveSkeleton(String apiUrl, String homeSiteKey, List<MovieSort.SortData> sorts) {
        if (sorts == null || sorts.isEmpty()) return;
        Data d = get();
        // 换线路：兜底快照里是旧线路的影片，绝不能补进新线路的缓存
        String newUrl = apiUrl == null ? "" : apiUrl;
        String oldUrl = d.apiUrl == null ? "" : d.apiUrl;
        if (!newUrl.equals(oldUrl)) keepVideos.clear();
        d.apiUrl = newUrl;
        d.homeSiteKey = homeSiteKey == null ? "" : homeSiteKey;
        d.empty = false;
        Map<String, Tab> old = new LinkedHashMap<>();
        if (d.tabs != null) for (Tab t : d.tabs) old.put(t.id, t);
        List<Tab> tabs = new ArrayList<>();
        for (MovieSort.SortData s : sorts) {
            if (s == null || s.id == null) continue;
            Tab t = old.containsKey(s.id) ? old.get(s.id) : new Tab();
            t.id = s.id;
            t.name = s.name;
            t.flag = s.flag;
            tabs.add(t);
        }
        d.tabs = tabs;
        writeAsync(d);
    }

    /** GridFragment 第一页数据回来后归档该分类的影片列表 */
    public static synchronized void putVideos(String sortId, List<Movie.Video> videos) {
        if (sortId == null || videos == null || videos.isEmpty()) return;
        Data d = get();
        if (d.tabs == null) return;
        for (Tab t : d.tabs) {
            if (sortId.equals(t.id)) {
                t.videos = videos.size() > MAX_PER_TAB ? new ArrayList<>(videos.subList(0, MAX_PER_TAB)) : new ArrayList<>(videos);
                // 这个分类的新数据已经到了，兜底快照不再需要它
                keepVideos.remove(sortId);
                writeAsync(d);
                return;
            }
        }
    }

    /**
     * 真拉首页前清空**内存**里的旧影片列表（让各分类重新拉取，而不是拿旧数据秒显后就不管了）。
     *
     * ⚠️ cz（2026-10-07 实证修复）：这里**只清内存、不再落盘**，清掉的影片先备份到 {@link #keepVideos}，
     * 落盘时由 {@link #snapshotOf} 补回。原实现顺手 writeAsync 落盘，导致"清空已写盘、新数据还没回来"
     * 这个中间态被持久化 —— 刷新途中被杀就会留下半截缓存。
     */
    public static synchronized void clearVideos() {
        Data d = get();
        if (d.tabs == null) return;
        keepVideos.clear();
        for (Tab t : d.tabs) {
            if (t.videos != null && !t.videos.isEmpty()) keepVideos.put(t.id, t.videos);
            t.videos = null;
        }
    }

    /** 空源状态落盘：下次冷启动直接显示"添加订阅源"引导 */
    public static synchronized void saveEmpty(String apiUrl) {
        Data d = get();
        d.apiUrl = apiUrl == null ? "" : apiUrl;
        d.empty = true;
        d.tabs = new ArrayList<>();
        keepVideos.clear();
        writeAsync(d);
    }

    /** 取某分类的缓存影片列表（无则返回 null，调用方走网络模式） */
    public static synchronized List<Movie.Video> videosOf(String sortId) {
        Data d = get();
        if (d.tabs == null) return null;
        for (Tab t : d.tabs) {
            if (sortId != null && sortId.equals(t.id)) return t.videos;
        }
        return null;
    }

    /**
     * 异步落盘（合并写入）。
     *
     * 首页刷新时各 tab 的数据是陆续回来的，原实现每个 tab 到达就做一次全量 JSON
     * 序列化 + 写文件（30 个 tab 就是 30 次），在低配机上构成真实的 IO 风暴。
     * 现在改为：写入请求只登记最新快照，WRITE_MERGE_MS 窗口内的多次变更合并成一次落盘；
     * 窗口结束后若又有新快照，会自动追加一轮，保证最终状态一定落盘。
     */
    private static void writeAsync(final Data d) {
        pending.set(snapshotOf(d));
        scheduleWrite();
    }

    private static void scheduleWrite() {
        if (!scheduled.compareAndSet(false, true)) return;
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(WRITE_MERGE_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                Data s = pending.getAndSet(null);
                scheduled.set(false);
                if (s != null) writeNow(s);
                if (pending.get() != null) scheduleWrite();
            }
        });
    }

    /**
     * 先做快照拷贝再异步落盘，避免主线程继续改对象导致 JSON 半新半旧。
     *
     * 内存里某分类的影片被 {@link #clearVideos()} 清空（刷新进行中）时，用 {@link #keepVideos}
     * 的备份补齐再落盘 —— 磁盘上永不出现「骨架有、影片全空」的半截缓存。
     */
    private static Data snapshotOf(Data d) {
        Data snapshot = new Data();
        snapshot.apiUrl = d.apiUrl;
        snapshot.homeSiteKey = d.homeSiteKey;
        snapshot.empty = d.empty;
        snapshot.tabs = new ArrayList<>();
        if (d.tabs != null) {
            for (Tab t : d.tabs) {
                Tab n = new Tab();
                n.id = t.id;
                n.name = t.name;
                n.flag = t.flag;
                if (t.videos != null && !t.videos.isEmpty()) {
                    n.videos = new ArrayList<>(t.videos);
                } else if (keepVideos.containsKey(t.id)) {
                    n.videos = new ArrayList<>(keepVideos.get(t.id));
                }
                snapshot.tabs.add(n);
            }
        }
        return snapshot;
    }

    /** tmp + rename 防半截文件 */
    private static void writeNow(Data snapshot) {
        try {
            File f = file();
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileWriter w = new FileWriter(tmp);
            w.write(GSON.toJson(snapshot));
            w.close();
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Throwable ignored) {
        }
    }
}
