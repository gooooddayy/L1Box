package com.github.tvbox.osc.ui.dialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.databinding.DialogShareSubscriptionBinding;
import com.github.tvbox.osc.ui.tv.QRCodeGen;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.core.CenterPopupView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 分享订阅:展示二维码 + 保存二维码到本地 / 复制链接
 */
public class ShareSubscriptionDialog extends CenterPopupView {

    private final String name;
    private final String url;
    private Bitmap qrBitmap;

    public ShareSubscriptionDialog(@NonNull Context context, String name, String url) {
        super(context);
        this.name = name;
        this.url = url;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_share_subscription;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        DialogShareSubscriptionBinding b = DialogShareSubscriptionBinding.bind(getPopupImplView());
        b.tvTitle.setText("分享: " + (name == null ? "" : name));
        b.tvUrl.setText(url == null ? "" : url);
        qrBitmap = QRCodeGen.generateBitmap(url == null ? "" : url, 220, 220, 2);
        if (qrBitmap != null) b.ivQr.setImageBitmap(qrBitmap);
        Utils.disableTooltip(b.ivQr);
        // 「保存二维码」按钮：保存二维码图片到本地
        b.btnClose.setOnClickListener(v -> saveQrToLocal());
        // 「复制链接」按钮：复制单仓/多仓源链接
        b.btnShare.setOnClickListener(v -> copyLink());
    }

    /** 把二维码图片保存进系统相册(Pictures/L1Box)，Android 10+ 无需额外存储权限 */
    private void saveQrToLocal() {
        if (qrBitmap == null) {
            Toast.makeText(getContext(), "二维码生成失败，无法保存", Toast.LENGTH_SHORT).show();
            return;
        }
        String displayName = "L1Box_分享_" + System.currentTimeMillis() + ".png";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/L1Box");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                ContentResolver cr = getContext().getContentResolver();
                Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    Toast.makeText(getContext(), "保存失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                try (OutputStream os = cr.openOutputStream(uri)) {
                    qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                cr.update(uri, values, null, null);
                Toast.makeText(getContext(), "二维码已保存到相册", Toast.LENGTH_SHORT).show();
                dismiss();
            } else {
                // Android 9 及以下：写入应用私有目录(免权限)，路径在提示里给出
                File dir = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES), "L1Box");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, displayName);
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                Toast.makeText(getContext(), "二维码已保存到: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
                dismiss();
            }
        } catch (Throwable e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 复制单仓/多仓源链接到剪贴板 */
    private void copyLink() {
        if (url == null || url.isEmpty()) {
            Toast.makeText(getContext(), "链接为空", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("L1Box", url));
                Toast.makeText(getContext(), "链接已复制", Toast.LENGTH_SHORT).show();
                dismiss();
            }
        } catch (Throwable e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "复制失败", Toast.LENGTH_SHORT).show();
        }
    }
}
