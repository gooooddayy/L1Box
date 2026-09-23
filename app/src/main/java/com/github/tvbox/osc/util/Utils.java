package com.github.tvbox.osc.util;

import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatDelegate;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.VodInfo;

import java.util.Formatter;
import java.util.List;
import java.util.Locale;


public class Utils {

    /**
     * 关掉 View 的长按气泡。
     *
     * 来源：Android 8.0 起，View 的 tooltip 会默认取 contentDescription —— 布局里给图标写了
     * `android:contentDescription="投屏"` 这类描述，在手机上长按就会弹一个灰底小气泡；
     * 底部导航的「首页/我的」则由 Material 的 NavigationBarItemView 用菜单标题自动设置。
     * 这里只清 tooltip，**保留 contentDescription 本身**（不改无障碍语义，也不动长按原有的点击行为）。
     *
     * 低版本（API < 26）本来就没有 tooltip 机制，直接跳过；用版本判断而非 TooltipCompat，
     * 是为了不引入额外的类依赖，行为也更直观。
     */
    public static void disableTooltip(View view) {
        if (view == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setTooltipText(null);
        }
    }

    /**
     * 清掉整棵子树的 tooltip（用于底部导航这类"气泡长在内部子 View 上"的复合控件）。
     *
     * 为什么要递归：BottomNavigationView 的直接子 View 只有一个菜单容器，真正承载
     * 「首页 / 我的」的 item View 在它里面。只清直接子级等于清了个容器，item 上的 tooltip
     * 原封不动 —— 实测过，气泡照旧弹。
     *
     * 为什么要挂长按：Android 的 View.performLongClickInternal 顺序是
     * 「onLongClickListener → contextMenu → startTooltip」，长按回调返回 true 会直接短路，
     * 根本走不到弹气泡那一步。所以这层是**机制兜底**：无论 Material 之后在什么时机
     * （切 tab、setChecked、更新菜单）又给它重设 tooltip，都弹不出来，不依赖"我们清得够早"。
     *
     * 只对 isClickable() 的 View 挂长按：这类 View 才是长按回调的落点。像图标、文字这种
     * 默认不可点击的子 View **绝不能挂** —— 挂上就变 longClickable，DOWN 事件被它消费，
     * 父 item 就收不到 click，「点文字切 tab」会失效。
     */
    public static void disableTooltipDeep(View view) {
        if (view == null) return;
        disableTooltip(view);
        if (view.isClickable()) {
            view.setOnLongClickListener(v -> true);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                disableTooltipDeep(group.getChildAt(i));
            }
        }
    }

    public static boolean supportsPiPMode() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    public static int getSeriesSpanCount(List<VodInfo.VodSeries> list) {
        int spanCount = 4;
        int total = 0;
        for (VodInfo.VodSeries item : list) total += item.name.length();
        int offset = (int) Math.ceil((double) total / list.size());
        if (offset >= 12) spanCount = 1;
        else if (offset >= 8) spanCount = 2;
        else if (offset >= 4) spanCount = 3;
        else if (offset >= 2) spanCount = 4;
        return spanCount;
    }

    public static String stringForTime(long timeMs) {
//        if (timeMs <= 0 || timeMs >= 24 * 60 * 60 * 1000) {
//            return "00:00";
//        }
        long totalSeconds = timeMs / 1000;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        StringBuilder stringBuilder = new StringBuilder();
        Formatter mFormatter = new Formatter(stringBuilder, Locale.getDefault());
        if (hours > 0) {
            return mFormatter.format("%d:%02d:%02d", hours, minutes, seconds).toString();
        } else {
            return mFormatter.format("%02d:%02d", minutes, seconds).toString();
        }
    }

    public static boolean isDarkTheme(){
        int currentNightMode = App.getInstance().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES || AppCompatDelegate.getDefaultNightMode()==AppCompatDelegate.MODE_NIGHT_YES;
    }

    public static void initTheme(){
        // 主题设置已下线：App 固定浅色，无论系统深浅色、历史 THEME_TAG 值为何
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
    }
}