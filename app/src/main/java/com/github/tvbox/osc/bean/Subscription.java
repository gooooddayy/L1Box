package com.github.tvbox.osc.bean;


import com.github.tvbox.osc.util.L1SubUrl;

import java.util.ArrayList;
import java.util.List;

public class Subscription {
    public Subscription() {
    }

    public Subscription(String name, String url) {
        this.name = name;
        this.url = L1SubUrl.normalize(url);
    }

    String name;
    String url;
    //选择状态
    boolean isChecked;
    //置顶
    private boolean top;

    // ============== Phase A 新增字段 ==============
    /** 是否多仓（storeHouse）父源；为 true 时 url 为父仓地址，lines 为子线路，activeLineUrl 为当前选中子线路 */
    private boolean isMultiRepo = false;
    /** 多仓子线路列表（storeHouse 解析出的 Source 列表） */
    private List<Source> lines = new ArrayList<>();
    /** 当前选中的子线路 url（仅多仓时使用） */
    private String activeLineUrl = "";

    public boolean isTop() {
        return top;
    }

    public void setTop(boolean top) {
        this.top = top;
    }

    public boolean isChecked() {
        return isChecked;
    }

    public Subscription setChecked(boolean checked) {
        isChecked = checked;
        return this;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        // 订阅地址唯一写入口：BOM/零宽字符/首尾引号/漏写 http:// 在数据层一次收敛，
        // 免得"扫码进的脏、手输的干净"这种按入口分叉的问题。normalize 幂等。
        this.url = L1SubUrl.normalize(url);
    }

    public boolean isMultiRepo() {
        return isMultiRepo;
    }

    public void setMultiRepo(boolean multiRepo) {
        isMultiRepo = multiRepo;
    }

    public List<Source> getLines() {
        // Gson 反序列化绕过字段初始化器，lines 可能为 null（历史数据/异常数据），兜底避免调用方 NPE
        if (lines == null) lines = new ArrayList<>();
        return lines;
    }

    public void setLines(List<Source> lines) {
        this.lines = lines != null ? lines : new ArrayList<>();
    }

    public String getActiveLineUrl() {
        return activeLineUrl != null ? activeLineUrl : "";
    }

    public void setActiveLineUrl(String activeLineUrl) {
        // 同样收敛：子线路地址来自仓库 JSON，带空白/引号的情况都见过，而它会被直接写进 API_URL
        this.activeLineUrl = L1SubUrl.normalize(activeLineUrl);
    }

    /** 当前订阅实际生效的地址（多仓取 activeLineUrl；普通取 url） */
    public String getEffectiveUrl() {
        if (!isMultiRepo) return url;
        String active = getActiveLineUrl();
        if (!active.isEmpty()) return active;
        // 多仓但"当前线路"为空（老版本数据 / 异常数据）：回落到第一条线路。
        // 否则会把空串写进 API_URL，表现为"切换订阅后变成空源"，比用错源更难排查。
        List<Source> ls = getLines();
        return ls.isEmpty() ? "" : ls.get(0).getSourceUrl();
    }
}