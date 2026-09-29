package com.github.tvbox.osc.ui.activity

import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.angcyo.tablayout.DslTabLayout
import com.blankj.utilcode.util.GsonUtils
import com.blankj.utilcode.util.KeyboardUtils
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ScreenUtils
import com.blankj.utilcode.util.ToastUtils
import com.github.catvod.crawler.JarLoader
import com.github.catvod.crawler.JsLoader
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.api.ApiConfig.LoadConfigCallback
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.DoubanSuggestBean
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.databinding.ActivityFastSearchBinding
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.event.ServerEvent
import com.github.tvbox.osc.picasso.MyOkhttpDownLoader
import com.github.tvbox.osc.ui.adapter.FastSearchAdapter
import com.github.tvbox.osc.ui.dialog.DoubanSuggestDialog
import com.github.tvbox.osc.ui.dialog.SearchCheckboxDialog
import com.github.tvbox.osc.ui.dialog.SearchSuggestionsDialog
import com.github.tvbox.osc.ui.dialog.TipDialog
import com.github.tvbox.osc.util.DeviceProfile
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.L1Executors
import com.github.tvbox.osc.util.L1FallbackDns
import com.github.tvbox.osc.util.L1ImageInflight
import com.github.tvbox.osc.util.NetworkMonitor
import com.github.tvbox.osc.util.SearchHelper
import com.github.tvbox.osc.viewmodel.SourceViewModel
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.interfaces.SimpleCallback
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.callback.StringCallback
import com.squareup.picasso.Picasso
import com.orhanobut.hawk.Hawk
import com.zhy.view.flowlayout.FlowLayout
import com.zhy.view.flowlayout.TagAdapter
import okhttp3.Response
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

class FastSearchActivity : BaseVbActivity<ActivityFastSearchBinding>(), TextWatcher {

    companion object {
        /** 搜索整体超时：到点强制收尾，避免弱网下无限转圈 */
        private const val SEARCH_TIMEOUT_MS = 30000L

        /** 搜索结果合并窗口：窗口内到达的多站点结果合并为一次列表刷新 */
        private const val SEARCH_FLUSH_MS = 120L

        // ── F1（2026-09-28 用户实测修订 · by2）─────────────────────────
        // 第一版是"滑动中只攒不落地，停稳一次全落"，真机结果是**右侧也出现断触**：
        // 攒批期间列表长时间不动、停手那一刻一次性插入几百条（插入＋重绑定＝主线程长任务），
        // 手感就是"滑着滑着突然不跟手"。改成**限流不暂停**：滑动中照常落地，
        // 只是每批压到 SCROLL_BATCH_MAX 条，剩下的下一拍继续 —— 列表持续推进、单次负载恒定。
        private const val SCROLL_BATCH_MAX = 12

        /** P1（2026-09-28）：图片"可见窗口"的预取余量（上下各多算几个 item），避免滑到边缘才加载 */
        private const val IMG_WINDOW_PREFETCH = 6

        /** 滚动中更新"图片需求窗口"的节流间隔 */
        private const val IMG_WINDOW_REFRESH_MS = 200L

        /** 帧耗时统计：一帧超过这个毫秒数就算"卡了一下"（60Hz 一帧 16.7ms，32ms≈丢两帧） */
        private const val FRAME_JANK_MS = 32L

        /** 停稳后多久做"可见图片体检"（给在下载的图留落地时间，避免误判） */
        private const val IMG_AUDIT_DELAY_MS = 1500L

        /** 一次体检最多补绑几项（防止大面积重绑反而卡） */
        private const val IMG_AUDIT_MAX_RETRY = 6

        /**
         * 一轮搜索内"可见±6 预取"的总额度（2026-09-28 用户口径：放宽成可见±6）。
         *
         * 为什么要有额度：不做上限的话，用户来回滑几次就会把整轮 500 多张全预热一遍 ——
         * 那就等于没有"可见优先"，白白占满 8 条并发槽、把用户真正要看的那几张挤到后面。
         * 额度用完就停，日志里能直接看到"剩余额度"。
         */
        private const val IMG_PREFETCH_BUDGET = 200

        /**
         * 站点 chip 分帧创建：每帧最多建几个（cc 实测一次性建 40 个造成 364ms 卡顿）。
         *
         * cj（2026-09-29）：改按设备档位现取（低档 4、中/高档 8）。低档机主线程更慢，
         * 同样 8 个 chip 的单帧代价更高，减半换取更小的单帧负载。中/高档与改造前逐字一致。
         */
        private val SITE_TAB_BATCH: Int
            get() = DeviceProfile.siteTabBatch()

        // ── 左栏断触（2026-09-28 用户报）───────────────────────────────
        // 症状：搜索页左栏拖着拖着突然不跟手（用户猜"起始滑动点落在无效的地方"）。
        // 根因：搜索结果每回来一个站点就 `addView` 一个 tab，重搜还会 `removeAllViews` 清空 ——
        // 手势进行中替换子 View，系统会向手势发 ACTION_CANCEL，拖动被中途掐断。
        // （另一半是库的设计：`DslTabLayout` 只在 maxScrollY>0「内容确实能滚」时才接管手势，
        //   tab 少 / 刚清空那一瞬间本来就拖不动 —— 那部分不动库。）
        // 做法：视图改动（新增/清空重建）一律延后到"距最后一次左栏滚动 > 400ms"再执行；
        // 数据（spNames/keyToName/待加列表）仍然立刻更新，逻辑与结果不受影响。
        private const val SITE_TAB_SETTLE_MS = 400L

        /**
         * 等待可搜（S1）的探池节奏：每 500ms 一次**纯内存读，零网络请求**。
         *
         * 冷启动时订阅配置要**走网络**拉，而内置首页配置是 assets 里的、瞬间就绪 ——
         * 两者之间存在数百毫秒到数秒的真空窗口。等待期间界面停在 loading
         * （不会先闪一个"暂无数据"再变出结果）。
         *
         * 2026-09-23 定稿（#2 四态状态机）：**这里不再有时间上限**。此前是 20s 预算用尽即
         * 判空（2026-09-22 的 READY_RETRY_MAX=40），而"等够时间＝没有结果"在逻辑上不成立 ——
         * 就绪时刻实测可晚到 ≈16.5s，预算天生踩空就是假空。现在等待由系统承担：
         * 20 秒只是**换一句文案**的分界（继续转圈继续等），池一到立刻出结果。
         * 正常搜索零影响：站点池一定论立刻跳出等待，热态 57~67ms 不变。
         */
        private const val READY_RETRY_DELAY_MS = 500L

        /** 等待期间给「正在初始化」提示的时机（4×500ms≈2s）：不能等到失败才说，观感是"提示→马上失败" */
        private const val INIT_HINT_AT_RETRY = 4

        /** 20 秒（40×500ms）：只把文案从「正在初始化播放源」换成「播放源仍在加载」，**不是**失败 */
        private const val LOADING_SWITCH_AT_RETRY = 40

        /**
         * A 补全守卫的观测上限（40×500ms＝20s，2026-09-24）。
         *
         * 守卫负责"首波有结果之后**再等一个池变化**"，用来补上冷启动预热、切源、
         * jar 装载完成这三类情况下首波没见过的那批站点。它**纯内存探池、零网络请求**，
         * 用户完全无感（不弹提示、不动界面）：正常场景池早已稳定，第二拍就自己结束。
         *
         * 上限取 20 秒是照着实测定的 —— 订阅就绪实测 4.7~16.5 秒，取 20 秒刚好覆盖，
         * 网络慢到这个上限仍不回来时收工，用户手动再搜一次即是全量新池。
         */
        private const val GUARD_MAX_TICKS = 40

        /**
         * 停滞检测阈值（bv，2026-09-24）：**0 结果**的搜索里，连续 10 秒一个站点回调都没有，
         * 就认定有站点卡住了，直接按超时收尾 —— 不再干等满 30 秒看门狗。
         *
         * 为什么要它：bu 复测 A2 四轮**全是 30 秒超时收尾**，而那一轮 30 秒里有 **22 秒几乎零日志**
         * （每秒 0~3 行，只有 jar 自己每 5 秒重试一次）—— 早就没有任何站点在工作了，用户却要白等。
         * 10 秒这个数的依据：正常轮次 2~8 秒收尾、首条结果 64ms~2.4s，10 秒静默已远超"任何站点在工作"
         * 的形态，而不是"慢"。
         *
         * ⚠ **两档阈值**（bw，2026-09-24）：0 结果的搜索走本常量，**有结果**的走 `SETTLE_QUIET_MS`（见下）。
         * 让慢站点继续补 —— "结果条数一个字不少"这条不能破。
         */
        private const val STALL_QUIET_MS = 10000L

        /**
         * **结果稳定**阈值（bw，2026-09-24）：**有结果**的搜索里，连续 3 秒一个站点回调都没有，
         * 就认定该回来的都回来了，按**正常收尾**结束 —— 不再干等满 30 秒看门狗。
         *
         * 为什么必须加它：bv 真机 8 轮搜索**全部**是「超时收尾 30006ms 已回=39/40」，最刺眼的一轮
         * 首条结果 **75ms** 就上了屏，转圈却挂满 30 秒。两个出口都进不去：
         *   ① 正常收尾只能由 `allRunCount == 0` 触发，而 40 站里恒定有 1 个不回，永远不为 0；
         *   ② 停滞检测带着「结果为空」的判据（当初刻意不打扰有结果的搜索），有结果时完全不生效。
         *
         * 为什么走 onFirstWaveDone 而不是 finishSearchByTimeout：后者会 `shutdownNow()` 中断在途
         * 搜索线程，迟到结果直接丢；前者不碰线程池，收尾后回来的结果会走 searchData() 的迟到分支
         * **正常落地并刷新列表**，结果条数一个字不少，最坏只是列表又多了几条。
         *
         * 3 秒这个数的依据：实测站点回调集中在 0~3 秒（首条 55~317ms，冷启动最慢 2.5s），
         * 3 秒**完全静默**（任何回调含空回调都会刷新 lastProgressMs）＝确实没人还在跑。
         */
        private const val SETTLE_QUIET_MS = 3000L

        /** 停滞检测的节拍（1 秒一拍；纯内存读，零网络请求） */
        private const val STALL_TICK_MS = 1000L

        /**
         * 订阅「同址重拉」的每启动一次护栏（2026-09-23 定稿 #2）。
         *
         * 站点池已定论 + 站点数 0 + 本机存有订阅地址 ＝ "订阅未加载成功"，与"订阅里没有站点"
         * 和"用户把站点全过滤掉了"是两回事，不能拿"暂无数据"冒充。自动重拉**地址一字不改**
         * （是重试，不是换源），且**每启动最多 1 次**：地址不对的话重拉一百次也一样，
         * 一次足够，之后交用户手动（弹窗里的「重试」不受此护栏限制）。
         * static：跟随进程，换页面不重置；重开 App 才重新给一次。
         */
        private var subReloadTried = false

        private var mCheckSources: HashMap<String, String>? = null
        fun setCheckedSourcesForSearch(checkedSources: HashMap<String, String>?) {
            mCheckSources = checkedSources
        }
    }

    private lateinit var sourceViewModel : SourceViewModel
    private var searchAdapter = FastSearchAdapter()
    private var searchAdapterFilter = FastSearchAdapter()
    private var searchTitle: String? = ""
    private var spNames = HashMap<String, String>()

    /** key→站点名 反查表：结果回调按 sourceKey 取名字，替掉遍历 spNames 的线性查找 */
    private var keyToName = HashMap<String, String>()
    private var isFilterMode = false
    private var searchFilterKey: String? = "" // 过滤的key
    private var resultVods = HashMap<String, MutableList<Movie.Video>>()
    private var mSearchSuggestionsDialog: SearchSuggestionsDialog? = null

    /** 订阅失败态的提示弹窗（#2 定稿：复用现成 TipDialog，不新增界面） */
    private var subFailDialog: TipDialog? = null
    override fun init() {
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        initView()
        initData()
        //历史搜索
        initHistorySearch()
    }

    private fun initView() {
        mBinding.etSearch.setOnEditorActionListener { _: TextView?, actionId: Int, _: KeyEvent? ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(mBinding.etSearch.text.toString())
                return@setOnEditorActionListener true
            }
            false
        }
        mBinding.etSearch.addTextChangedListener(this)
        mBinding.ivFilter.setOnClickListener { filterSearchSource() }
        mBinding.ivBack.setOnClickListener { finish() }
        mBinding.ivSearch.setOnClickListener {
            search(mBinding.etSearch.text.toString())
        }
        mBinding.tabLayout.configTabLayoutConfig {
            onSelectViewChange  = { _, selectViewList, _, _ ->
                    val tvItem: TextView = selectViewList.first() as TextView
                    filterResult(tvItem.text.toString())
                }
        }
        // 左栏断触（2026-09-28）：记录左栏最后一次滚动时刻 —— 站点 tab 的增删一律等"停手"才做
        // （见 scheduleSiteTabs）。DslTabLayout 滚动走 scrollTo，View 的滚动回调能收到。
        mBinding.tabLayout.setOnScrollChangeListener { _, _, _, _, _ ->
            siteTabsLastScrollMs = SystemClock.elapsedRealtime()
        }
        mBinding.mGridView.setHasFixedSize(true)
        // P1（2026-09-28）：**关掉 item 插入动画**。结果每拍持续插入 12 条、持续十几秒，
        // 默认 DefaultItemAnimator 会让手指下的 item 一路位移/淡入 —— 视觉上就是"不跟手"。
        mBinding.mGridView.itemAnimator = null
        // F3（2026-09-28）：缓存加厚（默认 2）—— 滑动来回时减少重绑定
        // cj（2026-09-29）：条数改按设备档位取（低档 3、中/高档仍为 6），中高档行为逐字不变。
        mBinding.mGridView.setItemViewCacheSize(DeviceProfile.itemViewCacheSize())
        mBinding.mGridView.setLayoutManager(LinearLayoutManager(this))
        mBinding.mGridView.adapter = searchAdapter
        // 2026-09-28：**不再给 adapter 设"可见门闸"**（上一版按 position 判可见性，导致
        // "点左侧特定站点时整页图不加载"的误判）。请求一律照发，越界的在途请求由
        // L1ImageInflight.cancelOutside() 在停稳/切站点时取消。
        // F1（2026-09-28 用户实测修订 · by2）：滑动中**只限流不暂停** —— 每批最多 SCROLL_BATCH_MAX 条，
        // 其余下一拍继续。第一版"滑动中暂停落地＋暂停图片"在真机上导致右侧断触与图片大面积变慢，
        // 已按实测撤掉（原因见 SCROLL_BATCH_MAX 处的注释）。
        mBinding.mGridView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                scrollPauseActive = newState != RecyclerView.SCROLL_STATE_IDLE
                if (scrollPauseActive) {
                    // 滑动中：节流刷新"取消越界在途下载"的判断（不动任何视图、不发任何预判）
                    scheduleImageWindow(IMG_WINDOW_REFRESH_MS)
                } else {
                    // 停稳：①把攒着的落完 ②取消已滚出可见范围的图片下载（让并发槽让给可见的图）
                    // 注意：这里**不再**对可见区间强制重绑（那是上一版为门闸兜底加的补丁，已随门闸撤除）
                    if (pendingResults.isNotEmpty() && !flushScheduled) scheduleFlush()
                    cancelOutOfWindowImages()
                    scheduleVisibleImageAudit()
                    // 停稳后顺手把"马上要看到的那一圈"图预热（用户口径：可见±6 预取）
                    prefetchAroundVisible()
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // 2026-09-28：**滑动中不取消任何下载** —— 滑动中"可见范围"每秒都在变，
                // 取消会误伤"fling 结束后正好停在屏幕里"的那些图（cc 实测：取消数≈失败里的
                // SocketException 数，且被取消的图若来自复用缓存就不再重绑，会永久停在灰图）。
                // 取消只在**停稳**（下面 IDLE 分支）做，那时可见范围已经稳定。
            }
        })
        // 过滤列表同样要参与"取消越界在途下载"（否则切换站点后旧列表的在途请求会一直占着并发槽）
        mBinding.mGridViewFilter.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    cancelOutOfWindowImages()
                    scheduleVisibleImageAudit()
                    prefetchAroundVisible()
                }
            }
        })
        // 2026-09-28：帧耗时统计**页面存活期常开**（上一版只覆盖搜索期间，拖左栏的卡顿根本没被记录）
        startFrameWatch()
        searchAdapter.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            val video = searchAdapter.data[position]
            // 进详情页**不拆搜索**（2026-09-14 aq 修复"反复进出详情页后补全停止"）：
            // 原来这里会把搜索池 shutdownNow()，并把返回的"尚未开跑"任务缓存起来、回页时重投。
            // 但 shutdownNow 只交出排队中的任务，**已经在跑的被直接打断且永不回来**；回页时队列往往
            // 已经空了 → 这一轮在飞的站点被永久丢弃，且完成计数被重新基准化 → 页面"正常收尾"却少一块结果。
            // 搜索本身是有界任务（看门狗 30 秒封顶），让它后台跑完即可，返回时看到的就是完整列表。
            val bundle = Bundle()
            bundle.putString("id", video.id)
            bundle.putString("sourceKey", video.sourceKey)
            // 片名兜底：部分源详情接口不回 vod_name，详情页据此避免显示"暂无信息"
            bundle.putString("title", video.name)
            jumpActivity(DetailActivity::class.java, bundle)
        }
        mBinding.mGridViewFilter.setLayoutManager(LinearLayoutManager(this))

        mBinding.mGridViewFilter.adapter = searchAdapterFilter
        searchAdapterFilter.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            val video = searchAdapterFilter.data[position]
            if (video != null) {
                // 同上：过滤视图里点击同样不拆搜索
                val bundle = Bundle()
                bundle.putString("id", video.id)
                bundle.putString("sourceKey", video.sourceKey)
                bundle.putString("title", video.name)
                jumpActivity(DetailActivity::class.java, bundle)
            }
        }

        searchAdapter.setOnItemLongClickListener { _, _, position ->
            val video = searchAdapter.data[position]
            getDoubanSuggest(video.name)
            true
        }
        searchAdapterFilter.setOnItemLongClickListener { _, _, position ->
            val video = searchAdapterFilter.data[position]
            getDoubanSuggest(video.name)
            true
        }

        setLoadSir(mBinding.llLayout)
    }

    /**
     * 指定搜索源(过滤)
     */
    private fun filterSearchSource() {
        // 用快照遍历：直连原始站点池会在切线路重载 clear 时抛并发修改异常闪退
        val allSourceBean = ApiConfig.get().getSourceBeanList()
        if (allSourceBean.isNotEmpty()) {
            val searchAbleSource: MutableList<SourceBean> = ArrayList()
            for (sourceBean: SourceBean in allSourceBean) {
                if (sourceBean.isSearchable) {
                    searchAbleSource.add(sourceBean)
                }
            }
            val mSearchCheckboxDialog = SearchCheckboxDialog(this@FastSearchActivity, searchAbleSource, mCheckSources)
            mSearchCheckboxDialog.show()
        }

    }

    private fun filterResult(spName: String) {
        if (spName == "全部显示") {
            isFilterMode = false
            mBinding.mGridView.visibility = View.VISIBLE
            mBinding.mGridViewFilter.visibility = View.GONE
            return
        }
        isFilterMode = true
        mBinding.mGridView.visibility = View.GONE
        mBinding.mGridViewFilter.visibility = View.VISIBLE
        val key = spNames[spName]
        if (key.isNullOrEmpty()) return
        if (searchFilterKey === key) return
        searchFilterKey = key
        // by2 崩溃修复（2026-09-28 真机 FATAL）：原来这里是 `(resultVods[key])!!` ——
        // 老防线只挡住"名字不在站点表里"，**没挡住"名字在、但该站点还没有结果桶"**。
        // 原设计 tab 只在"有结果"时才出现，所以撞不到；一旦左栏出现尚未返回结果的站点
        // （预建/补轮等任何来源），点它必定 NPE 闪退。这里按"该站点暂无结果"处理成空列表。
        val list: List<Movie.Video>? = resultVods[key]
        searchAdapterFilter.setNewData(if (list == null) ArrayList() else ArrayList(list))
        // 2026-09-28：切换站点后，旧列表的"在途图片下载"要按新列表的可见范围重新判一次
        // （不然它们会一直占着并发槽，新列表的图排队）。等一帧让过滤列表完成布局再算。
        mBinding.mGridViewFilter.post { cancelOutOfWindowImages() }
    }

    private fun initData() {
        mCheckSources = SearchHelper.getSourcesForSearch()
        if (intent != null && intent.hasExtra("title")) {
            val title = intent.getStringExtra("title")
            if (!TextUtils.isEmpty(title)) {
                showLoading()
                search(title)
            }
        }
    }

    private fun hideHotAndHistorySearch(isHide: Boolean) {
        if (isHide) {
            mBinding.llSearchSuggest.visibility = View.GONE
            mBinding.llSearchResult.visibility = View.VISIBLE
        } else {
            mBinding.llSearchSuggest.visibility = View.VISIBLE
            mBinding.llSearchResult.visibility = View.GONE
        }
    }

    private fun initHistorySearch() {
        val mSearchHistory: List<String> = Hawk.get(HawkConfig.HISTORY_SEARCH, ArrayList())
        mBinding.llHistory.visibility = if (mSearchHistory.isNotEmpty()) View.VISIBLE else View.GONE
        mBinding.flHistory.adapter = object : TagAdapter<String?>(mSearchHistory) {
            override fun getView(parent: FlowLayout, position: Int, s: String?): View {
                val tv: TextView = LayoutInflater.from(this@FastSearchActivity).inflate(
                    R.layout.item_search_word_hot,
                    mBinding.flHistory, false
                ) as TextView
                tv.text = s
                return tv
            }
        }
        // 长按删除单条历史。监听必须挂在 TagFlowLayout 自己包出来的容器上，**不能**挂在 getView 返回的
        // 标签控件上：库的 changeAdapter() 对该控件调用了 setClickable(false)、把点击回调挂到容器，
        // 控件一旦自己吃掉触摸序列，单击搜索就会失效。容器本身可点击，长按与单击互不干扰。
        // setAdapter 内部同步执行 changeAdapter()，此处子 View 已就绪且顺序与数据一致。
        for (i in 0 until mBinding.flHistory.childCount) {
            val word = mSearchHistory.getOrNull(i) ?: break
            mBinding.flHistory.getChildAt(i).setOnLongClickListener {
                removeSearchHistory(word)
                true
            }
        }
        mBinding.flHistory.setOnTagClickListener { _: View?, position: Int, _: FlowLayout? ->
            search(mSearchHistory[position])
            true
        }
        findViewById<View>(R.id.iv_clear_history).setOnClickListener { view: View ->
            Hawk.put(HawkConfig.HISTORY_SEARCH, ArrayList<Any>())
            //FlowLayout及其adapter貌似没有清空数据的api,简单粗暴重置
            view.postDelayed({ initHistorySearch() }, 300)
        }
    }

    /**
     * 长按删除**单条**搜索历史。
     *
     * 按文本定位而不按下标：删掉一条后剩下的条目会整体前移，用下标会删错。
     * 重建沿用"清空全部"那套（延迟后调 initHistorySearch）：长按触发时手指仍按在标签上、
     * 触摸序列尚未结束，立即在事件回调里拆掉整层子 View 会与库内部的触摸记录打架。
     * 重建后流式布局自动补位，不会留空位。
     */
    private fun removeSearchHistory(word: String) {
        val history: ArrayList<String?> = Hawk.get(HawkConfig.HISTORY_SEARCH, ArrayList<String?>())
        if (!history.remove(word)) return
        Hawk.put(HawkConfig.HISTORY_SEARCH, history)
        mBinding.flHistory.postDelayed({ initHistorySearch() }, 300)
    }

    /**
     * 联想搜索
     */
    private fun getSuggest(text: String) {
        // 加载热词
        OkGo.get<String>("https://suggest.video.iqiyi.com/?if=mobile&key=$text")
            .execute(object : AbsCallback<String?>() {
                override fun onSuccess(response: com.lzy.okgo.model.Response<String?>) {
                    val titles: MutableList<String> = ArrayList()
                    try {
                        val json = JsonParser.parseString(response.body()).asJsonObject
                        val datas = json["data"].asJsonArray
                        for (data: JsonElement in datas) {
                            val item = data as JsonObject
                            titles.add(item["name"].asString.trim { it <= ' ' })
                        }
                    } catch (th: Throwable) {
                        LogUtils.d(th.toString())
                    }
                    if (titles.isNotEmpty()) {
                        showSuggestDialog(titles)
                    }
                }

                @Throws(Throwable::class)
                override fun convertResponse(response: Response): String {
                    return response.body()!!.string()
                }
            })
    }

    private fun showSuggestDialog(list: List<String>) {
        if (mSearchSuggestionsDialog == null) {
            mSearchSuggestionsDialog =
                SearchSuggestionsDialog(this@FastSearchActivity, list
                ) { _, text ->
                    LogUtils.d("搜索:$text")
                    mSearchSuggestionsDialog!!.dismissWith { search(text) }
                }
            XPopup.Builder(this@FastSearchActivity)
                .atView(mBinding.etSearch)
                .notDismissWhenTouchInView(mBinding.etSearch)
                .isViewMode(true) //开启View实现
                .isRequestFocus(false) //不强制焦点
                .setPopupCallback(object : SimpleCallback() {
                    override fun onDismiss(popupView: BasePopupView) { // 弹窗关闭了就置空对象,下次重新new
                        super.onDismiss(popupView)
                        mSearchSuggestionsDialog = null
                    }
                })
                .asCustom(mSearchSuggestionsDialog)
                .show()
        } else { // 不为空说明弹窗为打开状态(关闭就置空了).直接刷新数据
            mSearchSuggestionsDialog!!.updateSuggestions(list)
        }
    }

    private fun saveSearchHistory(searchWord: String?) {
        if (!searchWord.isNullOrEmpty()) {
            val history = Hawk.get(HawkConfig.HISTORY_SEARCH, ArrayList<String?>())
            if (!history.contains(searchWord)) {
                history.add(0, searchWord)
            } else {
                history.remove(searchWord)
                history.add(0, searchWord)
            }
            if (history.size > 30) {
                history.removeAt(30)
            }
            Hawk.put(HawkConfig.HISTORY_SEARCH, history)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun server(event: ServerEvent) {
        if (event.type == ServerEvent.SERVER_SEARCH) {
            val title = event.obj as String
            showLoading()
            search(title)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    override fun refresh(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_SEARCH_RESULT) {
            try {
                searchData(if (event.obj == null) null else event.obj as AbsXml)
            } catch (_: Throwable) {
                searchData(null)
            }
        }
    }

    private fun search(title: String?) {
        if (title.isNullOrEmpty()) {
            ToastUtils.showShort("请输入搜索内容")
            return
        }
        // G2（2026-09-28）：一轮搜索＝一份图片成败账本，收尾时打统计行
        MyOkhttpDownLoader.imgStatsReset()
        // P1：需求窗口与队列计数复位；开始量本轮帧耗时（"跟不跟手"的量化）
        L1ImageInflight.reset()
        FastSearchAdapter.resetImageCounters()
        lastVisibleImageAudit = "可见图片：未体检"
        // 预取记账与额度按轮复位（每轮一份新额度，见 IMG_PREFETCH_BUDGET）
        prefetchedUrls.clear()
        prefetchBudget = IMG_PREFETCH_BUDGET
        stage("搜索中")
        // 断网时立即给出明确提示，不让用户干等 10 秒超时；同时收掉上一轮还在跑的搜索
        if (!NetworkMonitor.isOnline()) {
            cancelSearchWatchdog()
            cancel()
            try {
                if (searchExecutorService != null) {
                    searchExecutorService!!.shutdownNow()
                    searchExecutorService = null
                }
            } catch (_: Throwable) {
            }
            allRunCount.set(0)
            showEmpty()
            ToastUtils.showShort("当前无网络连接，请检查网络后重试")
            return
        }

        //先移除监听,避免重新设置要搜索的文字触发搜索建议并弹窗
        mBinding.etSearch.removeTextChangedListener(this)
        mBinding.etSearch.setText(title)
        mBinding.etSearch.setSelection(title.length)
        mBinding.etSearch.addTextChangedListener(this)
        if (mSearchSuggestionsDialog != null && mSearchSuggestionsDialog!!.isShow) {
            mSearchSuggestionsDialog!!.dismiss()
        }
        if (!Hawk.get(HawkConfig.PRIVATE_BROWSING, false)) { //无痕浏览不存搜索历史
            saveSearchHistory(title)
        }
        hideHotAndHistorySearch(true)
        KeyboardUtils.hideSoftInput(this)
        cancel()
        showLoading()
        searchTitle = title
        //fenci();
        mBinding.mGridView.visibility = View.INVISIBLE
        mBinding.mGridViewFilter.visibility = View.GONE
        searchAdapter!!.setNewData(ArrayList())
        searchAdapterFilter!!.setNewData(ArrayList())
        resultVods.clear()
        searchFilterKey = ""
        isFilterMode = false
        spNames.clear()
        keyToName.clear()
        pendingResults.clear()
        // 左栏断触（2026-09-28）：清空重建走队列（用户正拖左栏时不动视图，停手 400ms 内补齐）
        queueSiteTabsReset()
        // 用户手动发起的搜索：所有"等一等"的节拍与提示都从这里重新开始。
        // 不在 searchResult() 里清是因为那里也是"等池变化后重搜"的入口 —— 清在那儿等于等待失效。
        searchWatchdogHandler.removeCallbacks(waitTickRunnable)
        readyRetry = 0
        longLoadHintShown = false
        waitDebounceSig = ""
        // 用户手动新一轮：上一轮的补全守卫作废（"已搜集合"与"已补过"都不再适用，见 searchResult 的清空）
        guardDone = false
        guardTicks = 0
        phase = Phase.S2
        searchResult()
    }

    private var searchExecutorService: ExecutorService? = null
    private val allRunCount = AtomicInteger(0)

    /**
     * #2（2026-09-23 定稿）4 态收敛状态机。**只有 S4 到得了"暂无数据"**。
     *
     * ```
     * S1 等待可搜 ──池就绪──▶ S2 搜索中 ──收齐/看门狗──▶ S3 复检
     *   ▲                                                  │
     *   └────────── 本轮不可信：回 S2（由池变化驱动）◀──────┤
     *                                                      └──六条判据全成立──▶ S4 诚实空态
     *
     * S2 搜索中 ──收齐且有结果──▶ S5 补全守卫 ──池变化──▶ S2（只搜新增站点）
     * ```
     * 关键：S1 **没有任何时间上限**，也没有任何通往"暂无数据"的路径 —— 假空被结构性删除。
     * 另外一条旁路：池已定论但池里空的＋有订阅地址 ＝ 订阅未加载成功，单独成态，不冒充空态。
     *
     * S5（A 补全守卫，2026-09-24）与 S3 是**两条独立路径**，互不干扰：
     * S3 是"0 结果时重搜"（无次数上限、由池变化驱动），S5 是"有结果时补搜新增站点"（最多一次）。
     */
    private enum class Phase { S1, S2, S3, S4, S5, SUB_FAIL }

    private var phase = Phase.S1

    /** 本轮搜索内"等待可搜"的探池节拍计数（纯计数，**不再当预算用**；只在用户手动 search() 里清零） */
    private var readyRetry = 0

    /**
     * 探池节拍。两种等待共用：
     *  - S1：池未定论 → 池一定论就进 S2；
     *  - S3：本轮不可信（0 结果但期间池变过/有半途装载）→ 等池签名变化再进 S2。
     */
    private val waitTickRunnable = Runnable { if (!isFinishing && !isDestroyed) onWaitTick() }

    /** 「正在初始化」/「仍在加载」两句提示的每次手动搜索状态（见 onWaitTick） */
    private var longLoadHintShown = false

    /**
     * 本轮的"世界状态"快照，收尾时拿它和当前值比对（见 reviewZeroResult）。
     *  - roundPoolSettled：发起那一刻池是否已有定论；
     *  - roundLoadSeq：发起那一刻 jar 装载序号；
     *  - roundPoolSig：发起那一刻的**池签名**（池大小 + 定论 + 装载序号），
     *    它把"池变了/半途装载"两件事合成一个比较 —— 也是"池每变一次放行一次"的驱动量。
     *  - roundDone：本轮**实际回调回来**的站点数（bv，2026-09-24）。判据② 的真数据源 ——
     *    原来这里叫 roundAsked，而它在发起时被赋成 `siteKey.size`（与 roundSiteCount **同一个值**）
     *    ⇒ 判据② 恒真、什么都测不出来（bu 复测挖出的洞 5）。现在按每个站点的真实回调自增。
     *  - roundEndedByTimeout：本轮是否以**超时收尾**结束（bv）。正常收尾只能由 `count == 0` 触发，
     *    而 `count == 0` 在代码上就等于"发起的站点全都回来了"；反过来，超时收尾**必然**有站点没回来。
     *    所以它是"没问全"最硬的一条证据，比数回调个数更不容易被上一轮的迟到回调污染。
     */
    private var roundPoolSettled = true
    private var roundLoadSeq = 0L
    private var roundPoolSig = ""
    private var roundDone = 0
    private var roundEndedByTimeout = false

    /** 本轮发起时的"空站点"累计值。增量＝这一轮有多少站点因 jar 未就绪/加固不可用被跳过 */
    private var roundSpiderNull = 0L

    /**
     * 本轮发起时**已经见过**的"被跳过站点"账本快照（bu，2026-09-24）。
     *
     * 与 roundSpiderNull 的分工：那个是"跳了几次"（同一个坏站点每轮都算一次），这个是"跳过了哪些
     * **新面孔**"的基准。判空只看新面孔 —— 老面孔是本机跑不了的站点，反复等它没有意义（等到天荒地老
     * 也还是空），而新面孔可能是瞬时的。
     */
    private var roundSkippedBase: Set<String> = emptySet()

    /** 本轮**新面孔**（此前从未被跳过、这一轮被跳过）的站点 key。纯内存读，零网络请求 */
    private fun freshSkippedKeys(): Set<String> = JarLoader.skippedKeys() - roundSkippedBase

    /**
     * S3 等池变化时的去抖用：上一拍看到的池签名。
     *
     * 冷启动时一批 jar 会集中落定，loadSeq 可能在 1~2 秒内连跳十几次。
     * 若"一变就重搜"，十几次装载会被放大成十几次全站搜索。这里要求变化**连续两拍（≥500ms）一致**
     * 才算落定，把一串连续装载折叠成一次重搜。
     */
    private var waitDebounceSig = ""

    /**
     * A（缓存清单先开搜 · 档 2，2026-09-24）本轮**已提交搜索**的站点 key 集合。
     *
     * 补轮据此算"池里新增、首波没搜过"的差集：源没变化时节集为空 ⇒ **一个请求都不发**，
     * 源新增 2 个站点就只发 2 个请求。搜索请求总数与并发节拍一个字不改。
     */
    private val roundAskedKeys = HashSet<String>()

    /** 本轮是否为**补轮**（只追加不清空），供收尾日志与守卫自锁使用 */
    private var roundAppendOnly = false

    /** 补全守卫是否已收工（补过一轮、或确认无新增）。**最多补一次**，防止 jar 陆续装载引发多轮补搜 */
    private var guardDone = false

    /** 补全守卫已观测的拍数（兜底上限见 GUARD_MAX_TICKS） */
    private var guardTicks = 0

    /**
     * 池签名：**纯内存读，零网络请求**。
     * 站点数 + 定论标志 + 主 jar 是否已装载定论 + **站点 key 集合指纹** + **池来源（缓存/网络）**。
     *
     * bv（2026-09-24）**把 jar 装载序号撤出签名**：`loadSeq` 是"装载**活动**"计数器（失败也计数），
     * 不是"池**内容**变了没"。真机实证（bu 18:58:23）：`41|true|4|true|false -> 41|true|8|true|false`
     * —— 站点数、主 jar、池来源**全没变**，变的只有它；而同一时段的日志里**没有任何一条 jar 装载
     * 成功记录**。后果是判据③（池未变）恒假 ⇒ S3 每 1 秒又起一整轮 30 秒 ⇒ **永不收敛**。
     * "jar 装上了 ⇒ 站点变可搜"这件事改由**判据⑥（本轮新面孔被跳过）**覆盖：装不上会被 `skipNull`
     * 记账 ⇒ 是新面孔 ⇒ 重搜一轮；真装上了，那一轮本来就有结果。两个方向都不会漏。
     * `loadSeq` 本身保留 —— 收尾摘要里的"本轮装载增量"是诊断硬指标，只是不再参与判据。
     *
     * 最后一段是 bu（2026-09-24）补的：冷启动预热时首波搜的是**本地缓存那份清单**，网络配置到达会换掉
     * 整个池，而两者站点数**常常完全相同**（缓存本来就是上一次网络配置的存档）⇒ 前四段一字不变 ⇒ S3 会把
     * "换了内容的池"当成"池没变"，直接判空。真机实证：18:17:32.852 复检还认得出 `缓存池=true`（所以那一拍
     * 不敢判空），1 秒后 18:17:33.855 就判了空，中间签名一字未变，变的只有这个布尔位 ——
     * 敢不敢判空取决于标志位时序，而不是"池真的没变"。
     */
    private fun poolSignature(): String {
        // 注意：运算符必须写在**行尾**。函数体的顶层没有括号包着，行首的 `+` 会被 Kotlin
        // 当成新语句的开头（一元加），直接编译报 "Expecting member declaration"。
        // 主 jar 的装载状态也进签名（bt，2026-09-24）：装载中与装载完是两个世界 ——
        // 装完那一刻签名必须变，S3 才会带着就绪的 jar 重搜一次（治 A3 的 8ms 假空）。
        return ApiConfig.get().getSourceBeanList().size.toString() + "|" +
                ApiConfig.get().isSitePoolSettled() + "|" +
                JarLoader.mainSettled() + "|" +
                poolKeySig() + "|" +
                ApiConfig.get().isPoolFromCache()
    }

    /**
     * 站点 key 集合指纹（bv，2026-09-24）：**纯内存读，零网络请求**。
     *
     * 它才是"池**内容**变了没"的真信号 —— 源里增删站点会变，同一个池被反复查询不会变。
     * 排序后拼接再取哈希：输出短、进日志不占篇幅，判据只比较"变没变"。
     */
    private fun poolKeySig(): Int {
        val keys = ArrayList<String>()
        for (b in ApiConfig.get().getSourceBeanList()) keys.add(b.key)
        keys.sort()
        return keys.joinToString("|").hashCode()
    }

    /** 本机是否存有订阅地址（判断"订阅未加载成功"的第三个条件） */
    private fun hasSubUrl(): Boolean = Hawk.get(HawkConfig.API_URL, "").isNotEmpty()

    /** 本轮计时 / 站点数 / DNS 计数快照（2026-09-22 诊断埋点，见 printRoundSummary） */
    private var roundStartMs = 0L
    private var roundFirstResultMs = -1L
    private var roundSiteCount = 0
    private var roundDohBase: LongArray = LongArray(0)

    /**
     * 回合诊断（2026-09-22）：把"慢不慢"从体感变成数字，且**只加日志、不改任何行为**。
     *
     * 打这五项是因为排查时缺的恰好就是它们：站点池规模、命中数、**首条结果耗时**（用户真正
     * 的体感来源）、整轮总耗时、本轮引发的 jar 装载增量（"冷轮"的硬指标）。最后一段是本窗口内的
     * DNS 活动 —— DoH 命中/失败/熔断/直连，用来回答"安全DNS 到底有没有参与、有没有在拖后腿"，
     * 这正是"改了 DNS 之后是不是变慢了"这个问题的唯一判据（体感和时间戳都不足以定论）。
     */
    private fun printRoundSummary(tag: String) {
        if (roundStartMs <= 0L) return
        val total = SystemClock.elapsedRealtime() - roundStartMs
        val created = JarLoader.loadSeq() - roundLoadSeq
        System.out.println("搜索耗时：" + tag + " 站点=" + roundSiteCount
                + " 命中=" + searchAdapter.data.size
                + " 首条=" + (if (roundFirstResultMs < 0) "无" else roundFirstResultMs.toString() + "ms")
                + " 总耗时=" + total + "ms 已回=" + roundDone + "/" + roundSiteCount
                + " 本轮装载增量=" + created
                + " 跳过=" + (JarLoader.spiderNullSeq() - roundSpiderNull)
                + " 新增跳过=" + freshSkippedKeys().size
                + " " + L1FallbackDns.delta(roundDohBase))
    }

    /** 已收尾（正常归零或看门狗超时）：收尾动作幂等，迟到事件不再重复走一遍 */
    private var firstWaveFinished = false

    /**
     * 第一波全部返回：落地结果、判空态后收工。
     *
     * @param settled bw（2026-09-24）：这次收尾不是因为「所有站点都答了」，而是**有结果的静默稳定**
     *                （见 SETTLE_QUIET_MS 与 stallCheckTick）。只影响日志标签，行为与原来逐字一致 ——
     *                迟到的结果仍走 searchData() 的迟到分支落地。
     */
    private fun onFirstWaveDone(settled: Boolean = false) {
        if (firstWaveFinished) return
        firstWaveFinished = true
        searchWatchdogHandler.removeCallbacks(flushRunnable)
        flushSearchResults()
        cancelSearchWatchdog()
        printRoundSummary(
                if (roundAppendOnly) "补全收尾" else if (settled) "结果稳定收尾" else "收尾")
        // G2（2026-09-28）：本轮图片成败账本 —— 失败域名分布是二期"按域名补 Referer"的定刀依据
        System.out.println("图片加载：" + MyOkhttpDownLoader.imgStatsSummary())
        // 2026-09-28：图片"在途账本"—— 取出/取消/在途（取消=滚出可见范围后被及时掐掉的下载）
        System.out.println("图片队列：" + L1ImageInflight.summary())
        // 图片"发起"次数与空地址数：判断"取出少"到底是缓存命中还是压根没请求
        System.out.println("图片发起：" + FastSearchAdapter.imageCounterSummary())
        // 可见区真实落地率（扫描 ImageView 上的状态标记，不受缓存/取消干扰）
        System.out.println(lastVisibleImageAudit)
        // 本轮帧耗时快照（页面存活期一直统计，这里只取样不清表）：最差帧带时刻与当时阶段
        System.out.println(snapshotFrameStats("本轮"))
        if (searchAdapter.data.size <= 0) {
            // 0 结果：交给 S3 复检 —— 这是一条**独立路径**，与补全守卫互不干扰
            reviewZeroResult()
        } else {
            // 有结果：再看一眼池还会不会变。冷启动预热（首波只搜了缓存清单）、切源（首波只搜了旧源）、
            // jar 装载完成，这三类情况都会在随后带出**首波没搜过**的站点，
            // 由补全守卫决定要不要追加一轮（见 onGuardTick）。守卫自己会快速收工，不必在这里预判。
            beginGuard()
        }
        cancel()
    }

    /**
     * **S3 复检**（2026-09-23 定稿 #2）：0 结果不可信就直接回 S2，**不再有时间预算，也没有次数上限**。
     *
     * 旧实现的判空判据是**时间**（"等够 20 秒了吗 + 重搜 2 次用完了吗"）—— 这在逻辑上根本不成立：
     * 等得久不代表源里没有这部片。而它的两个后果都是用户实测到的：冷启首搜"暂无数据"（假空），
     * 以及 2 次额度被共享计数空转掉之后仍然给空态。
     *
     * 新判据是**完整性**，六条**同时**成立才敢说"暂无数据"：
     *   ① 池已就绪 —— 收尾时再读一次（不信任发起时的快照），池还在途就没有任何结论可言；
     *   ② 本轮**实际回调回来的站点数**＝计划站点数（bv 改：`roundDone >= roundSiteCount`，且**不是超时收尾**）
     *      —— 原来这里比的是 `roundAsked`，而它被赋成与 `roundSiteCount` **同一个值** ⇒ 恒真、什么都
     *      测不出来（bu 复测挖出的洞 5）。超时收尾必然意味着"有站点没回来"，那是最硬的反证；
     *   ③ 期间池未变、无半途装载（池签名与发起时逐字相同）—— 这是"假空"的机制性标志：
     *      jar 落定会改 `loadSeq`，池变化会改池大小；实测 jar 的 `searchContent` 在配置/代理
     *      未就绪时抛 NPE 或返空串，站点就被静默记成 0 条（ar 版日志 63 次 NPE，
     *      栈底正是本页的 searchResult）。
     *   ④ 结果确实 0。
     *   ⑤ 主 jar 装载已有结论（2026-09-24 bt 追加）—— 主 jar 没装上前，依赖它的站点 `getSpider`
     *      会**立刻**返回空站点（真机 3/3 复现 8ms 就给了"暂无数据"），
     *      这一轮的 0 结果里混着"根本没被问过"的站点。装载中一律不判空。
     *   ⑥ 本轮没有**新面孔**站点被跳过（2026-09-24 bu 追加）—— 与 ⑤ 是两件事：⑤ 只管主 jar，
     *      它管的是**任何一个**站点（jar 没挂上 / 首次装载就失败 / 构造抛异常，见 JarLoader.getSpider
     *      的三处 skipNull）。真机实证：每轮日志都 `跳过=1`，而同一个冷门词两分钟前在剧圈99 是搜得到的，
     *      却给出了空态 —— 判据里没有任何一条看这个数，等于把"40 站里 1 站没被问、39 站真没有"和
     *      "40 站全问过都没有"当成同一件事。**只认新面孔**（`JarLoader.skippedKeys()` 的账本差集）：
     *      老面孔是本机跑不了的站点，反复等它只会退回无限转圈。
     *
     * 任一条不成立 ⇒ **回 S2 重搜**，驱动量是**池变化**（见 onWaitTick）：池不会无限变，
     * 所以既天然收敛、又不会空转。这也正是用户"再点一下搜索就正常了"那个手动绕行的自动化版。
     */
    private fun reviewZeroResult() {
        phase = Phase.S3
        val seqNow = JarLoader.loadSeq()
        val sigNow = poolSignature()
        val settled = ApiConfig.get().isSitePoolSettled()
        val cachePool = ApiConfig.get().isPoolFromCache()
        // A 护栏（2026-09-24）：池"已定论"还不够 —— 若这份定论来自**冷启动预热的那份缓存原文**
        // （网络配置还在途中，见 ApiConfig.bootPoolFromCache），那"0 结果"只说明"那份缓存清单里
        // 没有这部片"，**不说明这个源里没有**。判据① 必须把它排除，否则假空会从这条新路径重新回来。
        // 被排除后走 S3 等网络池：池一变化就重搜，网络失败/内容与缓存一致则由稳定出口承认空态。
        // ⑤（bt，2026-09-24）：主 jar 装载**已有结论**。主 jar 没装上前，依赖它的站点 getSpider 会
        // **立刻**返回空站点（真机 3/3 复现 8ms 就给了空态），这一轮的 0 结果里混着"根本没被问过"
        // 的站点 —— 这就是 A3 的假空。装载中一律不判空：等它出结论（成功 ⇒ 签名变、重搜出结果；
        // 失败 ⇒ 下一轮走到这里才是诚实的空态）。
        val mainSettled = JarLoader.mainSettled()
        val skipped = JarLoader.spiderNullSeq() - roundSpiderNull
        val freshSkip = freshSkippedKeys()
        val poolReady = settled && !cachePool                      // ①
        val askedAll = !roundEndedByTimeout && roundDone >= roundSiteCount && roundSiteCount > 0   // ②
        val unchanged = sigNow == roundPoolSig                        // ③
        val zero = searchAdapter.data.size <= 0                       // ④
        val noFreshSkip = freshSkip.isEmpty()                         // ⑥
        System.out.println("搜索复检：0 结果 六条判据 池就绪=" + poolReady
                + "(定论=" + settled + " 缓存池=" + cachePool + ")" + " 问全=" + askedAll
                + "(" + roundDone + "/" + roundSiteCount + ") 池未变=" + unchanged
                + "(" + roundPoolSig + "->" + sigNow + ") 结果0=" + zero
                + " 主jar定论=" + mainSettled + " 跳过站点=" + skipped
                + " 新增跳过=" + freshSkip.size + (if (freshSkip.isEmpty()) "" else "(" + freshSkip + ")")
                + " jar装载=" + roundLoadSeq + "->" + seqNow);
        if (mainSettled && !noFreshSkip) {
            // ⑥ 不成立：这一轮有**新面孔**站点没被问到（jar 没挂上 / 首次装载就失败 / 构造抛异常）。
            // 「跳过站点数」本身进不了判据 —— 那个数每轮都被同一个坏站点垫着，等于永远不成立（无限转圈）。
            // 所以只认新面孔，并给它们**一次**重试：重搜后这些 key 已进账本、不再是新面孔，
            // 于是要么装上（出结果）、要么沉底（下一轮放行判空）。既救瞬时失败，又保证收敛。
            System.out.println("搜索复检：本轮有 " + freshSkip.size + " 个站点被跳过（" + freshSkip + "），先重搜一轮再谈空态");
            searchResult(fromWaitingRetry = true)
            return
        }
        if (poolReady && askedAll && unchanged && zero && mainSettled && noFreshSkip) {
            phase = Phase.S4
            System.out.println("搜索复检：六条判据全成立，判定为真空，给出空态");
            showEmpty()
            return
        }
        // 不可信：保持转圈，等池变化后自动重搜（S3 → S2）。**这一步不弹任何失败提示** ——
        // 它压根不是失败，是"还有东西在装载"。
        waitDebounceSig = ""
        showLoading()
        beginWait("复检判定本轮不可信，等池变化")
    }

    /**
     * 探池节拍（S1 与 S3 共用；**纯内存读，零网络请求**）。
     *
     * 触发条件按当前处于哪个态决定：
     *  - S1（池未定论）：池一有定论立刻进 S2；
     *  - S3（本轮不可信）：池签名变化**且连续两拍一致**（去抖，把一串集中落定的 jar 折叠成一次重搜）
     *    才进 S2。
     * 20 秒（40 拍）不改变任何行为，只把那句提示文案换成「播放源仍在加载」—— 继续转圈继续等。
     */
    private fun onWaitTick() {
        // 补全守卫自成一态：首波**有结果**，等池变化后追加一轮（见 onGuardTick）
        if (phase == Phase.S5) {
            onGuardTick()
            return
        }
        // 只有 S1（池未定论）与 S3（本轮不可信、等池变化）需要探池。
        // 其它态收到迟到的节拍一律丢弃 —— 否则会在结果已经上屏之后又擅自发起一轮搜索。
        if (phase != Phase.S1 && phase != Phase.S3) return
        readyRetry++
        if (readyRetry == INIT_HINT_AT_RETRY) {
            // 「正在初始化播放源」前缀已在 ToastSweeper 的 HOST_TEXTS 白名单里，无需再登记
            ToastUtils.showShort("正在初始化播放源，请稍候…")
        }
        if (readyRetry >= LOADING_SWITCH_AT_RETRY && !longLoadHintShown) {
            longLoadHintShown = true
            // 20 秒后**只改文案、不改行为**（定稿口径）。刻意不以「正在」开头：
            // 那会被横幅清扫器当成 jar 初始化横幅误扫（ToastSweeper 三条铁律之一）。
            ToastUtils.showLong("播放源仍在加载，请稍候…")
        }
        val settled = ApiConfig.get().isSitePoolSettled()
        if (!settled) {
            // 池还没定论：再等一拍（S1 无上限）
            beginWait("池未定论")
            return
        }
        if (phase == Phase.S1) {
            // 池刚定论 ⇒ 立刻开搜（正常搜索零等待）
            logWaitGo(settled)
            searchResult(fromWaitingRetry = true)
            return
        }
        // ── S3：本轮 0 结果不可信。判据从"等池变化"升级为**还有没有东西在路上**：
        //   ① 签名还在动（本拍与上拍不同）⇒ 有 ⇒ 记下来继续等（去抖，把一串集中落定的
        //      jar 折叠成一次重搜）；
        //   ② 连续两拍一致（稳定了）：池变过 ⇒ 重搜；没变过且再无在途装载 ⇒ 承认空态。
        // ②里的"再无在途装载"＝ 池已定论 ∧ **不是冷启动预热的缓存池**（A，2026-09-24）。
        // 这一支同时堵住两个洞：缓存池 0 结果不再甩假空；预热后网络失败也不会无限转圈。
        val sig = poolSignature()
        if (sig != waitDebounceSig) {
            waitDebounceSig = sig
            beginWait("S3 等池稳定")
            return
        }
        if (sig != roundPoolSig) {
            logWaitGo(settled)
            searchResult(fromWaitingRetry = true)
            return
        }
        if (ApiConfig.get().isPoolFromCache()) {
            // 池还是冷启动预热的那份缓存原文：网络配置在路上 ⇒ 继续等（A 的假空护栏落点）
            beginWait("S3 等网络池")
            return
        }
        if (!JarLoader.mainSettled()) {
            // 主 jar 还在装载：这时站点返回的 0 条不算数（见 reviewZeroResult 判据⑤）。
            // 装载一有结论签名就会变（poolSignature 含 mainSettled），届时走上面"池变过 ⇒ 重搜"。
            beginWait("S3 等主jar装载结论")
            return
        }
        // ⑥ 与 reviewZeroResult 同一条判据（bu，2026-09-24）：有新面孔站点被跳过就别急着判空。
        // 这一支是真机实证的落点 —— 18:17:33.855 的假空正是从这里给出的（签名稳定、无在途装载），
        // 而那一轮日志里明明写着 `跳过=1`。重搜后这些 key 已进账本，不会再触发，故不会循环。
        val freshSkip = freshSkippedKeys()
        if (freshSkip.isNotEmpty()) {
            System.out.println("搜索复检：池已稳定但有 " + freshSkip.size + " 个站点被跳过（" + freshSkip + "），先重搜一轮");
            searchResult(fromWaitingRetry = true)
            return
        }
        // bv（2026-09-24）兜底出口：池稳、无在途装载、无新面孔被跳过 —— **但还有站点没回来**。
        // 这是"有站点真卡死"的形态（第三方 jar 内部没有超时保护）：池不会再变了，等下去也是等。
        // 所以给一个**诚实的结论** —— 既不是无限转圈，也不冒充"暂无数据"（那四个字只配真空态）。
        if (roundDone < roundSiteCount) {
            phase = Phase.S4
            System.out.println("搜索复检：池已稳定但 " + (roundSiteCount - roundDone) + " 个站点未响应（已回 "
                    + roundDone + "/" + roundSiteCount + "），按「部分未响应」收尾");
            showEmpty()
            ToastUtils.showLong("部分站点未响应，未找到结果，请稍后重试")
            return
        }
        phase = Phase.S4
        System.out.println("搜索复检：池已稳定且无在途装载，判定为真空，给出空态");
        showEmpty()
    }

    /** 等待结束、放行搜索时的统一埋点（S1 池定论 / S3 池变化） */
    private fun logWaitGo(settled: Boolean) {
        System.out.println("搜索等待：池已可搜（定论=" + settled
                + " 签名=" + roundPoolSig + "->" + poolSignature()
                + " 等待拍数=" + readyRetry + "），进入搜索");
    }

    /**
     * **S5 补全守卫**（A 档 2，2026-09-24）：首波**有结果**时，再等一拍池的变化。
     *
     * 为什么需要它：冷启动预热时首波搜的是**缓存里那份清单**，网络配置随后到达会换掉整个池；
     * 切源时新源配置到达同理；jar 装载完成也会带出新站点。这些站点首波都没搜过，
     * 需要在首波结果**之后追加一轮**补上。
     *
     * 守卫**纯内存探池，零网络请求**。三个出口：
     *   ① 池签名稳定地变了 ⇒ 补一轮（只搜新增站点、结果追加不清空），补完即收工；
     *   ② 网络池已到 ∧ 池稳定 ∧ 与首波一致 ⇒ 没有新增，直接收工（正常场景第二拍就走到这里）；
     *   ③ 观测拍数达 GUARD_MAX_TICKS ⇒ 兜底收工。
     *
     * **最多补一次**（guardDone 自锁）：否则 jar 陆续装载会把"补一轮"放大成"补很多轮"。
     */
    private fun onGuardTick() {
        if (guardDone) return
        guardTicks++
        val sig = poolSignature()
        if (sig != waitDebounceSig) {
            // 还在动（或第一拍）：记下来，下一拍再判稳定
            waitDebounceSig = sig
            beginWait("补全守卫观察池")
            return
        }
        if (sig != roundPoolSig) {
            // 池稳定地变了 ⇒ 有新站点首波没搜过，补一轮
            guardDone = true
            System.out.println("搜索补全：池已变化（" + roundPoolSig + "->" + sig + "），发起补轮")
            searchResult(appendOnly = true)
            return
        }
        if (ApiConfig.get().isSitePoolSettled() && !ApiConfig.get().isPoolFromCache()) {
            // 网络池已到、池稳定且与首波一致 ⇒ 没有新增可补
            guardDone = true
            System.out.println("搜索补全：源无变化，跳过")
            return
        }
        if (guardTicks >= GUARD_MAX_TICKS) {
            guardDone = true
            System.out.println("搜索补全：观测超时（" + guardTicks + " 拍），结束守卫")
            return
        }
        beginWait("补全守卫等网络池")
    }

    /** 启动补全守卫（首波有结果时调用；池不稳则沿用同一套探池节拍） */
    private fun beginGuard() {
        if (guardDone || isFinishing || isDestroyed) return
        phase = Phase.S5
        waitDebounceSig = ""
        guardTicks = 0
        System.out.println("搜索补全：首波有结果，启动补全守卫（缓存池=" + ApiConfig.get().isPoolFromCache()
                + " 池签名=" + roundPoolSig + "）")
        searchWatchdogHandler.removeCallbacks(waitTickRunnable)
        searchWatchdogHandler.postDelayed(waitTickRunnable, READY_RETRY_DELAY_MS)
    }

    /**
     * 进入 S1 等待（转圈 + 节拍探池）。**这里没有时间上限，也没有通往"暂无数据"的路径** ——
     * 这是"假空被结构性删除"的落点。
     *
     * @param reason 埋点用
     */
    private fun beginWait(reason: String) {
        if (isFinishing || isDestroyed) return
        // 保持当前等待态：S3（复检等池）与 S5（补全守卫等池）**都不能被降级回 S1** ——
        // 它们的放行判据与 S1 不同（要等"池稳定地变化"，而非"池一定论"）。
        if (phase != Phase.S3 && phase != Phase.S5) phase = Phase.S1
        searchWatchdogHandler.removeCallbacks(waitTickRunnable)
        searchWatchdogHandler.postDelayed(waitTickRunnable, READY_RETRY_DELAY_MS)
        if (readyRetry <= 1) {
            System.out.println("搜索等待：进入 " + phase + " 等待（" + reason + "）站点池定论="
                    + ApiConfig.get().isSitePoolSettled() + " 池签名=" + roundPoolSig)
        }
    }

    /** 搜索超时看门狗。
     *
     * 弱网下 spider 调用没有超时保护：某个站点卡住就会一直占着搜索线程，
     * 全部线程被占满后 allRunCount 永不归零 → 界面无限转圈，只能杀掉页面。
     * 到点强制收尾：展示已搜到的结果并取消剩余请求，jar 自身逻辑不受影响。
     */
    private val searchWatchdogHandler = Handler(Looper.getMainLooper())
    private var searchWatchdogRunning = false
    private val searchWatchdog = Runnable { finishSearchByTimeout() }

    /** 最近一次"站点有动静"（任何回调，含空结果）的时刻；停滞检测据此判断是不是早就卡死了 */
    private var lastProgressMs = 0L

    /** 本轮是否已因停滞而收尾（防重复触发） */
    private var stallReported = false

    /**
     * **停滞检测**（bv，2026-09-24）：**只对 0 结果的搜索**生效。
     *
     * 看门狗 30 秒是"最坏情况上限"，但对**已经卡死**的那一轮，用户要白等满 30 秒 —— 真机旁证：
     * A2 那一轮 30 秒里有 22 秒几乎零日志，早就没有任何站点在工作了。
     *
     * 判据三条同时成立才收尾：`在途 > 0`（确实还有站点没回来）、`结果为空`（有结果就不打扰，
     * 让慢站点继续补）、`静默 ≥ STALL_QUIET_MS`。收尾动作就是走正常的超时收尾 —— 已有结果照常展示、
     * 0 结果交给 S3 复检，不新增任何终点。
     */
    // 这一段是"自引用"踩了两次坑之后定下来的写法，**别改回去**：
    //   ① 不写类型（`= Runnable { … }`）⇒ 递归类型推断失败（Type checking has run into a recursive problem）；
    //   ② 写类型但直接 `= Runnable { … postDelayed(stallRunnable, …) }` ⇒ `Variable 'stallRunnable'
    //      must be initialized`（`val` 的初始化表达式里引用自己＝未初始化就使用）。
    // ⇒ 唯一稳的形态：**体里只调用一个方法**，自引用发生在 `run()` 被调用的那一刻，初始化早已完成。
    // （与同文件 `flushRunnable = Runnable { flushSearchResults() }` 同一写法。）
    private val stallRunnable: Runnable = Runnable { stallCheckTick() }

    private fun stallCheckTick() {
        if (firstWaveFinished || phase != Phase.S2 || stallReported) return
        val quiet = SystemClock.elapsedRealtime() - lastProgressMs
        // bw（2026-09-24）：**两档**。0 结果与有结果要的东西不一样 ——
        //   0 结果：还不能下结论，交给 S3 六条判据；10 秒静默只说明"别等了，按超时收尾去定性"。
        //   有结果：结果已经在屏幕上，用户等的只是「搜完了」这个信号；而那个不回的站点可能永远不回
        //           （bv 实测 8/8 轮都是 39/40）—— 不能陪它等满 30 秒。
        val hasResult = searchAdapter.data.size > 0 || searchAdapterFilter.data.size > 0
        if (hasResult) {
            // 有结果这一档：走**正常收尾**。不中断在途搜索 ⇒ 迟到的结果照常追加落地（详见 SETTLE_QUIET_MS）
            if (quiet >= SETTLE_QUIET_MS) {
                stallReported = true
                System.out.println("搜索稳定：" + (quiet / 1000) + " 秒无站点回调（在途=" + allRunCount.get()
                        + " 已回=" + roundDone + "/" + roundSiteCount
                        + " 命中=" + searchAdapter.data.size + "），按「结果稳定」收尾")
                onFirstWaveDone(settled = true)
                return
            }
        } else if (allRunCount.get() > 0 && quiet >= STALL_QUIET_MS) {
            // 0 结果这一档：bv 口径**一个字没动**，仍是"10 秒静默 ⇒ 按超时收尾 ⇒ S3 判据定性"
            stallReported = true
            System.out.println("搜索停滞：" + (quiet / 1000) + " 秒无站点回调（在途=" + allRunCount.get()
                    + "），按超时收尾")
            finishSearchByTimeout()
            return
        }
        searchWatchdogHandler.postDelayed(stallRunnable, STALL_TICK_MS)
    }

    private fun startStallCheck() {
        searchWatchdogHandler.removeCallbacks(stallRunnable)
        searchWatchdogHandler.postDelayed(stallRunnable, STALL_TICK_MS)
    }

    /**
     * 搜索结果合并刷新。
     *
     * 每个站点返回都会触发一次列表通知，并发高时多个站点几乎同时回来，
     * 主线程被连续的通知与动画占满，表现为"边搜边卡、列表抖动"。
     * 这里把窗口期内的结果攒起来一次落地：用户感知不到这点延迟，
     * 列表刷新次数却从 N 次降到 1~3 次。
     */
    private val pendingResults = ArrayList<Movie.Video>()
    private var flushScheduled = false
    private val flushRunnable = Runnable { flushSearchResults() }

    /** F1（by2 修订）：滑动状态由 onScrollStateChanged 维护，只用来**限制单批条数**（不再暂停落地） */
    private var scrollPauseActive = false

    // ── 图片"在途取消"（2026-09-28 用户口径版）────────────────────────
    // 请求照发（不预判、不跳过）；停稳/切站点时把"已滚出可见±预取"的在途下载取消掉，
    // 让并发槽及时让给用户真正要看的图。（上一版的"可见门闸"因误判已被撤除，见 L1ImageInflight 注释。）
    private val imageWindowRunnable = Runnable { cancelOutOfWindowImages() }

    /** 当前**活跃**列表（过滤模式下是过滤列表）的可见范围 */
    private fun visibleRange(): IntRange? {
        val rv = if (isFilterMode) mBinding.mGridViewFilter else mBinding.mGridView
        val lm = rv.layoutManager as? LinearLayoutManager ?: return null
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last < first) return null
        return first..last
    }

    private fun activeData(): List<Movie.Video> =
            if (isFilterMode) searchAdapterFilter.data else searchAdapter.data

    private fun scheduleImageWindow(delayMs: Long) {
        searchWatchdogHandler.removeCallbacks(imageWindowRunnable)
        searchWatchdogHandler.postDelayed(imageWindowRunnable, delayMs)
    }

    /**
     * 取消"已经滚出可见（含预取）范围"的图片下载。
     * 拿不到可见范围时**一个都不取消**（保守：宁可慢一点也不误伤）。
     */
    private fun cancelOutOfWindowImages() {
        val r = visibleRange() ?: return
        val data = activeData()
        if (data.isEmpty()) return
        val from = maxOf(0, r.first - IMG_WINDOW_PREFETCH)
        val to = minOf(data.size - 1, r.last + IMG_WINDOW_PREFETCH)
        val urls = ArrayList<String>(to - from + 1)
        for (i in from..to) {
            val v = data.getOrNull(i) ?: continue
            if (!v.pic.isNullOrEmpty()) urls.add(v.pic.trim())
        }
        L1ImageInflight.cancelOutside(urls)
    }

    // ── 可见图片体检（2026-09-28）：找出"屏幕上确实还没出图"的项并给它们第二次机会 ──
    // 为什么需要：图片请求一旦"被取消"或"下载成功但 view 已被复用"（Picasso 会直接丢弃结果），
    // 那一格就永远停在灰图 —— 因为 RecyclerView 的复用缓存（itemViewCacheSize=6）里的项
    // **不会重新绑定**，也就不会再次发起请求。用户看到的"很多图片不加载"主要来自这里。
    // 做法：停稳后扫一遍可见项（按 ImageView 上的状态标记），对"非成功"的项各补一次绑定。
    private val visibleImageAuditRunnable = Runnable { auditVisibleImages() }

    private fun scheduleVisibleImageAudit() {
        searchWatchdogHandler.removeCallbacks(visibleImageAuditRunnable)
        // 给"正在下载的图"留出落地时间，避免把刚发出请求的项误判成失败而反复重绑
        searchWatchdogHandler.postDelayed(visibleImageAuditRunnable, IMG_AUDIT_DELAY_MS)
    }

    private fun auditVisibleImages() {
        val rv = if (isFilterMode) mBinding.mGridViewFilter else mBinding.mGridView
        val r = visibleRange() ?: return
        var ok = 0
        var pending = 0
        var err = 0
        val needRetry = ArrayList<Int>(4)
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val pos = rv.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            val iv = child.findViewById<android.widget.ImageView>(R.id.ivThumb) ?: continue
            when (iv.tag) {
                FastSearchAdapter.IMG_OK -> ok++
                FastSearchAdapter.IMG_ERR -> {
                    err++
                    if (needRetry.size < IMG_AUDIT_MAX_RETRY) needRetry.add(pos)
                }
                else -> {
                    pending++
                    if (needRetry.size < IMG_AUDIT_MAX_RETRY) needRetry.add(pos)
                }
            }
        }
        lastVisibleImageAudit = "可见图片：已加载=$ok 未加载=$pending 失败=$err 补绑=${needRetry.size}"
        // dd（2026-09-28）：**体检完立刻打一行**。
        // 原来只在"搜索收尾"那一刻打印，而用户滑列表是在收尾**之后** ⇒ 每轮收尾打到的永远是
        // `未体检`（下一轮 search() 又会把它复位），这个度量等于白加 —— 用户反馈"我没法判断哪个图
        // 该出不该出"正是这个原因。改成每次体检即报，数字当场可见。
        System.out.println(lastVisibleImageAudit)
        // 只对"看得到的、没出图的"项补一次绑定（= 重新发起请求；Picasso 不缓存失败，会真的重试）
        val adapter = if (isFilterMode) searchAdapterFilter else searchAdapter
        for (pos in needRetry) adapter.notifyItemChanged(pos)
    }

    /**
     * **可见 ±6 预取**（2026-09-28 用户口径：由"只请求当屏可见的 6 张"放宽到"可见±6"）。
     *
     * 现状：adapter 是"绑定即请求"，而 RecyclerView 只绑定可见项＋缓存项 ⇒ 命中 522 条时
     * 也只有个位数张图会被请求，滚到跟前才开始下载 ⇒ 滑动时总有空窗。
     *
     * 做法：停稳后对**可见±IMG_WINDOW_PREFETCH** 的项调 `fetch()` —— 只把字节取进图片磁盘缓存，
     * 不绑定视图、不改任何状态；等滚到跟前时直接命中缓存。**不是门闸、不是暂停**：所有请求照发，
     * 这里只是"提前把用户马上要看的图取回来"。
     *
     * 三条护栏：
     *  · 同一轮内同一地址只预取一次（`prefetched` 记账）；
     *  · 总额度 `IMG_PREFETCH_BUDGET`，用完即停（防止来回滑把 500 张全预热）；
     *  · 范围与 `cancelOutOfWindowImages` 的"保留窗口"完全一致（可见±6），不会被自己取消。
     */
    private fun prefetchAroundVisible() {
        try {
            if (prefetchBudget <= 0) return
            val r = visibleRange() ?: return
            val data = activeData()
            if (data.isEmpty()) return
            val from = maxOf(0, r.first - IMG_WINDOW_PREFETCH)
            val to = minOf(data.size - 1, r.last + IMG_WINDOW_PREFETCH)
            var n = 0
            for (i in from..to) {
                if (prefetchBudget <= 0) break
                val v = data.getOrNull(i) ?: continue
                val pic = v.pic
                if (pic.isNullOrEmpty()) continue
                val u = DefaultConfig.checkReplaceProxy(pic.trim())
                if (u.isEmpty() || !prefetchedUrls.add(u)) continue
                prefetchBudget--
                FastSearchAdapter.countPrefetchStart()
                Picasso.get().load(u).fetch()
                n++
            }
            if (n > 0) {
                System.out.println("图片预取：本批=" + n + " 累计=" + prefetchedUrls.size
                        + " 剩余额度=" + prefetchBudget)
            }
        } catch (e: Throwable) {
            // 预取是纯加分项，任何异常都不允许影响列表
        }
    }

    // ── 帧耗时统计（2026-09-28 改造：**页面存活期常开** + 记录最差帧阶段）──────────
    // 为什么改造：上一版只在"搜索发起→收尾"之间统计，而用户拖左栏多在收尾之后 ⇒ 卡顿没被记录
    // （cb 数据只有 3 次/轮，与体感"3~4 次卡一次"对不上，就是因为测量盲区）。
    // 系统日志只在掉帧 >30 帧时才写 Choreographer，微卡完全看不见 —— 必须自己量。
    private var frameWatching = false
    private var frameLastNs = 0L
    private var frameCount = 0
    private var frameSumMs = 0L
    private var frameWorstMs = 0L
    private var frameWorstAtWall = 0L
    private var frameWorstStage = "-"
    private var frameJankCount = 0
    /** 当前阶段（由关键路径更新），用于给"最差帧"归因 */
    @Volatile
    private var frameStage = "启动"

    /** 最近一次"可见图片体检"结论（收尾时打印） */
    private var lastVisibleImageAudit = "可见图片：未体检"
    /** 可见±6 预取：本轮已预取过的图片地址（防重复请求） */
    private val prefetchedUrls = HashSet<String>()
    /** 可见±6 预取：本轮剩余额度（用完即停，见 IMG_PREFETCH_BUDGET） */
    private var prefetchBudget = IMG_PREFETCH_BUDGET

    /**
     * 标记"当前正在做什么"，供最差帧归因。
     *
     * 🔴 ch（2026-09-29）：**它只是标签，会被残留** —— cd/ce/cf/cg 四轮最差帧都标「结果落地」
     * （240~314ms），看着像"我们的落地代码慢"，其实那 314ms 是**加固 jar 首次构造 WebView
     * 取 Cookie/UA**（`WebViewFactory: Loading com.google.android.webview` 与该帧同刻、数值吻合
     * 313.51≈314）。原因是 frameStage 只在 stage() 被调用时更新，之后一直留着。
     * ⇒ 现在改成**昂贵初始化点前后都显式标记**，并保留一个括号写清"谁在跑"。
     */
    private fun stage(name: String) {
        frameStage = name
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameWatching) return
            if (frameLastNs > 0L) {
                val gap = (frameTimeNanos - frameLastNs) / 1_000_000L
                frameCount++
                frameSumMs += gap
                if (gap > frameWorstMs) {
                    frameWorstMs = gap
                    frameWorstAtWall = System.currentTimeMillis()
                    frameWorstStage = frameStage
                }
                if (gap > FRAME_JANK_MS) frameJankCount++
            }
            frameLastNs = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startFrameWatch() {
        if (frameWatching) return
        frameWatching = true
        frameLastNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** 取样并**重置计数但不停表**（页面存活期一直统计；收尾时打一行本轮快照） */
    private fun snapshotFrameStats(round: String): String {
        if (frameCount <= 0) return "帧耗时[$round]：无样本"
        val avg = frameSumMs / frameCount
        val at = if (frameWorstAtWall > 0)
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(frameWorstAtWall))
        else "-"
        val s = "帧耗时[$round]：帧数=$frameCount 平均=${avg}ms 最差=${frameWorstMs}ms@$at($frameWorstStage)"
        frameCount = 0
        frameSumMs = 0L
        frameWorstMs = 0L
        frameWorstAtWall = 0L
        frameWorstStage = "-"
        frameJankCount = 0
        return s
    }

    // ── 左栏（站点栏）视图改动队列：拖动中一律不动视图 ──────────────────
    private var siteTabsLastScrollMs = 0L
    private var siteTabsNeedReset = false
    private val siteTabsPending = ArrayList<String>()
    private var siteTabsFlushPosted = false
    /** 待**分帧创建**的站点 chip 队列（见 drainSiteTabQueue） */
    private val siteTabsQueue = ArrayList<String>()
    private val siteTabsDrainRunnable = Runnable { drainSiteTabQueue("分帧") }
    /** 手指是否正按在左栏上（由 Activity 的 dispatchTouchEvent 记账，见该函数） */
    private var siteTabsTouchDown = false
    private val siteTabsFlushRunnable = Runnable { flushSiteTabsNow() }

    /**
     * 左栏的触摸记账（2026-09-28 补的缺口）。
     *
     * 只看"最近有没有滚动过"是不够的：手指**刚落下、还没产生滚动事件**的那几十毫秒里，
     * 排队中的 tab 落地照样会换掉子 View，系统随即给手势发 ACTION_CANCEL ——
     * 用户感受到的就是"起始滑动点无效、拖一下就断"（这正是用户原话）。
     * 所以在 Activity 这一层旁观触摸流：只有**左栏上没有手指**、且 400ms 内没滚动过，才允许改视图。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = Rect()
                if (mBinding.tabLayout.getGlobalVisibleRect(r)
                        && r.contains(ev.x.toInt(), ev.y.toInt())) {
                    siteTabsTouchDown = true
                    siteTabsLastScrollMs = SystemClock.elapsedRealtime()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (siteTabsTouchDown) {
                    siteTabsTouchDown = false
                    siteTabsLastScrollMs = SystemClock.elapsedRealtime()
                    if ((siteTabsPending.isNotEmpty() || siteTabsNeedReset) && !siteTabsFlushPosted) {
                        scheduleSiteTabs()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 左栏是否"正在被摸"或在停手窗口内（真则不许改视图，只排队） */
    private fun siteTabsBusy(): Boolean =
            siteTabsTouchDown ||
                    SystemClock.elapsedRealtime() - siteTabsLastScrollMs < SITE_TAB_SETTLE_MS

    private fun scheduleSiteTabs() {
        if (siteTabsFlushPosted) return
        siteTabsFlushPosted = true
        val left = SITE_TAB_SETTLE_MS - (SystemClock.elapsedRealtime() - siteTabsLastScrollMs)
        searchWatchdogHandler.postDelayed(siteTabsFlushRunnable, if (left > 0) left else 0L)
    }

    /** 排入"清空重建"（不立即动视图） */
    private fun queueSiteTabsReset() {
        siteTabsNeedReset = true
        siteTabsPending.clear()
        scheduleSiteTabs()
    }

    /**
     * 左栏 tab 的增补规则（2026-09-28 定稿）：
     *
     * tab **只代表"有结果的站点"**（"全部显示" + 每个真的返回了结果的站点）——
     * 这是本页原有语义，用户 09-28 真机明确否掉了"点搜索就把 40 个站点全列出来"的做法。
     * 实现上由结果回调里的 `addWordAdapterIfNeed` → `queueSiteTab` 逐个追加（走停手窗口队列，
     * 拖动中不动视图），**不做任何预建**。
     *
     * 另一条崩溃教训（同日真机 FATAL）：预建会让"尚未返回结果的站点"也出现在左栏，
     * 点它时 `resultVods[key]` 还是 null ⇒ `filterResult` 里原来的 `!!` 直接 NPE 闪退。
     * 语义恢复后该路径不再产生，`filterResult` 那边也已按"暂无结果"兜底。
     */
    private fun queueSiteTab(name: String) {
        if (siteTabsPending.contains(name) || existsSiteTab(name)) return
        siteTabsPending.add(name)
        scheduleSiteTabs()
    }

    private fun existsSiteTab(name: String): Boolean {
        for (i in 0 until mBinding.tabLayout.childCount) {
            val item = mBinding.tabLayout.getChildAt(i) as? TextView ?: continue
            if (name == item.text.toString()) return true
        }
        return false
    }

    /** 停手之后统一落地：该清的清、该加的加（顺序保持"全部显示"在最前） */
    private fun flushSiteTabsNow() {
        siteTabsFlushPosted = false
        if (isFinishing || isDestroyed) return
        if (siteTabsBusy()) {
            scheduleSiteTabs() // 用户又动了，继续顺延
            return
        }
        stage("建站点栏")
        if (siteTabsNeedReset) {
            siteTabsNeedReset = false
            mBinding.tabLayout.removeAllViews()
            mBinding.tabLayout.addView(getSiteTextView("全部显示"))
            mBinding.tabLayout.setCurrentItem(0, true, false)
            siteTabsQueue.clear()
            siteTabsQueue.addAll(siteTabsPending)
            siteTabsPending.clear()
            drainSiteTabQueue("重建")
            return
        }
        if (siteTabsPending.isNotEmpty()) {
            siteTabsQueue.addAll(siteTabsPending)
            siteTabsPending.clear()
            drainSiteTabQueue("追加")
        }
    }

    /**
     * **分帧创建站点 chip**（2026-09-28）。
     *
     * cc 实测：一次性创建 40 个 chip 让主线程卡了 **364ms**（帧埋点抓到
     * `最差=364ms@(建站点栏)`）—— 那正是"拖着左栏突然卡一下"的来源：搜索开始时 40 个 chip
     * 要一起建出来（每个都含 setBackgroundResource + 文本测量）。现在每帧只建 SITE_TAB_BATCH 个，
     * 剩下的下一帧继续；期间用户要是摸上左栏就整体顺延（交给已有的"停手窗口"）。
     */
    private fun drainSiteTabQueue(tagName: String) {
        if (siteTabsQueue.isEmpty()) return
        if (siteTabsBusy()) {
            scheduleSiteTabs()
            return
        }
        var built = 0
        while (siteTabsQueue.isNotEmpty() && built < SITE_TAB_BATCH) {
            val name = siteTabsQueue.removeAt(0)
            if (!existsSiteTab(name)) {
                mBinding.tabLayout.addView(getSiteTextView(name))
                built++
            }
        }
        if (siteTabsQueue.isNotEmpty()) {
            searchWatchdogHandler.postDelayed(siteTabsDrainRunnable, 16L)
        }
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        searchWatchdogHandler.postDelayed(flushRunnable, SEARCH_FLUSH_MS)
    }

    /** 把攒下的结果一次性落地（视图已销毁则直接丢弃） */
    private fun flushSearchResults() {
        flushScheduled = false
        if (pendingResults.isEmpty()) return
        // F1（by2 修订）：**限流不暂停** —— 滑动中照常落地，但每批最多 SCROLL_BATCH_MAX 条，
        // 剩下的排下一拍。这样列表持续推进（不会"滑着滑着没反应"），单帧负载也恒定不变。
        val take = if (scrollPauseActive) minOf(SCROLL_BATCH_MAX, pendingResults.size) else pendingResults.size
        val batch = ArrayList(pendingResults.subList(0, take))
        pendingResults.subList(0, take).clear()
        if (isFinishing || isDestroyed) return
        stage("结果落地(插入)")
        if (searchAdapter.data.size > 0) {
            searchAdapter.addData(batch)
        } else {
            showSuccess()
            if (!isFilterMode) mBinding.mGridView.visibility = View.VISIBLE
            searchAdapter.setNewData(batch)
        }
        // 还有攒着没落的：接着排下一拍（滑动中就是限流，停稳后一拍落完）
        if (pendingResults.isNotEmpty()) scheduleFlush()
        // P1：新落地的条目可能就在可见区，把需求窗口跟着更新（否则它们要等下次滚动才加载图）
        scheduleImageWindow(0L)
    }

    // 线程命名/空闲回收统一由 L1Executors 负责（原自定义工厂仅做命名与 daemon，已并入）
    private fun getSiteTextView(text: String): TextView {
        val textView = TextView(this)
        textView.text = text
        textView.gravity = Gravity.CENTER
        // 站点卡片化：白底圆角卡片，选中态绿底白字（bg_site_chip 为 selector）
        textView.setBackgroundResource(R.drawable.bg_site_chip)
        textView.setTextColor(ContextCompat.getColorStateList(this, R.color.site_chip_text))
        textView.maxLines = 1
        textView.ellipsize = TextUtils.TruncateAt.END
        val params = DslTabLayout.LayoutParams(-2, -2)
        params.topMargin = 20
        params.bottomMargin = 20
        textView.setPadding(20, 18, 20, 18)
        textView.layoutParams = params
        return textView
    }

    /**
     * fromWaitingRetry＝本次进入是"等池"（S1 探池节拍 / S3 等池变化）触发的自旋重试。
     * 此时**不能**重置诊断基线：否则预算用尽那一刻刚好重入过，收尾打出的"总耗时"只有几毫秒
     * （09-22 实测 `总耗时=7ms` 假计时）。用户手动 search() 那一轮走默认 false——独立的一波，
     * 基线理应重新开始。
     */
    private fun searchResult(fromWaitingRetry: Boolean = false, appendOnly: Boolean = false) {
        // 进新的一波：把还在排队的探池节拍收掉（本函数自己会决定是搜索还是继续等）
        searchWatchdogHandler.removeCallbacks(waitTickRunnable)
        try {
            if (searchExecutorService != null) {
                searchExecutorService!!.shutdownNow()
                searchExecutorService = null
                JsLoader.stopAll()
            }
        } catch (th: Throwable) {
            th.printStackTrace()
        } finally {
            // 补轮（A 档 2）**不清空列表**：它的结果要追加在已有结果后面
            // （用户口径：追加在后、不闪不重排）。首波与 S1/S3 重搜则照旧清空 ——
            // 那两种情况下列表本来就该从零开始。
            if (!appendOnly) {
                searchAdapter.setNewData(ArrayList())
                searchAdapterFilter.setNewData(ArrayList())
            }
            allRunCount.set(0)
        }
        // 单轮搜索状态复位：上一轮的收尾标志清掉
        firstWaveFinished = false
        roundAppendOnly = appendOnly
        // 等池变化后的重搜会**重入本函数**，所以"每个站点一个 tab"和站点名表必须在这里重新开始：
        // 否则"全部显示"会一轮轮叠加、上一轮的站点名残留（search() 里的那份清理只覆盖用户手动那一轮）。
        // 补轮例外：tab 与站点名表**原样保留** —— 由 addWordAdapterIfNeed 增量补新增站点，不重建。
        if (!appendOnly) {
            // 左栏断触（2026-09-28）：清空重建也走队列（用户正拖左栏时不动视图）
            queueSiteTabsReset()
            spNames.clear()
            keyToName.clear()
        }
        // 本轮"世界状态"快照：收尾时用它判断这一轮的 0 结果可不可信（见 reviewZeroResult）
        roundPoolSettled = ApiConfig.get().isSitePoolSettled()
        roundLoadSeq = JarLoader.loadSeq()
        roundPoolSig = poolSignature()
        roundDone = 0
        roundEndedByTimeout = false
        roundSpiderNull = JarLoader.spiderNullSeq()
        roundSkippedBase = JarLoader.skippedKeys()
        phase = Phase.S2
        // 本轮诊断基线：计时起点 / 首条结果 / 站点数 / DNS 计数（见 printRoundSummary）
        // 自旋重试不重置（见 fromWaitingRetry 注释），其余入口都从新的一轮算起
        if (!fromWaitingRetry) {
            roundStartMs = SystemClock.elapsedRealtime()
            roundFirstResultMs = -1L
            roundSiteCount = 0
            roundDohBase = L1FallbackDns.counters()
        }
        // ab 口径（2026-09-14 an 批次一）：全员并发取 searchConcurrency()（低端 min(cores,6) / 其余 10）。
        // al 的"分源并发"（普通源 16 + 家族 jar 信号量 10）**整条撤除**，用户 09-14 拍板不再考虑：
        // 提速只能走"不重复加载"，不再走"提高并发"—— 后者已被 ae/ai/al/am 四版实验否掉。
        searchExecutorService = L1Executors.fixed("l1box-search", DeviceProfile.searchConcurrency())
        val searchRequestList: MutableList<SourceBean> = ArrayList()
        // 快照拷贝（加锁），防止切线路重载站点池时并发修改闪退
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList())
        val home = ApiConfig.get().homeSourceBean
        searchRequestList.remove(home)
        searchRequestList.add(0, home)
        val siteKey = ArrayList<String>()
        if (!appendOnly) {
            // 补轮时"全部显示"与当前选中 tab 都**不再重建、也不重置选中项** ——
            // 补轮是后台追加，不能把用户正看着的站点 tab 拉回"全部显示"。
            // 左栏断触（2026-09-28）：这里不再直接 addView/"全部显示"重建 —— 上面已经入队
            // queueSiteTabsReset()，落地时会清空后先补"全部显示"再补站点 tab（顺序不变）。
        }
        for (bean: SourceBean in searchRequestList) {
            if (!bean.isSearchable) {
                continue
            }
            // 空集合等于"没有过滤意图"，不能当成"全都不选"：
            // 冷启动时站点池尚未装载，SearchHelper.getSources() 会回退出一张空表，
            // 若把空表当作过滤条件，会把全部站点滤掉 —— 又一种"冷启动首搜空源"。
            if (mCheckSources != null && mCheckSources!!.isNotEmpty() && !mCheckSources!!.containsKey(bean.key)) {
                continue
            }
            // 补轮只搜**首波没搜过**的站点（池里新增的那些）。已搜过的既不重复请求，
            // 也不重复登记站点名（spNames/keyToName 在补轮时未清空，映射还在）。
            if (appendOnly && roundAskedKeys.contains(bean.key)) {
                continue
            }
            siteKey.add(bean.key)
            spNames[bean.name] = bean.key
            keyToName[bean.key] = bean.name
            allRunCount.incrementAndGet()
        }
        if (siteKey.isEmpty()) {
            if (appendOnly) {
                // 差集为空（源没有变化）⇒ **一个请求都不发**，直接收尾（定稿口径）。
                // 这一支绝不能走 handleNoSearchableSite()：那里会把"补轮无事可做"误读成
                // "一个可搜站点都没有"，进而给出空态或订阅失败弹窗。
                System.out.println("搜索补全：源无变化，跳过")
                cancel()
                return
            }
            // 一个可搜站点都没有 ⇒ 根本不进 S2：交给 handleNoSearchableSite 分流
            // （池未就绪 → S1 无上限等待 / 订阅未加载成功 → 单独成态 / 其余 → 诚实空态）
            printRoundSummary("无可搜站点")
            handleNoSearchableSite()
            cancel()
            return
        }
        // 非补轮 = 全新一波：已搜集合从零开始；补轮则**累加**（首波的集合是补轮的差集基准）
        if (!appendOnly) roundAskedKeys.clear()
        readyRetry = 0
        roundSiteCount = siteKey.size
        // bv：原来这里还有一行 `roundAsked = siteKey.size` —— 它与上一行**同一个值**，正是判据②
        // 恒真的来源。判据② 现在比的是 `roundDone`（真实回调数），该字段已整体删除。
        roundAskedKeys.addAll(siteKey)
        if (appendOnly) {
            System.out.println("搜索补全：差集=" + siteKey.size + " 站点（池签名=" + roundPoolSig + "）");
        }
        // 左栏 tab 由结果回调按"真的有结果的站点"逐个追加（见 queueSiteTab）。
        // 这里**不做预建**：用户 09-28 真机明确否掉"点搜索就把所有站点列出来"，
        // 且预建会让"还没结果的站点"可点 → 点进去必崩（filterResult 的 resultVods[key] 还是 null）。
        System.out.println("搜索轮次：" + phase + " 可搜站点=" + siteKey.size + " 站点池定论=" + roundPoolSettled
                + " jar装载序号=" + roundLoadSeq + " 池签名=" + roundPoolSig);
        for (key: String in siteKey) {
            searchExecutorService!!.execute {
                try {
                    // ak 定稿（2026-09-13）：两参调用 = quick=false，与 ab 版逐字一致。
                    // quick=true 是 ae 遗留、ag 砍补全轮时漏撤的：它让线程立刻取下一个站点，
                    // 把多个加固家族 jar 的首次初始化挤进同一时间窗，是崩溃密度的推手之一。
                    // jar 内部对 quick=true 的具体行为无法静态确认（真机日志也抓不到），不赌，退回基线。
                    //
                    // ch（2026-09-29）：标记"站点在跑"。jar 首次初始化会构造 WebView 取 Cookie/UA，
                    // 那一帧能到 300ms+（cg 轮实测 313.51ms）。**主线程此时并没有在做我们的落地代码**，
                    // 所以标签必须说清"是站点在跑"，否则最差帧会被误记成「结果落地」（四轮踩这个坑）。
                    stage("站点在跑")
                    sourceViewModel.getSearch(key, searchTitle)
                } catch (_: Throwable) {
                    // Throwable 兜底：spider jar 抛 Error（OOM/StackOverflow 等）也不能杀进程
                }
            }
        }
        startSearchWatchdog()
        // bv（2026-09-24）：0 结果的搜索不再干等满 30 秒看门狗（见 stallRunnable）
        lastProgressMs = SystemClock.elapsedRealtime()
        stallReported = false
        startStallCheck()
    }

    /**
     * 一个可搜站点都没有时的分流（2026-09-23 定稿 #2）。三种含义**完全不同**，不能都用"暂无数据"打发：
     *
     *  ① **池未就绪**（冷启动订阅还在网络途中）→ S1 等待，**无时间上限**、纯内存探池零请求。
     *     这一支是"假空被结构性删除"的落点：只要池没定论，就永远到不了"暂无数据"。
     *  ② **池已定论 + 池里空的 + 本机有订阅地址** ＝ 订阅未加载成功 → 订阅失败单独成态。
     *     它和"订阅里没有站点"、以及"用户把站点全过滤掉了"是两回事，不能冒充空态。
     *  ③ 其余（没配订阅 / 订阅里确实没有可搜站点 / 用户过滤导致全排除）→ **S4 诚实空态**。
     */
    private fun handleNoSearchableSite() {
        // ① 池未定论：等，不判空
        if (!ApiConfig.get().isSitePoolSettled()) {
            if (readyRetry == 0) {
                System.out.println("搜索轮次：可搜站点=0 但站点池未定论，进入 S1 等待（无上限）")
            }
            // 提示不在这里出：0 秒就弹"正在初始化"同样是"提示→马上失败"的观感，
            // 统一交给 onWaitTick 在 ≈2 秒那一拍（readyRetry == INIT_HINT_AT_RETRY）
            beginWait("站点池未定论")
            return
        }
        // ② 订阅未加载成功：有地址、池却是空的（定论已下）
        if (!ApiConfig.get().hasSubscription() && hasSubUrl()) {
            System.out.println("搜索轮次：" + phase + " 可搜站点=0 且站点池为空但有订阅地址 ⇒ 订阅未加载成功");
            enterSubFail()
            return
        }
        // ③ 诚实空态
        System.out.println("搜索轮次：可搜站点=0 池已定论且无订阅地址/无可用站点 ⇒ 真空态"
                + "（池定论=" + roundPoolSettled + " jar装载=" + roundLoadSeq + "/" + JarLoader.loadSeq() + "）");
        phase = Phase.S4
        showEmpty()
    }

    /**
     * **订阅失败单独成态**（2026-09-23 定稿 #2）：不冒充"暂无数据"。
     *
     * 识别只用现成信息：池已定论 + 站点数 0 + 本机存有订阅地址（不新增任何状态）。
     * 自动**同址重拉 1 次**（地址一字不改，是重试不是换源；每启动最多 1 次，见 subReloadTried），
     * 成功即自动开搜；再失败 → 只留「重试」按钮交用户手动（复用现成的 TipDialog，不新增界面）。
     */
    private fun enterSubFail() {
        phase = Phase.SUB_FAIL
        // 保持转圈：此刻既不是"有结果"，也不是"结论已下"，界面不该给出任何结论
        showLoading()
        if (!subReloadTried) {
            subReloadTried = true
            System.out.println("搜索轮次：SUB_FAIL 同址重拉订阅 1 次（每启动最多 1 次）");
            reloadSubNow()
            return
        }
        showSubFailDialog()
    }

    /** 同址重拉订阅（地址取自 Hawk，一字不改）。自动路径受 subReloadTried 护栏，手动「重试」不受限。 */
    private fun reloadSubNow() {
        if (isFinishing || isDestroyed) return
        ApiConfig.get().loadConfig(false, object : LoadConfigCallback {
            override fun retry() {}

            override fun success() {
                if (isFinishing || isDestroyed) return
                System.out.println("搜索轮次：SUB_FAIL 同址重拉成功，自动开搜")
                searchWatchdogHandler.removeCallbacks(waitTickRunnable)
                readyRetry = 0
                phase = Phase.S2
                searchResult()
            }

            override fun error(msg: String) {
                if (isFinishing || isDestroyed) return
                System.out.println("搜索轮次：SUB_FAIL 同址重拉仍失败 msg=" + msg)
                showSubFailDialog()
            }
        }, this)
    }

    /**
     * 订阅失败态的出口：**只留重试**，顺带把"去订阅管理"放在标题点击上（沿用 HomeFragment 的既有做法，
     * 复用现成的 TipDialog 与 dialog_tip 布局，不新增任何界面元素）。
     */
    private fun showSubFailDialog() {
        if (isFinishing || isDestroyed) return
        if (subFailDialog != null && subFailDialog!!.isShowing) return
        if (subFailDialog == null) {
            subFailDialog = TipDialog(this, "订阅没有加载出可用站点，请检查网络或订阅地址",
                "重试", "取消", object : TipDialog.OnListener {
                    override fun left() {
                        subFailDialog?.hide()
                        // 用户手动的重试：不受"每启动 1 次"护栏限制
                        showLoading()
                        reloadSubNow()
                    }

                    override fun right() {
                        subFailDialog?.hide()
                    }

                    override fun cancel() {}

                    override fun onTitleClick() {
                        subFailDialog?.hide()
                        jumpActivity(SubscriptionActivity::class.java)
                    }
                })
        }
        subFailDialog!!.show()
    }

    /** 搜索发起后启动超时看门狗（每次重搜都会重置计时） */
    private fun startSearchWatchdog() {
        searchWatchdogHandler.removeCallbacks(searchWatchdog)
        searchWatchdogRunning = true
        searchWatchdogHandler.postDelayed(searchWatchdog, SEARCH_TIMEOUT_MS)
    }

    private fun cancelSearchWatchdog() {
        searchWatchdogRunning = false
        searchWatchdogHandler.removeCallbacks(searchWatchdog)
        searchWatchdogHandler.removeCallbacks(stallRunnable)
    }

    /** 超时收尾：已搜到的结果照常展示，一条都没有时**交给 S3 复检**（不再直接判空） */
    private fun finishSearchByTimeout() {
        if (!searchWatchdogRunning) return
        searchWatchdogRunning = false
        firstWaveFinished = true
        // bv（2026-09-24）：超时收尾在代码上等价于"至少有一个站点没回调"（正常收尾只能由 count == 0 触发）
        // ⇒ 判据② 直接判不成立，不再给空态，改走"部分未响应"或继续等池。
        roundEndedByTimeout = true
        searchWatchdogHandler.removeCallbacks(stallRunnable)
        try {
            if (searchExecutorService != null) {
                searchExecutorService!!.shutdownNow()
                searchExecutorService = null
                JsLoader.stopAll()
            }
        } catch (_: Throwable) {
        }
        cancel()
        allRunCount.set(0)
        // 超时收尾同样要先把合并队列落地，否则已搜到的结果会被误判成"没有结果"
        searchWatchdogHandler.removeCallbacks(flushRunnable)
        flushSearchResults()
        printRoundSummary("超时收尾")
        // P1：超时收尾也要给图片队列与帧耗时结论（否则慢的那一轮没有数据）
        System.out.println("图片加载：" + MyOkhttpDownLoader.imgStatsSummary())
        System.out.println("图片队列：" + L1ImageInflight.summary())
        // 超时收尾同样要有帧耗时结论（否则慢的那一轮没有数据）
        System.out.println(snapshotFrameStats("本轮"))
        val hasResult = searchAdapter.data.size > 0 || searchAdapterFilter.data.size > 0
        if (hasResult) {
            showSuccess()
            if (!isFilterMode) mBinding.mGridView.visibility = View.VISIBLE
            // 这句只在"确实拿到了东西、但不是全部站点都回来了"时才说。
            // 一条都没有的情况不再走这里说：那时要么回 S1/S3 继续等（说"超时"会让人以为结束了），
            // 要么是证过的真空态（自有"暂无数据"文案）。
            ToastUtils.showShort("部分站点响应超时，已展示当前搜索结果")
            // 与正常收尾一致：有结果就看一眼池还会不会变（补全守卫自会快速收工）
            beginGuard()
        } else {
            // 超时收尾同样不能直接判空：超时最常见的成因就是站点正卡在首次初始化上。
            // S3 复检会按六条判据决定是"继续等池变化重搜"还是"诚实空态"。
            reviewZeroResult()
        }
    }

    /**
     * 添加到最后面并返回最后一个key
     * @param key
     * @return
     */
    private fun addWordAdapterIfNeed(key: String): String {
        try {
            // 哈希反查，替掉原先每次遍历 spNames.keys + 全量比对子 View 文本的两轮线性查找
            val name = keyToName[key] ?: return key
            // 左栏断触（2026-09-28）：不再随手 addView —— 用户正拖左栏时动视图会掐断手势，
            // 这里只入队，等停手（见 flushSiteTabsNow）统一补上。去重在入队与落地两处都做。
            queueSiteTab(name)
            return key
        } catch (e: Exception) {
            return key
        }
    }

    private fun matchSearchResult(name: String, searchTitle: String?): Boolean {
        var searchTitle = searchTitle
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(searchTitle)) return false
        searchTitle = searchTitle!!.trim { it <= ' ' }
        val arr = searchTitle.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        var matchNum = 0
        for (one: String in arr) {
            if (name.contains(one)) matchNum++
        }
        return if (matchNum == arr.size) true else false
    }

    private fun searchData(absXml: AbsXml?) {
        // bv（2026-09-24）：任何回调（含"这个站点没有结果"的空回调）都算"有动静"，
        // 停滞检测据此区分"卡死"与"还在跑"。
        lastProgressMs = SystemClock.elapsedRealtime()
        var lastSourceKey = ""
        if ((absXml != null) && (absXml.movie != null) && (absXml.movie.videoList != null) && (absXml.movie.videoList.size > 0)) {
            for (video: Movie.Video in absXml.movie.videoList) {
                if (!matchSearchResult(video.name, searchTitle)) continue
                // 去重（仅同站点+影片ID）：个别源单页会返回重复条目；
                // 跨源同名片、同片不同版本、不同站点一律保留
                val bucket = resultVods[video.sourceKey]
                if (bucket == null) {
                    resultVods[video.sourceKey] = ArrayList<Movie.Video>().also { it.add(video) }
                } else {
                    if (bucket.any { it.id == video.id }) continue
                    bucket.add(video)
                }
                // 首条结果耗时（2026-09-22）：用户体感的真正来源是"多久看到第一条"，
                // 不是整轮收尾时刻 —— 之前只有收尾日志，所以"冷启特别慢"只能靠描述。
                if (roundFirstResultMs < 0) {
                    roundFirstResultMs = SystemClock.elapsedRealtime() - roundStartMs
                    println("搜索首条结果：耗时=" + roundFirstResultMs + "ms 站点="
                            + video.sourceKey + " 关键词=" + searchTitle)
                }
                // 先入合并队列，由刷新窗口统一落地，避免每个站点返回都触发一次列表通知
                pendingResults.add(video)
                if (video.sourceKey !== lastSourceKey) { // 记录本批站点的 key，用于站点头填充
                    lastSourceKey = addWordAdapterIfNeed(video.sourceKey)
                }
            }
        }
        if (!firstWaveFinished) {
            // 判据② 的真数据源：这是**实际回调回来**的站点数（bv，2026-09-24）
            roundDone++
            val count = allRunCount.decrementAndGet()
            // 必须是 == 0 而不是 <= 0（bu，2026-09-24）：allRunCount 初值就是 0，而新一轮开始时会先
            // set(0) 再逐个 increment。落在这个窗口里的**孤立回调**（上一波迟到的结果）会把计数打成 -1，
            // 用 <= 0 就会触发一次"本轮压根没开始"的伪收尾。真机日志里这种伪收尾有 14 次
            // （`问全=false(0/0) 池未变=false(->...)`，`->` 前为空即本轮 roundPoolSig 从未设置过），
            // 当时全靠"问全"判据挡着才没出事 —— 早几十微秒落进窗口就是假空。
            if (count == 0) {
                onFirstWaveDone()
            } else {
                scheduleFlush()
            }
        } else if (pendingResults.isNotEmpty()) {
            // 收尾后仍有迟到结果（事件早于收尾已入队）：只要落地，不再判空态
            scheduleFlush()
        }
    }

    private fun cancel() {
        OkGo.getInstance().cancelTag("search")
    }

    override fun onDestroy() {
        super.onDestroy()
        // 停掉所有"延迟节拍"：帧统计、停稳取消判断、图片体检、chip 分帧队列
        frameWatching = false
        searchWatchdogHandler.removeCallbacks(imageWindowRunnable)
        searchWatchdogHandler.removeCallbacks(visibleImageAuditRunnable)
        searchWatchdogHandler.removeCallbacks(siteTabsDrainRunnable)
        searchWatchdogHandler.removeCallbacks(flushRunnable)
        searchWatchdogHandler.removeCallbacks(waitTickRunnable)
        searchWatchdogHandler.removeCallbacks(stallRunnable)
        pendingResults.clear()
        cancelSearchWatchdog()
        cancel()
        try {
            if (subFailDialog != null && subFailDialog!!.isShowing) subFailDialog!!.hide()
        } catch (_: Throwable) {
        }
        subFailDialog = null
        try {
            if (searchExecutorService != null) {
                searchExecutorService!!.shutdownNow()
                searchExecutorService = null
                JsLoader.load()
            }
        } catch (th: Throwable) {
            th.printStackTrace()
        }
    }

    override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
    override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
    override fun afterTextChanged(editable: Editable) {
        val text = editable.toString()
        if (TextUtils.isEmpty(text)) {
            mSearchSuggestionsDialog?.dismiss()
            hideHotAndHistorySearch(false)
        } else {
            getSuggest(text)
        }
    }

    private fun getDoubanSuggest(text: String) {
        OkGo.get<String>("https://movie.douban.com/j/subject_suggest?q="+text.trim())
            .execute(object : StringCallback(){
                override fun onSuccess(response: com.lzy.okgo.model.Response<String>?) {
                    val list = GsonUtils.fromJson<List<DoubanSuggestBean>>(
                        response?.body(),
                        object : TypeToken<List<DoubanSuggestBean>>() {}.type
                    )

                    //暂时只保留第一个,分数查询接口有限制
                    val filterList = list.filter {
                        it.title == text
                    }
                    if (filterList.isEmpty()){
                        ToastUtils.showShort("暂无评分信息")
                        return
                    }

                    XPopup.Builder(this@FastSearchActivity)
                        .maxHeight(ScreenUtils.getScreenHeight() - (ScreenUtils.getScreenHeight() / 4))
                        .asCustom(DoubanSuggestDialog(this@FastSearchActivity,filterList.subList(0,1)))
                        .show()
                }
            })
    }
}