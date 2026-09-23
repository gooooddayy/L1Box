package com.github.tvbox.osc.bean;

import android.text.TextUtils;

/**
 * 一台被发现的可投屏设备（DLNA / UPnP MediaRenderer）
 */
public class DlnaDevice {
    public String name;       // friendlyName
    public String location;   // 设备描述 XML 地址（LOCATION）
    public String controlUrl; // AVTransport 控制地址（已解析为绝对地址）
    public String ip;         // 设备 IP，仅用于展示

    public DlnaDevice() {
    }

    public DlnaDevice(String name, String location, String controlUrl, String ip) {
        this.name = name;
        this.location = location;
        this.controlUrl = controlUrl;
        this.ip = ip;
    }

    public String getDisplayName() {
        if (TextUtils.isEmpty(name)) return TextUtils.isEmpty(ip) ? "未知设备" : ip;
        if (TextUtils.isEmpty(ip) || ip.equals(name)) return name;
        return name + " (" + ip + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DlnaDevice)) return false;
        DlnaDevice d = (DlnaDevice) o;
        String key = controlUrl != null ? controlUrl : location;
        String okey = d.controlUrl != null ? d.controlUrl : d.location;
        if (key != null) return key.equals(okey);
        return false;
    }

    @Override
    public int hashCode() {
        String key = controlUrl != null ? controlUrl : location;
        return key != null ? key.hashCode() : 0;
    }
}
