package com.github.tvbox.osc.ui.activity

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
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
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.DoubanSuggestBean
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.databinding.ActivityFastSearchBinding
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.event.ServerEvent
import com.github.tvbox.osc.ui.adapter.FastSearchAdapter
import com.github.tvbox.osc.ui.dialog.DoubanSuggestDialog
import com.github.tvbox.osc.ui.dialog.SearchCheckboxDialog
import com.github.tvbox.osc.ui.dialog.SearchSuggestionsDialog
import com.github.tvbox.osc.util.DeviceProfile
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.L1Executors
import com.github.tvbox.osc.util.L1FallbackDns
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

        /**
         * 站点池未装载完时的等待节奏。
         *
         * 冷启动时订阅配置要**走网络**拉（实测从本地缓存命中的日志 0 次，说明每次冷启动都重新拉），
         * 而内置首页配置是 assets 里的、瞬间就绪 —— 两者之间存在数百毫秒到数秒的真空窗口。
         * 原来只等 4×500ms＝2 秒，弱网下不够；改为 10×500ms＝5 秒，仍是有界等待。
         * 等待期间界面停在 loading（不会先闪一个"暂无数据"再变出结果）。
         *
         * 2026-09-22 拍板（A8 实测）：就绪时刻＝冷启那次网络拉订阅配置的完成时刻，
         * 网络一抖 10s 超时＋回退 ⇒ 就绪可晚到 ≈+16.5s，5s 预算天生踩空（极速冷启首搜空态实证）。
         * 改 40×500ms＝20s，且计数**全程共享**（自动重搜轮不清零，见 readyRetry 注释）⇒ 最长等 20s。
         * 正常搜索零影响：站点池一定论立刻跳出等待（:664 分支），热态 57~67ms / 冷启首条 1.7~2.2s 不变。
         */
        private const val READY_RETRY_MAX = 40
        private const val READY_RETRY_DELAY_MS = 500L

        /** 等待期间给「正在初始化」提示的时机（4×500ms≈2s）：不能等预算用尽才说，观感是"提示→马上失败" */
        private const val INIT_HINT_AT_RETRY = 4

        /**
         * "0 结果不可信"时的自动重搜（2026-09-14 冷启动首搜空源·第二根因）。
         *
         * 判据见 JarLoader.loadSeq()：本轮搜索期间若有 jar 装载落定，说明搜索与初始化撞在一起了，
         * 此时站点返回的 0 条是**假空**（jar 自己的 searchContent 在配置/代理未就绪时抛 NPE 或返空串），
         * 不能当成"真的没有"。自动重搜一次即可拿到结果，等价于用户手点的第二下，但不用他动手。
         * 上限 2 次是为了在"源真的没结果"时快速收敛到空态，不无限重试。
         */
        private const val AUTO_RESEARCH_MAX = 2
        private const val AUTO_RESEARCH_DELAY_MS = 600L

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
        mBinding.mGridView.setHasFixedSize(true)
        mBinding.mGridView.setLayoutManager(LinearLayoutManager(this))
        mBinding.mGridView.adapter = searchAdapter
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
        val list: List<Movie.Video> = (resultVods[key])!!
        // 复制桶：过滤视图用独立副本（首波期间桶仍会增长，不与 adapter 共享引用）
        searchAdapterFilter.setNewData(ArrayList(list))
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
        mBinding.tabLayout.removeAllViews()
        // 用户手动发起的搜索：所有"等一等/自动重搜"的预算都从这里重新开始。
        // 不在 searchResult() 里清是因为那也是自动重搜的入口 —— 清在那儿等于自动重搜失效。
        readyRetry = 0
        autoResearches = 0
        initHintShown = false
        searchResult()
    }

    private var searchExecutorService: ExecutorService? = null
    private val allRunCount = AtomicInteger(0)

    /** 本轮搜索内"站点池未就绪"的重试次数（站点池一旦有源即清零，不跨轮累积）。
     *  2026-09-22 拍板：预算 20s 且**全程共享**——自动重搜轮不清零（清了就回到"自动轮零等待"的空转），
     *  只在用户手动 search() 里清零。等待重试本身会重入 searchResult()，所以绝不能在那条入口清。 */
    private var readyRetry = 0
    private val readyRetryRunnable = Runnable { if (!isFinishing && !isDestroyed) searchResult(fromWaitingRetry = true) }

    /** 「正在初始化」提示只在每次手动搜索期间显示一次 */
    private var initHintShown = false

    /**
     * 本轮的"世界状态"快照：发起搜索那一刻站点池是否已有定论、jar 装载序号是多少。
     * 收尾时拿它和当前值比对，判断这一轮的 0 结果可不可信（见 verdictEmpty）。
     */
    private var roundPoolSettled = true
    private var roundLoadSeq = 0L

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
                + " 总耗时=" + total + "ms 本轮装载增量=" + created
                + " " + L1FallbackDns.delta(roundDohBase))
    }

    /** 本轮因"0 结果不可信"已自动重搜的次数；用户手动搜索时清零 */
    private var autoResearches = 0
    private val autoResearchRunnable = Runnable { if (!isFinishing && !isDestroyed) searchResult() }

    /** 已收尾（正常归零或看门狗超时）：收尾动作幂等，迟到事件不再重复走一遍 */
    private var firstWaveFinished = false

    /** 第一波全部返回：落地结果、判空态后收工 */
    private fun onFirstWaveDone() {
        if (firstWaveFinished) return
        firstWaveFinished = true
        searchWatchdogHandler.removeCallbacks(flushRunnable)
        flushSearchResults()
        cancelSearchWatchdog()
        printRoundSummary("收尾")
        if (searchAdapter.data.size <= 0) {
            verdictEmpty()
        }
        cancel()
    }

    /**
     * "一条结果都没有"的裁决（2026-09-14 冷启动首搜空源·定稿）。
     *
     * 0 结果有两种来源，**页面上长得一模一样**，但只有一种该给用户看"暂无数据"：
     *
     *  - **假空**：本轮搜索与初始化撞车了。两个可观测的撞车标志 ——
     *      ① 发起搜索时站点池还没有定论（订阅仍在网络途中，内置首页源又不参与搜索）；
     *      ② 本轮期间有 jar 装载落定（`loadSeq` 变了）——jar 自己的 `searchContent` 在
     *         配置/代理未就绪时会抛 NPE 或返空串，站点就被静默记成 0 条。
     *    实测证据：ar 版日志里 63 次 `App99.searchContent` 抛 `NullPointerException`
     *    （HashMap 构造拿到 null map），栈底正是 `FastSearchActivity.searchResult$lambda-16`。
     *  - **真空**：源真的没有这个关键词的结果。
     *
     * 假空下点第二下就好了 —— 这正是用户的手动绕行（"再点一次搜索就正常了"）。
     * 这里把它自动化：自动重搜（上限 AUTO_RESEARCH_MAX 次），重搜时保持 loading 而不是闪空态。
     */
    private fun verdictEmpty() {
        val seqNow = JarLoader.loadSeq()
        val unreliable = !roundPoolSettled || (seqNow != roundLoadSeq)
        if (unreliable && autoResearches < AUTO_RESEARCH_MAX) {
            autoResearches++
            System.out.println("搜索收尾：0 结果但与初始化重叠（站点池定论=" + roundPoolSettled
                    + " jar装载序号=" + roundLoadSeq + "->" + seqNow
                    + "），自动重搜第 " + autoResearches + " 次");
            ToastUtils.showShort("正在初始化播放源，自动重试…")
            showLoading()
            searchWatchdogHandler.removeCallbacks(autoResearchRunnable)
            searchWatchdogHandler.postDelayed(autoResearchRunnable, AUTO_RESEARCH_DELAY_MS)
            return
        }
        System.out.println("搜索收尾：0 结果且判定为真空（站点池定论=" + roundPoolSettled
                + " jar装载序号=" + roundLoadSeq + "->" + seqNow
                + " 自动重搜=" + autoResearches + "），给出空态");
        showEmpty()
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

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        searchWatchdogHandler.postDelayed(flushRunnable, SEARCH_FLUSH_MS)
    }

    /** 把攒下的结果一次性落地（视图已销毁则直接丢弃） */
    private fun flushSearchResults() {
        flushScheduled = false
        if (pendingResults.isEmpty()) return
        val batch = ArrayList(pendingResults)
        pendingResults.clear()
        if (isFinishing || isDestroyed) return
        if (searchAdapter.data.size > 0) {
            searchAdapter.addData(batch)
        } else {
            showSuccess()
            if (!isFilterMode) mBinding.mGridView.visibility = View.VISIBLE
            searchAdapter.setNewData(batch)
        }
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
     * fromWaitingRetry＝本次进入是"站点池未就绪"的自旋重试。此时**不能**重置诊断基线：
     * 否则预算用尽那一刻刚好重入过，收尾打出的"总耗时"只有几毫秒（09-22 实测 `总耗时=7ms` 假计时）。
     * 自动重搜（autoResearchRunnable）走默认 false——每轮是独立的一波，基线理应重新开始。
     */
    private fun searchResult(fromWaitingRetry: Boolean = false) {
        try {
            if (searchExecutorService != null) {
                searchExecutorService!!.shutdownNow()
                searchExecutorService = null
                JsLoader.stopAll()
            }
        } catch (th: Throwable) {
            th.printStackTrace()
        } finally {
            searchAdapter.setNewData(ArrayList())
            searchAdapterFilter.setNewData(ArrayList())
            allRunCount.set(0)
        }
        // 单轮搜索状态复位：上一轮的收尾标志清掉
        firstWaveFinished = false
        // 自动重搜会**重入本函数**，所以"每个站点一个 tab"和站点名表必须在这里重新开始：
        // 否则"全部显示"会一轮轮叠加、上一轮的站点名残留（search() 里的那份清理只覆盖用户手动那一轮）。
        mBinding.tabLayout.removeAllViews()
        spNames.clear()
        keyToName.clear()
        // 本轮"世界状态"快照：收尾时用它判断这一轮的 0 结果可不可信（见 verdictEmpty）
        roundPoolSettled = ApiConfig.get().isSitePoolSettled()
        roundLoadSeq = JarLoader.loadSeq()
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
        mBinding.tabLayout.addView(getSiteTextView("全部显示"))
        mBinding.tabLayout.setCurrentItem(0, true, false)
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
            siteKey.add(bean.key)
            spNames[bean.name] = bean.key
            keyToName[bean.key] = bean.name
            allRunCount.incrementAndGet()
        }
        if (siteKey.isEmpty()) {
            // "没有一个可搜索的站点"有两种完全不同的含义，必须分开（2026-09-14 冷启动首搜空源根因）：
            //   ① 站点池装载尚无定论（冷启动时订阅还在下载、内置首页源也还没解析）——这是暂态，
            //      直接给空态会让用户看到"空源"，只能退回首页等装载完再搜才正常；
            //   ② 装载已有定论（确实没订阅，或订阅里没有一个可搜站点）——这才是真空态。
            // ①按固定节奏重试，装载一完成即可直接出结果，用户不用回首页。
            if (!ApiConfig.get().isSitePoolSettled() && readyRetry < READY_RETRY_MAX) {
                readyRetry++
                // 等到 ≈2s 还没就绪就提前说一声（09-22 拍板 Q1）：原来是预算用尽才弹
                // 「正在初始化播放源，自动重试…」，观感是"提示→马上失败"。文案前缀
                // 「正在初始化播放源」已在 ToastSweeper HOST_TEXTS 白名单里，无需再登记。
                if (readyRetry >= INIT_HINT_AT_RETRY && !initHintShown) {
                    initHintShown = true
                    ToastUtils.showShort("正在初始化播放源，请稍候…")
                }
                searchWatchdogHandler.removeCallbacks(readyRetryRunnable)
                searchWatchdogHandler.postDelayed(readyRetryRunnable, READY_RETRY_DELAY_MS)
                return
            }
            // 走到这里：等待预算用尽，或装载已有定论（确实没订阅 / 订阅里没有一个可搜站点）。
            // 不能直接判空 —— 若"定论"本身是假的（例如订阅仍在途中），这就是假空态。
            // 交给 verdictEmpty 统一裁决（不可信则自动重搜，可信才落"暂无数据"）。
            System.out.println("搜索轮次：可搜站点=0 站点池定论=" + roundPoolSettled
                    + " jar装载序号=" + roundLoadSeq + "/" + JarLoader.loadSeq()
                    + " 等待次数=" + readyRetry + " 自动重搜=" + autoResearches);
            printRoundSummary("无可搜站点")
            verdictEmpty()
            cancel()
            return
        }
        readyRetry = 0
        roundSiteCount = siteKey.size
        System.out.println("搜索轮次：可搜站点=" + siteKey.size + " 站点池定论=" + roundPoolSettled
                + " jar装载序号=" + roundLoadSeq + " 自动重搜=" + autoResearches);
        for (key: String in siteKey) {
            searchExecutorService!!.execute {
                try {
                    // ak 定稿（2026-09-13）：两参调用 = quick=false，与 ab 版逐字一致。
                    // quick=true 是 ae 遗留、ag 砍补全轮时漏撤的：它让线程立刻取下一个站点，
                    // 把多个加固家族 jar 的首次初始化挤进同一时间窗，是崩溃密度的推手之一。
                    // jar 内部对 quick=true 的具体行为无法静态确认（真机日志也抓不到），不赌，退回基线。
                    sourceViewModel.getSearch(key, searchTitle)
                } catch (_: Throwable) {
                    // Throwable 兜底：spider jar 抛 Error（OOM/StackOverflow 等）也不能杀进程
                }
            }
        }
        startSearchWatchdog()
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
    }

    /** 超时收尾：已搜到的结果照常展示，一条都没有才给空态 */
    private fun finishSearchByTimeout() {
        if (!searchWatchdogRunning) return
        searchWatchdogRunning = false
        firstWaveFinished = true
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
        val hasResult = searchAdapter.data.size > 0 || searchAdapterFilter.data.size > 0
        if (hasResult) {
            showSuccess()
            if (!isFilterMode) mBinding.mGridView.visibility = View.VISIBLE
        } else {
            // 超时收尾同样不能直接判空：超时最常见的成因就是站点正卡在首次初始化上
            verdictEmpty()
        }
        ToastUtils.showShort("部分站点响应超时，已展示当前搜索结果")
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
            for (i in 0 until mBinding.tabLayout.childCount) {
                val item = mBinding.tabLayout.getChildAt(i) as? TextView ?: continue
                if (name == item.text.toString()) {
                    return key
                }
            }
            mBinding.tabLayout.addView(getSiteTextView(name))
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
            val count = allRunCount.decrementAndGet()
            if (count <= 0) {
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
        searchWatchdogHandler.removeCallbacks(flushRunnable)
        searchWatchdogHandler.removeCallbacks(readyRetryRunnable)
        searchWatchdogHandler.removeCallbacks(autoResearchRunnable)
        pendingResults.clear()
        cancelSearchWatchdog()
        cancel()
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