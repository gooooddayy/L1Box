package com.github.tvbox.osc.util;

import android.app.Activity;

import java.util.Stack;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class AppManager {
    // 直接初始化, 避免"栈尚未创建就调用出栈方法"的空指针崩溃
    private static final Stack<Activity> activityStack = new Stack<>();

    private AppManager() {
    }

    private static class SingleHolder {
        private static AppManager instance = new AppManager();
    }

    public static AppManager getInstance() {
        return SingleHolder.instance;
    }

    /**
     * 添加Activity到堆栈
     */
    public void addActivity(Activity activity) {
        activityStack.add(activity);
    }

    /**
     * 是否有activity
     */
    public boolean isActivity() {
        return !activityStack.isEmpty();
    }

    /**
     * 获取当前Activity（堆栈中最后一个压入的）
     */
    public Activity currentActivity() {
        return activityStack.isEmpty() ? null : activityStack.lastElement();
    }

    /**
     * 结束当前Activity（堆栈中最后一个压入的）
     */
    public void finishActivity() {
        if (activityStack.isEmpty()) return;
        Activity activity = activityStack.lastElement();
        if (!activity.isFinishing()) {
            activity.finish();
        }
    }

    public void finishActivity(Activity activity) {
        activityStack.remove(activity);
    }


    /**
     * 结束指定类名的Activity
     */
    public void finishActivity(Class<?> cls) {
        for (Activity activity : activityStack) {
            if (activity.getClass().equals(cls)) {
                if (!activity.isFinishing()) {
                    activity.finish();
                }
                break;
            }
        }
    }

    public void backActivity(Class<?> cls) {
        while (!activityStack.empty()) {
            Activity activity = activityStack.pop();
            if (activity.getClass().equals(cls)) {
                activityStack.push(activity);
                break;
            } else {
                activity.finish();
            }
        }
    }

    /**
     * 结束所有Activity
     */
    public void finishAllActivity() {
        if (activityStack != null && activityStack.size() > 0) {
            for (int i = 0, size = activityStack.size(); i < size; i++) {
                Activity activity = activityStack.get(i);
                if (null != activityStack.get(i)) {
                    if (!activity.isFinishing()) {
                        activity.finish();
                    }
                }
            }
            activityStack.clear();
        }
    }

    /**
     * 获取指定的Activity
     */
    public Activity getActivity(Class<?> cls) {
        if (activityStack != null) {
            for (Activity activity : activityStack) {
                if (activity.getClass().equals(cls)) {
                    return activity;
                }
            }
        }
        return null;
    }

    public void appExit(int code) {
        try {
            finishAllActivity();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(code);
        } catch (Exception e) {
            activityStack.clear();
            e.printStackTrace();
        }
    }
}