package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogEditSubscriptionBinding;
import com.lxj.xpopup.core.CenterPopupView;

/**
 * 编辑订阅(名称+地址)
 */
public class EditSubscriptionDialog extends CenterPopupView {

    public interface OnEditListener {
        void onEdit(String name, String url);
    }

    private final String initName;
    private final String initUrl;
    private final OnEditListener mListener;

    public EditSubscriptionDialog(@NonNull Context context, String name, String url, OnEditListener listener) {
        super(context);
        this.initName = name;
        this.initUrl = url;
        this.mListener = listener;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_edit_subscription;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        DialogEditSubscriptionBinding b = DialogEditSubscriptionBinding.bind(getPopupImplView());
        b.etName.setText(initName);
        b.etUrl.setText(initUrl);
        b.etName.setSelection(b.etName.getText().length());
        b.btnCancel.setOnClickListener(v -> dismiss());
        b.btnConfirm.setOnClickListener(v -> {
            String name = b.etName.getText().toString().trim();
            String url = b.etUrl.getText().toString().trim();
            if (TextUtils.isEmpty(url)) {
                com.blankj.utilcode.util.ToastUtils.showShort("地址不能为空");
                return;
            }
            if (name.length() > 8) {
                com.blankj.utilcode.util.ToastUtils.showShort("名称不要过长,不方便记忆");
                return;
            }
            dismiss();
            if (mListener != null) mListener.onEdit(name, url);
        });
    }
}