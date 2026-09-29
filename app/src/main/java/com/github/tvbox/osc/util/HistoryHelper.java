package com.github.tvbox.osc.util;

/**
 * 历史记录保留条数。
 *
 * 2026-09-24 起固定 50 条：设置页的"30/50/70"选择器已移除，用户不再需要选。
 * 于是这里不再有"按下标取档位"的口径，只保留**唯一**的事实来源 ——
 * 原来的问题正是查询上限（100）与清理阈值（70）各自写死、互不相干。
 * 超出部分由 RoomDataManger.getAllVodRecord 调 DAO 的 reserver() 自动清掉最早的记录。
 */
public class HistoryHelper {
    /** 历史记录保留条数（固定值） */
    public static final int HIS_NUM = 50;
}
