package android.widget;

import android.content.Context;
import android.util.Log;
import android.view.View;

/**
 * 替身 Toast：仅提供给 spider jar 的类加载器使用（JarToastBlockLoader 按类名拦截）。
 * jar 弹的所有 Toast 一律不显示，只落到后台日志（tag=JarToast），即"后台显示"。
 * 方法集与真实 Toast 保持二进制兼容，避免 jar 调用出现 NoSuchMethodError。
 */
public class Toast {

    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_LONG = 1;

    private CharSequence text = "";

    public Toast() {
    }

    public Toast(Context context) {
    }

    public static Toast makeText(Context context, CharSequence text, int duration) {
        return build(text);
    }

    public static Toast makeText(Context context, int resId, int duration) {
        return build(String.valueOf(resId));
    }

    private static Toast build(CharSequence text) {
        Toast t = new Toast();
        t.text = text;
        Log.d("JarToast", "已屏蔽 jar 弹窗(不显示): " + text);
        return t;
    }

    public Toast setDuration(int duration) {
        return this;
    }

    public void setGravity(int gravity, int xOffset, int yOffset) {
    }

    public void setMargin(float horizontalMargin, float verticalMargin) {
    }

    public void setText(CharSequence text) {
        this.text = text;
        Log.d("JarToast", "已屏蔽 jar 弹窗(不显示): " + text);
    }

    public View getView() {
        return null;
    }

    public void show() {
        Log.d("JarToast", "已屏蔽 jar 弹窗(不显示): " + text);
    }

    public void cancel() {
    }
}
