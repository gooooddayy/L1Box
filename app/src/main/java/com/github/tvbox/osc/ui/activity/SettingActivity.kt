package com.github.tvbox.osc.ui.activity

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.DiffUtil
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.IJKCode
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.ActivitySettingBinding
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter.SelectDialogInterface
import com.github.tvbox.osc.ui.dialog.SelectDialog
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.FileUtils
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.PlayerHelper
import com.github.tvbox.osc.util.Utils
import com.lxj.xpopup.XPopup
import com.orhanobut.hawk.Hawk
import okhttp3.HttpUrl
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import xyz.doikki.videoplayer.exo.ExoCacheConfig
import java.io.File

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
class SettingActivity : BaseVbActivity<ActivitySettingBinding>() {

    private var dnsOpt = Hawk.get(HawkConfig.DOH_URL, OkGoHelper.DEFAULT_DOH)
    private var currentLiveApi = Hawk.get(HawkConfig.LIVE_URL, "")
    override fun init() {

        mBinding.titleBar.leftView.setOnClickListener { onBackPressed() }
        mBinding.tvMediaCodec.text = Hawk.get(HawkConfig.IJK_CODEC, "")

        mBinding.tvDns.text = OkGoHelper.dnsHttpsList[Hawk.get(HawkConfig.DOH_URL, OkGoHelper.DEFAULT_DOH)]
        // 主页内容固定显示豆瓣热播 - 设置项已移除
        mBinding.tvHistoryNum.text =
            HistoryHelper.getHistoryNumName(Hawk.get(HawkConfig.HISTORY_NUM, 0))
        mBinding.tvScaleType.text = PlayerHelper.getScaleName(Hawk.get(HawkConfig.PLAY_SCALE, 0))
        // 默认播放器：装了的外部播放器照常可选，只有"配置指向一个本机没有的播放器"（外部已卸载、
        // 换设备后配置同步过来）才落回 Exo 并写回，免得这里显示一个已经点不动的项。
        val savedPlayType = Hawk.get(HawkConfig.PLAY_TYPE, 2)
        val playType = PlayerHelper.normalizeAvailablePlayerType(savedPlayType)
        if (playType != savedPlayType) {
            Hawk.put(HawkConfig.PLAY_TYPE, playType)
        }
        mBinding.tvPlay.text = PlayerHelper.getPlayerName(playType)
        mBinding.tvRenderType.text =
            PlayerHelper.getRenderName(Hawk.get(HawkConfig.PLAY_RENDER, 0))

        mBinding.switchPrivateBrowsing.setChecked(Hawk.get(HawkConfig.PRIVATE_BROWSING, false))
        mBinding.llPrivateBrowsing.setOnClickListener { view: View? ->
            val newConfig = !Hawk.get(HawkConfig.PRIVATE_BROWSING, false)
            mBinding.switchPrivateBrowsing.setChecked(newConfig)
            Hawk.put(HawkConfig.PRIVATE_BROWSING, newConfig)
        }

        // 局域网文件管理开关入口已移除：9978 门禁逻辑保留（RemoteServer 按
        // HawkConfig.ALLOW_LAN_FILE_MANAGE 判定，无 UI 入口时永久保持默认关闭，仅本机可管理）

        // 后台播放设置项已移除(功能下线)

        mBinding.tvSpeed.text = Hawk.get(HawkConfig.VIDEO_SPEED, 3.0f).toString()
        mBinding.llPressSpeed.setOnClickListener {
            val types = ArrayList<String>()
            types.add("2.0")
            types.add("3.0")
            types.add("4.0")
            types.add("5.0")
            types.add("6.0")
            types.add("8.0")
            types.add("10.0")
            val defaultPos = types.indexOf(Hawk.get(HawkConfig.VIDEO_SPEED, 3.0f).toString())
            val dialog = SelectDialog<String>(this@SettingActivity)
            dialog.setTip("请选择")
            dialog.setAdapter(object : SelectDialogInterface<String?> {
                override fun click(value: String?, pos: Int) {
                    Hawk.put(HawkConfig.VIDEO_SPEED, value?.toFloat())
                    mBinding.tvSpeed.text = value
                }

                override fun getDisplay(name: String?): String {
                    return name ?: ""
                }
            }, SelectDialogAdapter.stringDiff, types, defaultPos)
            dialog.show()
        }

        // 数据备份还原入口已按需求移除

        mBinding.llDns.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val dohUrl = Hawk.get(HawkConfig.DOH_URL, OkGoHelper.DEFAULT_DOH)
            val dialog = SelectDialog<String>(this@SettingActivity)
            dialog.setTip("请选择安全DNS")
            dialog.setAdapter(object : SelectDialogInterface<String?> {
                override fun click(value: String?, pos: Int) {
                    mBinding.tvDns.text = OkGoHelper.dnsHttpsList[pos]
                    Hawk.put(HawkConfig.DOH_URL, pos)
                    // 记下"用户自己选过"：默认值迁移（App.upgradeDohDefault）与将来的默认值调整
                    // 一律不再碰用户的选择，包括他主动选"关闭"
                    Hawk.put(HawkConfig.DOH_USER_SET, true)
                    val url = OkGoHelper.getDohUrl(pos)
                    OkGoHelper.dnsOverHttps.setUrl(if (url.isEmpty()) null else HttpUrl.get(url))
                    IjkMediaPlayer.toggleDotPort(pos > 0)
                }

                override fun getDisplay(name: String?): String {
                    return name ?: ""
                }
            },SelectDialogAdapter.stringDiff, OkGoHelper.dnsHttpsList, dohUrl)
            dialog.show()
        }

        mBinding.llMediaCodec.setOnClickListener { v: View? ->
            val ijkCodes = ApiConfig.get().ijkCodes
            if (ijkCodes == null || ijkCodes.size == 0) return@setOnClickListener
            FastClickCheckUtil.check(v)
            var defaultPos = 0
            val ijkSel = Hawk.get(HawkConfig.IJK_CODEC, "")
            for (j in ijkCodes.indices) {
                if (ijkSel == ijkCodes[j].name) {
                    defaultPos = j
                    break
                }
            }
            val dialog = SelectDialog<IJKCode>(this@SettingActivity)
            dialog.setTip("请选择IJK解码")
            dialog.setAdapter(object : SelectDialogInterface<IJKCode?> {
                override fun click(value: IJKCode?, pos: Int) {
                    value?.selected(true)
                    mBinding.tvMediaCodec.text = value?.name
                }

                override fun getDisplay(code: IJKCode?): String {
                    return code?.name ?: ""
                }
            }, object : DiffUtil.ItemCallback<IJKCode>() {
                override fun areItemsTheSame(oldItem: IJKCode, newItem: IJKCode): Boolean {
                    return oldItem === newItem
                }

                override fun areContentsTheSame(oldItem: IJKCode, newItem: IJKCode): Boolean {
                    return oldItem.name.contentEquals(newItem.name)
                }
            }, ijkCodes, defaultPos)
            dialog.show()
        }

        mBinding.llScale.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val defaultPos = Hawk.get(HawkConfig.PLAY_SCALE, 0)
            val players = ArrayList<Int>()
            players.add(0)
            players.add(1)
            players.add(2)
            players.add(3)
            players.add(4)
            players.add(5)
            val dialog = SelectDialog<Int>(this@SettingActivity)
            dialog.setTip("请选择画面缩放")
            dialog.setAdapter(object : SelectDialogInterface<Int?> {
                override fun click(value: Int?, pos: Int) {
                    Hawk.put(HawkConfig.PLAY_SCALE, value)
                    mBinding.tvScaleType.text = value?.let { PlayerHelper.getScaleName(it) }
                }

                override fun getDisplay(value: Int?): String {
                    return PlayerHelper.getScaleName(value ?: 0)
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, players, defaultPos)
            dialog.show()
        }

        mBinding.llPlay.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val playerType = PlayerHelper.normalizeAvailablePlayerType(Hawk.get(HawkConfig.PLAY_TYPE, 2))
            var defaultPos = 0
            // 候选项＝本机确实可用的播放器：系统 + Exo + IJK + 已安装的外部播放器。
            // 没装的外部不列（列了也吊不起来），装了的一律显示。
            val players = PlayerHelper.getAvailablePlayerTypes()
            val renders = ArrayList<Int>()
            for (p in players.indices) {
                renders.add(p)
                if (players[p] == playerType) {
                    defaultPos = p
                }
            }
            val dialog = SelectDialog<Int>(this@SettingActivity)
            dialog.setTip("请选择默认播放器")
            dialog.setAdapter(object : SelectDialogInterface<Int?> {
                override fun click(value: Int?, pos: Int) {
                    val thisPlayerType = players[pos]
                    Hawk.put(HawkConfig.PLAY_TYPE, thisPlayerType)
                    mBinding.tvPlay.text = PlayerHelper.getPlayerName(thisPlayerType)
                    PlayerHelper.init()
                }

                override fun getDisplay(value: Int?): String {
                    return PlayerHelper.getPlayerName(players[value?:0])
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, renders, defaultPos)
            dialog.show()
        }

        mBinding.llRender.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val defaultPos = Hawk.get(HawkConfig.PLAY_RENDER, 0)
            val renders = ArrayList<Int>()
            renders.add(0)
            renders.add(1)
            val dialog = SelectDialog<Int>(this@SettingActivity)
            dialog.setTip("请选择默认渲染方式")
            dialog.setAdapter(object : SelectDialogInterface<Int?> {
                override fun click(value: Int?, pos: Int) {
                    Hawk.put(HawkConfig.PLAY_RENDER, value)
                    mBinding.tvRenderType.text = PlayerHelper.getRenderName(value?:0)
                    PlayerHelper.init()
                }

                override fun getDisplay(value: Int?): String {
                    return PlayerHelper.getRenderName(value?:0)
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, renders, defaultPos)
            dialog.show()
        }
        // 主页内容已固定豆瓣热播,设置项移除

        mBinding.llHistoryNum.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val defaultPos = Hawk.get(HawkConfig.HISTORY_NUM, 0)
            val types = ArrayList<Int>()
            types.add(0)
            types.add(1)
            types.add(2)
            val dialog = SelectDialog<Int>(this@SettingActivity)
            dialog.setTip("保留历史记录数量")
            dialog.setAdapter(object : SelectDialogInterface<Int?> {
                override fun click(value: Int?, pos: Int) {
                    Hawk.put(HawkConfig.HISTORY_NUM, value)
                    mBinding.tvHistoryNum.text = HistoryHelper.getHistoryNumName(value?:0)
                }

                override fun getDisplay(value: Int?): String {
                    return HistoryHelper.getHistoryNumName(value?:0)
                }
            }, object : DiffUtil.ItemCallback<Int>() {
                override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean {
                    return oldItem == newItem
                }
            }, types, defaultPos)
            dialog.show()
        }
        mBinding.llClearCache.setOnClickListener { view: View ->
            XPopup.Builder(this)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm(
                    "一键清理",
                    "将清除全部本地数据（订阅配置、播放设置、观看历史、收藏、搜索记录、缓存），恢复到刚安装的状态。\n\n确定执行吗？"
                ) { onClickFactoryReset(view) }.show()
        }

        // 导出崩溃日志：最新一份复制到 /sdcard/L1Box_crash_log.txt，便于反馈排障
        mBinding.tvCrashLog.text = if (com.github.tvbox.osc.util.CrashLog.hasCrash()) "有记录" else "无记录"
        mBinding.llCrashLog.setOnClickListener { v: View ->
            FastClickCheckUtil.check(v)
            Thread {
                val path = com.github.tvbox.osc.util.CrashLog.exportLatest()
                runOnUiThread {
                    if (path != null) {
                        mBinding.tvCrashLog.text = "已导出"
                        ToastUtils.showLong("已导出到 $path")
                    } else {
                        ToastUtils.showLong(if (com.github.tvbox.osc.util.CrashLog.hasCrash()) "导出失败，请检查存储权限" else "暂无崩溃记录")
                    }
                }
            }.start()
        }
        // 主题设置入口已移除：App 固定浅色（Utils.initTheme 强制 MODE_NIGHT_NO）

        mBinding.switchVideoPurify.setChecked(Hawk.get(HawkConfig.VIDEO_PURIFY, true))
        // toggle purify video -------------------------------------
        mBinding.llVideoPurify.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val newConfig = !Hawk.get(HawkConfig.VIDEO_PURIFY, true)
            mBinding.switchVideoPurify.setChecked(newConfig)
            Hawk.put(HawkConfig.VIDEO_PURIFY, newConfig)
        }
        mBinding.switchIjkCachePlay.setChecked(Hawk.get(HawkConfig.IJK_CACHE_PLAY, false))
        mBinding.llIjkCachePlay.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val newConfig = !Hawk.get(HawkConfig.IJK_CACHE_PLAY, false)
            mBinding.switchIjkCachePlay.setChecked(newConfig)
            Hawk.put(HawkConfig.IJK_CACHE_PLAY, newConfig)
        }
        mBinding.switchExoDiskCache.setChecked(Hawk.get(HawkConfig.EXO_DISK_CACHE, true))
        mBinding.llExoDiskCache.setOnClickListener { v: View? ->
            FastClickCheckUtil.check(v)
            val newConfig = !Hawk.get(HawkConfig.EXO_DISK_CACHE, true)
            mBinding.switchExoDiskCache.setChecked(newConfig)
            Hawk.put(HawkConfig.EXO_DISK_CACHE, newConfig)
            // 立刻推给 player 模块（它读不到 Hawk），下一次起播即生效，不需要重启应用
            ExoCacheConfig.setEnabled(newConfig)
        }
    }

    override fun onBackPressed() {
        if (dnsOpt != Hawk.get(
                HawkConfig.DOH_URL,
                OkGoHelper.DEFAULT_DOH
            ) || currentLiveApi != Hawk.get(HawkConfig.LIVE_URL, "")
        ) { // dns/doh/直播源有更改,需重载页面
            //AppManager.getInstance().finishAllActivity()
            if (currentLiveApi == Hawk.get(HawkConfig.LIVE_URL, "")) { //未更改直播源,不需重载api等
                val bundle = Bundle()
                bundle.putBoolean(IntentKey.CACHE_CONFIG_CHANGED, true)
                jumpActivity(MainActivity::class.java, bundle)
            } else {
                jumpActivity(MainActivity::class.java)
            }
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        } else {
            super.onBackPressed()
        }
    }

    /**
     * 一键清理：清除全部本地数据与缓存（Hawk 配置、Room 数据库=历史/收藏/搜索记录、
     * 内部 files/databases/shared_prefs/code_cache、内外缓存目录），等同恢复出厂，
     * 完成后重启进程回到启动页。lib 目录为系统符号链接，跳过。
     */
    private fun onClickFactoryReset(v: View) {
        FastClickCheckUtil.check(v)
        ToastUtils.showLong("正在清理，完成后将自动重启…")
        Thread {
            // 1. Hawk 配置整体清除（订阅/播放器/各项开关还原默认值）
            try {
                Hawk.deleteAll()
            } catch (ignored: Throwable) {
            }
            // 2. 内部数据目录（databases/shared_prefs/files/code_cache/no_backup）
            val dataDir = filesDir.parentFile
            if (dataDir != null && dataDir.isDirectory) {
                dataDir.listFiles()?.forEach { child ->
                    if (child.name != "lib") deleteRecursive(child)
                }
            }
            // 3. 外部缓存兜底（部分设备 cacheDir/外部缓存不在 dataDir 内）
            try {
                externalCacheDir?.let { deleteRecursive(it) }
            } catch (ignored: Throwable) {
            }
            // 4. 重启到启动页（留 300ms 让启动 Intent 进栈后再杀进程）
            runOnUiThread {
                try {
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    if (intent != null) startActivity(intent)
                } catch (ignored: Throwable) {
                }
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 300)
            }
        }.start()
    }

    private fun deleteRecursive(file: File) {
        file.listFiles()?.forEach { deleteRecursive(it) }
        file.delete()
    }

    // getHomeRecName 已移除(主页内容固定豆瓣热播)    }
}