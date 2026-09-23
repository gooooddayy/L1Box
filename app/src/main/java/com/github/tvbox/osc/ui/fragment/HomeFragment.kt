package com.github.tvbox.osc.ui.fragment

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import com.github.tvbox.osc.event.NetworkEvent
import com.github.tvbox.osc.event.RefreshEvent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.angcyo.tablayout.delegate.ViewPager1Delegate.Companion.install
import com.blankj.utilcode.util.ConvertUtils
import com.blankj.utilcode.util.ScreenUtils
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.api.ApiConfig.LoadConfigCallback
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseLazyFragment
import com.github.tvbox.osc.base.BaseVbFragment
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.MovieSort.SortData
import com.github.tvbox.osc.bean.Subscription
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.FragmentHomeBinding
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.CollectActivity
import com.github.tvbox.osc.ui.activity.FastSearchActivity
import com.github.tvbox.osc.ui.activity.HistoryActivity
import com.github.tvbox.osc.ui.activity.MainActivity
import com.github.tvbox.osc.ui.activity.SubscriptionActivity
import com.github.tvbox.osc.ui.dialog.ChooseSourceDialog
import com.github.tvbox.osc.ui.dialog.LastViewedDialog
import com.github.tvbox.osc.ui.dialog.TipDialog
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HomeCache
import com.github.tvbox.osc.util.HomeConfigLoader
import com.github.tvbox.osc.util.NetworkMonitor
import com.github.tvbox.osc.viewmodel.SourceViewModel
import com.lxj.xpopup.XPopup
import com.orhanobut.hawk.Hawk
import kotlinx.coroutines.Dispatchers
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : BaseVbFragment<FragmentHomeBinding>() {

    companion object {
        /** "上次观看"弹窗本次进程是否已展示过：每次软件开启只显示一次，切页返回不再重复弹 */
        var sLastViewedShown = false
    }

    /**
     * 提供给主页返回操作
     */
    val tabIndex: Int
        get() = mBinding.tabLayout.currentItemIndex

    /**
     * 提供给主页返回操作
     */
    val allFragments: List<BaseLazyFragment>
        get() = fragments

    private var sourceViewModel: SourceViewModel? = null
    private val fragments: MutableList<BaseLazyFragment> = ArrayList()
    private val mHandler = Handler()

    /**
     * 顶部tabs分类集合,用于渲染tab页,每个tab对应fragment内的数据
     */
    private var mSortDataList: List<SortData> = ArrayList()
    private var dataInitOk = false
    private var jarInitOk = false
    /** 内置首页.json 是否已就绪（首页展示与订阅完全解耦） */
    private var homeDataOk = false
    /** 离开主页时记录的配置源地址，用于返回时判断订阅是否变更 */
    private var mLastApiUrl = ""
    /** 静默重载站点池：置位时 loadJar 完成后不重拉首页内容，避免切线路时首页闪一下加载态 */
    private var mSilentReload = false
    /** 站点池重载中标记：防止返回首页时 RefreshEvent 与 onResume 重复触发导致两次 loadConfig 并发（"冲突闪退"根因） */
    private var mReloading = false
    /** 加载在途标记：onResume 发起的完整加载流程未结束时置位，阻止再次 onResume 叠加新的 loadConfig */
    private var mLoadInFlight = false
    /** 冷启动缓存恢复标记：置位时后台刷新流程完成后不重拉首页（保留缓存 UI），消费一次自动失效 */
    private var mSkipHomeLoadOnce = false
    /**
     * 待补刷标记：上一次刷新还在跑时到来的重载请求（用户切了源/切了线路）。
     * 此时不能并发（会撕裂站点池），但**更不能丢弃** —— 在跑的那次读的是旧地址，
     * 丢弃的后果就是"切了源，界面还停在上一个源的线路数据"，且毫无提示。
     */
    private var mPendingReload = false
    /**
     * "这份订阅一个站点都没有"是否已提示过。
     * 这类源的表现最让人困惑：添加、启用全流程都成功，界面却搜不出任何东西 ——
     * 因为各层看到的都是"装载正常完成、只是结果为空"。提示只在真的判空时给，
     * 且同一份配置只给一次（切线路会重新武装），不反复弹。
     */
    private var mNoSiteWarned = false

    /** 首页站点候选（按序尝试）：当前线路站点优先，内置首页源兜底 */
    private var mHomeSiteCandidates: List<String> = emptyList()
    /** 当前尝试到第几个候选站点 */
    private var mHomeSiteIndex = 0
    /** 首页加载看门狗：超时强制结束加载态，杜绝"一直转圈" */
    private val mHomeWatchdog = Runnable { forceFinishHomeLoad() }

    var errorTipDialog: TipDialog? = null

    /**
     * true: 配置变更重载
     * false: 全部重载(api变更、重启app等)
     */
    var onlyConfigChanged = false

    override fun init() {
        ControlManager.get().startServer()
        mBinding.nameContainer.setOnLongClickListener {
            refreshHomeSources()
            true
        }
        mBinding.search.setOnClickListener {
            jumpActivity(FastSearchActivity::class.java)
        }
        mBinding.ivHistory.setOnClickListener {
            jumpActivity(HistoryActivity::class.java)
        }
        mBinding.ivCollect.setOnClickListener {
            jumpActivity(CollectActivity::class.java)
        }
        setupHomeTitle()
        setLoadSir(mBinding.contentLayout)
        initViewModel()
        mBinding.btnGoSubscription.setOnClickListener {
            jumpActivity(SubscriptionActivity::class.java)
        }
        setupSwipeRefresh()
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
        // 视图重建（切 tab 返回首页、Activity 重建）时不再重复拉取：
        // 已有分类数据直接用缓存重建 tab；首次加载由 onResume 统一驱动，避免与 init 叠加
        if (mSortDataList.isNotEmpty()) {
            showSuccess()
            initViewPager()
        }
    }

    /** 首页下拉刷新：列表滚到顶部时才生效，避免和列表滚动冲突 */
    private fun setupSwipeRefresh() {
        mBinding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.green_gradient_start),
            ContextCompat.getColor(requireContext(), R.color.green_gradient_end)
        )
        mBinding.swipeRefresh.setChildScrollCheck { currentGridCanScrollUp() }
        mBinding.swipeRefresh.setOnRefreshListener { reloadHome() }
    }

    /** 当前 tab 的列表是否还能继续向上滚（true=没到顶部，不应触发下拉刷新） */
    private fun currentGridCanScrollUp(): Boolean {
        val f = fragments.getOrNull(mBinding.tabLayout.currentItemIndex)
        return if (f is GridFragment) f.canScrollUp() else false
    }

    private fun stopRefresh() {
        if (mBinding.swipeRefresh.isRefreshing) {
            mBinding.swipeRefresh.isRefreshing = false
        }
    }

    /**
     * 订阅/设置变更后重新加载首页。
     * 与 reloadSitePool 的差别：这里由用户主动触发或配置变更事件触发，需要完整重拉线路与首页内容。
     */
    private fun reloadHome() {
        if (!isFragmentAlive()) {
            stopRefresh()
            return
        }
        // 断网时立即停止刷新动画并说明原因，不让用户白等一轮加载超时
        if (!NetworkMonitor.isOnline()) {
            stopRefresh()
            ToastUtils.showShort("当前无网络连接，请检查网络后重试")
            return
        }
        setupHomeTitle()
        reloadSitePool()
    }

    /** 订阅页/设置页改动后回到首页，自动刷新首页内容 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onHomeRefreshEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_HOME_REFRESH && isFragmentAlive()) {
            reloadHome()
        }
    }

    /**
     * 网络恢复（WiFi 重连、流量切回）时自动重试一次，省去用户手动下拉。
     * 仅在「没有加载在途 + 首页仍无内容 + 确实有订阅源」时触发：
     * 空源状态（用户没订阅）不重试，正在加载中也不叠加新请求。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onNetworkEvent(event: NetworkEvent) {
        if (!event.online || !isFragmentAlive()) return
        if (mLoadInFlight || mReloading) return
        if (mSortDataList.isNotEmpty()) return
        if (!ApiConfig.get().hasSubscription()) return
        reloadSitePool()
    }

    /** 回调可能晚于 Fragment 销毁（切页、配置变更、系统回收），此时操作 View/子 Fragment 会崩 */
    private fun isFragmentAlive(): Boolean {
        val act = activity ?: return false
        return isAdded && !act.isFinishing && !act.isDestroyed
    }

    /**
     * 左上角标题（单控件，两种形态）：
     * - 多仓：只显示 L1Box + 下拉箭头，点击展开线路切换（只换搜索站点池，首页内容不变）
     * - 单仓：显示软件名称，不可点击
     */
    private fun setupHomeTitle() {
        val subs = Hawk.get(HawkConfig.SUBSCRIPTIONS, ArrayList<Subscription>())
        val active = subs.firstOrNull { it.isChecked && it.isMultiRepo && !it.lines.isNullOrEmpty() }
        if (active == null) {
            mBinding.tvName.text = getString(R.string.app_name)
            mBinding.tvName.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            mBinding.tvName.isClickable = false
            mBinding.tvName.setOnClickListener(null)
            return
        }
        // 多仓：固定显示软件名，不显示线路名（线路切换在弹层里选）
        mBinding.tvName.text = getString(R.string.app_name)
        mBinding.tvName.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, R.drawable.ic_arrow_down)
        mBinding.tvName.isClickable = true
        mBinding.tvName.setOnClickListener { showLinePicker(active, subs) }
    }

    /**
     * 切换线路：替换搜索/播放用的站点池，首页内容也随新线路的站点一起刷新。
     * 后台静默重载，不重启 Activity。
     */
    private fun showLinePicker(active: Subscription, subs: ArrayList<Subscription>) {
        XPopup.Builder(requireContext())
            .asCustom(
                ChooseSourceDialog(requireContext(), active.lines) { _: Int, pickedUrl: String? ->
                    // 空/无效线路：绝不写入 HawkConfig.API_URL，否则下次 loadConfig 取到空串直接 NPE 闪退
                    if (pickedUrl.isNullOrBlank()) return@ChooseSourceDialog
                    active.activeLineUrl = pickedUrl
                    ApiConfig.saveApiUrl(pickedUrl)
                    Hawk.put(HawkConfig.SUBSCRIPTIONS, subs)
                    setupHomeTitle()
                    ToastUtils.showShort("已切换线路,搜索将使用该线路的站点")
                    reloadSitePool()
                }.setTitle("切换影视线路")
            ).show()
    }

    private fun initViewModel() {
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        sourceViewModel?.sortResult?.observe(this) { absXml: AbsSortXml? ->
            val sortList = absXml?.classes?.sortList
            // 当前站点取不到分类 → 自动顺延下一个候选站点（一条线路通常多个站点，任一可用即可）
            if (sortList.isNullOrEmpty() && tryNextHomeSite()) return@observe
            cancelHomeWatchdog()
            mLoadInFlight = false
            showSuccess()
            stopRefresh()
            // withMy=false：不再强制插入固定"主页"tab，分类完全由源站点自己提供
            mSortDataList = DefaultConfig.adjustSort(
                ApiConfig.get().getHomeSourceBean().key ?: "",
                if (sortList.isNullOrEmpty()) ArrayList() else sortList,
                false
            )
            initViewPager()
            // 首页冷启动缓存：分类骨架渲染成功即保存（合并已归档的影片列表，异步落盘）
            HomeCache.saveSkeleton(
                Hawk.get(HawkConfig.API_URL, ""),
                ApiConfig.get().getHomeSourceBean().key ?: "",
                mSortDataList
            )
        }
    }

    private fun initData() {
        // 回调可能晚于 Fragment 销毁到达，此时继续走加载流程会崩
        if (!isFragmentAlive()) return
        val mainActivity = mActivity as MainActivity
        onlyConfigChanged = mainActivity.useCacheConfig

        // 左上角形态（多仓=线路可切换 / 单仓=软件名称）
        setupHomeTitle()

        // 不再显示加载状态条：配置源加载期间保持内容区可见（无 tab 时为空），避免丑陋的加载条
        showSuccess()
        mBinding.homeEmptyState.visibility = View.GONE
        // 看门狗覆盖整条链路（内置配置→订阅→jar→取数），任何一步回调不来都会强制结束加载态
        startHomeWatchdog()

        // 1) 内置首页配置：仅提供 adjustSort 命名空间用的 homeSourceBean.key，不再作为首页展示内容
        if (!homeDataOk) {
            val homeReady = ApiConfig.get().loadLocalHomeConfig(object : LoadConfigCallback {
                override fun retry() {
                    mHandler.post { initData() }
                }
                override fun success() {
                    homeDataOk = true
                    mHandler.postDelayed({ initData() }, 50)
                }
                override fun error(msg: String) {
                    // 首页配置异常也不阻塞：订阅站点池仍可用于搜索
                    homeDataOk = true
                    mHandler.postDelayed({ initData() }, 50)
                }
            })
            // 内置配置始终存在，理论上必命中；万一缺失也继续走订阅，避免卡在加载态
            if (homeReady) return
            homeDataOk = true
            mHandler.postDelayed({ initData() }, 50)
            return
        }

        // 2) 订阅站点池：供搜索/播放使用（未配置订阅时回调 error("-1")，搜索走首页源兜底）
        if (!dataInitOk) {
            loadConfig()
            return
        }

        // 3) 均就绪后取首页数据
        when {
            dataInitOk && jarInitOk -> {
                // 首页内容取自当前线路的站点（站点提供什么就展示什么，与搜索/播放同源）
                startHomeLoad()
            }
            dataInitOk && !jarInitOk -> {
                // spider 为空时 loadJar() 不会产生任何回调，必须直接放行，否则永久停在加载态
                if (ApiConfig.get().spider.isNullOrEmpty()) {
                    jarInitOk = true
                    startHomeLoad()
                } else {
                    loadJar()
                }
            }
            else -> {
                loadConfig()
            }
        }
    }

    /**
     * 冷启动缓存秒显：线路地址与缓存一致时，用上次保存的分类+影片列表直接渲染首页；
     * 空源缓存则直接显示空态引导。缓存不匹配（换过线路/首次安装）返回 false 走全量流程。
     */
    private fun tryRestoreCache(curUrl: String): Boolean {
        val cache = HomeCache.get()
        if (cache.apiUrl != curUrl) return false
        if (cache.empty) {
            showSuccess()
            showHomeEmpty()
            return true
        }
        val tabs = cache.tabs ?: return false
        if (tabs.isEmpty()) return false
        val sorts = ArrayList<SortData>()
        for (t in tabs) {
            if (t.id == null) continue
            val sd = SortData(t.id, t.name ?: "")
            sd.flag = t.flag
            sorts.add(sd)
        }
        if (sorts.isEmpty()) return false
        mSortDataList = sorts
        mSkipHomeLoadOnce = true
        showSuccess()
        initViewPager()
        return true
    }

    /**
     * 首页内容来源：自动调用当前线路站点自己的首页数据。
     * 不再依赖固定的豆瓣热播——站点提供什么就展示什么，与搜索/播放同源，随订阅自动更新。
     */
    private fun startHomeLoad() {
        if (mSkipHomeLoadOnce) {
            // 冷启动已用缓存渲染且线路未变：后台刷新只保证订阅/jar/站点池就绪，不重拉首页
            mSkipHomeLoadOnce = false
            cancelHomeWatchdog()
            mLoadInFlight = false
            stopRefresh()
            return
        }
        mHomeSiteIndex = 0
        mHomeSiteCandidates = buildHomeSiteCandidates()
        // 每次真拉首页前清掉旧影片列表，防止换线路后旧数据混入新缓存
        HomeCache.clearVideos()
        if (mHomeSiteCandidates.isEmpty()) {
            cancelHomeWatchdog()
            mLoadInFlight = false
            showSuccess()
            stopRefresh()
            showHomeEmpty()
            HomeCache.saveEmpty(Hawk.get(HawkConfig.API_URL, ""))
            return
        }
        startHomeWatchdog()
        loadHomeFromSite(0)
    }

    /**
     * 首页候选站点只取**订阅站点池**，有源就按源提供内容。
     * 空源（用户未配置任何订阅线路）返回空 → 由 startHomeLoad 显示"添加订阅"引导，
     * 不用内置首页源兜底：内置源只能展示、点进去却搜不到资源，属于误导。
     *
     * 注意：判断必须在 loadConfig 之后（dataInitOk 阶段）才能生效——
     * initData 开头订阅还没拉完，此时 hasSubscription() 恒为 false，会误判成空源。
     */
    private fun buildHomeSiteCandidates(): List<String> {
        if (!ApiConfig.get().hasSubscription()) return emptyList()
        val keys = ArrayList<String>()
        for (sb in ApiConfig.get().getSourceBeanList()) {
            val k = sb.key ?: continue
            if (k.isNotEmpty() && !keys.contains(k)) keys.add(k)
        }
        // 每个站点最长 15s（getSort 的 Future 超时），只试 3 个：再多会拖长等待，且失败站点往往成片失效
        return keys.take(3)
    }

    private fun loadHomeFromSite(index: Int) {
        val key = mHomeSiteCandidates.getOrNull(index) ?: return
        val sb = ApiConfig.get().getSource(key) ?: return
        ApiConfig.get().setHomeSite(sb)
        sourceViewModel?.getSort(key)
    }

    /** 还有下一个候选站点就切过去重试；没有则结束尝试，交由空状态兜底 */
    private fun tryNextHomeSite(): Boolean {
        val next = mHomeSiteIndex + 1
        if (next >= mHomeSiteCandidates.size) return false
        mHomeSiteIndex = next
        loadHomeFromSite(next)
        return true
    }

    private fun startHomeWatchdog() {
        mHandler.removeCallbacks(mHomeWatchdog)
        // 30s：单站点最长 15s（getSort 的 Future 超时），留足第二个站点完整尝试的窗口，
        // 否则顺延还没出结果就被判定超时，白白浪费一次成功的机会
        mHandler.postDelayed(mHomeWatchdog, 30000)
    }

    private fun cancelHomeWatchdog() {
        mHandler.removeCallbacks(mHomeWatchdog)
    }

    /** 兜底：无论回调因何迟迟不来，都强制结束加载态，改为明确的空状态而非无限转圈 */
    private fun forceFinishHomeLoad() {
        // 看门狗兜底：无论因何迟迟不来回调，都先解除重载锁，避免后续切线路/刷新被永久吞掉
        mReloading = false
        mLoadInFlight = false
        showSuccess()
        stopRefresh()
        if (mSortDataList.isEmpty()) {
            showHomeEmpty()
            if (!NetworkMonitor.isOnline()) {
                ToastUtils.showShort("当前无网络连接，请检查网络后重试")
            } else {
                ToastUtils.showShort("首页加载超时，请检查订阅线路或长按标题重试")
            }
        }
    }

    private fun showHomeEmpty() {
        mBinding.contentLayout.visibility = View.GONE
        mBinding.homeEmptyState.visibility = View.VISIBLE
    }

    private fun loadConfig(){
        ApiConfig.get().loadConfig(onlyConfigChanged, object : LoadConfigCallback {

            override fun retry() {
                mHandler.post { initData() }
            }

            override fun success() {
                dataInitOk = true
                if (ApiConfig.get().spider.isNullOrEmpty()) {
                    jarInitOk = true
                }
                warnIfNoSite()
                mHandler.postDelayed({ initData() }, 50)
            }

            override fun error(msg: String) {
                // 订阅拉取失败（离线/不可达等）不阻塞首页，仍用已有站点池/内置源渲染
                mHandler.post {
                    dataInitOk = true
                    jarInitOk = true
                    if (!msg.equals("-1", ignoreCase = true)) {
                        // ApiConfig 给出的已经是**具体**结论（"订阅内容异常：返回的是网页，不是订阅配置" /
                        // "订阅拉取失败，请检查网络"），照实显示；只有它没给结论时才用兜底文案。
                        // 用 Long 而不是 Short：这类结论比"更新失败"长，Short 常被截断。
                        val tip = msg.trim()
                        com.blankj.utilcode.util.ToastUtils.showLong(
                            if (tip.isEmpty()) "订阅更新失败，请检查线路配置" else tip
                        )
                    }
                    initData()
                }
            }
        }, activity)
    }

    /**
     * 订阅装载完成、但**一个站点都没有**时提示一次。
     *
     * 站点池为空在各层都是"正常返回"（确已装载完成），边界上分不出"装完了但确实空"与
     * "本来就没内容"，所以只能由这里凭 ApiConfig 带出的实际数量来判断并说清楚 ——
     * 否则用户看到的就是"全都成功了，却搜不出任何东西"。
     */
    private fun warnIfNoSite() {
        if (mNoSiteWarned) return
        if (ApiConfig.get().lastSiteCount != 0) return
        mNoSiteWarned = true
        mHandler.post {
            com.blankj.utilcode.util.ToastUtils.showLong("订阅里没有可用站点，请检查源地址")
        }
    }

    private fun loadJar(){
        if (!ApiConfig.get().spider.isNullOrEmpty()) {
            ApiConfig.get().loadJar(
                onlyConfigChanged,
                ApiConfig.get().spider,
                object : LoadConfigCallback {
                    override fun success() {
                        jarInitOk = true
                        if (mSilentReload) {
                            mSilentReload = false
                            // 线路已切换，首页内容取自新线路的站点，需重新拉取（不弹"上次观看"）
                            showSuccess()
                            startHomeLoad()
                            return
                        }
                        mHandler.postDelayed({
                            if (!onlyConfigChanged && !sLastViewedShown) {
                                // 每次软件开启只显示一次"上次观看"，切页返回/刷新首页不再弹
                                sLastViewedShown = true
                                queryHistory()
                            }
                            initData()
                        }, 50)
                    }

                    override fun retry() {
                        // 空实现会让 jarInitOk 永远为 false 而停在加载态；直接放行交由后续兜底
                        jarInitOk = true
                        mHandler.post { initData() }
                    }
                    override fun error(msg: String) {
                        jarInitOk = true
                        if (mSilentReload) {
                            mSilentReload = false
                            mHandler.post { ToastUtils.showShort("线路切换失败,请重试") }
                            return
                        }
                        mHandler.post {
                            ToastUtils.showShort("更新订阅失败")
                            initData()
                        }
                    }
                })
        }
    }

    private fun showTipDialog(msg: String) {
        if (errorTipDialog == null) {
            errorTipDialog =
                TipDialog(requireActivity(), msg, "重试", "取消", object : TipDialog.OnListener {
                    override fun left() {
                        mHandler.post {
                            initData()
                            errorTipDialog?.hide()
                        }
                    }

                    override fun right() {
                        dataInitOk = true
                        jarInitOk = true
                        mHandler.post {
                            initData()
                            errorTipDialog?.hide()
                        }
                    }

                    override fun cancel() {
                        dataInitOk = true
                        jarInitOk = true
                        mHandler.post {
                            initData()
                            errorTipDialog?.hide()
                        }
                    }

                    override fun onTitleClick() {
                        errorTipDialog?.hide()
                        jumpActivity(SubscriptionActivity::class.java)
                    }
                })
        }
        if (!errorTipDialog!!.isShowing) errorTipDialog!!.show()
    }

    private fun getTabTextView(text: String): TextView {
        val textView = TextView(mContext)
        textView.text = text
        textView.gravity = Gravity.CENTER
        textView.setPadding(
            ConvertUtils.dp2px(20f),
            ConvertUtils.dp2px(10f),
            ConvertUtils.dp2px(5f),
            ConvertUtils.dp2px(10f)
        )
        return textView
    }

    private fun initViewPager() {
        // 晚到的回调在 Fragment 已销毁时创建子 Fragment 会抛 IllegalStateException
        if (!isFragmentAlive()) return
        if (mSortDataList.isNotEmpty()) {
            // 有分类数据：收起空状态，正常渲染标签页
            mBinding.contentLayout.visibility = View.VISIBLE
            mBinding.homeEmptyState.visibility = View.GONE
            mBinding.tabLayout.removeAllViews()
            fragments.clear()
            // 全部标签都来自源站点自己的分类，不再插入固定的"主页/豆瓣热播"
            for (data in mSortDataList) {
                mBinding.tabLayout.addView(getTabTextView(data.name))
                // 有缓存列表（冷启动恢复场景）直接预置，否则走网络模式
                fragments.add(GridFragment.newInstance(data, HomeCache.videosOf(data.id)))
            }
            //重新渲染vp
            // 先换 adapter 再调 offscreenPageLimit：offscreenPageLimit 会立即触发 ViewPager.populate()，
            // 若还挂着旧 adapter 而 fragments 已被 clear/addAll，就会抛
            // "adapter changed contents without notifyDataSetChanged"（切换源崩溃的根因）
            mBinding.mViewPager.adapter =
                object : FragmentStatePagerAdapter(getChildFragmentManager()) {
                    override fun getItem(position: Int): Fragment {
                        return fragments[position]
                    }

                    override fun getCount(): Int {
                        return fragments.size
                    }
                }
            // 让所有分类页保持存活（默认只缓存相邻1页），左右滑动/点标签切换时不再反复销毁重建，滑动更顺
            mBinding.mViewPager.offscreenPageLimit = fragments.size
            //tab和vp绑定
            install(mBinding.mViewPager, mBinding.tabLayout, true)
        } else {
            // 站点无任何分类（无订阅/无 spider 等）：显示友好空状态，避免一片空白
            showHomeEmpty()
        }
    }

    /**
     * 提供给主页返回操作
     */
    fun scrollToFirstTab(): Boolean {
        return if (mBinding.tabLayout.currentItemIndex != 0) {
            mBinding.mViewPager.setCurrentItem(0, false)
            true
        } else {
            false
        }
    }

    override fun onPause() {
        super.onPause()
        mLastApiUrl = Hawk.get(HawkConfig.API_URL, "")
        // 不可 removeCallbacksAndMessages(null)：会连带清掉待执行的 initData，
        // 从其他页面返回后首页就会永久停在加载态。
        // 看门狗也不在此取消——加载中切走再回来仍需它兜底，只在 onDestroy 取消。
    }

    override fun onResume() {
        super.onResume()
        if (!isFragmentAlive()) return
        // 已有重载在跑（如从订阅页返回时 RefreshEvent 触发的 reloadSitePool、或上一次
        // loadConfig 的 OkGo 回调尚未回来）就不再叠加：两个 loadConfig 并发会互相覆盖
        // sourceBeanList、撕裂首页加载流程（历史上表现为"冲突闪退"）。
        // 在跑的那次读的是**发起时刻**的地址；这期间用户若切了源，跳过就等于漏刷新。
        // 所以不能直接丢弃：记下待补刷，等当前这次跑完再补一次。
        if (mReloading || mLoadInFlight) {
            if (Hawk.get(HawkConfig.API_URL, "") != mLastApiUrl) mPendingReload = true
            return
        }
        // 线路未变且首页已完整加载过：直接保留现有内容（分类页/滚动位置），不重复刷新。
        // 源内容更新由下拉刷新 / 切线路 / 订阅页变更事件驱动；空源时 mSortDataList 为空，
        // 条件不满足 → 每次仍走原流程检查，自然回到"添加订阅源"引导。
        // 首次启动 mLastApiUrl 为初始空值且 dataInitOk=false，必然走全量刷新。
        val curUrl = Hawk.get(HawkConfig.API_URL, "")
        if (dataInitOk && jarInitOk && mSortDataList.isNotEmpty() && curUrl == mLastApiUrl) return
        // 每次启动/切回首页都重新刷新订阅源并抓取影视信息（不复用缓存，不依赖 URL 变化）。
        // 注意：绝不调用 removeCallbacksAndMessages(null)——那会连带清掉尚未执行的 initData
        // postDelayed 回调，导致切回后首页永远停在加载态；也绝不提前把 mReloading 置 false，
        // 否则会绕过上面的防重入判断重新触发并发 loadConfig。
        // 冷启动提速：线路与缓存一致时先用本地缓存秒显首页（或空源引导），
        // 订阅/jar/站点池照常在后台刷新，刷新完成后不重拉首页（保留缓存 UI）。
        if (mSortDataList.isEmpty()) tryRestoreCache(curUrl)
        mLoadInFlight = true
        homeDataOk = false
        dataInitOk = false
        jarInitOk = false
        mHomeSiteIndex = 0
        onlyConfigChanged = false
        initData()
    }

    /**
     * 重载搜索/播放用的站点池，并连带刷新首页内容（首页取自当前线路的站点）。
     * 不重启 Activity。
     */
    private fun reloadSitePool() {
        // 防止重复进入：返回首页时 SubscriptionActivity 的 RefreshEvent 与 onResume 的 API_URL 比对
        // 可能先后各触发一次，两次 loadConfig 并发会相互覆盖、撕裂首页加载流程，表现为"冲突闪退"。
        // 但"跳过"不等于"丢弃"：正在跑的那次读的是旧地址，丢掉这次切源请求，用户就会看到
        // "换了源但界面还是上一个源的线路数据"。所以记下待补刷，等它跑完补一次。
        if (mReloading) {
            mPendingReload = true
            return
        }
        mReloading = true
        mLoadInFlight = true
        // 用户主动重载（切线路/下拉/订阅变更）必须真刷首页，清除冷启动缓存的跳过标记
        mSkipHomeLoadOnce = false
        dataInitOk = false
        jarInitOk = false
        onlyConfigChanged = false
        // 不显示加载状态条（取消显示），保持内容区可见即可
        showSuccess()
        mBinding.homeEmptyState.visibility = View.GONE
        startHomeWatchdog()
        ApiConfig.get().loadConfig(false, object : LoadConfigCallback {
            override fun retry() {}
            override fun success() {
                // 配置加载阶段结束，解除重载锁，便于后续（如再次切线路）正常触发
                mReloading = false
                dataInitOk = true
                // 换了一份配置：空站点提示重新武装 —— 新线路若同样没有站点，也得说清楚
                mNoSiteWarned = false
                warnIfNoSite()
                if (ApiConfig.get().spider.isNullOrEmpty()) {
                    jarInitOk = true
                    // 站点池已换新线路，首页内容随之刷新
                    startHomeLoad()
                } else {
                    mSilentReload = true
                    loadJar()
                }
                schedulePendingReload()
            }
            override fun error(msg: String) {
                mReloading = false
                dataInitOk = true
                jarInitOk = true
                // 切换失败也用当前站点池重拉一次，避免停留在旧线路内容或加载态
                startHomeLoad()
                schedulePendingReload()
            }
        }, activity)
    }

    /**
     * 补刷：本次刷新期间若收到过被跳过的重载请求（切源/切线路），在这里补一次。
     * 延迟发起是为了让当前这次刷新的后续步骤（loadJar / 首页加载）先落地，
     * 避免两次流程抢时序；只补一次，不循环（mPendingReload 已清位）。
     */
    private fun schedulePendingReload() {
        if (!mPendingReload) return
        mPendingReload = false
        mHandler.postDelayed({
            if (isFragmentAlive() && !mReloading) {
                mLastApiUrl = Hawk.get(HawkConfig.API_URL, "")
                reloadSitePool()
            }
        }, 800)
    }

    /** 长按左上角：重新拉取订阅站点池并重刷首页内容，无需重启 Activity。 */
    private fun refreshHomeSources() {
        ToastUtils.showShort("正在重新拉取线路站点")
        setupHomeTitle()
        reloadSitePool()
    }

    override fun onDestroy() {
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        super.onDestroy()
        cancelHomeWatchdog()
        ControlManager.get().stopServer()
    }

    private fun queryHistory() {
        lifecycleScope.launch {
            val vodInfoList = withContext(Dispatchers.IO) {
                val allVodRecord = RoomDataManger.getAllVodRecord(100)
                val vodInfoList: MutableList<VodInfo?> = ArrayList()
                for (vodInfo in allVodRecord) {
                    if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty()) vodInfo.note =
                        vodInfo.playNote
                    vodInfoList.add(vodInfo)
                }
                vodInfoList
            }

            // 查询完成后更新UI
            if (vodInfoList.isNotEmpty() && vodInfoList[0] != null) {
                XPopup.Builder(context)
                    .hasShadowBg(false)
                    .isDestroyOnDismiss(true)
                    .isCenterHorizontal(true)
                    .isTouchThrough(true)
                    .offsetY(ScreenUtils.getAppScreenHeight() - 360)
                    .asCustom(LastViewedDialog(requireContext(), vodInfoList[0]))
                    .show()
                    .delayDismiss(4000)
            }
        }
    }

}