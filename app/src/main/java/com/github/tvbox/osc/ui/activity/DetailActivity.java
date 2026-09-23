package com.github.tvbox.osc.ui.activity;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;

import com.blankj.utilcode.util.AppUtils;
import com.blankj.utilcode.util.ConvertUtils;
import com.blankj.utilcode.util.LogUtils;
import com.blankj.utilcode.util.NotificationUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.constant.IntentKey;
import com.github.tvbox.osc.databinding.ActivityDetailBinding;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.receiver.BatteryReceiver;
import com.github.tvbox.osc.ui.adapter.ParseAdapter;
import com.github.tvbox.osc.ui.adapter.SeriesAdapter;
import com.github.tvbox.osc.ui.adapter.SeriesFlagAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.AllVodSeriesBottomDialog;
import com.github.tvbox.osc.ui.dialog.AllVodSeriesRightDialog;
import com.github.tvbox.osc.ui.dialog.CastDialog;
import com.github.tvbox.osc.ui.dialog.QuickSearchDialog;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.fragment.PlayFragment;
import com.github.tvbox.osc.ui.widget.GridSpacingItemDecoration;
import com.github.tvbox.osc.util.DeviceProfile;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.L1Executors;
import com.github.tvbox.osc.util.PlayTrace;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.ScreenShotListenManager;
import com.github.tvbox.osc.util.SearchHelper;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.Utils;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.gyf.immersionbar.ImmersionBar;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.BasePopupView;
import com.lxj.xpopup.enums.PopupPosition;
import com.lxj.xpopup.interfaces.OnSelectListener;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author pj567
 * @date :2020/12/22
 * @description:
 */

public class DetailActivity extends BaseVbActivity<ActivityDetailBinding> {
    private PlayFragment playFragment = null;
    private SourceViewModel sourceViewModel;
    private Movie.Video mVideo;
    private VodInfo vodInfo;

    public VodInfo getVodInfo() {
        return vodInfo;
    }
    public SeriesFlagAdapter seriesFlagAdapter;
    public SeriesAdapter seriesAdapter;
    public String vodId;
    public String sourceKey;
    private View seriesFlagFocus = null;
    private boolean isReverse;
    private String preFlag = "";
    private HashMap<String, String> mCheckSources = null;
    BatteryReceiver mBatteryReceiver = new BatteryReceiver();
    //改为view模式无法自动响应返回键操作,onBackPress时手动dismiss
    private BasePopupView mAllSeriesRightDialog;
    private BasePopupView mAllSeriesBottomDialog;
    /**
     * Home键广播,用于触发后台服务
     */
    private BroadcastReceiver mHomeKeyReceiver;
    private BroadcastReceiver mRemoteActionReceiver; // PiP 远程媒体操作广播接收(后台播放移除后保留,仍用于画中画控制)

    /**
     * 截屏监听
     */
    ScreenShotListenManager screenShotListenManager;

    @Override
    protected void init() {
        initReceiver();
        initView();
        initViewModel();
        initData();
        registerReceiver(mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        ImmersionBar.with(this)
                .statusBarColor(R.color.black)
                .navigationBarColor(R.color.white)
                .fitsSystemWindows(true)
                .statusBarDarkFont(false)
                .init();
        toggleScreenShotListen(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mBinding.ivPrivateBrowsing.postDelayed(NotificationUtils::cancelAll, 800);
    }

    private void initView() {
        mBinding.ivPrivateBrowsing.setVisibility(Hawk.get(HawkConfig.PRIVATE_BROWSING, false) ? View.VISIBLE : View.GONE);
        mBinding.ivPrivateBrowsing.setOnClickListener(view -> ToastUtils.showShort("当前为无痕浏览"));
        mBinding.previewPlayerPlace.setVisibility(showPreview ? View.VISIBLE : View.GONE);

        mBinding.mGridView.setHasFixedSize(true);
        // 选集:一行3集,显示三行,上下滑动
        mBinding.mGridView.setLayoutManager(new V7GridLayoutManager(this.mContext, 3));
        mBinding.mGridView.addItemDecoration(new GridSpacingItemDecoration(3, 20, true));

        seriesAdapter = new SeriesAdapter(true);
        mBinding.mGridView.setAdapter(seriesAdapter);
        mBinding.mGridViewFlag.setHasFixedSize(true);
        seriesFlagAdapter = new SeriesFlagAdapter();
        mBinding.mGridViewFlag.setAdapter(seriesFlagAdapter);
        isReverse = false;
        preFlag = "";
        if (showPreview) {
            playFragment = new PlayFragment();
            getSupportFragmentManager().beginTransaction().add(R.id.previewPlayer, playFragment).commit();
            getSupportFragmentManager().beginTransaction().show(playFragment).commitAllowingStateLoss();
        }

        findViewById(R.id.tvDownload).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                use1DMDownload();
            }
        });
        // 投屏：与播放页同一套 CastDialog，取当前播放中的最终地址
        findViewById(R.id.tvCast).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String url = getCurrentVodUrl();
                if (playFragment == null || TextUtils.isEmpty(url)) {
                    ToastUtils.showShort("请先播放视频后再投屏");
                    return;
                }
                if (url.contains("127.0.0.1") || url.contains("localhost")) {
                    ToastUtils.showShort("该视频经过本地代理，无法投屏到局域网设备");
                    return;
                }
                String title = "";
                if (vodInfo != null) {
                    try {
                        VodInfo.VodSeries vs = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex);
                        title = vodInfo.name + " " + vs.name;
                    } catch (Exception e) {
                        title = vodInfo.name;
                    }
                }
                try {
                    // 必须经 XPopup.Builder 包装，直接 show() 会因 popupInfo 为 null 崩溃
                    new XPopup.Builder(DetailActivity.this).asCustom(new CastDialog(DetailActivity.this, url, title)).show();
                } catch (Exception e) {
                    ToastUtils.showShort("投屏暂不可用");
                }
            }
        });
        // 收藏页 / 历史浏览页入口
        // 外部播放：候选＝系统播放器 + 已安装的外部播放器（与播放页同一份清单）
        findViewById(R.id.tvExtPlayer).setOnClickListener(v -> showExternalPlayDialog());
        // 清掉「投屏 / 下载 / 外部播放」三个图标的长按气泡（只清气泡，不动无障碍描述与点击行为）
        Utils.disableTooltip(findViewById(R.id.tvCast));
        Utils.disableTooltip(findViewById(R.id.tvDownload));
        Utils.disableTooltip(findViewById(R.id.tvExtPlayer));
        findViewById(R.id.tvCollectPage).setOnClickListener(view -> jumpActivity(CollectActivity.class));
        findViewById(R.id.tvHistoryPage).setOnClickListener(view -> jumpActivity(HistoryActivity.class));
        mBinding.tvSort.setOnClickListener(new View.OnClickListener() {
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onClick(View v) {
                sortSeries();
            }
        });
        mBinding.tvCollect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = mBinding.tvCollect.getText().toString();
                if ("加入收藏".equals(text)) {
                    RoomDataManger.insertVodCollect(sourceKey, vodInfo);
                    Toast.makeText(DetailActivity.this, "已加入收藏夹", Toast.LENGTH_SHORT).show();
                    mBinding.tvCollect.setText("取消收藏");
                } else {
                    RoomDataManger.deleteVodCollect(sourceKey, vodInfo);
                    Toast.makeText(DetailActivity.this, "已移除收藏夹", Toast.LENGTH_SHORT).show();
                    mBinding.tvCollect.setText("加入收藏");
                }
            }
        });

        seriesFlagAdapter.setOnItemClickListener((adapter, view, position) -> {
            chooseFlag(position);
        });

        seriesAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                chooseSeries(position, false);
            }
        });

        mBinding.tvAllSeries.setOnClickListener(view -> {
            showAllSeriesDialog();
        });

        mBinding.tvSite.setOnClickListener(view -> {
            startQuickSearch();
            QuickSearchDialog quickSearchDialog = new QuickSearchDialog(DetailActivity.this);
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, quickSearchData));
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, quickSearchWord));
            quickSearchDialog.show();
            if (pauseRunnable != null && pauseRunnable.size() > 0) {
                // 命名 + 空闲自动回收：spider 的 searchContent 是同步阻塞调用，
                // 池线程可能长时间停在里面；core 线程可超时退出，避免逐次累积。
                searchExecutorService = L1Executors.fixed("l1box-detail-search", DeviceProfile.detailConcurrency());
                for (Runnable runnable : pauseRunnable) {
                    searchExecutorService.execute(runnable);
                }
                pauseRunnable.clear();
                pauseRunnable = null;
            }
            quickSearchDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    try {
                        if (searchExecutorService != null) {
                            pauseRunnable = searchExecutorService.shutdownNow();
                            searchExecutorService = null;
                        }
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
            });
        });
        mBinding.tvChangeLine.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            quickLineChange();
        });
        setLoadSir(mBinding.llLayout);
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    private void initReceiver() {
        // 注册广播接收器
        if (mHomeKeyReceiver == null) {
            mHomeKeyReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action != null && action.equals(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) {
                        // home key handled (no-op: 后台播放功能已移除)
                    }
                }
            };
            registerReceiver(mHomeKeyReceiver, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        }
    }

    /**
     * 排序(正/倒序切换)：反序后保持"当前在播的那一集"为高亮与 playIndex 指向项，
     * 而非跳到第 0 集，避免出现"高亮集≠在播集"的错位，并保证下一集连播方向正确。
     */
    public void sortSeries() {
        if (vodInfo != null && vodInfo.seriesMap.size() > 0) {
            List<VodInfo.VodSeries> cur = vodInfo.seriesMap.get(vodInfo.playFlag);
            // 反序前先记录"当前正在播放的集"(以 selected 为准,回退到 playIndex)
            int playing = -1;
            if (cur != null) {
                for (int j = 0; j < cur.size(); j++) {
                    if (cur.get(j).selected) { playing = j; break; }
                }
            }
            if (playing < 0) playing = vodInfo.playIndex;

            vodInfo.reverseSort = !vodInfo.reverseSort;
            isReverse = !isReverse;
            vodInfo.reverse(); // 列表反序:原本在 playing 的集,现在到了 (size-1-playing)

            if (cur != null && cur.size() > 0) {
                // 让"当前在播的集"在新顺序里依然是选中/高亮项,且 playIndex 指向它
                int newIndex = cur.size() - 1 - playing;
                if (newIndex < 0) newIndex = 0;
                vodInfo.playIndex = newIndex;
                for (int j = 0; j < cur.size(); j++) cur.get(j).selected = false;
                cur.get(newIndex).selected = true;
            }

            seriesAdapter.notifyDataSetChanged();
            updateSortText();
        }
    }

    /**
     * 排序状态文案跟随当前实际显示顺序:
     * 选集标题显示「选集（正序）」/「选集（倒序）」,右侧排序按钮仅保留图标
     */
    public void updateSortText() {
        if (mBinding == null) return;
        String order = (vodInfo != null && vodInfo.reverseSort) ? "倒序" : "正序";
        mBinding.tvSort.setText("");
        mBinding.tvSeriesTitle.setText("选集（" + order + "）");
    }

    public void showAllSeriesDialog() {
        if (fullWindows) {
            mAllSeriesRightDialog = new XPopup.Builder(this)
                    .isViewMode(true)//隐藏导航栏(手势条)在dialog模式下会闪一下,改为view模式,但需处理onBackPress的隐藏,下方同理
                    .hasNavigationBar(false)
                    .popupHeight(ScreenUtils.getScreenHeight())
                    .popupPosition(PopupPosition.Right)
                    .enableDrag(false)//禁用拖拽,内部有横向rv
                    .asCustom(new AllVodSeriesRightDialog(this));
            mAllSeriesRightDialog.show();
        } else {
            mAllSeriesBottomDialog = new XPopup.Builder(this)
                    .isViewMode(true)
                    .hasNavigationBar(false)
                    .maxHeight(ScreenUtils.getScreenHeight() - (ScreenUtils.getScreenHeight() / 4))
                    .asCustom(new AllVodSeriesBottomDialog(this, (position, text) -> {
                        chooseSeries(position, false);
                    }));
            mAllSeriesBottomDialog.show();
        }
    }

    private void chooseFlag(int position) {
        //新选中的flag
        String newFlag = seriesFlagAdapter.getData().get(position).name;
        if (vodInfo != null && !vodInfo.playFlag.equals(newFlag)) {
            for (int i = 0; i < vodInfo.seriesFlags.size(); i++) {//遍历flag集合
                VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(i);
                if (flag.name.equals(vodInfo.playFlag)) {//取消当前播放的选中状态
                    flag.selected = false;
                    seriesFlagAdapter.notifyItemChanged(i);
                    break;
                }
            }
            //新选中的flag
            VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(position);
            flag.selected = true;
            //清除上一个线路集数的选中状态
            List<VodInfo.VodSeries> currentSeriesList = vodInfo.seriesMap.get(vodInfo.playFlag);
            if (currentSeriesList.size() > vodInfo.playIndex) {//有效集数
                currentSeriesList.get(vodInfo.playIndex).selected = false;
            }
            vodInfo.playFlag = newFlag;
            seriesFlagAdapter.notifyItemChanged(position);
            refreshList();
        }
    }

    private void chooseSeries(int position, boolean reloadWithChangeLine) {
        if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
            boolean reload = false;
            for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
                seriesAdapter.getData().get(j).selected = false;
                seriesAdapter.notifyItemChanged(j);
            }
            //解决倒叙不刷新
            if (vodInfo.playIndex != position) {
                seriesAdapter.getData().get(position).selected = true;
                seriesAdapter.notifyItemChanged(position);
                vodInfo.playIndex = position;

                reload = true;
            }
            //解决当前集不刷新的BUG
            if (!preFlag.isEmpty() && !vodInfo.playFlag.equals(preFlag)) {
                reload = true;
            }

            seriesAdapter.getData().get(vodInfo.playIndex).selected = true;
            seriesAdapter.notifyItemChanged(vodInfo.playIndex);

            //选集全屏 想选集不全屏的注释下面一行
            if (!showPreview || reload || reloadWithChangeLine) {
                jumpToPlay();
            }
        }
    }

    private void initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch();
    }

    /** 来源页带来的片名，用于源详情接口缺失 vod_name 时兜底显示（不覆盖源返回的真名） */
    private String fallbackTitle = null;

    private List<Runnable> pauseRunnable = null;

    private void jumpToPlay() {
        if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
            preFlag = vodInfo.playFlag;
            //更新播放地址
            Bundle bundle = new Bundle();
            //保存历史
            insertVod(sourceKey, vodInfo);
            bundle.putString("sourceKey", sourceKey);
//            bundle.putSerializable("VodInfo", vodInfo);
            App.getInstance().setVodInfo(vodInfo);
            if (previewVodInfo == null) {
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(bos);
                    oos.writeObject(vodInfo);
                    oos.flush();
                    oos.close();
                    ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()));
                    previewVodInfo = (VodInfo) ois.readObject();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (previewVodInfo != null) {
                previewVodInfo.playerCfg = vodInfo.playerCfg;
                previewVodInfo.playFlag = vodInfo.playFlag;
                previewVodInfo.playIndex = vodInfo.playIndex;
                previewVodInfo.seriesMap = vodInfo.seriesMap;
                // 线路存档共享同一份引用：播放页往表里写（用户手动切播放器），详情页这边要能看见
                previewVodInfo.flagPlayerCfgMap = vodInfo.flagPlayerCfgMap;
//                    bundle.putSerializable("VodInfo", previewVodInfo);
                App.getInstance().setVodInfo(previewVodInfo);
            }
            playFragment.setData(bundle);

            //定位选集
            mBinding.mGridView.scrollToPosition(vodInfo.playIndex);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    void refreshList() {
        int seriesSize = vodInfo.seriesMap.get(vodInfo.playFlag).size();
        if (seriesSize > 0 && seriesSize <= vodInfo.playIndex) {//当前集数大于新选线路的总集数,设置为最后一集
            vodInfo.playIndex = seriesSize - 1;
        }

        if (vodInfo.seriesMap.get(vodInfo.playFlag) != null) {
            boolean canSelect = true;
            for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
                if (vodInfo.seriesMap.get(vodInfo.playFlag).get(j).selected) {
                    canSelect = false;
                    break;
                }
            }
            if (canSelect)
                vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex).selected = true;
        }
        seriesAdapter.setNewData(vodInfo.seriesMap.get(vodInfo.playFlag));

    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.detailResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    showSuccess();
                    mVideo = absXml.movie.videoList.get(0);
                    // 源详情缺失片名时用来源页带来的名字补上（历史/收藏因此也能存到正确片名）
                    if (TextUtils.isEmpty(mVideo.name) && !TextUtils.isEmpty(fallbackTitle)) {
                        mVideo.name = fallbackTitle;
                    }
                    vodInfo = new VodInfo();
                    // 源详情可能缺 id。历史记录的去重键是 (sourceKey, vodId)，而写入用的是 vodInfo.id
                    // （来自源详情返回的 video）、读取用的是本次请求的 vodId —— id 一空两边就对不上：
                    //   ① 每次播放都查不到旧记录 ⇒ 历史里同一部片堆出一长串；
                    //   ② 卡片上带着空 id 跳回来 ⇒ 不知道请求哪一部 ⇒ 一直转圈。
                    // 这里用本次请求的 vodId 补齐，保证同一个片子写进去、下次还读得出来。
                    if (TextUtils.isEmpty(mVideo.id)) {
                        mVideo.id = vodId;
                    }
                    vodInfo.setVideo(mVideo);
                    vodInfo.sourceKey = mVideo.sourceKey;

                    mBinding.tvName.setText(TextUtils.isEmpty(mVideo.name) ? "暂无信息" : mVideo.name);
                    String srcName = ApiConfig.get().getSourceName(mVideo.sourceKey);
                    mBinding.tvSite.setText("来源：" + (TextUtils.isEmpty(srcName) ? "未知" : srcName));

                    if (vodInfo.seriesMap != null && vodInfo.seriesMap.size() > 0) {//线路
                        mBinding.mGridViewFlag.setVisibility(View.VISIBLE);
                        mBinding.mGridView.setVisibility(View.VISIBLE);
                        mBinding.mEmptyPlaylist.setVisibility(View.GONE);

                        VodInfo vodInfoRecord = RoomDataManger.getVodInfo(sourceKey, vodId);
                        // 读取历史记录
                        if (vodInfoRecord != null) {
                            vodInfo.playIndex = Math.max(vodInfoRecord.playIndex, 0);
                            vodInfo.playFlag = vodInfoRecord.playFlag;
                            vodInfo.playerCfg = vodInfoRecord.playerCfg;
                            // 各线路的播放器存档一起恢复（没有则保持空表，按默认走）
                            if (vodInfoRecord.flagPlayerCfgMap != null) {
                                vodInfo.flagPlayerCfgMap = vodInfoRecord.flagPlayerCfgMap;
                            }
                            vodInfo.reverseSort = vodInfoRecord.reverseSort;
                        } else {
                            vodInfo.playIndex = 0;
                            vodInfo.playFlag = null;
                            vodInfo.playerCfg = "";
                            vodInfo.reverseSort = false;
                        }

                        if (vodInfo.reverseSort) {
                            vodInfo.reverse();
                        }
                        updateSortText();

                        if (vodInfo.playFlag == null || !vodInfo.seriesMap.containsKey(vodInfo.playFlag))
                            vodInfo.playFlag = (String) vodInfo.seriesMap.keySet().toArray()[0];

                        int flagScrollTo = 0;
                        for (int j = 0; j < vodInfo.seriesFlags.size(); j++) {
                            VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(j);
                            if (flag.name.equals(vodInfo.playFlag)) {
                                flagScrollTo = j;
                                flag.selected = true;
                            } else
                                flag.selected = false;
                        }
//                        setTextShow(tvPlayUrl, "播放地址：", vodInfo.seriesMap.get(vodInfo.playFlag).get(0).url);
                        //设置线路数据
                        seriesFlagAdapter.setNewData(vodInfo.seriesFlags);
                        mBinding.mGridViewFlag.scrollToPosition(flagScrollTo);

                        refreshList();
                        if (showPreview) {
                            jumpToPlay();
                            mBinding.previewPlayer.setVisibility(View.VISIBLE);
                            toggleSubtitleTextSize();
                        }
                        // startQuickSearch();
                    } else {//空布局
                        mBinding.mGridViewFlag.setVisibility(View.GONE);
                        mBinding.mGridView.setVisibility(View.GONE);
                        mBinding.mEmptyPlaylist.setVisibility(View.VISIBLE);
                    }
                } else {
                    showEmpty();
                    mBinding.previewPlayer.setVisibility(View.GONE);
                }
            }
        });
    }

    private String getHtml(String label, String content) {
        if (content == null) {
            content = "";
        }
        return label + "<font color=\"#FFFFFF\">" + content + "</font>";
    }

    private void initData() {
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            fallbackTitle = bundle.getString("title");
            loadDetail(bundle.getString("id", null), bundle.getString("sourceKey", ""));
        }
    }

    private void loadDetail(String vid, String key) {
        if (vid != null) {
            vodId = vid;
            sourceKey = key;
            // 诊断：详情页拿到的 (站点, vodId) 是什么 —— 历史记录能不能命中同一条就看这两个值
            PlayTrace.diag("详情请求 key=" + key + " vid=" + vid);
            showLoading();
            sourceViewModel.getDetail(sourceKey, vodId);
            boolean isVodCollect = RoomDataManger.isVodCollect(sourceKey, vodId);
            if (isVodCollect) {
                mBinding.tvCollect.setText("取消收藏");
            } else {
                mBinding.tvCollect.setText("加入收藏");
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_REFRESH) {
            if (event.obj != null) {
                if (event.obj instanceof Integer) {
                    int index = (int) event.obj;
                    for (int j = 0; j < vodInfo.seriesMap.get(vodInfo.playFlag).size(); j++) {
                        seriesAdapter.getData().get(j).selected = false;
                        seriesAdapter.notifyItemChanged(j);
                    }
                    seriesAdapter.getData().get(index).selected = true;
                    seriesAdapter.notifyItemChanged(index);
                    //mBinding.mGridView.setSelection(index);
                    vodInfo.playIndex = index;
                    //保存历史
                    insertVod(sourceKey, vodInfo);
                } else if (event.obj instanceof JSONObject) {
                    vodInfo.playerCfg = ((JSONObject) event.obj).toString();
                    //保存历史
                    insertVod(sourceKey, vodInfo);
                }

            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_SELECT) {
            if (event.obj != null) {
                Movie.Video video = (Movie.Video) event.obj;
                // 快捷换线重载详情时同步更新兜底名，防止新源也缺片名时显示上一个源的名字
                fallbackTitle = video.name;
                loadDetail(video.id, video.sourceKey);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_WORD_CHANGE) {
            if (event.obj != null) {
                String word = (String) event.obj;
                switchSearchWord(word);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_RESULT) {
            try {
                searchData(event.obj == null ? null : (AbsXml) event.obj);
            } catch (Exception e) {
                searchData(null);
            }
        }
    }

    private String searchTitle = "";
    private boolean hadQuickStart = false;
    private final List<Movie.Video> quickSearchData = new ArrayList<>();
    private final List<String> quickSearchWord = new ArrayList<>();
    private ExecutorService searchExecutorService = null;

    /**
     * 换源搜索超时兜底：弱网下 spider 调用没有超时保护，
     * 卡死的站点会长期占着搜索线程，反复进出详情页会持续累积。
     * 到点取消剩余请求（结果已增量展示，不影响用户已看到的换源列表）。
     */
    private static final long QUICK_SEARCH_TIMEOUT_MS = 20000L;
    private final Handler quickSearchHandler = new Handler(Looper.getMainLooper());
    private final Runnable quickSearchWatchdog = new Runnable() {
        @Override
        public void run() {
            try {
                if (searchExecutorService != null) {
                    searchExecutorService.shutdownNow();
                    searchExecutorService = null;
                }
                OkGo.getInstance().cancelTag("quick_search");
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    };

    private void switchSearchWord(String word) {
        OkGo.getInstance().cancelTag("quick_search");
        quickSearchData.clear();
        searchTitle = word;
        searchResult();
    }

    private void startQuickSearch() {
        initCheckedSourcesForSearch();
        if (hadQuickStart)
            return;
        hadQuickStart = true;
        OkGo.getInstance().cancelTag("quick_search");
        quickSearchWord.clear();
        searchTitle = mVideo.name;
        quickSearchData.clear();
        quickSearchWord.addAll(SearchHelper.splitWords(searchTitle));
        // 分词
        OkGo.<String>get("http://api.pullword.com/get.php?source=" + URLEncoder.encode(searchTitle) + "&param1=0&param2=0&json=1")
                .tag("fenci")
                .execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() != null) {
                            return response.body().string();
                        } else {
                            throw new IllegalStateException("网络请求错误");
                        }
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        String json = response.body();
                        try {
                            for (JsonElement je : new Gson().fromJson(json, JsonArray.class)) {
                                quickSearchWord.add(je.getAsJsonObject().get("t").getAsString());
                            }
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                        List<String> words = new ArrayList<>(new HashSet<>(quickSearchWord));
                        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, words));
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                    }
                });

        searchResult();
    }

    private void searchResult() {
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        // 命名 + 空闲自动回收。shutdownNow 的语义不变（新旧池行为一致），
        // 但 core 线程可超时退出 —— 即使某次搜索的线程卡在同步网络调用里，
        // 也不会像默认线程工厂那样永久驻留（原线程名 pool-NN 正是来自这里）。
        searchExecutorService = L1Executors.fixed("l1box-detail-search", DeviceProfile.detailConcurrency());
        List<SourceBean> searchRequestList = new ArrayList<>();
        // 搜索只走订阅线路的站点池；首页源（豆瓣）不提供播放资源，不参与搜索
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList());

        ArrayList<String> siteKey = new ArrayList<>();
        for (SourceBean bean : searchRequestList) {
            if (!bean.isSearchable() || !bean.isQuickSearch()) {
                continue;
            }
            // 空集合等于"没有过滤意图"：冷启动站点池未装载时 SearchHelper 会回退出一张空表，
            // 把它当作过滤条件会把全部站点滤掉（与 FastSearchActivity 同一处根因）
            if (mCheckSources != null && !mCheckSources.isEmpty() && !mCheckSources.containsKey(bean.getKey())) {
                continue;
            }
            siteKey.add(bean.getKey());
        }
        for (String key : siteKey) {
            searchExecutorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        sourceViewModel.getQuickSearch(key, searchTitle);
                    } catch (Throwable th) {
                        // Throwable 兜底：spider jar 抛 Error 也不能杀进程
                        th.printStackTrace();
                    }
                }
            });
        }
        // 每次发起换源搜索都重置计时，避免弱网下线程被卡死站点长期占用
        quickSearchHandler.removeCallbacks(quickSearchWatchdog);
        quickSearchHandler.postDelayed(quickSearchWatchdog, QUICK_SEARCH_TIMEOUT_MS);
    }

    private void searchData(AbsXml absXml) {
        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
            List<Movie.Video> data = new ArrayList<>();
            for (Movie.Video video : absXml.movie.videoList) {
                // 去除当前相同的影片
                if (video.sourceKey.equals(sourceKey) && video.id.equals(vodId))
                    continue;
                data.add(video);
            }
            quickSearchData.addAll(data);
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, data));
        }
    }

    private void insertVod(String sourceKey, VodInfo vodInfo) {
        if (Hawk.get(HawkConfig.PRIVATE_BROWSING, false)) {//无痕浏览
            return;
        }
        try {
            vodInfo.playNote = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex).name;
        } catch (Throwable th) {
            vodInfo.playNote = "";
        }
        // 诊断：卡片上显示的那行字（片名/集名/线路）就是这三个字段；id 是历史去重键的一半
        PlayTrace.diag("历史写入 片=" + vodInfo.name + " 集名=" + vodInfo.playNote + " 线路=" + vodInfo.playFlag
                + " id=" + vodInfo.id + " 图=" + (TextUtils.isEmpty(vodInfo.pic) ? "空" : "有"));
        RoomDataManger.insertVodRecord(sourceKey, vodInfo);
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
    }

    @Override
    protected void onDestroy() {
        registerActionReceiver(false);
        super.onDestroy();
        unregisterReceiver(mBatteryReceiver);
        // 注销广播接收器
        if (mHomeKeyReceiver != null) {
            unregisterReceiver(mHomeKeyReceiver);
            mHomeKeyReceiver = null;
        }

        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        quickSearchHandler.removeCallbacks(quickSearchWatchdog);
        OkGo.getInstance().cancelTag("fenci");
        OkGo.getInstance().cancelTag("detail");
        OkGo.getInstance().cancelTag("quick_search");
        toggleScreenShotListen(false);
    }

    @Override
    public void onBackPressed() {
        if (mAllSeriesRightDialog != null && mAllSeriesRightDialog.isShow()) {
            mAllSeriesRightDialog.dismiss();
            return;
        }
        if (mAllSeriesBottomDialog != null && mAllSeriesBottomDialog.isShow()) {
            mAllSeriesBottomDialog.dismiss();
            return;
        }
        if (playFragment.hideAllDialogSuccess()) {//fragment有弹窗隐藏并拦截返回
            return;
        }
        if (fullWindows) {
            toggleFullPreview();
            mBinding.mGridView.requestFocus();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && playFragment != null && fullWindows) {
            if (playFragment.dispatchKeyEvent(event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // preview
    VodInfo previewVodInfo = null;
    boolean showPreview = Hawk.get(HawkConfig.SHOW_PREVIEW, true);
    ; // true 开启 false 关闭
    boolean fullWindows = false;
    ViewGroup.LayoutParams windowsPreview = null;
    ViewGroup.LayoutParams windowsFull = null;

    public void toggleFullPreview() {
        if (windowsPreview == null) {
            windowsPreview = mBinding.previewPlayer.getLayoutParams();
        }
        if (windowsFull == null) {//全屏尺寸
            windowsFull = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        fullWindows = !fullWindows;

        //交由fragment处理播放器全屏逻辑
        playFragment.changedLandscape(fullWindows);
        //activity处理预览尺寸(全屏/非全屏预览)
        mBinding.previewPlayer.setLayoutParams(fullWindows ? windowsFull : windowsPreview);
        mBinding.mGridView.setVisibility(fullWindows ? View.GONE : View.VISIBLE);
        mBinding.mGridViewFlag.setVisibility(fullWindows ? View.GONE : View.VISIBLE);

        //全屏下禁用详情页几个按键的焦点 防止上键跑过来
        mBinding.tvSort.setFocusable(!fullWindows);
        mBinding.tvCollect.setFocusable(!fullWindows);
        toggleSubtitleTextSize();
    }

    void toggleSubtitleTextSize() {
        int subtitleTextSize = SubtitleHelper.getTextSize(this);
        if (!fullWindows) {
            subtitleTextSize *= 0.6;
        }
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE, subtitleTextSize));
    }

    /**
     * 下载：仅复制当前视频的真实播放地址到剪贴板，供用户粘贴到任意下载器。
     * 不再吊起 1DM、不弹安装引导（曾自动拉起外部下载器，行为不可预期）。
     * 详情页下载按钮与播放中复合弹窗的下载按钮共用本方法。
     */
    public void use1DMDownload() {
        if (vodInfo != null && vodInfo.seriesMap.get(vodInfo.playFlag).size() > 0) {
            VodInfo.VodSeries vod = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex);
            String url = TextUtils.isEmpty(playFragment.getFinalUrl()) ? vod.url : playFragment.getFinalUrl();
            if (TextUtils.isEmpty(url)) {
                ToastUtils.showShort("资源异常,请稍后重试");
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("video_url", url));
                ToastUtils.showShort("视频链接已复制，可粘贴到下载器");
            } else {
                ToastUtils.showShort("复制失败");
            }
        } else {
            ToastUtils.showShort("资源异常,请稍后重试");
        }
    }

    /**
     * 画中画模式
     */
    public void enterPip() {
        if (Utils.supportsPiPMode()) {
            // 创建一个Intent对象，模拟按下Home键
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            startActivity(intent);

            // Calculate Video Resolution
            int vWidth = playFragment.getPlayer().getVideoSize()[0];
            int vHeight = playFragment.getPlayer().getVideoSize()[1];
            Rational ratio;
            if (vWidth != 0) {
                if ((((double) vWidth) / ((double) vHeight)) > 2.39) {
                    vHeight = (int) (((double) vWidth) / 2.35);
                }
                ratio = new Rational(vWidth, vHeight);
            } else {
                ratio = new Rational(16, 9);
            }
            List<RemoteAction> actions = new ArrayList<>();
            actions.add(generateRemoteAction(android.R.drawable.ic_media_previous, IntentKey.BROADCAST_ACTION_PREV, "Prev", "Play Previous"));
            actions.add(generateRemoteAction(android.R.drawable.ic_media_play, IntentKey.BROADCAST_ACTION_PLAYPAUSE, "Play", "Play/Pause"));
            actions.add(generateRemoteAction(android.R.drawable.ic_media_next, IntentKey.BROADCAST_ACTION_NEXT, "Next", "Play Next"));
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(ratio)
                    .setActions(actions).build();
            playFragment.getPlayer().postDelayed(() -> {//代码模拟home键时会立即执行,toggleFullPreview中竖屏有切换横屏操作,
                if (!fullWindows) {
                    toggleFullPreview();
                }
            }, 300);
            enterPictureInPictureMode(params);
            playFragment.getController().hideBottom();

            playFragment.getPlayer().postDelayed(() -> {
                if (!playFragment.getPlayer().isPlaying()) {
                    playFragment.getController().togglePlay();
                }
            }, 400);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private RemoteAction generateRemoteAction(int iconResId, int actionCode, String title, String desc) {
        final PendingIntent intent =
                PendingIntent.getBroadcast(
                        DetailActivity.this,
                        actionCode,
                        new Intent(IntentKey.BROADCAST_ACTION).putExtra("action", actionCode),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        final Icon icon = Icon.createWithResource(DetailActivity.this, iconResId);
        return (new RemoteAction(icon, title, desc, intent));
    }

    /**
     * 事件接收广播(画中画/后台播放点击事件)
     * @param isRegister 注册/注销
     */
    private void registerActionReceiver(boolean isRegister) {
        if (isRegister) {
            mRemoteActionReceiver = new BroadcastReceiver() {

                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !intent.getAction().equals(IntentKey.BROADCAST_ACTION) || playFragment.getController() == null) {
                        return;
                    }

                    int currentStatus = intent.getIntExtra("action", 1);
                    if (currentStatus == IntentKey.BROADCAST_ACTION_PREV) {
                        playFragment.playPrevious();
                    } else if (currentStatus == IntentKey.BROADCAST_ACTION_PLAYPAUSE) {
                        playFragment.getController().togglePlay();
                    } else if (currentStatus == IntentKey.BROADCAST_ACTION_NEXT) {
                        playFragment.playNext(false);
                    } else if (currentStatus == IntentKey.BROADCAST_ACTION_CLOSE) {
                        finish();
                        NotificationUtils.cancelAll();
                    }
                }
            };
            registerReceiver(mRemoteActionReceiver, new IntentFilter(IntentKey.BROADCAST_ACTION));
        } else {
            if (mRemoteActionReceiver != null) {
                unregisterReceiver(mRemoteActionReceiver);
                mRemoteActionReceiver = null;
            }
            if (playFragment.getPlayer().isPlaying()) {// 退出画中画时,暂停播放(画中画的全屏也会触发,但全屏后会自动播放)
                playFragment.getController().togglePlay();
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        registerActionReceiver(Utils.supportsPiPMode() && isInPictureInPictureMode);
        // 画中画时隐藏播放器的旋转按钮，退出后自动恢复
        if (playFragment != null && playFragment.getController() != null) {
            playFragment.getController().onPipModeChanged(isInPictureInPictureMode);
        }
    }

    public String getCurrentVodUrl() {
        return playFragment == null ? "" : playFragment.getFinalUrl();
    }

    /**
     * 详情页「播放器」：候选＝系统 + Exo + IJK硬/软 + 已安装的外部播放器（与播放页同一份清单，09-18 口径）。
     * 地址取当前预览播放器的最终地址、请求头沿用播放中那一份，全程不新增任何网络请求。
     * 选内置内核＝写进「本片·本线路」存档并在预览里重播（作用于同线路的所有集）；
     * 选外部播放器＝仅本次吊起第三方 App，不写配置（下一集回到本线路配置）。
     */
    private void showExternalPlayDialog() {
        final String url = getCurrentVodUrl();
        if (playFragment == null || TextUtils.isEmpty(url)) {
            ToastUtils.showShort("请先播放视频后再使用外部播放器");
            return;
        }
        try {
            String title = (vodInfo == null) ? "" : vodInfo.name;
            if (vodInfo != null) {
                try {
                    VodInfo.VodSeries vs = vodInfo.seriesMap.get(vodInfo.playFlag).get(vodInfo.playIndex);
                    title = vodInfo.name + " " + vs.name;
                } catch (Exception ignored) {
                    // 取不到集名就退化成剧名，不影响吊起
                }
            }
            final String playTitle = title;
            final HashMap<String, String> headers = playFragment.getPlayingHeaders();
            final long progress = playFragment.getSavedProgressOfCurrent();
            final ArrayList<Integer> items = PlayerHelper.getPlayerPickItems();
            // 勾选要落在"当前正在用的那个播放器"上：清单里的元素就是候选项编码，按编码找位置即可。
            final int curItem = playFragment.getCurrentPlayerItem();
            int defaultPos = 0;
            for (int p = 0; p < items.size(); p++) {
                if (items.get(p) == curItem) defaultPos = p;
            }
            String curName = playFragment.getCurrentPlayerItemName();
            if (TextUtils.isEmpty(curName)) {
                curName = PlayerHelper.getPlayerItemName(PlayerHelper.defaultPlayerType(ApiConfig.get().getSource(sourceKey)));
            }
            SelectDialog<Integer> dialog = new SelectDialog<>(this);
            dialog.setTip("请选择播放器（当前：" + curName + "）");
            dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                @Override
                public void click(Integer value, int pos) {
                    try {
                        dialog.cancel();
                        if (pos < 0 || pos >= items.size()) return;
                        int item = items.get(pos);
                        if (PlayerHelper.isExternalPlayer(item)) {
                            // 外部播放器：仅本次吊起，不写配置
                            openWithExternalPlayer(item, url, playTitle, headers, progress);
                        } else if (playFragment == null || !playFragment.switchPlayerItem(item)) {
                            ToastUtils.showShort("切换失败，请稍后重试");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public String getDisplay(Integer val) {
                    return PlayerHelper.getPlayerItemName(val);
                }
            }, new DiffUtil.ItemCallback<Integer>() {
                @Override
                public boolean areItemsTheSame(@NonNull Integer oldItem, @NonNull Integer newItem) {
                    return oldItem.intValue() == newItem.intValue();
                }

                @Override
                public boolean areContentsTheSame(@NonNull Integer oldItem, @NonNull Integer newItem) {
                    return oldItem.intValue() == newItem.intValue();
                }
            }, items, defaultPos);
            dialog.show();
        } catch (Exception e) {
            e.printStackTrace();
            ToastUtils.showShort("外部播放暂不可用");
        }
    }

    /**
     * 吊起第三方播放器。失败时只说一句：详情页预览本来就还在内置播放，不需要额外回退动作
     * （播放页那边不同，吊起失败会退回内置继续播，见 PlayFragment.startPlayUrl）。
     */
    private void openWithExternalPlayer(int playerType, String url, String title, HashMap<String, String> headers, long progress) {
        boolean ok = PlayerHelper.runExternalPlayer(playerType, this, url, title, "", headers, progress);
        if (ok) {
            // 吊起成功：暂停预览，避免与第三方播放器同时出声
            if (playFragment != null) playFragment.pausePlayback();
        } else {
            ToastUtils.showShort("调用" + PlayerHelper.getPlayerName(playerType) + "失败");
        }
    }

    public void quickLineChange() {
        List<VodInfo.VodSeriesFlag> flags = seriesFlagAdapter.getData();
        if (flags.size() > 1) {
            int currentIndex = 0;
            for (int i = 0; i < flags.size(); i++) {
                if (flags.get(i).selected) {
                    currentIndex = i;
                }
            }
            currentIndex += 1;
            if (currentIndex >= flags.size()) {
                currentIndex = 0;
            }
            mBinding.mGridViewFlag.smoothScrollToPosition(currentIndex);
            chooseFlag(currentIndex);
            mBinding.mGridView.postDelayed(() -> chooseSeries(vodInfo.playIndex, true), 300);
        }
    }

    public void showParseRoot(boolean show, ParseAdapter adapter) {
        mBinding.rvParse.setAdapter(adapter);
        int defaultIndex = 0;
        for (int i = 0; i < adapter.getData().size(); i++) {
            if (adapter.getData().get(i).isDefault()) {
                defaultIndex = i;
                break;
            }
        }
        if (defaultIndex != 0) {
            mBinding.rvParse.scrollToPosition(defaultIndex);
        }
        mBinding.parseRoot.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void toggleScreenShotListen(boolean open) {
        if (open){
            if (screenShotListenManager == null){
                screenShotListenManager = ScreenShotListenManager.newInstance(this);
            }
            screenShotListenManager.setListener(imagePath -> {

                if (playFragment.getPlayer().isInPlaybackState())return;

                new XPopup.Builder(this)
                        .isDarkTheme(Utils.isDarkTheme())
                        .asCenterList("",new String[]{"跳转阿狸","跳转优汐","跳转夸父","关闭"}, null, (position, text) -> {
                            String pkg = "";
                            String cls = "";
                            switch (position){
                                case 0:
                                    pkg = "com.alicloud.databox";
                                    cls = "com.alicloud.databox.launcher.splash.SplashActivity";
                                    break;
                                case 1:
                                    pkg = "com.UCMobile";
                                    cls = "com.uc.browser.InnerUCMobile";
                                    break;
                                case 2:
                                    pkg = "com.quark.browser";
                                    cls = "com.ucpro.MainActivity";
                                    break;
                                case 3:
                                    return;
                            }
                            try {
                                startActivity(new Intent().setComponent(new ComponentName(pkg, cls)));
                            }catch (Exception e){
                                ToastUtils.showShort("未找到应用");
                            }
                        })
                        .show();
            });
            screenShotListenManager.startListen();
        }else {
            if (screenShotListenManager != null) {
                screenShotListenManager.stopListen();
            }
        }
    }
}
