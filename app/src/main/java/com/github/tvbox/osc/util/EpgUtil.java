package com.github.tvbox.osc.util;

import android.content.res.AssetManager;

import com.github.tvbox.osc.base.App;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.HashMap;

public class EpgUtil {

    private static JsonObject epgDoc = null;
    private static HashMap<String, JsonObject> epgHashMap = new HashMap<>();
    private static boolean parseStarted = false;

    public static void init() {
        // 启动提速：EPG 表解析挪到后台线程，主线程只在真正查询时才用到它
        synchronized (EpgUtil.class) {
            if (epgDoc != null || parseStarted)
                return;
            parseStarted = true;
        }
        new Thread(EpgUtil::parseEpg, "epg-load").start();
    }

    private static void parseEpg() {
        synchronized (EpgUtil.class) {
            if (epgDoc != null)
                return;
            try {
            AssetManager assetManager = App.getInstance().getAssets(); //获得assets资源管理器（assets中的文件无法直接访问，可以使用AssetManager访问）
            InputStreamReader inputStreamReader = new InputStreamReader(assetManager.open("epg_data.json"),"UTF-8"); //使用IO流读取json文件内容
            BufferedReader br = new BufferedReader(inputStreamReader);//使用字符高效流
            String line;
            StringBuilder builder = new StringBuilder();
            while ((line = br.readLine())!=null){
                builder.append(line);
            }
            br.close();
            inputStreamReader.close();
            if(!builder.toString().isEmpty()){
                epgDoc =  new Gson().fromJson(builder.toString(), (Type)JsonObject.class);// 从builder中读取了json中的数据。
                for (JsonElement opt : epgDoc.get("epgs").getAsJsonArray()) {
                    JsonObject obj = (JsonObject) opt;
                    String name = obj.get("name").getAsString().trim();
                    String[] names  = name.split(",");
                    for (String string : names) {
                        epgHashMap.put(string,obj);
                    }
                }
                return;
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        }
    }

    public static String[] getEpgInfo(String channelName) {
        try {
            synchronized (EpgUtil.class) {
                if (epgHashMap.containsKey(channelName)) {
                    JsonObject obj = epgHashMap.get(channelName);
                    return new String[] {
                            obj.get("logo").getAsString(),
                            obj.get("epgid").getAsString()
                    };
                }
            }
        }catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }
}
