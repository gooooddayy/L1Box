package com.github.tvbox.osc.util;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.base.App;
import com.squareup.picasso.Cache;
import com.squareup.picasso.Picasso;

import java.lang.reflect.Field;

/**
 * 内存压力释放：把"丢了还能拿回来"的内存让还给系统，换取进程不被低内存杀手干掉。
 *
 * 只动缓存，不动任何业务状态——首页数据、播放进度、订阅配置一概不碰，
 * 所以最坏情况只是图片下次重新解码，功能与数据零损失。
 */
public final class MemoryGuard {

    private MemoryGuard() {
    }

    private static volatile long lastRelease;

    /**
     * 释放图片内存缓存（Picasso + Glide）。
     * 必须在主线程调用：Glide.clearMemory() 有主线程要求，两处调用点都在主线程。
     * 幂等：前后台判定与系统 trim 回调可能几乎同时触发，短时间内重复调用直接忽略。
     */
    public static void releaseImageCache() {
        long now = System.currentTimeMillis();
        if (now - lastRelease < 2000L) return;
        lastRelease = now;
        // 首页大列表（GridAdapter 等）用的是 Picasso。
        // Picasso 官方没有提供清空缓存的方法，cache 字段为包内可见，
        // 只能反射取出 Cache 实例；Cache 接口自带 clear()。
        // 这里是应用自身代码的字段，不涉及系统隐藏 API 限制。
        try {
            Field f = Picasso.class.getDeclaredField("cache");
            f.setAccessible(true);
            Cache cache = (Cache) f.get(Picasso.get());
            if (cache != null) cache.clear();
        } catch (Throwable ignored) {
        }
        // 本地视频/文件夹页缩略图走 Glide，量小但也一并放掉
        try {
            Glide.get(App.getInstance()).clearMemory();
        } catch (Throwable ignored) {
        }
    }
}
