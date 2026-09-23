package com.github.tvbox.osc.ui.activity

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * 竖屏扫码 Activity: 锁定 portrait,并在左下角提供"图库"按钮,
 * 支持从相册选择图片识别二维码(非空则按标准 SCAN_RESULT 返回,交由订阅页处理)
 */
class PortraitCaptureActivity : CaptureActivity() {

    companion object {
        private const val REQ_PICK_IMAGE = 9001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 强制竖屏:覆盖 zxing 默认按设备自然方向(横屏设备会横过来)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        addGalleryButton()
    }

    override fun onResume() {
        super.onResume()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun addGalleryButton() {
        val decor = window.decorView as? ViewGroup ?: return
        val btn = Button(this)
        btn.text = "图库"
        btn.setBackgroundColor(Color.parseColor("#CC000000"))
        btn.setTextColor(Color.WHITE)
        val dp = resources.displayMetrics.density
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.START
        params.leftMargin = (dp * 24).toInt()
        params.bottomMargin = (dp * 24).toInt()
        btn.layoutParams = params
        btn.setOnClickListener { openGallery() }
        decor.addView(btn)
        btn.bringToFront()
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQ_PICK_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PICK_IMAGE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.data != null) {
                val uri: Uri = data.data!!
                try {
                    val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    val text = decodeQR(bitmap)
                    if (!text.isNullOrEmpty()) {
                        returnScanResult(text)
                        return
                    }
                    Toast.makeText(this, "未识别到二维码", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "图片识别失败", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun decodeQR(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        return try {
            QRCodeReader().decode(binary).text
        } catch (e: Exception) {
            null
        }
    }

    private fun returnScanResult(text: String) {
        val intent = Intent()
        intent.putExtra("SCAN_RESULT", text)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }
}
