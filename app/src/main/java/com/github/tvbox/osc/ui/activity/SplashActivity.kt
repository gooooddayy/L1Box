package com.github.tvbox.osc.ui.activity

import android.content.Intent
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivitySplashBinding

class SplashActivity : BaseVbActivity<ActivitySplashBinding>() {

    private val goMain = Runnable {
        // 500ms 内用户可能已退出, 此时跳转会作用在失效的 Activity 上
        if (isFinishing || isDestroyed) return@Runnable
        startActivity(Intent(this@SplashActivity, MainActivity::class.java))
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }

    override fun init() {
        App.getInstance().isNormalStart = true
        // 不再做固定 500ms 品牌停留：冷启动期间系统已显示启动背景图，
        // 这里直接跳主页，省去 500ms 空等（post 下一帧执行，保留可取消语义）
        mBinding.root.post(goMain)
    }

    override fun onDestroy() {
        if (isBindingInitialized()) mBinding.root.removeCallbacks(goMain)
        super.onDestroy()
    }
}