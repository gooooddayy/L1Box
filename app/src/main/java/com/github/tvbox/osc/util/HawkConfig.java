package com.github.tvbox.osc.util;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class HawkConfig {
    public static final String API_URL = "api_url";
    /**
     * 订阅地址备份：每次写入非空 API_URL 时同步写一份。
     * API_URL 意外丢失(异常退出/存储损坏)时启动自动恢复，避免用户重新扫码配置。
     * 用户主动清空订阅时两者一起清，不会被"恢复"回来。
     */
    public static final String API_URL_BACKUP = "api_url_backup";
    public static final String LIVE_URL = "live_url";
    public static final String EPG_URL = "epg_url";
    public static final String SHOW_PREVIEW = "show_preview";
    public static final String SUBSCRIPTIONS = "api_history";
    public static final String LIVE_HISTORY = "live_history";
    public static final String EPG_HISTORY = "epg_history";
    public static final String HOME_API = "home_api";
    public static final String DEFAULT_PARSE = "parse_default";
    public static final String DEBUG_OPEN = "debug_open";
    public static final String IJK_CODEC = "ijk_codec";
    public static final String PLAY_TYPE = "play_type";//0 系统 1 ijk 2 exo 10 MXPlayer
    public static final String PLAY_RENDER = "play_render"; //0 texture 2
    public static final String PLAY_SCALE = "play_scale"; //0 texture 2
    public static final String PLAY_TIME_STEP = "play_time_step"; //0 texture 2
    public static final String DOH_URL = "doh_url";
    /**
     * 安全DNS 出厂默认值迁移标记（2026-09-19）。
     *
     * 旧版默认是 0（关闭）——那时内嵌 DoH 对私有地址直接抛异常、失败也不回退，
     * 打开会让本机 127.0.0.1 的净化/代理/首页路由全部解析失败，默认开着是害人。
     * 现在回退与熔断都已就位（util/L1FallbackDns + util/L1DnsPolicy），默认改开启。
     * 老用户 Hawk 里存的仍是旧的 0，用这个标记表示"迁移已执行过一次"。
     */
    public static final String DOH_UPGRADED = "doh_upgraded";
    /**
     * 用户是否在设置页手动选过安全DNS。
     * 与上面的迁移标记配合：只要用户明确表达过选择，后续任何默认值调整都不再碰它。
     */
    public static final String DOH_USER_SET = "doh_user_set";
    /**
     * 0 豆瓣热播 1 数据源推荐 2 关闭主页
     */
    public static final String HOME_REC = "home_rec";
    public static final String HISTORY_NUM = "history_num";
    public static final String LIVE_CHANNEL = "last_live_channel_name";
    public static final String LIVE_CHANNEL_REVERSE = "live_channel_reverse";
    public static final String LIVE_CROSS_GROUP = "live_cross_group";
    public static final String LIVE_CONNECT_TIMEOUT = "live_connect_timeout";
    public static final String LIVE_SHOW_NET_SPEED = "live_show_net_speed";
    public static final String LIVE_SHOW_TIME = "live_show_time";
    public static final String FAST_SEARCH_MODE = "fast_search_mode";
    public static final String SUBTITLE_TEXT_SIZE = "subtitle_text_size";
    public static final String SUBTITLE_TIME_DELAY = "subtitle_time_delay";
    public static final String SOURCES_FOR_SEARCH = "checked_sources_for_search";
    public static final String NOW_DATE = "now_date"; //当前日期
    public static final String REMOTE_TVBOX = "remote_tvbox_host";
    public static final String IJK_CACHE_PLAY = "ijk_cache_play";
    /**
     * Exo 磁盘缓存（2026-09-18）。默认开。
     *
     * 语义是"播放器本来就要发的请求，顺便在本地留一份副本"——不增加任何网络请求，
     * 命中时反而减少请求，所以不与站点风控冲突。本机端点（/l1.m3u8、/proxy）强制不缓存，
     * 判据在 player 模块的 ExoCacheConfig.isCacheableUrl()。
     */
    public static final String EXO_DISK_CACHE = "exo_disk_cache";
    /**
     * 无痕浏览
     */
    public static final String PRIVATE_BROWSING = "private_browsing";
    /**
     * 主题,跟随系统0,浅1,深2
     */
    public static final String THEME_TAG = "theme_tag";
    /**
     * 后台播放模式 0 关闭,1 开启,2 画中画
     */
    public static final String BACKGROUND_PLAY_TYPE = "background_play_type";
    /**
     * 广告过滤
     */
    public static final String VIDEO_PURIFY = "video_purify";
    /**
     * 长按的倍速播放设置
     */
    public static final String VIDEO_SPEED = "video_speed";
    /**
     * 搜索记录
     */
    public static final String HISTORY_SEARCH = "history_search";
    /**
     * 允许局域网设备管理本机文件（上传 / 新建目录 / 删除 / 目录浏览）。
     * 默认关闭：关闭时这些操作只允许 127.0.0.1（本机 App 自身）发起。
     * 关闭的原因——9978 服务监听 0.0.0.0，同网段任意设备都能删改本机文件，
     * 属于典型的越权访问面，而这些端点在 App 内没有任何入口、用户并不知情。
     */
    public static final String ALLOW_LAN_FILE_MANAGE = "allow_lan_file_manage";
}