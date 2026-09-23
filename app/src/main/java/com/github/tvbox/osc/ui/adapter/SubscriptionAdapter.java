package com.github.tvbox.osc.ui.adapter;

import android.view.View;
import android.widget.CompoundButton;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.util.Utils;

import java.util.Comparator;
import java.util.List;

public class SubscriptionAdapter extends BaseQuickAdapter<Subscription, BaseViewHolder> {
    public SubscriptionAdapter() {
        super(R.layout.item_subscription);
    }

    @Override
    protected void convert(BaseViewHolder helper, Subscription item) {
        helper.setText(R.id.tv_name, item.getName())
                .setText(R.id.tv_url, item.getUrl());

        // 行 3 胶囊: 多仓 / 单仓
        if (item.isMultiRepo()) {
            helper.setText(R.id.tv_multi, "多仓");
        } else {
            helper.setText(R.id.tv_multi, "单仓");
        }

        // 行 3 使用源: 多仓时显示当前活跃线路名(可点击弹窗); 单仓时隐藏(单仓徽标在 tv_multi 即可)
        if (item.isMultiRepo()) {
            String active = item.getEffectiveUrl();
            String name = null;
            if (item.getLines() != null) {
                for (com.github.tvbox.osc.bean.Source line : item.getLines()) {
                    if (active != null && active.equals(line.getSourceUrl())) {
                        name = line.getSourceName();
                        break;
                    }
                }
            }
            if (name == null) name = active;
            helper.setText(R.id.tv_use_source, "使用源:" + name);
            helper.setVisible(R.id.tv_use_source, true);
        } else {
            helper.setVisible(R.id.tv_use_source, false);
        }

        // 子控件点击注册
        helper.addOnClickListener(R.id.iv_more);
        // 清掉「更多操作」的长按气泡（列表项会被反复复用，每次绑定都清一遍）
        Utils.disableTooltip(helper.getView(R.id.iv_more));
        // 多仓时才让「使用源」可点击; 单仓不可点
        if (item.isMultiRepo()) {
            helper.addOnClickListener(R.id.tv_use_source);
        } else {
            // 移除之前的点击回调,避免上一次绑定残留
            View useSource = helper.getView(R.id.tv_use_source);
            if (useSource != null) useSource.setOnClickListener(null);
        }

        // Switch: 先清监听再设状态,再挂监听,防止 setChecked 递归触发
        SwitchCompat sw = helper.getView(R.id.sw_enable);
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(item.isChecked());
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mEnableListener != null) {
                    mEnableListener.onEnableChanged(item, isChecked);
                }
            }
        });
    }

    /** Switch 开启/关闭回调 */
    public interface OnEnableChangeListener {
        void onEnableChanged(Subscription item, boolean enabled);
    }

    private OnEnableChangeListener mEnableListener;

    public void setOnEnableChangeListener(OnEnableChangeListener l) {
        this.mEnableListener = l;
    }

    /**
     * 刷新列表时候,添加去重和排序
     */
    @Override
    public void setNewData(@Nullable List<Subscription> data) {
        if (data != null) {
            //去除 url 重复的订阅
            for (int i = 0; i < data.size(); i++) {
                for (int j = i + 1; j < data.size(); j++) {
                    if (data.get(i).getUrl().equals(data.get(j).getUrl())) {
                        data.remove(j);
                        j--;
                    }
                }
            }
            data.sort(mComparator);
        }
        super.setNewData(data);
    }

    Comparator<Subscription> mComparator = (s1, s2) -> {
        if (s1.isTop() && !s2.isTop()) {
            return -1;
        } else if (!s1.isTop() && s2.isTop()) {
            return 1;
        } else if (s1.isChecked() && !s2.isChecked()) {
            return -1;
        } else if (!s1.isChecked() && s2.isChecked()) {
            return 1;
        } else {
            return 0;
        }
    };
}
