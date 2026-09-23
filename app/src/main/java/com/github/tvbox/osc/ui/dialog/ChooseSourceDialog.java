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
        rv.setAdapter(sourceAdapter);
        sourceAdapter.setNewData(mSources);
        // 当前启用线路高亮
        String currentUrl = pickCurrentUrl();
        if (currentUrl != null) {
            for (int i = 0; i < mSources.size(); i++) {
                if (currentUrl.equals(mSources.get(i).getSourceUrl())) {
                    RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(i);
                    if (vh != null) vh.itemView.setSelected(true);
                    break;
                }
            }
        }

        sourceAdapter.setOnItemClickListener((adapter, view, position) -> {
            dismissWith(() -> {
                if (mListener!=null){
                    mListener.onSelect(position, mSources.get(position).getSourceUrl());
                }
            });
        });
    }

    /**
     * 从订阅(Hawk)读取当前选中 url,用于在弹窗中高亮显示。
     * 由于弹窗构造期并未显式传入 currentUrl, 通过 HawkConfig.API_URL + active 仓名比对。
     * 无匹配时返回 null(不预选)。
     */
    private String pickCurrentUrl() {
        try {
            String apiUrl = com.orhanobut.hawk.Hawk.get(com.github.tvbox.osc.util.HawkConfig.API_URL, "");
            return apiUrl == null || apiUrl.isEmpty() ? null : apiUrl;
        } catch (Throwable ignore) {
            return null;
        }
    }
}