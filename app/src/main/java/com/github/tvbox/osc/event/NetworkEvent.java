package com.github.tvbox.osc.event;

/**
 * 网络状态变化事件（由 NetworkMonitor 发出）。
 * online=true 表示恢复了可用网络，可用于自动重试此前失败的加载。
 */
public class NetworkEvent {
    public final boolean online;

    public NetworkEvent(boolean online) {
        this.online = online;
    }
}
