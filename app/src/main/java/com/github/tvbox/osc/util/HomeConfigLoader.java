package com.github.tvbox.osc.util;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 首页内容配置（首页.json）读取。
 * 优先级：/sdcard/首页.json（外置覆盖，便于临时调试） > APK 内置 assets/home_config.json。
 * 内置资源保证零权限、零外部依赖，任何设备都能读到首页内容。
 */
public class HomeConfigLoader {
    private static final String TAG = "HomeConfigLoader";
    /** APK 内置首页配置 */
    public static final String ASSET_NAME = "home_config.json";
    /** 外置覆盖文件名（sdcard 根目录，中文名便于用户识别） */
    public static final String OVERRIDE_NAME = "首页.json";

    /** 读取首页配置文本。失败返回 null。 */
    public static String load(Context context) {
        String json = readOverride();
        if (json != null) {
            Log.d(TAG, "使用外置首页配置: /sdcard/" + OVERRIDE_NAME);
            return json;
        }
        json = readAsset(context);
        if (json != null) return json;
        Log.w(TAG, "首页配置不可用：内置资源与外置文件均读取失败");
        return null;
    }

    /** 读外置覆盖文件。无权限/不存在/解析异常一律静默返回 null，不打扰用户。 */
    private static String readOverride() {
        try {
            File f = new File("/sdcard", OVERRIDE_NAME);
            if (!f.exists() || !f.isFile() || !f.canRead()) return null;
            String s = readStream(new FileInputStream(f));
            return TextUtils.isEmpty(s) ? null : s;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static String readAsset(Context context) {
        if (context == null) return null;
        InputStream in = null;
        try {
            in = context.getAssets().open(ASSET_NAME);
            return readStream(in);
        } catch (Throwable th) {
            Log.w(TAG, "读取内置首页配置失败: " + th.getMessage());
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static String readStream(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append('\n');
        }
        br.close();
        String s = sb.toString();
        return TextUtils.isEmpty(s) ? null : s;
    }
}
