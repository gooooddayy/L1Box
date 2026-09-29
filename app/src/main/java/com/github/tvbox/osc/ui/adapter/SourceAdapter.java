package com.github.tvbox.osc.ui.adapter;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Source;
import com.github.tvbox.osc.util.L1SubUrl;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */
public class SourceAdapter extends BaseQuickAdapter<Source, BaseViewHolder> {
    /**
     * 当前生效的线路地址（已归一化）。
     *
     * 高亮**不能**在 setNewData 之后去 RecyclerView 里取 ViewHolder 再 setSelected：
     * 那一刻布局还没走完，findViewHolderForAdapterPosition 恒返回 null，选中态一次都下发不出去
     * （这正是"线路列表里看不出当前是哪条"的成因）。
     * 改为在 convert 里按地址判定——它是在 View 真正绑定数据时执行的，时机天然正确，
     * 且条目被回收复用时也会重新赋值，不会残留上一条的高亮。
     */
    private String selectedUrl = "";

    public SourceAdapter() {
        super(R.layout.item_source, new ArrayList<>());
    }

    /** 必须在 setNewData 之前调用（convert 依赖它）。传 null/空串表示不高亮任何一条。 */
    public void setSelectedUrl(String url) {
        this.selectedUrl = url == null ? "" : url;
    }

    @Override
    protected void convert(BaseViewHolder helper, Source item) {
        helper.setText(R.id.tv_name, item.getSourceName());
        // 不展示 url,只用 tv_name 一行作为「线路名」按钮
        // 高亮背景 bg_source_item 就挂在 tv_name 上（selector 的 state_selected 分支），故设在它身上。
        // 两侧都归一化后再比：子线路地址来自仓库 JSON（只做过 trim），而 activeLineUrl 是归一化过的，
        // 直接 equals 会因大小写/引号/空白差异漏判。
        boolean selected = !selectedUrl.isEmpty()
                && selectedUrl.equals(L1SubUrl.normalize(item.getSourceUrl()));
        helper.getView(R.id.tv_name).setSelected(selected);
    }
}
