package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Source;
import com.github.tvbox.osc.ui.adapter.SourceAdapter;
import com.lxj.xpopup.core.BottomPopupView;
import com.lxj.xpopup.core.CenterPopupView;
import com.lxj.xpopup.interfaces.OnSelectListener;

import java.util.ArrayList;
import java.util.List;

/**
 * @Author : Liu XiaoRan
 * @Email : 592923276@qq.com
 * @Date : on 2023/9/7 16:33.
 * @Description :
 */
public class ChooseSourceDialog extends BottomPopupView {
    List<Source> mSources;
    private final OnSelectListener mListener;
    private CharSequence mTitle;
    /** 当前生效线路地址（由调用方下传；为空时回落到 Hawk 里的 API_URL） */
    private String mCurrentUrl;

    public ChooseSourceDialog(@NonNull Context context, List<Source> sources, OnSelectListener listener) {
        super(context);
        mSources = sources;
        mListener = listener;
    }

    /** 自定义标题(留空则用布局默认"请选择要导入的仓库") */
    public ChooseSourceDialog setTitle(CharSequence title) {
        this.mTitle = title;
        return this;
    }

    /**
     * 指定"当前生效线路"用于高亮。调用方（首页 / 订阅管理）手上就有订阅对象，
     * 直接下传它的 activeLineUrl 比反查 Hawk 更准：
     * 订阅管理里那一行**未必是当前启用的订阅**，此时 Hawk 里的 API_URL 属于另一条订阅。
     * 留空则回落到 Hawk（保持原行为）。
     */
    public ChooseSourceDialog setCurrentUrl(String currentUrl) {
        this.mCurrentUrl = currentUrl;
        return this;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_sources;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        android.app.Activity activity = getActivity();
        if (activity == null) {
            // 极端生命周期下 getActivity() 可能为 null，直接关闭避免 LinearLayoutManager(null) NPE
            dismiss();
            return;
        }
        if (mTitle != null) {
            android.widget.TextView tvTitle = findViewById(R.id.title);
            if (tvTitle != null) tvTitle.setText(mTitle);
        }
        RecyclerView rv = findViewById(R.id.rv);
        rv.setLayoutManager(new LinearLayoutManager(activity));
        SourceAdapter sourceAdapter = new SourceAdapter();
        // 当前启用线路高亮：地址必须在 setNewData **之前**交给 adapter，由它在条目绑定时下发选中态。
        // 旧实现在 setNewData 之后立刻 findViewHolderForAdapterPosition —— 那一刻布局尚未走完，
        // 返回值恒为 null，于是 setSelected(true) 一次都没真正执行过，列表里永远看不出当前是哪条线路。
        sourceAdapter.setSelectedUrl(pickCurrentUrl());
        rv.setAdapter(sourceAdapter);
        sourceAdapter.setNewData(mSources);

        sourceAdapter.setOnItemClickListener((adapter, view, position) -> {
            dismissWith(() -> {
                if (mListener!=null){
                    mListener.onSelect(position, mSources.get(position).getSourceUrl());
                }
            });
        });
    }

    /**
     * 取"当前生效线路"的地址用于高亮，已做归一化（子线路地址来自仓库 JSON，与归一化过的
     * activeLineUrl 存在大小写/引号/空白差异，不归一化会漏判）。
     * 优先用调用方下传的 activeLineUrl；没传才回落到 HawkConfig.API_URL（当前启用订阅的有效地址）。
     * 无匹配时返回空串（表示不高亮任何一条）。
     */
    private String pickCurrentUrl() {
        try {
            String url = mCurrentUrl;
            if (url == null || url.isEmpty()) {
                url = com.orhanobut.hawk.Hawk.get(com.github.tvbox.osc.util.HawkConfig.API_URL, "");
            }
            if (url == null || url.isEmpty()) return "";
            return com.github.tvbox.osc.util.L1SubUrl.normalize(url);
        } catch (Throwable ignore) {
            return "";
        }
    }
}