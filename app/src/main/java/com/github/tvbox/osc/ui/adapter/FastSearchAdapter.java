package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.picasso.RoundTransformation;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.MD5;
import com.orhanobut.hawk.Hawk;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class FastSearchAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {

    // ── 图片状态标记（2026-09-28）：给宿主一个"这张图到底出没出来"的可靠口径 ──────
    // 之前只能靠 downloader 的账本推断，而"网络成功"≠"用户看到"（view 可能已被复用、
    // 或请求被取消），所以直接在 ImageView 上记状态，宿主扫描可见项即可统计真实落地率。
    public static final String IMG_PH = "img_ph";     // 占位中（尚未成功）
    public static final String IMG_OK = "img_ok";     // 已成功显示
    public static final String IMG_ERR = "img_err";   // 失败

    /** 收尾诊断用：本轮"发起加载"的次数与"地址为空"的次数 */
    private static final java.util.concurrent.atomic.AtomicInteger LOAD_STARTED =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger EMPTY_PIC =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 可见±6 预取的次数（2026-09-28 用户口径；由宿主 prefetchAroundVisible 记账） */
    private static final java.util.concurrent.atomic.AtomicInteger PREFETCH_STARTED =
            new java.util.concurrent.atomic.AtomicInteger();

    public static void resetImageCounters() {
        LOAD_STARTED.set(0);
        EMPTY_PIC.set(0);
        PREFETCH_STARTED.set(0);
    }

    /** 宿主预取一张图时调用（只计数，不涉及任何判定） */
    public static void countPrefetchStart() {
        PREFETCH_STARTED.incrementAndGet();
    }

    public static String imageCounterSummary() {
        return "绑定发起=" + LOAD_STARTED.get() + " 预取=" + PREFETCH_STARTED.get()
                + " 空地址=" + EMPTY_PIC.get();
    }

    public FastSearchAdapter() {
        super(R.layout.item_search, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {

        // with preview
        helper.setText(R.id.tvName, item.name);
        helper.setText(R.id.tvSite, ApiConfig.get().getSourceName(item.sourceKey));
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        // 2026-09-28（用户口径）：**绑定即请求，不做任何"该不该加载"的预判**（去掉可见性门闸后，
        // "点左侧特定站点图片不加载"那类误判随之消失）。滚出屏幕的请求由 L1ImageInflight 及时取消。
        if (!TextUtils.isEmpty(item.pic)) {
            LOAD_STARTED.incrementAndGet();
            ivThumb.setTag(IMG_PH);
            // bv（2026-09-24）：与首页/历史/收藏**同一口径**（trim + checkReplaceProxy）。
            // 原来这里是裸 load(item.pic)：带首尾空白或 `proxy://` 开头的地址在搜索页必然失败，
            // 而同样的地址在首页能显示 —— 这就是"搜索页挺多图片没加载出来"里地址那一类的来源。
            Picasso.get()
                    .load(DefaultConfig.checkReplaceProxy(item.pic.trim()))
                    // key 里不再混位置（bv）：列表按 SEARCH_FLUSH_MS=120ms 合批刷新，位置一变 key 就变，
                    // 内存缓存命中率≈0，每张图都要重新解码。
                    .transform(new RoundTransformation(MD5.string2MD5(item.pic))
                            .centerCorp(true)
                            .override(AutoSizeUtils.dp2px(mContext, 86), AutoSizeUtils.dp2px(mContext, 116))
                            .roundRadius(AutoSizeUtils.dp2px(mContext, 8), RoundTransformation.RoundType.ALL))
                    .placeholder(R.drawable.img_loading_placeholder)
                    // 2026-09-28 用户口径：失败仍用原来的灰图（红叹号版已撤）——"图没出来"到底是
                    // DNS/拦截、超时还是 HTTP 状态码，改由收尾那行**图片加载账本**回答（by 实测：
                    // DNS 正常、失败以图站反爬为主）。by2 起也不再给本页图片打 tag 做滑动暂停 ——
                    // 暂停会让请求反复重排队尾，反而让图片"大面积变慢"。
                    .error(R.drawable.img_loading_placeholder)
                    .into(ivThumb, new com.squareup.picasso.Callback() {
                        @Override
                        public void onSuccess() {
                            ivThumb.setTag(IMG_OK);
                        }

                        @Override
                        public void onError(Exception e) {
                            ivThumb.setTag(IMG_ERR);
                        }
                    });
        } else {
            EMPTY_PIC.incrementAndGet();
            ivThumb.setTag(IMG_PH);
            ivThumb.setImageResource(R.drawable.img_loading_placeholder);
        }

    }
}