package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.database.ExoDatabaseProvider;
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider;
import com.google.android.exoplayer2.ext.rtmp.RtmpDataSource;
import com.google.android.exoplayer2.ext.rtmp.RtmpDataSourceFactory;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.ts.DefaultTsPayloadReaderFactory;
import com.google.android.exoplayer2.extractor.ts.TsExtractor;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

public final class ExoMediaSourceHelper {

    private static final String TAG = "L1Cache";

    private static volatile ExoMediaSourceHelper sInstance;

    private final String mUserAgent;
    private final Context mAppContext;
    private OkHttpClient mOkClient = null;
    private Cache mCache;

    /** 命中统计（进程级）：用来核对"磁盘缓存到底有没有被用上"，不看日志就只能靠猜 */
    private static volatile long sCachedReadBytes = 0L;
    private static volatile long sLastHitLogAt = 0L;

    /**
     * 只观测、不改行为：Exo 每次从缓存读到数据都会回调 onCachedBytesRead，
     * 缓存被跳过时回调 onCacheIgnored。两者都只打日志。
     */
    private final CacheDataSource.EventListener mCacheListener = new CacheDataSource.EventListener() {
        @Override
        public void onCachedBytesRead(long cacheSizeBytes, long cachedBytesRead) {
            sCachedReadBytes += cachedBytesRead;
            long now = SystemClock.elapsedRealtime();
            // 节流：这条回调可能每几十毫秒就来一次，不节流会把别的埋点淹掉
            if (now - sLastHitLogAt < 2000L) return;
            sLastHitLogAt = now;
            Log.i(TAG, "命中读取 本段 " + (cachedBytesRead >> 10) + "KB 累计 "
                    + (sCachedReadBytes >> 20) + "MB 缓存库 " + (cacheSizeBytes >> 20) + "MB");
        }

        @Override
        public void onCacheIgnored(int reason) {
            Log.i(TAG, "缓存被忽略 reason=" + reason + "（本次直连，不影响播放）");
        }
    };

    private ExoMediaSourceHelper(Context context) {
        mAppContext = context.getApplicationContext();
        mUserAgent = Util.getUserAgent(mAppContext, mAppContext.getApplicationInfo().name);
    }

    public static ExoMediaSourceHelper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ExoMediaSourceHelper.class) {
                if (sInstance == null) {
                    sInstance = new ExoMediaSourceHelper(context);
                }
            }
        }
        return sInstance;
    }

    public void setOkClient(OkHttpClient client) {
        mOkClient = client;
    }

    public MediaSource getMediaSource(String uri) {
        return getMediaSource(uri, null, false);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers) {
        return getMediaSource(uri, headers, false);
    }

    public MediaSource getMediaSource(String uri, boolean isCache) {
        return getMediaSource(uri, null, isCache);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache) {
        return getMediaSource(uri, headers, isCache, -1);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache, int errorCode) {
        Uri contentUri = Uri.parse(uri);
        if ("rtmp".equals(contentUri.getScheme())) {
            return new ProgressiveMediaSource.Factory(new RtmpDataSource.Factory())
                    .createMediaSource(MediaItem.fromUri(contentUri));
        } else if ("rtsp".equals(contentUri.getScheme())) {
            return new RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(contentUri));
        }
        int contentType = inferContentType(uri);
        // 每次都按"当次请求头"新建数据源工厂：UA / Referer 只作用于本次地址。
        // 改造前是拿一个单例工厂反射改写它的 userAgent 字段 —— A 站点设过 UA 之后，
        // 后面所有站点（包括根本没给 UA 的）都会带着 A 的 UA 去请求，是"换了源就播不出"的来源之一。
        DataSource.Factory factory = isCache ? getCacheDataSourceFactory(headers) : getDataSourceFactory(headers);
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
            return new DefaultMediaSourceFactory(getDataSourceFactory(headers), getExtractorsFactory()).createMediaSource(getMediaItem(uri, errorCode));
        }
        switch (contentType) {
            case C.TYPE_DASH:
                return new DashMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(contentUri));
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(contentUri));
            default:
            case C.TYPE_OTHER:
                // 与上面重试分支保持同一套 TS 提取参数：支持 TS 内 DTS 音轨(HDMV)并把时间戳搜索窗口放大 3 倍，
                // 原先只在 errorCode 重试时才生效，正常播放路径用的是默认 extractors，白白少了这层兼容
                return new ProgressiveMediaSource.Factory(factory, getExtractorsFactory())
                        .createMediaSource(MediaItem.fromUri(contentUri));
        }
    }

    private static MediaItem getMediaItem(String uri, int errorCode) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(Uri.parse(uri.trim().replace("\\", "")));
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
            builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        return builder.build();
    }

    private static synchronized ExtractorsFactory getExtractorsFactory() {
        return new DefaultExtractorsFactory().setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS).setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 3);

    }

    private int inferContentType(String fileName) {
        fileName = fileName.toLowerCase();
        if (fileName.contains(".mpd")) {
            return C.TYPE_DASH;
        } else if (fileName.contains(".m3u8")) {
            return C.TYPE_HLS;
        } else {
            return C.TYPE_OTHER;
        }
    }

    private DataSource.Factory getCacheDataSourceFactory(Map<String, String> headers) {
        if (mCache == null) {
            mCache = newCache();
        }
        if (mCache == null) {
            // 缓存建不起来（目录拿不到 / 索引库打不开 / 目录被另一个实例占用）就直接连 ——
            // 缓存只是锦上添花，绝不能因为它失败导致播不了
            return getDataSourceFactory(headers);
        }
        return new CacheDataSource.Factory()
                .setCache(mCache)
                .setUpstreamDataSourceFactory(getDataSourceFactory(headers))
                .setEventListener(mCacheListener)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    /**
     * 建磁盘缓存库。任何异常都返回 null（调用方退回直连），绝不向外抛。
     *
     * 目录优先外置缓存目录（不占"应用数据"配额、不需要存储权限），拿不到时退到内置缓存目录，
     * 两者都拿不到才放弃。上限取自 {@link ExoCacheConfig#getMaxBytes()}（默认 256MB），超限由 LRU 自动淘汰。
     *
     * 注意：SimpleCache 对同一个目录同时只允许开一个实例，本类又是单例（sInstance），
     * 所以这里必须走"实例字段惰性创建 + 失败置 null"，不要在别处再 new 一个。
     */
    private Cache newCache() {
        try {
            File dir = mAppContext.getExternalCacheDir();
            if (dir == null) dir = mAppContext.getCacheDir();
            if (dir == null) {
                Log.i(TAG, "缓存目录拿不到，本次直连");
                return null;
            }
            // 目录名取 ExoCacheConfig 的常量：清理逻辑（改名/删除）必须与这里的创建点指向同一目录，
            // 两处各写一份字面串则迟早漂移 —— 漂移的后果是"清了但没清到"或"清了却还在写"，都很难查。
            File cacheDir = new File(dir, ExoCacheConfig.CACHE_DIR_NAME);
            Cache cache = new SimpleCache(cacheDir,
                    new LeastRecentlyUsedCacheEvictor(ExoCacheConfig.getMaxBytes()),
                    new StandaloneDatabaseProvider(mAppContext));
            Log.i(TAG, "磁盘缓存已就绪 上限 " + (ExoCacheConfig.getMaxBytes() >> 20)
                    + "MB 目录=" + cacheDir.getAbsolutePath());
            return cache;
        } catch (Throwable th) {
            Log.w(TAG, "磁盘缓存创建失败，本次直连: " + th);
            return null;
        }
    }

    /**
     * Returns a new DataSource factory.
     *
     * @return A new DataSource factory.
     */
    private DataSource.Factory getDataSourceFactory(Map<String, String> headers) {
        return new DefaultDataSource.Factory(mAppContext, createHttpDataSourceFactory(headers));
    }

    /**
     * 按"当次请求头"新建一个 HttpDataSource 工厂（不再复用单例字段，也不再用反射改 UA）。
     *
     * 传入的 User-Agent 走 setUserAgent 而不是塞进默认请求头 —— 两条路径同时带 UA 会重复发送；
     * 且这里是"读"请求头而不是删改，调用方持有的那张表保持原样（重试时还能再取到 UA）。
     * 其余请求头 trim 后作为本次的默认请求头。
     */
    private DataSource.Factory createHttpDataSourceFactory(Map<String, String> headers) {
        OkHttpDataSource.Factory f = new OkHttpDataSource.Factory(mOkClient).setUserAgent(mUserAgent);
        try {
            if (headers != null && !headers.isEmpty()) {
                HashMap<String, String> props = new HashMap<>();
                for (String k : headers.keySet()) {
                    String v = headers.get(k);
                    if (v == null) continue;
                    if ("User-Agent".equalsIgnoreCase(k)) {
                        if (!TextUtils.isEmpty(v.trim())) f.setUserAgent(v.trim());
                        continue;
                    }
                    props.put(k, v.trim());
                }
                if (!props.isEmpty()) f.setDefaultRequestProperties(props);
            }
        } catch (Throwable th) {
            // 请求头只影响兼容性，这里出问题不能拖垮起播：退回默认 UA 继续
        }
        return f;
    }

    public void setCache(Cache cache) {
        this.mCache = cache;
    }

}