package com.github.tvbox.osc.bean;

import com.github.tvbox.osc.api.ApiConfig;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * @author pj567
 * @date :2020/12/22
 * @description: 自定义装载解析后影片的容器对象,包括影片信息,线路信息,播放信息,记录当前选择的线路,集数等
 */
public class VodInfo implements Serializable {
    public String last;//时间
    //内容id
    public String id;
    //父级id
    public int tid;
    //影片名称 <![CDATA[老爸当家]]>
    public String name;
    //类型名称
    public String type;
    //视频分类zuidam3u8,zuidall
    public String dt;
    //图片
    public String pic;
    //语言
    public String lang;
    //地区
    public String area;
    //年份
    public int year;
    public String state;
    //描述集数或者影片信息<![CDATA[共40集]]>
    public String note;
    //演员<![CDATA[张国立,蒋欣,高鑫,曹艳艳,王维维,韩丹彤,孟秀,王新]]>
    public String actor;
    //导演<![CDATA[陈国星]]>
    public String director;
    public ArrayList<VodSeriesFlag> seriesFlags;
    /**
     * 线路集合
     */
    public LinkedHashMap<String, List<VodSeries>> seriesMap;
    public String des;// <![CDATA[权来]
    /**
     * 记录选择线路存储于seriesMap的key
     */
    public String playFlag = null;
    /**
     * 记录当前选择的集数
     */
    public int playIndex = 0;
    public String playNote = "";
    public String sourceKey;
    public String playerCfg = "";
    public boolean reverseSort = false;
    /**
     * 按线路存档的播放器配置：线路名(playFlag) → 该线路的配置 json。
     *
     * 用户 09-18 拍板：手动切播放器只影响**同线路本片的所有集**，换线路互不影响。
     * 这张表是唯一真相，playerCfg 退化为"当前线路的快照"（兼容既有读点）。
     * 随历史记录一起序列化（RoomDataManger 只排除 seriesFlags/seriesMap），跨重启保留。
     */
    public LinkedHashMap<String, String> flagPlayerCfgMap = new LinkedHashMap<>();

    public String getLinePlayerCfg(String flag) {
        if (flag == null || flagPlayerCfgMap == null) return "";
        String cfg = flagPlayerCfgMap.get(flag);
        return (cfg == null) ? "" : cfg;
    }

    public void setLinePlayerCfg(String flag, String cfg) {
        if (flag == null) return;
        if (flagPlayerCfgMap == null) flagPlayerCfgMap = new LinkedHashMap<>();
        flagPlayerCfgMap.put(flag, cfg);
    }

    public void setVideo(Movie.Video video) {
        last = video.last;
        id = video.id;
        tid = video.tid;
        name = video.name;
        type = video.type;
        // dt = video.dt;
        pic = video.pic;
        lang = video.lang;
        area = video.area;
        year = video.year;
        state = video.state;
        note = video.note;
        actor = video.actor;
        director = video.director;
        des = video.des;
        if (video.urlBean != null && video.urlBean.infoList != null && video.urlBean.infoList.size() > 0) {
            LinkedHashMap<String, List<VodSeries>> tempSeriesMap = new LinkedHashMap<>();
            seriesFlags = new ArrayList<>();
            for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                if (urlInfo.beanList != null && urlInfo.beanList.size() > 0) {
                    List<VodSeries> seriesList = new ArrayList<>();
                    for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlInfo.beanList) {
                        seriesList.add(new VodSeries(infoBean.name, infoBean.url));
                    }
                    tempSeriesMap.put(urlInfo.flag, seriesList);
                    seriesFlags.add(new VodSeriesFlag(urlInfo.flag));
                }
            }

            seriesMap = new LinkedHashMap<>();
            for (VodSeriesFlag flag : seriesFlags) {
                seriesMap.put(flag.name, tempSeriesMap.get(flag.name));
            }
        }
    }

    public void reverse() {
        Set<String> flags = seriesMap.keySet();
        for (String flag : flags) {
            Collections.reverse(seriesMap.get(flag));
        }
    }

    public static class VodSeriesFlag implements Serializable {

        public String name;
        public boolean selected;

        public VodSeriesFlag() {

        }

        public VodSeriesFlag(String name) {
            this.name = name;
        }
    }

    /**
     * 线路的子资源(如: 第*集 )
     */
    public static class VodSeries implements Serializable {
        //第*集
        public String name;
        //第*集的url
        public String url;
        //选中状态
        public boolean selected;

        public VodSeries() {
        }

        public VodSeries(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
}