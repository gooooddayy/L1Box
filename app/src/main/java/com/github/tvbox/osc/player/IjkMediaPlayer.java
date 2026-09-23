package com.github.tvbox.osc.player;

import android.content.Context;
import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.MD5;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;
import tv.danmaku.ijk.media.player.misc.IjkTrackInfo;
import xyz.doikki.videoplayer.ijk.IjkPlayer;

public class IjkMediaPlayer extends IjkPlayer {

    private IJKCode codec = null;

    public IjkMediaPlayer(Context context, IJKCode codec) {
        super(context);
        this.codec = codec;
    }

    @Override
    public void setOptions() {
        super.setOptions();
        IJKCode codecTmp = this.codec == null ? ApiConfig.get().getCurrentIJKCode() : this.codec;
        LinkedHashMap<String, String> options = codecTmp.getOption();
        if (options != null) {
            for (String key : options.keySet()) {
                String value = options.get(key);
                String[] opt = key.split("\\|");
                int category = Integer.parseInt(opt[0].trim());
                String name = opt[1].trim();
                try {
                    assert value != null;
                    long valLong = Long.parseLong(value);
                    mMediaPlayer.setOption(category, name, valLong);
                } catch (Exception e) {
                    mMediaPlayer.setOption(category, name, value);
                }
            }
        }

        // 在每个数据包之后启用 I/O 上下文的刷新
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1);
        // 当 CPU 处理不过来的时候的丢帧帧数，默认为 0，参数范围是 [-1, 120]
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 5);
        // 设置视频流格式
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", tv.danmaku.ijk.media.player.IjkMediaPlayer.SDL_FCC_RV32);

        //开启内置字幕
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER, "subtitle", 1);

        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_timeout", -1);
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        try {
            applyDefaultNetworkTimeout(path);
            if (path.contains("rtsp") || path.contains("udp") || path.contains("rtp")) {
                mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 1);
                mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp");
                mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_flags", "prefer_tcp");
                mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 512 * 1000);
                mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 2 * 1000 * 1000);
            } else if (!TextUtils.isEmpty(path)
                    && !path.contains(".m3u8")
                    && (path.contains(".mp4") || path.contains(".mkv") || path.contains(".avi"))) {
                if (Hawk.get(HawkConfig.IJK_CACHE_PLAY, false)) {
                    String cachePath = FileUtils.getCachePath() + "/ijkcaches/";
                    String cacheMapPath = cachePath;
                    File cacheFile = new File(cachePath);
                    if (!cacheFile.exists()) cacheFile.mkdirs();
                    String tmpMd5 = MD5.string2MD5(path);
                    cachePath += tmpMd5 + ".file";
                    cacheMapPath += tmpMd5 + ".map";
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_file_path", cachePath);
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_map_path", cacheMapPath);
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "parse_cache_map", 1);
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "auto_save_map", 1);
                    mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_max_capacity", 60 * 1024 * 1024);
                    path = "ijkio:cache:ffio:" + path;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        setDataSourceHeader(headers);
        mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "ijkio,ffio,async,cache,crypto,file,dash,http,https,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data");
        super.setDataSource(path, null);
    }

    /** 网络读写超时（微秒）。20 秒是很宽松的阈值：只用来终结"连上之后彻底不再吐数据"的挂死 */
    private static final long DEFAULT_RW_TIMEOUT_US = 20L * 1000 * 1000;

    /**
     * 补默认网络超时。
     *
     * IJK 默认**没有**网络超时：地址连上以后如果对端不再吐数据，它会无限期挂着 ——
     * 表现就是"一直转圈、不报错、也进不了兜底链"，用户对着黑屏干等。
     *
     * 两条边界，都是为了不引入新问题：
     *  ① 只补 http/https。rtsp/udp/rtp 是直播路径，那里的停顿是常态，套超时会被反复打断。
     *  ② 只补"订阅没指定"的情况。站点下发的 IJK 解码分组里若已含 rw_timeout，一律以它为准 ——
     *     宿主不覆盖用户的设置。
     */
    private void applyDefaultNetworkTimeout(String path) {
        if (TextUtils.isEmpty(path)) return;
        if (path.startsWith("rtsp") || path.startsWith("udp") || path.startsWith("rtp")
                || path.contains("://rtsp") || path.contains("://udp") || path.contains("://rtp")) {
            return;
        }
        if (hasCodecOption("rw_timeout")) return;
        try {
            mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "rw_timeout", DEFAULT_RW_TIMEOUT_US);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 订阅（IJK 解码分组）里是否已指定某个 ffmpeg 选项。key 形如 "4|rw_timeout" */
    private boolean hasCodecOption(String name) {
        try {
            IJKCode codecTmp = this.codec == null ? ApiConfig.get().getCurrentIJKCode() : this.codec;
            if (codecTmp == null) return false;
            LinkedHashMap<String, String> options = codecTmp.getOption();
            if (options == null) return false;
            for (String key : options.keySet()) {
                if (key != null && key.endsWith("|" + name)) return true;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return false;
    }

    private void setDataSourceHeader(Map<String, String> headers) {
        if (headers != null && !headers.isEmpty()) {
            String userAgent = headers.get("User-Agent");
            if (!TextUtils.isEmpty(userAgent)) {
                mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", userAgent);
                // 移除header中的User-Agent，防止重复
                headers.remove("User-Agent");
            }
            if (headers.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    String value = entry.getValue();
                    if (!TextUtils.isEmpty(value)) {
                        sb.append(entry.getKey());
                        sb.append(": ");
                        sb.append(value);
                        sb.append("\r\n");
                    }
                }
                mMediaPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", sb.toString());
            }
        }
    }

    public TrackInfo getTrackInfo() {
        IjkTrackInfo[] trackInfo = mMediaPlayer.getTrackInfo();
        if (trackInfo == null) return null;
        TrackInfo data = new TrackInfo();
        int subtitleSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
        int audioSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        int index = 0;
        for (IjkTrackInfo info : trackInfo) {
            if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) {//音轨信息
                TrackInfoBean t = new TrackInfoBean();
                t.name = info.getInfoInline();
                t.language = info.getLanguage();
                t.trackId = index;
                t.selected = index == audioSelected;
                data.addAudio(t);
            }
            if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {//内置字幕
                TrackInfoBean t = new TrackInfoBean();
                t.name = info.getInfoInline();
                t.language = info.getLanguage();
                t.trackId = index;
                t.selected = index == subtitleSelected;
                data.addSubtitle(t);
            }
            index++;
        }
        return data;
    }

    public void setTrack(int trackIndex) {
        mMediaPlayer.selectTrack(trackIndex);
    }

    public void setOnTimedTextListener(IMediaPlayer.OnTimedTextListener listener) {
        mMediaPlayer.setOnTimedTextListener(listener);
    }

}
