package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.github.tvbox.osc.base.App;

import java.util.Map;

/**
 * 播放进度存储（独立于 CacheManager 的 cache 表）。
 *
 * 为什么不复用 CacheManager：cache 表属于 Room 库（tvbox.vN.db），与历史/收藏同库，
 * 给表加字段必须升库版本；而该库文件名带版本号（AppDataManager.dbPath()），
 * 升版本＝换库文件＝历史收藏全丢。进度数据自成一档后既零迁移风险，
 * 也让续播与缓存/历史彻底解耦。
 *
 * 记录结构：MD5(进度键) → "进度毫秒|写入时间戳"。
 * 进度键由 PlayFragment 提供（源Key＋剧ID＋线路＋集数），不含易变的播放地址，
 * 因此同一条目换解析地址后仍能续播。
 *
 * 上限 {@value #MAX_RECORDS} 条，超出后淘汰最旧的记录，避免长期无限增长。
 * 「看完即删」在入口处完成：progress <= 0 一律删除记录。
 */
public class ProgressStore {

    private static final String SP_NAME = "l1_play_progress";
    /** 最多保留的进度条数，超出按写入时间淘汰最旧的 */
    private static final int MAX_RECORDS = 300;
    private static final char SEP = '|';

    private static SharedPreferences sp() {
        return App.getInstance().getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    /** 保存进度；progress <= 0 表示已看完，直接删除记录 */
    public static void save(String key, long progress) {
        if (TextUtils.isEmpty(key)) return;
        try {
            SharedPreferences p = sp();
            String k = MD5.string2MD5(key);
            if (progress <= 0) {
                if (p.contains(k)) p.edit().remove(k).apply();
                return;
            }
            p.edit().putString(k, progress + String.valueOf(SEP) + System.currentTimeMillis()).apply();
            trimIfNeeded(p);
        } catch (Throwable ignored) {
            // 存储异常不能影响播放主流程
        }
    }

    /** 删除某条进度（重播、跳集时调用） */
    public static void remove(String key) {
        save(key, 0);
    }

    /** 读取进度，无记录或解析失败返回 0 */
    public static long get(String key) {
        if (TextUtils.isEmpty(key)) return 0;
        try {
            String v = sp().getString(MD5.string2MD5(key), null);
            if (v == null) return 0;
            int i = v.indexOf(SEP);
            return Long.parseLong(i < 0 ? v : v.substring(0, i));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 超出上限时淘汰一条最旧记录。
     * SharedPreferences.getAll() 是内存操作；而保存动作只发生在暂停/退出/切后台，
     * 频率极低，直接全量检查即可，无需再维护计数器。
     */
    private static void trimIfNeeded(SharedPreferences p) {
        Map<String, ?> all = p.getAll();
        if (all.size() <= MAX_RECORDS) return;
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, ?> e : all.entrySet()) {
            long t = timeOf(e.getValue());
            if (t < oldestTime) {
                oldestTime = t;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) p.edit().remove(oldestKey).apply();
    }

    private static long timeOf(Object v) {
        if (!(v instanceof String)) return 0;
        String s = (String) v;
        int i = s.indexOf(SEP);
        if (i < 0) return 0;
        try {
            return Long.parseLong(s.substring(i + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
