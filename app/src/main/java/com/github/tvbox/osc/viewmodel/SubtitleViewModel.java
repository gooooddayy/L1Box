package com.github.tvbox.osc.viewmodel;

import android.text.TextUtils;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.github.tvbox.osc.bean.Subtitle;
import com.github.tvbox.osc.bean.SubtitleData;
import com.github.tvbox.osc.ui.dialog.SearchSubtitleDialog;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SubtitleViewModel extends ViewModel {

    public MutableLiveData<SubtitleData> searchResult;

    public SubtitleViewModel() {
        searchResult = new MutableLiveData<>();
    }

    public void searchResult(String title, int page) {
        // 在线字幕源已移除：默认不请求任何外部字幕站点，返回空列表由 UI 提示。
        setSearchListData(new ArrayList<Subtitle>(), true, false);
    }

    public void getSearchResultSubtitleUrls(Subtitle subtitle) {
        // 在线字幕源已移除（原实现请求第三方字幕站）
    }

    public void getSubtitleUrl(Subtitle subtitle, SearchSubtitleDialog.SubtitleLoader subtitleLoader) {
        // 在线字幕源已移除（原实现请求第三方字幕站）
    }

    private void setSearchListData(List<Subtitle> data, boolean isNew, boolean isZip) {
        try {
            SubtitleData subtitleData = new SubtitleData();
            subtitleData.setSubtitleList(data);
            subtitleData.setIsNew(isNew);
            subtitleData.setIsZip(isZip);
            searchResult.postValue(subtitleData);
        } catch (Throwable e) {
            e.printStackTrace();
            searchResult.postValue(null);
        }
    }

    private int pagesTotal = -1;

}
