package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.ConvertUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.FastClickCheckUtil;

import org.jetbrains.annotations.NotNull;

public class SubtitleDialog extends BaseDialog {

    public TextView selectInternal;

    private SearchSubtitleListener mSearchSubtitleListener;
    private LocalFileChooserListener mLocalFileChooserListener;
    private SubtitleViewListener mSubtitleViewListener;

    public SubtitleDialog(@NonNull @NotNull Context context) {
        super(context);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        setContentView(R.layout.dialog_subtitle);
        initView(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(getWindow().getAttributes());
        lp.gravity = Gravity.CENTER;
        lp.width = ConvertUtils.dp2px(330);

        getWindow().setAttributes(lp);
        getWindow().setWindowAnimations(R.style.DialogFadeAnimation); // Set the animation style
    }

    private void initView(Context context) {
        selectInternal = findViewById(R.id.selectInternal);

        selectInternal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                dismiss();
                mSubtitleViewListener.selectInternalSubtitle();
            }
        });
        findViewById(R.id.subtitleOpen).setOnClickListener(v -> {
            dismiss();
            mSubtitleViewListener.subtitleOpen(true);
        });
        findViewById(R.id.subtitleClose).setOnClickListener(v -> {
            dismiss();
            mSubtitleViewListener.subtitleOpen(false);
        });
    }

    public void setLocalFileChooserListener(LocalFileChooserListener localFileChooserListener) {
        mLocalFileChooserListener = localFileChooserListener;
    }

    public interface LocalFileChooserListener {
        void openLocalFileChooserDialog();
    }

    public void setSearchSubtitleListener(SearchSubtitleListener searchSubtitleListener) {
        mSearchSubtitleListener = searchSubtitleListener;
    }

    public interface SearchSubtitleListener {
        void openSearchSubtitleDialog();
    }

    public void setSubtitleViewListener(SubtitleViewListener subtitleViewListener) {
        mSubtitleViewListener = subtitleViewListener;
    }

    public interface SubtitleViewListener {
        void setTextSize(int size);
        void setSubtitleDelay(int milliseconds);
        void selectInternalSubtitle();
        void setTextStyle(int style);
        void subtitleOpen(boolean b);
    }
}
