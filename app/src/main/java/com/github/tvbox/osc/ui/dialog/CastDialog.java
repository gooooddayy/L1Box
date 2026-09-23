package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.DlnaDevice;
import com.github.tvbox.osc.databinding.DialogCastBinding;
import com.github.tvbox.osc.util.DlnaManager;
import com.lxj.xpopup.core.CenterPopupView;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.List;

/**
 * 投屏设备选择 + 投屏后控制弹窗，复用 dialog_cast.xml。
 * 只做三件事：发现设备、推送、停止（不做暂停/进度/切集，控制权交给电视遥控器）。
 * 「关闭」只关弹窗、电视端继续播放；「停止」才终止远端播放并回到设备列表。
 * 会记住上次投屏的设备（Hawk 存 controlUrl），下次发现后自动置顶并标记「上次」。
 */
public class CastDialog extends CenterPopupView {

    private static final String KEY_LAST_DEVICE = "l1_dlna_last_control";

    private final String mUrl;
    private final String mTitle;
    private DialogCastBinding b;
    private final List<DlnaDevice> mDevices = new ArrayList<>();
    private DlnaAdapter mAdapter;

    private DlnaDevice mCastingDevice;

    public CastDialog(@NonNull Context context, String url, String title) {
        super(context);
        this.mUrl = url;
        this.mTitle = title == null ? "" : title;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_cast;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        b = DialogCastBinding.bind(getPopupImplView());
        b.rv.setLayoutManager(new LinearLayoutManager(getContext()));
        mAdapter = new DlnaAdapter(mDevices, this::pickDevice);
        b.rv.setAdapter(mAdapter);
        b.btnCancel.setOnClickListener(v -> closeDialog());
        b.btnConfirm.setOnClickListener(v -> refresh());
        b.btnStop.setOnClickListener(v -> stopCasting());
        refresh();
    }

    private void refresh() {
        setCastingMode(false);
        b.title.setText("投屏（搜索中…）");
        DlnaManager.get().discover(getContext(), 4000, (list, err) -> {
            mDevices.clear();
            if (list != null) mDevices.addAll(list);
            sortLastDeviceToTop();
            mAdapter.setLastControlUrl(Hawk.get(KEY_LAST_DEVICE, ""));
            mAdapter.notifyDataSetChanged();
            if (mDevices.isEmpty()) {
                b.title.setText(err != null ? "投屏（未发现设备：" + err + "）" : "投屏（未发现设备）");
                b.tvHint.setVisibility(View.VISIBLE);
            } else {
                b.title.setText("投屏（" + mDevices.size() + " 台设备）");
                b.tvHint.setVisibility(View.GONE);
            }
        });
    }

    private void sortLastDeviceToTop() {
        String last = Hawk.get(KEY_LAST_DEVICE, "");
        if (TextUtils.isEmpty(last) || mDevices.isEmpty()) return;
        DlnaDevice lastDev = null;
        for (DlnaDevice d : mDevices) {
            if (last.equals(d.controlUrl)) {
                lastDev = d;
                break;
            }
        }
        if (lastDev != null) {
            mDevices.remove(lastDev);
            mDevices.add(0, lastDev);
        }
    }

    private void pickDevice(DlnaDevice device) {
        mCastingDevice = device;
        Hawk.put(KEY_LAST_DEVICE, device.controlUrl);
        b.title.setText("正在推送到 " + device.getDisplayName() + "…");
        setCastingMode(false);
        b.rv.setVisibility(View.GONE);
        b.btnConfirm.setVisibility(View.GONE);
        b.tvHint.setVisibility(View.GONE);
        DlnaManager.get().cast(device, mUrl, mTitle, (ok, msg) -> {
            if (ok) {
                enterCastingMode();
            } else {
                ToastUtils.showShort("投屏失败：" + (msg == null ? "设备无响应" : msg));
                mCastingDevice = null;
                refresh();
            }
        });
    }

    private void enterCastingMode() {
        setCastingMode(true);
        b.tvHint.setVisibility(View.GONE);
        b.title.setText("投屏中：" + (mCastingDevice != null ? mCastingDevice.getDisplayName() : ""));
    }

    private void setCastingMode(boolean on) {
        b.castControls.setVisibility(on ? View.VISIBLE : View.GONE);
        b.rv.setVisibility(on ? View.GONE : View.VISIBLE);
        b.btnConfirm.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    /** 停止远端播放，回到设备列表（可再投别的设备/别的视频） */
    private void stopCasting() {
        DlnaDevice dev = mCastingDevice;
        mCastingDevice = null;
        if (dev != null) DlnaManager.get().stop(dev, (ok, msg) -> { });
        ToastUtils.showShort("已停止投屏");
        refresh();
    }

    /** 关闭只关弹窗：电视端继续播放，不打断 */
    private void closeDialog() {
        dismiss();
    }

    private static class DlnaAdapter extends RecyclerView.Adapter<DlnaAdapter.VH> {
        private final List<DlnaDevice> mData;
        private final OnItemClick mClick;
        private String mLastControlUrl = "";

        DlnaAdapter(List<DlnaDevice> data, OnItemClick click) {
            this.mData = data;
            this.mClick = click;
        }

        void setLastControlUrl(String url) {
            this.mLastControlUrl = url == null ? "" : url;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = View.inflate(parent.getContext(), R.layout.item_dlna_device, null);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            DlnaDevice d = mData.get(pos);
            String name = d.getDisplayName();
            if (!TextUtils.isEmpty(mLastControlUrl) && mLastControlUrl.equals(d.controlUrl)) {
                name = name + "（上次）";
            }
            h.name.setText(name);
            if (TextUtils.isEmpty(d.ip)) {
                h.ip.setVisibility(View.GONE);
            } else {
                h.ip.setText(d.ip);
                h.ip.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public int getItemCount() {
            return mData.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView ip;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.tv_name);
                ip = v.findViewById(R.id.tv_ip);
                v.setOnClickListener(x -> {
                    int p = getAdapterPosition();
                    if (p >= 0 && mClick != null) mClick.onClick(mData.get(p));
                });
            }
        }

        interface OnItemClick {
            void onClick(DlnaDevice device);
        }
    }
}
