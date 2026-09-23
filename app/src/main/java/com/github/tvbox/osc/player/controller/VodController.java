package com.github.tvbox.osc.player.controller;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.subtitle.widget.SimpleSubtitleView;
import com.github.tvbox.osc.ui.adapter.ParseAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.widget.MyBatteryView;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.ScreenUtils;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.Utils;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import java.util.Date;

import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

import static xyz.doikki.videoplayer.util.PlayerUtils.stringForTime;

public class VodController extends BaseController {

    // 旋转：彻底关闭陀螺仪，仅手动控制；长按旋转按钮锁定/解锁；
    // 进入全屏=横向(顺时针90°)，点击循环 横屏→反向横屏→竖屏→反向竖屏→回到横屏
    private int mRotateStep = 0;
    private boolean mRotateLocked = false;
    private boolean mIsFullScreen = false; // 来自 PlayFragment.changedLandscape(b)
    private static final int[] ROTATION_ORIENTATIONS = {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,            // 0 进入全屏(顺时针90°)
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,   // 1 对称横向全屏
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,            // 2 正向竖屏
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT      // 3 反向竖屏
    };
    private ImageView mRotateBtn;
    /** 是否处于画中画模式：PiP 下隐藏旋转按钮，退出后由 changedLandscape 恢复 */
    private boolean mInPip = false;
    /** 操作栏应否可见，由 1002/1003 维护；旋转按钮据此同步（读 View 状态会受淡出动画中间态干扰） */
    private boolean mBottomVisible = false;

    /**
     * 旋转按钮延寿中：点旋转后操作栏照旧立刻收起（好让用户看清旋转结果），
     * 但旋转按钮自己留下 {@link #dismissTimeOperationBar} 毫秒，便于接着点下一档；
     * 期内再点则重新计时。上游原版让它全屏常驻，正是为了"点完还能点下一档"，
     * 接入操作栏显隐后必须把这个例外补回来，否则点一次就再也点不到第二档。
     */
    private boolean mRotateKeepAlive = false;

    private final Runnable mRotateKeepAliveEnd = () -> {
        if (!mRotateKeepAlive) return;
        mRotateKeepAlive = false;
        if (mBottomVisible) return; // 操作栏已被唤回，按钮重新归它管
        applyRotateBtnVisibility(false);
    };

    public VodController(@NonNull @NotNull Context context) {
        super(context);
        mHandlerCallback = new HandlerCallback() {
            @Override
            public void callback(Message msg) {
                switch (msg.what) {
                    case 1000: { // seek 刷新
                        mProgressRoot.setVisibility(VISIBLE);
                        break;
                    }
                    case 1001: { // seek 关闭
                        mProgressRoot.setVisibility(GONE);
                        break;
                    }
                    case 1002: { // 显示底部菜单
                        mBottomVisible = true;
                        // 操作栏回来了：延寿作废，旋转按钮重新跟它同显同隐
                        clearRotateKeepAlive();
                        toggleViewShowWithAlpha(mBottomRoot, true);
                        toggleViewShowWithAlpha(mTopRoot1, true);
                        toggleViewShowWithAlpha(mTopRoot2, true);
                        if (!isLock){// 未上锁,随底部显示
                            toggleViewShowWithAlpha(mLockView, true);
                        }
                        applyRotateBtnVisibility(true);// 旋转按钮随操作栏一起出现
                        mNextBtn.requestFocus();
                        break;
                    }
                    case 1003: { // 隐藏底部菜单
                        mBottomVisible = false;
                        toggleViewShowWithAlpha(mBottomRoot, false);
                        toggleViewShowWithAlpha(mTopRoot1, false);
                        toggleViewShowWithAlpha(mTopRoot2, false);
                        if (!isLock){// 未上锁,随底部显示
                            toggleViewShowWithAlpha(mLockView, false);
                        }
                        applyRotateBtnVisibility(false);// 旋转按钮随操作栏一起隐藏,不再残留在画面上
                        if (listener != null) {
                            listener.onHideBottom();
                        }
                        break;
                    }
                    case 1004: { // 设置速度
                        if (isInPlaybackState()) {
                            try {
                                float speed = (float) mPlayerConfig.getDouble("sp");
                                mControlWrapper.setSpeed(speed);
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        } else
                            mHandler.sendEmptyMessageDelayed(1004, 100);
                        break;
                    }
                    case 1005: { // 下一集倒计时（每秒一跳）
                        if (!mNextTipShowing) break;
                        mNextTipCount--;
                        if (mNextTipCount <= 0) {
                            playNextEpisode();
                            break;
                        }
                        if (mNextTipText != null)
                            mNextTipText.setText(mNextTipCount + " 秒后播放下一集");
                        mHandler.sendEmptyMessageDelayed(1005, 1000);
                        break;
                    }
                    case 1006: { // 暂停期缓冲补刷（只动灰色缓冲段）
                        // 双条件守卫：暂停态 + 窗口可见。不能只信状态回调 ——
                        // 退后台时「窗口先变为不可见、暂停回调后到」的顺序会让计时器在后台空转；
                        // View 未 attach 时 getWindowVisibility() 返回 GONE，天然自停。
                        if (videoPlayState != VideoView.STATE_PAUSED || getWindowVisibility() != VISIBLE) break;
                        updateBufferProgress();
                        mHandler.sendEmptyMessageDelayed(1006, 1000);
                        break;
                    }
                }
            }
        };
    }

    private LinearLayout mLlSpeed;
    TextView mTvSpeedTip;
    SeekBar mSeekBar;
    TextView mCurrentTime;
    TextView mTotalTime;
    boolean mIsDragging;
    View mProgressRoot;
    TextView mProgressText;
    ImageView mProgressIcon;
    LinearLayout mBottomRoot;
    LinearLayout mTopRoot1;
    View mTopRoot2;
    LinearLayout mParseRoot;
    TvRecyclerView mGridView;
    TextView mPlayTitle1;
    TextView mPlayLoadNetSpeedRightTop;
    ImageView mNextBtn;
    ImageView mPreBtn;
    public TextView mPlayerScaleBtn;
    public TextView mPlayerSpeedBtn;
    public TextView mPlayerBtn;
    public TextView mPlayerIJKBtn;
    /** 播放器选择按钮：弹出「系统 + Exo + IJK硬/软 + 已安装外部」清单（见 PlayerHelper.getPlayerPickItems） */
    public TextView mPlayerExtBtn;
    public TextView mPlayerTimeStartEndText;
    public TextView mPlayerTimeStartBtn;
    public TextView mPlayerTimeSkipBtn;
    public TextView mPlayerTimeResetBtn;
    TextView mPlayPauseTime;
    TextView mPlayLoadNetSpeed;
    TextView mVideoSize;
    public SimpleSubtitleView mSubtitleView;
    public TextView mZimuBtn;
    public TextView mAudioTrackBtn;
    public TextView mLandscapePortraitBtn;
    private ImageView mIvPlayStatus;
    private View mChooseSeries;
    public MyBatteryView mMyBatteryView;
    private View mTopRightDeviceInfo;
    public TextView mPlayRetry;
    public TextView mPlayRefresh;
    ImageView mLockView;
    Handler myHandle;
    Runnable myRunnable;
    int dismissTimeOperationBar = 5000;//闲置多少毫秒隐藏操作栏(上中下)  默认6秒
    int dismissTimeLock = 2000;//闲置多少毫秒隐藏已上锁按钮

    /** 下一集倒计时浮层：播完（或片尾到点）不直接跳集，先给几秒反悔时间 */
    private static final int NEXT_TIP_SECONDS = 5;
    private View mNextTipRoot;
    private TextView mNextTipText;
    private boolean mNextTipShowing = false;
    private int mNextTipCount = 0;
    /**
     * 用户已点过「取消」：本集之内不再弹连播浮层。
     * 片尾跳过点取消后视频会继续播到真正的结尾，并再次触发 STATE_PLAYBACK_COMPLETED；
     * 若无此开关，取消后约 et 秒仍会被静默跳集，"取消"等于无效。
     * 每集起播时由 resetSpeed() 复位（与 skipEnd 同一时机）。
     */
    private boolean mNextTipCancelled = false;

    int videoPlayState = 0;
    LockRunnable lockRunnable = new LockRunnable();
    private boolean isLock = false;
    private ParseAdapter mParseAdapter;

    private Runnable myRunnable2 = new Runnable() {
        @Override
        public void run() {
            Date date = new Date();
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
            mPlayPauseTime.setText(timeFormat.format(date));
            String speed = PlayerHelper.getDisplaySpeed(mControlWrapper.getTcpSpeed());
            mPlayLoadNetSpeedRightTop.setText(speed);
            mPlayLoadNetSpeed.setText(speed);

            if (mControlWrapper.getVideoSize()[0] > 0 && mControlWrapper.getVideoSize()[1] > 0) {
                String width = Integer.toString(mControlWrapper.getVideoSize()[0]);
                String height = Integer.toString(mControlWrapper.getVideoSize()[1]);
                mVideoSize.setText(width + " x " + height);
            }

            mHandler.postDelayed(this, 1000);
        }
    };
    private class LockRunnable implements Runnable {
        @Override
        public void run() {
            if (isLock){//上锁的才隐藏,非上锁状态随操作栏显示隐藏
                mLockView.setVisibility(GONE);
            }
        }
    }

    @Override
    protected void initView() {
        super.initView();
        View pip = findViewById(R.id.pip);
        pip.setVisibility((Utils.supportsPiPMode() && Hawk.get(HawkConfig.BACKGROUND_PLAY_TYPE, 0) == 2)?VISIBLE:GONE);
        mMyBatteryView = findViewById(R.id.battery);
        mTopRightDeviceInfo = findViewById(R.id.container_top_right_device_info);
        mLlSpeed = findViewById(R.id.ll_speed);
        mTvSpeedTip = findViewById(R.id.tv_speed);
        mCurrentTime = findViewById(R.id.curr_time);
        mTotalTime = findViewById(R.id.total_time);
        mPlayTitle1 = findViewById(R.id.tv_info_name1);
        mPlayLoadNetSpeedRightTop = findViewById(R.id.tv_play_load_net_speed_right_top);
        mSeekBar = findViewById(R.id.seekBar);
        mProgressRoot = findViewById(R.id.tv_progress_container);
        mProgressIcon = findViewById(R.id.tv_progress_icon);
        mProgressText = findViewById(R.id.tv_progress_text);
        mBottomRoot = findViewById(R.id.bottom_container);
        mTopRoot1 = findViewById(R.id.tv_top_l_container);
        mTopRoot2 = findViewById(R.id.tv_top_r_container);
        mParseRoot = findViewById(R.id.parse_root);
        mGridView = findViewById(R.id.mGridView);
        mNextBtn = findViewById(R.id.play_next);
        mPreBtn = findViewById(R.id.play_pre);
        mPlayerScaleBtn = findViewById(R.id.play_scale);
        mPlayerSpeedBtn = findViewById(R.id.play_speed);
        mPlayerBtn = findViewById(R.id.play_player);
        mPlayerIJKBtn = findViewById(R.id.play_ijk);
        mPlayerExtBtn = findViewById(R.id.play_ext);
        mPlayerTimeStartEndText = findViewById(R.id.play_time_start_end_text);
        mPlayerTimeStartBtn = findViewById(R.id.play_time_start);
        mPlayerTimeSkipBtn = findViewById(R.id.play_time_end);
        mPlayerTimeResetBtn = findViewById(R.id.play_time_reset);
        mPlayPauseTime = findViewById(R.id.tv_sys_time);
        mPlayLoadNetSpeed = findViewById(R.id.tv_play_load_net_speed);
        mVideoSize = findViewById(R.id.tv_videosize);
        mSubtitleView = findViewById(R.id.subtitle_view);
        mZimuBtn = findViewById(R.id.zimu_select);
        mAudioTrackBtn = findViewById(R.id.audio_track_select);
        mLandscapePortraitBtn = findViewById(R.id.landscape_portrait);
        // 清掉长按气泡（不改无障碍描述，也不影响长按原有的旋转切换行为）
        Utils.disableTooltip(mLandscapePortraitBtn);
        mIvPlayStatus = findViewById(R.id.play_status);
        mChooseSeries = findViewById(R.id.choose_series);
        mLockView = findViewById(R.id.iv_lock);

        // 下一集倒计时浮层：可抢跳「立即播放」，或「取消」留在当前集
        mNextTipRoot = findViewById(R.id.next_episode_tip);
        mNextTipText = findViewById(R.id.next_episode_tip_text);
        findViewById(R.id.next_episode_play).setOnClickListener(v -> playNextEpisode());
        findViewById(R.id.next_episode_cancel).setOnClickListener(v -> dismissNextEpisodeTip());

        // 旋转按钮：全屏时浮动在左侧中部；点击循环旋转，长按锁定/解锁
        mRotateBtn = findViewById(R.id.rotate_screen);
        // 操作栏布局默认可见且不经过 1002，这里按真实状态取初值，保证旋转按钮首次与它一致
        mBottomVisible = isBottomVisible();
        mRotateBtn.setOnClickListener(v -> {
            if (mRotateLocked) {
                Toast.makeText(getContext(), "旋转已锁定，长按解锁", Toast.LENGTH_LONG).show();
                keepRotateBtnForNextTap(); // 刚点过它：同样延寿，方便马上长按解锁
                return;
            }
            mRotateStep = (mRotateStep + 1) % 4;
            applyRotation();
            keepRotateBtnForNextTap();
        });
        mRotateBtn.setOnLongClickListener(v -> {
            toggleRotateLock();
            keepRotateBtnForNextTap();
            return true;
        });

        ImageView mCastBtn = findViewById(R.id.iv_cast);
        Utils.disableTooltip(mCastBtn);
        mCastBtn.setOnClickListener(v -> {
            if (listener != null) listener.cast();
            hideBottom();
        });

        initSubtitleInfo();

        myHandle = new Handler(Looper.getMainLooper());

        mLockView.setOnClickListener(v -> {
            isLock = !isLock;
            if (isLock){// 上了锁
                mLockView.setImageResource(R.drawable.ic_lock);
                hideBottom();
                mHandler.removeCallbacks(lockRunnable);
                mHandler.postDelayed(lockRunnable,dismissTimeLock);
            }else {// 解了锁
                mLockView.setImageResource(R.drawable.ic_unlock);
                showBottom();
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            }
        });
        View rootView = findViewById(R.id.rootView);
        rootView.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (isLock) {
                    if (event.getAction() == MotionEvent.ACTION_UP) {//短暂显示上锁view,lockRunnable统一隐藏上锁view
                        mLockView.setVisibility(VISIBLE);
                        mHandler.removeCallbacks(lockRunnable);
                        mHandler.postDelayed(lockRunnable, dismissTimeLock);
                    }
                }
                return isLock;
            }
        });

        myRunnable = this::hideBottom;

        mPlayPauseTime.post(new Runnable() {
            @Override
            public void run() {
                mHandler.post(myRunnable2);
            }
        });

        mGridView.setLayoutManager(new V7LinearLayoutManager(getContext(), 0, false));
        mParseAdapter = new ParseAdapter();
        mParseAdapter.setOnItemClickListener((adapter, view, position) -> {
            ParseBean parseBean = mParseAdapter.getItem(position);
            // 当前默认解析需要刷新
            int currentDefault = mParseAdapter.getData().indexOf(ApiConfig.get().getDefaultParse());
            mParseAdapter.notifyItemChanged(currentDefault);
            ApiConfig.get().setDefaultParse(parseBean);
            mParseAdapter.notifyItemChanged(position);
            listener.changeParse(parseBean);
            hideBottom();
        });
        mGridView.setAdapter(mParseAdapter);
        mParseAdapter.setNewData(ApiConfig.get().getParseBeanList());

        //mParseRoot.setVisibility(VISIBLE);

        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }

                long duration = mControlWrapper.getDuration();
                long newPosition = (duration * progress) / seekBar.getMax();
                if (mCurrentTime != null)
                    mCurrentTime.setText(stringForTime((int) newPosition));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mIsDragging = true;
                mControlWrapper.stopProgress();
                mControlWrapper.stopFadeOut();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
                long duration = mControlWrapper.getDuration();
                long newPosition = (duration * seekBar.getProgress()) / seekBar.getMax();
                mControlWrapper.seekTo((int) newPosition);
                mIsDragging = false;
                mControlWrapper.startProgress();
                mControlWrapper.startFadeOut();
            }
        });

        mTopRoot1.setOnClickListener(view -> listener.exit());

        mPlayRetry = findViewById(R.id.play_retry);
        mPlayRetry.setOnClickListener(v -> {
            listener.replay(true);
            hideBottom();
        });
        mPlayRefresh = findViewById(R.id.play_refresh);
        mPlayRefresh.setOnClickListener(v -> {
            listener.replay(false);
            hideBottom();
        });
        mIvPlayStatus.setOnClickListener(view -> {
            togglePlay();
            if (videoPlayState == VideoView.STATE_PLAYING) {
                myHandle.removeCallbacks(myRunnable);
                myHandle.postDelayed(myRunnable, 300);
            }
        });
        mNextBtn.setOnClickListener(view -> {
            listener.playNext(false);
            hideBottom();
        });
        mPreBtn.setOnClickListener(view -> {
            listener.playPre();
            hideBottom();
        });
        findViewById(R.id.setting).setOnClickListener(view -> {
            hideBottom();
            listener.showSetting();
        });
        findViewById(R.id.iv_fullscreen).setOnClickListener(view -> {
            listener.toggleFullScreen();
            hideBottom();
        });
        pip.setOnClickListener(view -> {//画中画
            if (isInPlaybackState()){
                listener.pip();
                hideBottom();
            }
        });
        mPlayerScaleBtn.setOnClickListener(view -> {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            try {
                int scaleType = mPlayerConfig.getInt("sc");
                scaleType++;
                if (scaleType > 5)
                    scaleType = 0;
                mPlayerConfig.put("sc", scaleType);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
                mControlWrapper.setScreenScaleType(scaleType);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });
        mPlayerSpeedBtn.setOnClickListener(view -> setSpeed(""));

        mPlayerSpeedBtn.setOnLongClickListener(view -> {
            try {
                mPlayerConfig.put("sp", 3.0f);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
                speed_old = 3.0f;
                mControlWrapper.setSpeed(3.0f);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return true;
        });
        mPlayerBtn.setOnClickListener(view -> {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            // 短按只在内置播放器（含硬/软解码档）之间循环，顺序写死 Exo → IJK硬解 → IJK软解 → Exo。
            // 原先这里取的是「已存在的播放器列表」里的下一项，那份列表的顺序来自 HashMap（不确定）
            // 且混着外部播放器编号，短按会直接唤起 MX/VLC/Kodi；外部播放器只在右侧「外部」按钮的清单里出现。
            int nextItem = PlayerHelper.nextBuiltinItem(mPlayerConfig);
            if (PlayerHelper.applyPlayerItem(mPlayerConfig, nextItem)) {
                // 用户手动选的播放器：打标记，之后不再被设置页默认覆盖（09-18 口径）
                PlayerHelper.markUserPicked(mPlayerConfig);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
                listener.replay(false);
                hideBottom();
            }
            mPlayerBtn.requestFocus();
            mPlayerBtn.requestFocusFromTouch();
        });

        // 播放器按钮不再有长按行为（用户 09-17 拍板取消），选择播放器改由右侧这颗按钮点击弹出。
        // 09-18 口径：清单与详情页共用同一份（系统 + Exo + IJK硬/软 + 已装外部）——
        //   内置项＝写进「本片·本线路」存档（作用于同线路的所有集）；
        //   外部项＝仅本次吊起，不写配置（下一集回到本线路配置）。
        mPlayerExtBtn.setOnClickListener(view -> {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            FastClickCheckUtil.check(view);
            try {
                // 清单由"本机有没有 + 订阅给了哪些解码档"决定，缺省至少含系统播放器，故无需判空。
                final ArrayList<Integer> items = PlayerHelper.getPlayerPickItems();
                final int curItem = (mPlayerConfig == null) ? PlayerHelper.BUILTIN_EXO : PlayerHelper.currentPlayerItem(mPlayerConfig);
                int defaultPos = 0;
                for (int p = 0; p < items.size(); p++) {
                    if (items.get(p) == curItem) defaultPos = p;
                }
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip("请选择播放器（当前：" + PlayerHelper.getPlayerItemName(curItem) + "）");
                dialog.setAdapter(new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        try {
                            dialog.cancel();
                            if (pos < 0 || pos >= items.size()) return;
                            int item = items.get(pos);
                            if (PlayerHelper.isExternalPlayer(item)) {
                                // 外部播放器：仅本次吊起，本线路配置原样保留
                                listener.playExternal(item);
                                hideBottom();
                                return;
                            }
                            if (item == curItem) return;
                            if (PlayerHelper.applyPlayerItem(mPlayerConfig, item)) {
                                // 用户手动选的播放器：打标记，之后不再被设置页默认覆盖
                                PlayerHelper.markUserPicked(mPlayerConfig);
                                updatePlayerCfgView();
                                listener.updatePlayerCfg();
                                listener.replay(false);
                                hideBottom();
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        mPlayerExtBtn.requestFocus();
                        mPlayerExtBtn.requestFocusFromTouch();
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return PlayerHelper.getPlayerItemName(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, items, defaultPos);
                dialog.show();
            } catch (Exception e) {
                // 这里不再捕获 JSONException：候选项走 PlayerHelper 的方法，不再直接读写 JSONObject
                e.printStackTrace();
            }
        });
        mPlayerIJKBtn.setOnClickListener(view -> {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            try {
                String ijk = mPlayerConfig.getString("ijk");
                List<IJKCode> codecs = ApiConfig.get().getIjkCodes();
                for (int i = 0; i < codecs.size(); i++) {
                    if (ijk.equals(codecs.get(i).getName())) {
                        if (i >= codecs.size() - 1)
                            ijk = codecs.get(0).getName();
                        else {
                            ijk = codecs.get(i + 1).getName();
                        }
                        break;
                    }
                }
                mPlayerConfig.put("ijk", ijk);
                // 这颗「解析档」按钮切的就是 IJK 的硬解/软解，与弹窗里选「IJK播放器(硬解码/软解码)」
                // 是同一件事，同样算"用户手动选过播放器"。不打这个标记的话，配置里只留下了改过的
                // 档名而没有 plt，下次进这片子会被设置页默认的档位冲掉 —— 两个入口的口径就不一致了。
                PlayerHelper.markUserPicked(mPlayerConfig);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
                listener.replay(false);
                hideBottom();
            } catch (JSONException e) {
                e.printStackTrace();
            }
            mPlayerIJKBtn.requestFocus();
            mPlayerIJKBtn.requestFocusFromTouch();
        });
//        增加播放页面片头片尾时间重置
        mPlayerTimeResetBtn.setOnClickListener(v -> {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            try {
                mPlayerConfig.put("et", 0);
                mPlayerConfig.put("st", 0);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });
        mPlayerTimeStartBtn.setOnClickListener(view -> {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            try {
                int current = (int) mControlWrapper.getCurrentPosition();
                int duration = (int) mControlWrapper.getDuration();
                if (current > duration / 2) return;
                mPlayerConfig.put("st", current / 1000);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });
        mPlayerTimeStartBtn.setOnLongClickListener(view -> {
            try {
                mPlayerConfig.put("st", 0);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return true;
        });
        mPlayerTimeSkipBtn.setOnClickListener(view -> {
            myHandle.removeCallbacks(myRunnable);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            try {
                int current = (int) mControlWrapper.getCurrentPosition();
                int duration = (int) mControlWrapper.getDuration();
                if (current < duration / 2) return;
                mPlayerConfig.put("et", (duration - current) / 1000);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });
        mPlayerTimeSkipBtn.setOnLongClickListener(view -> {
            try {
                mPlayerConfig.put("et", 0);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return true;
        });
        mZimuBtn.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            listener.selectSubtitle();
            hideBottom();
        });
        mAudioTrackBtn.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            listener.selectAudioTrack();
            hideBottom();
        });
        mLandscapePortraitBtn.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            setLandscapePortrait();
            hideBottom();
        });
        mNextBtn.setNextFocusLeftId(R.id.play_time_start);
        mChooseSeries.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            hideBottom();
            listener.chooseSeries();
        });

        findViewById(R.id.container_playing_setting).setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    // User is scrolling, remove callbacks
                    myHandle.removeCallbacks(myRunnable);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // User stopped scrolling, post callbacks
                    myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
                    break;
            }
            return false;
        });
    }

    public void setSpeed(String speedStr) {
        myHandle.removeCallbacks(myRunnable);
        myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
        try {
            float speed = (float) mPlayerConfig.getDouble("sp");
            if (TextUtils.isEmpty(speedStr)) {// 未设置.点击切换
                speed += 0.25f;
                if (speed > 3)
                    speed = 0.5f;
            } else {
                speed = Float.parseFloat(speedStr);
            }
            mPlayerConfig.put("sp", speed);
            updatePlayerCfgView();
            listener.updatePlayerCfg();
            speed_old = speed;
            mControlWrapper.setSpeed(speed);
        } catch (Exception e) {
            ToastUtils.showShort("倍速参数异常");
            e.printStackTrace();
        }
    }

    private void hideLiveAboutBtn() {
        if (mControlWrapper != null && mControlWrapper.getDuration() == 0) {
            mPlayerSpeedBtn.setVisibility(GONE);
            mPlayerTimeStartEndText.setVisibility(GONE);
            mPlayerTimeStartBtn.setVisibility(GONE);
            mPlayerTimeSkipBtn.setVisibility(GONE);
            mPlayerTimeResetBtn.setVisibility(GONE);
            mNextBtn.setNextFocusLeftId(R.id.zimu_select);
        } else {
            mPlayerSpeedBtn.setVisibility(View.VISIBLE);
            mPlayerTimeStartEndText.setVisibility(View.VISIBLE);
            mPlayerTimeStartBtn.setVisibility(View.VISIBLE);
            mPlayerTimeSkipBtn.setVisibility(View.VISIBLE);
            mPlayerTimeResetBtn.setVisibility(View.VISIBLE);
            mNextBtn.setNextFocusLeftId(R.id.play_time_start);
        }
    }

    public void initLandscapePortraitBtnInfo() {
        if (mControlWrapper != null && mActivity != null) {
            int width = mControlWrapper.getVideoSize()[0];
            int height = mControlWrapper.getVideoSize()[1];
            double screenSqrt = ScreenUtils.getSqrt(mActivity);
            if (screenSqrt < 10.0 && width < height) {
                mLandscapePortraitBtn.setVisibility(View.VISIBLE);
                mLandscapePortraitBtn.setText("竖屏");
            }
        }
    }

    /**
     * 横竖屏切换
     */
    void setLandscapePortrait() {
        if (com.blankj.utilcode.util.ScreenUtils.isPortrait()){
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }else {
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    /**
     * 应用当前旋转步：0横屏/1反向横屏/2竖屏/3反向竖屏（用户指定循环顺序）
     */
    private void applyRotation() {
        if (mActivity == null) return;
        mActivity.setRequestedOrientation(ROTATION_ORIENTATIONS[mRotateStep]);
        if (mRotateBtn != null) {
            mRotateBtn.setAlpha(mRotateLocked ? 0.5f : 1.0f);
        }
    }

    /**
     * 旋转按钮显隐：仅"全屏且非画中画"时可见，可见时与操作栏同步淡入淡出。
     * 必须统一走 alpha 动画——若直接 setVisibility(VISIBLE)，上一次淡出残留的 alpha=0
     * 会让按钮变成"看不见但能点"的隐形控件。
     */
    private void applyRotateBtnVisibility(boolean show) {
        if (mRotateBtn == null) return;
        mRotateBtn.animate().cancel();
        // 延寿例外：刚点过旋转、操作栏已收起时，按钮不跟着走 —— 起/续 5 秒倒计时，让用户接着点下一档
        if (!show && mRotateKeepAlive && !mBottomVisible && mIsFullScreen && !mInPip) {
            myHandle.removeCallbacks(mRotateKeepAliveEnd);
            myHandle.postDelayed(mRotateKeepAliveEnd, dismissTimeOperationBar);
            return;
        }
        if (show && mIsFullScreen && !mInPip) {
            mRotateBtn.setVisibility(VISIBLE);
            mRotateBtn.animate().alpha(mRotateLocked ? 0.5f : 1.0f)
                    .setDuration(100)
                    .setInterpolator(new AccelerateInterpolator())
                    .start();
        } else {
            mRotateBtn.animate().alpha(0.0f)
                    .setDuration(100)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> mRotateBtn.setVisibility(GONE))
                    .start();
        }
    }

    /**
     * 点旋转后调用：操作栏照旧立刻收起（好让用户看清旋转结果），旋转按钮自己留下并起 5 秒倒计时。
     * 连点下一档不必重新唤出操作栏；停手 5 秒后由 {@link #mRotateKeepAliveEnd} 收起它。
     */
    private void keepRotateBtnForNextTap() {
        mRotateKeepAlive = true;
        // 操作栏已收起，那个闲置计时留着只会在几秒后再发一次 hideBottom 把延寿顶掉
        myHandle.removeCallbacks(myRunnable);
        hideBottom();
    }

    /** 延寿作废（操作栏被唤回 / 退出全屏 / 画中画 / 页面销毁）：按钮重新归操作栏管 */
    private void clearRotateKeepAlive() {
        mRotateKeepAlive = false;
        myHandle.removeCallbacks(mRotateKeepAliveEnd);
    }

    /**
     * 长按锁定/解锁旋转；锁定时禁止手动旋转
     */
    private void toggleRotateLock() {
        mRotateLocked = !mRotateLocked;
        if (mRotateLocked) {
            if (mRotateBtn != null) mRotateBtn.setImageResource(R.drawable.ic_lock);
            Toast.makeText(getContext(), "已锁定旋转", Toast.LENGTH_LONG).show();
        } else {
            if (mRotateBtn != null) mRotateBtn.setImageResource(R.drawable.ic_rotate);
            Toast.makeText(getContext(), "已解锁旋转", Toast.LENGTH_LONG).show();
        }
        applyRotation();
    }

    void initSubtitleInfo() {
        int subtitleTextSize = SubtitleHelper.getTextSize(mActivity);
        mSubtitleView.setTextSize(subtitleTextSize);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.player_vod_control_view;
    }

    public void showParse(boolean userJxList) {
        //mParseRoot.setVisibility(userJxList ? VISIBLE : GONE);
        if (listener!=null && mParseAdapter!=null){
            listener.showParseRoot(userJxList,mParseAdapter);
        }
    }

    /**
     * 竖屏视频禁用全屏入口
     */
    public void setFullscreenAllowed(boolean allowed) {
        View fullBtn = findViewById(R.id.iv_fullscreen);
        if (fullBtn != null) fullBtn.setVisibility(allowed ? View.VISIBLE : View.GONE);
    }

    private JSONObject mPlayerConfig = null;

    public void setPlayerConfig(JSONObject playerCfg) {
        this.mPlayerConfig = playerCfg;
        updatePlayerCfgView();
    }

    /** 按当前配置刷新控制条按钮文案。public：详情页「外部播放」切到系统播放器时也要同步刷新（PlayFragment 调用）。 */
    public void updatePlayerCfgView() {
        try {
            int playerType = mPlayerConfig.getInt("pl");
            mPlayerBtn.setText(PlayerHelper.getPlayerName(playerType));
            mPlayerScaleBtn.setText(PlayerHelper.getScaleName(mPlayerConfig.getInt("sc")));
            mPlayerIJKBtn.setText(mPlayerConfig.getString("ijk"));
            mPlayerIJKBtn.setVisibility(playerType == 1 ? VISIBLE : GONE);
            mPlayerScaleBtn.setText(PlayerHelper.getScaleName(mPlayerConfig.getInt("sc")));
            mPlayerSpeedBtn.setText("x" + mPlayerConfig.getDouble("sp"));
            mPlayerTimeStartBtn.setText(PlayerUtils.stringForTime(mPlayerConfig.getInt("st") * 1000));
            mPlayerTimeSkipBtn.setText(PlayerUtils.stringForTime(mPlayerConfig.getInt("et") * 1000));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void setTitle(String playTitleInfo) {
        mPlayTitle1.setText(playTitleInfo);
    }

    /**
     * 播放出错后的自动兜底切换：只在两个内置播放器之间切（IJK ⇄ Exo）。
     * 原先这里走的是播放器按钮自身的点击逻辑，而它循环的那份「已存在的播放器列表」顺序来自
     * HashMap（不确定）且包含 10~14 号外部播放器，会在播放失败时直接唤起 MX/VLC/Kodi，
     * 故自动路径改为本方法；用户在界面上主动点击/长按选播放器的行为保持不变。
     *
     * ⚠ 2026-09-18 核查：**当前全工程没有任何调用点**（真正的兜底走 PlayFragment.compatFallback
     * → startPlayUrl 的临时配置副本，那条路不写回用户配置，是对的）。本方法保留，但不要直接接上：
     * 它内部会调 listener.updatePlayerCfg()，那会把"自动切换"写进「本片·本线路」存档 ——
     * 用户手动选过播放器时（plt=1）下次不再按默认刷新，等于自动切换把用户的选择顶掉了。
     * 将来若要启用，先去掉那一次 updatePlayerCfg()。
     */
    public void switchToNextInternalPlayer() {
        if (mPlayerConfig == null) return;
        try {
            int playerType = mPlayerConfig.getInt("pl");
            int nextType = (playerType == 1) ? 2 : 1;
            mPlayerConfig.put("pl", nextType);
            updatePlayerCfgView();
            listener.updatePlayerCfg();
            listener.replay(false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void resetSpeed() {
        skipEnd = true;
        mNextTipCancelled = false; // 新一集起播：上一集的「取消」不再生效，本集照常给连播提示
        mHandler.removeMessages(1004);
        mHandler.sendEmptyMessageDelayed(1004, 100);
    }

    /**
     * 变成全屏
     *
     * @param b
     */
    public void changedLandscape(boolean b) {
        mPlayTitle1.setSelected(true);
        mIsFullScreen = b;
        // 全屏状态变化：延寿一律作废（退出全屏后按钮本就不该留在画面上）
        clearRotateKeepAlive();
        if (b) {
            mPreBtn.setVisibility(VISIBLE);
            mNextBtn.setVisibility(VISIBLE);
            mChooseSeries.setVisibility(VISIBLE);
            mTopRightDeviceInfo.setVisibility(VISIBLE);
            // 进入全屏=横屏(顺时针90°)，重置旋转步
            mRotateStep = 0;
            applyRotation();
        } else {
            mTopRightDeviceInfo.setVisibility(INVISIBLE);
            mPreBtn.setVisibility(GONE);
            mNextBtn.setVisibility(GONE);
            mChooseSeries.setVisibility(GONE);
        }
        // 旋转按钮跟随操作栏：操作栏此时可见才显示；非全屏/画中画由方法内部兜底隐藏
        applyRotateBtnVisibility(mBottomVisible);
    }

    /** 画中画模式变化：进入 PiP 时隐藏旋转按钮，退出后按全屏状态恢复 */
    public void onPipModeChanged(boolean inPip) {
        mInPip = inPip;
        clearRotateKeepAlive(); // 进出画中画：延寿作废
        applyRotateBtnVisibility(mBottomVisible);
    }

    public interface VodControlListener {
        void chooseSeries();

        void playNext(boolean rmProgress);

        void playPre();

        void prepared();

        void changeParse(ParseBean pb);

        void updatePlayerCfg();

        void replay(boolean replay);

        void errReplay();

        void selectSubtitle();

        void selectAudioTrack();

        void toggleFullScreen();

        void exit();

        void cast();

        /**
         * Imm..bar沉浸式在系统弹窗/部分弹窗消失后会重新显示标题栏状态栏(未解决),暂时将隐藏底部栏的时机回调给外部页面处理
         */
        void onHideBottom();

        void showSetting();

        void pip();

        void showParseRoot(boolean show,ParseAdapter adapter);

        /** 手动选外部播放器：仅本次吊起，不写配置（用户 09-18 口径） */
        void playExternal(int playerType);

        /** 后面还有没有剧集：决定播完是弹「下一集」倒计时，还是停在最后一帧 */
        boolean hasNext();
    }

    public void setListener(VodControlListener listener) {
        this.listener = listener;
    }

    private VodControlListener listener;

    private boolean skipEnd = true;

    @Override
    protected void setProgress(int duration, int position) {

        if (mIsDragging) {
            return;
        }
        super.setProgress(duration, position);
        if (skipEnd && position != 0 && duration != 0) {
            int et = 0;
            try {
                et = mPlayerConfig.getInt("et");
            } catch (JSONException e) {
                e.printStackTrace();
            }
            if (et > 0 && position + (et * 1000) >= duration) {
                skipEnd = false;
                // 到片尾跳过点：与自然播完走同一入口，两条路径共用防重锁，不会连跳两集
                showNextEpisodeTip();
            }
        }
        mCurrentTime.setText(PlayerUtils.stringForTime(position));
        mTotalTime.setText(PlayerUtils.stringForTime(duration));
        if (duration > 0) {
            mSeekBar.setEnabled(true);
            int pos = (int) (position * 1.0 / duration * mSeekBar.getMax());
            mSeekBar.setProgress(pos);
        } else {
            mSeekBar.setEnabled(false);
        }
        updateBufferProgress();
    }

    /**
     * 缓冲二级色的唯一写入点：播放中由进度表调用，暂停期由 1006 补刷调用。
     * 只写 setSecondaryProgress，不碰时间文字、不碰片尾判定 —— 暂停时反复走它也无副作用。
     */
    private void updateBufferProgress() {
        if (mSeekBar == null || mIsDragging) return;
        int percent = mControlWrapper.getBufferedPercentage();
        if (percent >= 95) {
            mSeekBar.setSecondaryProgress(mSeekBar.getMax());
        } else {
            mSeekBar.setSecondaryProgress(percent * 10);
        }
    }

    /**
     * 暂停后进度表会自我停表（BaseVideoController.mShowProgress 只认 isPlaying），
     * 但 Exo 的暂停只停渲染不停下载，缓冲仍在长 —— 不补刷的话界面会停在这一刻，
     * 恢复播放时灰条突然跳一下。这里每秒仅补刷缓冲段。
     */
    private void startBufferTicker() {
        mHandler.removeMessages(1006);
        mHandler.sendEmptyMessageDelayed(1006, 1000);
    }

    private void stopBufferTicker() {
        mHandler.removeMessages(1006);
    }

    private boolean simSlideStart = false;
    private int simSeekPosition = 0;
    private long simSlideOffset = 0;

    public void tvSlideStop() {
        if (!simSlideStart)
            return;
        mControlWrapper.seekTo(simSeekPosition);
        if (!mControlWrapper.isPlaying())
            mControlWrapper.start();
        simSlideStart = false;
        simSeekPosition = 0;
        simSlideOffset = 0;
    }

    public void tvSlideStart(int dir) {
        int duration = (int) mControlWrapper.getDuration();
        if (duration <= 0)
            return;
        if (!simSlideStart) {
            simSlideStart = true;
        }
        // 每次10秒
        simSlideOffset += (10000.0f * dir);
        int currentPosition = (int) mControlWrapper.getCurrentPosition();
        int position = (int) (simSlideOffset + currentPosition);
        if (position > duration) position = duration;
        if (position < 0) position = 0;
        updateSeekUI(currentPosition, position, duration);
        simSeekPosition = position;
    }

    @Override
    protected void updateSeekUI(int curr, int seekTo, int duration) {
        super.updateSeekUI(curr, seekTo, duration);
        if (seekTo > curr) {
            mProgressIcon.setImageResource(R.drawable.icon_pre);
        } else {
            mProgressIcon.setImageResource(R.drawable.icon_back);
        }
        mProgressText.setText(PlayerUtils.stringForTime(seekTo) + " / " + PlayerUtils.stringForTime(duration));
        mHandler.sendEmptyMessage(1000);
        mHandler.removeMessages(1001);
        mHandler.sendEmptyMessageDelayed(1001, 1000);
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        videoPlayState = playState;
        switch (playState) {
            case VideoView.STATE_IDLE:
                cancelNextEpisodeTip(); // 切集/重播：收起上一集残留的倒计时
                stopBufferTicker();
                break;
            case VideoView.STATE_PLAYING:
                initLandscapePortraitBtnInfo();
                stopBufferTicker(); // 进度表已接管刷新，暂停期的补刷停掉（两条路不会同时写）
                startProgress();
                mIvPlayStatus.setImageResource(R.drawable.ic_pause);
                break;
            case VideoView.STATE_PAUSED:
                mIvPlayStatus.setImageResource(R.drawable.ic_play);
                startBufferTicker(); // 暂停期继续补刷灰色缓冲段
                break;
            case VideoView.STATE_ERROR:
                stopBufferTicker();
                listener.errReplay();
                break;
            case VideoView.STATE_PREPARED:
                mPlayLoadNetSpeed.setVisibility(GONE);
                hideLiveAboutBtn();
                listener.prepared();
                break;
            case VideoView.STATE_BUFFERED:
                mPlayLoadNetSpeed.setVisibility(GONE);
                break;
            case VideoView.STATE_PREPARING:
            case VideoView.STATE_BUFFERING:
                if (mProgressRoot.getVisibility() == GONE) mPlayLoadNetSpeed.setVisibility(VISIBLE);
                break;
            case VideoView.STATE_PLAYBACK_COMPLETED:
                // 播完不直接跳下一集，先弹倒计时（最后一集则停在最后一帧）
                stopBufferTicker();
                showNextEpisodeTip();
                break;
        }
    }

    /**
     * 播完或片尾到点：不直接跳下一集，先弹倒计时给反悔机会。
     * 两条触发路径（自然播完、片尾到点）共用 mNextTipShowing 防重；
     * 没有下一集（最后一集）则不弹，画面停在最后一帧。
     */
    private void showNextEpisodeTip() {
        if (mNextTipShowing) return;
        if (mNextTipCancelled) return; // 用户本集已取消过：不再弹，也不自动跳
        if (listener == null || !listener.hasNext()) return;
        mNextTipShowing = true;
        mNextTipCount = NEXT_TIP_SECONDS;
        if (mNextTipText != null) mNextTipText.setText(NEXT_TIP_SECONDS + " 秒后播放下一集");
        if (mNextTipRoot != null) mNextTipRoot.setVisibility(VISIBLE);
        mHandler.removeMessages(1005);
        mHandler.sendEmptyMessageDelayed(1005, 1000);
    }

    /** 倒计时走完，或用户点「立即播放」 */
    private void playNextEpisode() {
        if (!mNextTipShowing) return;
        cancelNextEpisodeTip();
        if (listener != null) listener.playNext(true);
    }

    /**
     * 收起浮层并停掉倒计时。取消、切集、页面销毁都必须调：
     * 计时器挂在控制器自己的 Handler 上，若不取消，退到后台后仍会自己跳集。
     */
    private void cancelNextEpisodeTip() {
        mNextTipShowing = false;
        mHandler.removeMessages(1005);
        if (mNextTipRoot != null) mNextTipRoot.setVisibility(GONE);
    }

    /**
     * 用户点「取消」——与 {@link #cancelNextEpisodeTip()} 的区别是它同时记下"本集不再自动跳"。
     * 只收起浮层是不够的：片尾跳过点取消后视频继续播到真正结尾，会再触发一次播放完成，
     * 那时若无此标记又会弹一次并自动跳集，"取消"就白点了。
     */
    private void dismissNextEpisodeTip() {
        mNextTipCancelled = true;
        cancelNextEpisodeTip();
    }

    boolean isBottomVisible() {
        return mBottomRoot.getVisibility() == VISIBLE;
    }

    void showBottom() {
        mHandler.removeMessages(1003);
        mHandler.sendEmptyMessage(1002);
    }

    public void hideBottom() {
        mHandler.removeMessages(1002);
        mHandler.sendEmptyMessage(1003);
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        myHandle.removeCallbacks(myRunnable);
        if (super.onKeyEvent(event)) {
            return true;
        }
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (isBottomVisible()) {
            mHandler.removeMessages(1002);
            mHandler.removeMessages(1003);
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
            return super.dispatchKeyEvent(event);
        }
        boolean isInPlayback = isInPlaybackState();
        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (isInPlayback) {
                    tvSlideStart(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (isInPlayback) {
                    togglePlay();
                    return true;
                }
//            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {  return true;// 闲置开启计时关闭透明底栏
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_MENU) {
                if (!isBottomVisible()) {
                    showBottom();
                    myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
                    return true;
                }
            }
        } else if (action == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (isInPlayback) {
                    tvSlideStop();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }


    private boolean fromLongPress;
    private float speed_old = 1.0f;

    @Override
    public void onLongPress(MotionEvent e) {
        if (videoPlayState != VideoView.STATE_PAUSED) {
            fromLongPress = true;
            try {
                speed_old = (float) mPlayerConfig.getDouble("sp");
                float speed = Hawk.get(HawkConfig.VIDEO_SPEED, 3.0f);
                mPlayerConfig.put("sp", speed);
                updatePlayerCfgView();
                listener.updatePlayerCfg();
                mControlWrapper.setSpeed(speed);
                mLlSpeed.setVisibility(VISIBLE);
                mTvSpeedTip.setText(speed + "x");
            } catch (JSONException f) {
                f.printStackTrace();
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            if (fromLongPress) {
                fromLongPress = false;
                mLlSpeed.setVisibility(GONE);
                try {
                    float speed = speed_old;
                    mPlayerConfig.put("sp", speed);
                    updatePlayerCfgView();
                    listener.updatePlayerCfg();
                    mControlWrapper.setSpeed(speed);
                } catch (JSONException f) {
                    f.printStackTrace();
                }
            }
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        myHandle.removeCallbacks(myRunnable);
        if (!isBottomVisible()) {
            showBottom();
            // 闲置计时关闭
            myHandle.postDelayed(myRunnable, dismissTimeOperationBar);
        } else {
            hideBottom();
        }
        return true;
    }

    @Override
    public boolean onBackPressed() {
        if (super.onBackPressed()) {
            return true;
        }
        if (isBottomVisible()) {
            hideBottom();
            return true;
        }
        return false;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mHandler.removeCallbacks(myRunnable2);
        // 退出播放页必须停掉下一集倒计时，否则计时器会在后台把剧集往下跳
        cancelNextEpisodeTip();
        stopBufferTicker();
        clearRotateKeepAlive();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // 退到后台（按 Home/切走）时 View 不会 detach，只有窗口可见性会变，
        // 必须在此停表——否则用户在后台时剧集自己往下跳
        if (visibility != VISIBLE) {
            cancelNextEpisodeTip();
            stopBufferTicker();
            // 退后台：延寿作废；操作栏本就收起时顺手把旋转按钮也收掉（回前台点屏幕即恢复）
            clearRotateKeepAlive();
            if (!mBottomVisible) applyRotateBtnVisibility(false);
        } else if (videoPlayState == VideoView.STATE_PAUSED) {
            startBufferTicker(); // 回前台且仍是暂停态：恢复灰条补刷
        }
    }

    public void openSubtitle(boolean open) {
        if (open) {
            mSubtitleView.setVisibility(VISIBLE);
            Toast.makeText(getContext(), "字幕已开启", Toast.LENGTH_LONG).show();
        } else {
            mSubtitleView.setVisibility(View.GONE);
            Toast.makeText(getContext(), "字幕已关闭", Toast.LENGTH_LONG).show();
        }
        hideBottom();
    }

    public void increaseTime(String type) {
        try {
            int step = Hawk.get(HawkConfig.PLAY_TIME_STEP, 1);
            int time = mPlayerConfig.getInt(type);
            time += step;
            if (time > 30 * 10)
                time = 0;
            mPlayerConfig.put(type, time);
            updatePlayerCfgView();
            listener.updatePlayerCfg();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void decreaseTime(String type) {
        try {
            int step = Hawk.get(HawkConfig.PLAY_TIME_STEP, 1);
            int time = mPlayerConfig.getInt(type);
            time -= step;
            if (time < 0)
                time = (30 * 10);
            mPlayerConfig.put(type, time);
            updatePlayerCfgView();
            listener.updatePlayerCfg();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void toggleViewShowWithAlpha(View view, boolean show) {
        if (show) {
            view.setVisibility(View.VISIBLE);
            view.animate()
                    .alpha(1.0f)
                    .setDuration(100)
                    .setInterpolator(new AccelerateInterpolator())
                    .start();
        } else {
            view.animate()
                    .alpha(0.0f)
                    .setDuration(100)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> view.setVisibility(View.GONE))
                    .start();
        }
    }
}
