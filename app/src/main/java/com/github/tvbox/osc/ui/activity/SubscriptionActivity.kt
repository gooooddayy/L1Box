package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import com.blankj.utilcode.util.ClipboardUtils
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.Source
import com.github.tvbox.osc.bean.Subscription
import com.github.tvbox.osc.event.RefreshEvent
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.databinding.ActivitySubscriptionBinding
import com.github.tvbox.osc.ui.adapter.SubscriptionAdapter
import com.github.tvbox.osc.ui.dialog.ChooseSourceDialog
import com.github.tvbox.osc.ui.dialog.EditSubscriptionDialog
import com.github.tvbox.osc.ui.dialog.ShareSubscriptionDialog
import com.github.tvbox.osc.ui.dialog.SubsTipDialog
import com.github.tvbox.osc.ui.dialog.SubsciptionDialog
import com.github.tvbox.osc.ui.dialog.SubsciptionDialog.OnSubsciptionListener
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.L1SubContent
import com.github.tvbox.osc.util.L1SubUrl
import com.github.tvbox.osc.util.OkGoHelper
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.google.zxing.integration.android.IntentIntegrator
import com.lxj.xpopup.XPopup
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.Response
import com.orhanobut.hawk.Hawk
import java.io.File

class SubscriptionActivity : BaseVbActivity<ActivitySubscriptionBinding>() {

    // 配置源/线路变更不再需要在此判定：由 HomeFragment.onResume 比对 HawkConfig.API_URL 感知并后台重载
    private var mSelectedUrl = ""
    /** 进入页面时的订阅快照，用于离开时判断是否真的改动过 */
    private var mInitialSignature = ""
    private var mSubscriptions: MutableList<Subscription> = Hawk.get(HawkConfig.SUBSCRIPTIONS, ArrayList())
    private var mSubscriptionAdapter = SubscriptionAdapter()
    // 订阅抓取请求头统一由 OkGoHelper.subHeaders 提供（与启用阶段同一套，含 UA/Accept/
    // Accept-Language/Cache-Control/Referer），此处不再各写各的

    override fun init() {
        mInitialSignature = currentSignature()

        mBinding.rv.setAdapter(mSubscriptionAdapter)
        recomputeSelectedUrlFromList()
        commitList()

        mBinding.btnManualAdd.setOnClickListener { openManualAdd() }
        mBinding.btnScanAdd.setOnClickListener { openScan() }

        mBinding.ivUseTip.setOnClickListener {
            XPopup.Builder(this)
                .asCustom(SubsTipDialog(this))
                .show()
        }

        mBinding.titleBar.rightView.setOnClickListener {
            XPopup.Builder(this@SubscriptionActivity)
                .atView(mBinding.titleBar.rightView)
                .asAttachList(arrayOf("手动添加", "扫一扫添加"), IntArray(0)) { index: Int, _: String? ->
                    when (index) {
                        0 -> openManualAdd()
                        1 -> openScan()
                    }
                }.show()
        }

        // Switch 开启/关闭回调
        mSubscriptionAdapter.setOnEnableChangeListener { item, enabled ->
            if (enabled) {
                // 单选启用
                for (s in mSubscriptions) s.setChecked(s === item)
                mSelectedUrl = item.effectiveUrl
            } else {
                item.setChecked(false)
                if (mSubscriptions.none { it.isChecked }) {
                    mSelectedUrl = "" // 空源状态
                }
            }
            mSubscriptionAdapter.notifyDataSetChanged()
            refreshEmptyState()
        }

        // 行内子控件点击:iv_more(复合按钮, 包含多仓切换线路菜单); tv_use_source(多仓时点击直接弹线路选择)
        mSubscriptionAdapter.setOnItemChildClickListener { _: BaseQuickAdapter<*, *>?, view: View, position: Int ->
            LogUtils.d("订阅子控件点击")
            val item = mSubscriptions.get(position)
            when (view.id) {
                R.id.iv_more -> {
                    openCompositePopup(view, item, position)
                }
                R.id.tv_use_source -> {
                    // 多仓才走这里(单仓时 Adapter 不会注册点击); 复用现有多仓选线路弹窗
                    if (item.isMultiRepo && !item.lines.isNullOrEmpty()) {
                        openLinePickerForMultiRepo(item, position)
                    }
                }
            }
        }
    }

    /** 列表数据变更统一入口：刷新列表 + 同步空状态显隐 */
    private fun commitList() {
        mSubscriptionAdapter.setNewData(mSubscriptions)
        refreshEmptyState()
    }

    /** 空源才显示居中的"手动添加 / 扫一扫添加"双入口；有源即隐藏 */
    private fun refreshEmptyState() {
        val v = mBinding.emptyAddState
        if (mSubscriptions.isEmpty()) {
            if (v.visibility == View.VISIBLE) return
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.translationY = 24f
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            v.animate().cancel()
            v.visibility = View.GONE
        }
    }

    /** 从列表里算出当前选中项的有效 url */
    private fun recomputeSelectedUrlFromList() {
        mSelectedUrl = ""
        for (item in mSubscriptions) {
            if (item.isChecked) {
                mSelectedUrl = item.effectiveUrl
                break
            }
        }
    }

    /** 复合按钮弹窗:置顶/编辑/复制/分享/删除（多仓选线路走行内"使用源"胶囊，此处不再重复入口） */
    private fun openCompositePopup(anchor: View, item: Subscription, position: Int) {
        val options = mutableListOf<String>()
        options += if (item.isTop) "取消置顶" else "置顶"
        options += "编辑"
        options += "复制源链接"
        options += "分享(含二维码)"
        options += "删除"
        XPopup.Builder(this)
            .atView(anchor)
            .hasShadowBg(false)
            .asAttachList(options.toTypedArray(), null) { index: Int, _: String? ->
                when (options[index]) {
                    "置顶", "取消置顶" -> {
                        item.isTop = !item.isTop
                        mSubscriptions[position] = item
                        commitList()
                    }
                    "编辑" -> openEditDialog(item, position)
                    "复制源链接" -> {
                        ClipboardUtils.copyText(item.url)
                        ToastUtils.showLong("已复制")
                    }
                    "分享(含二维码)" -> openShareDialog(item)
                    "删除" -> confirmDelete(position)
                }
            }.show()
    }

    private fun confirmDelete(position: Int) {
        XPopup.Builder(this@SubscriptionActivity)
            .asConfirm("删除订阅", "确定删除订阅吗？") {
                val removed = mSubscriptions.removeAt(position)
                if (removed.isChecked) {
                    // 删的是当前启用的 → mSelectedUrl 置空(空源状态)
                    mSelectedUrl = ""
                } else {
                    recomputeSelectedUrlFromList()
                }
                mSubscriptionAdapter.notifyDataSetChanged()
                refreshEmptyState()
                ToastUtils.showShort("已删除")
            }.show()
    }

    private fun openEditDialog(item: Subscription, position: Int) {
        XPopup.Builder(this)
            .asCustom(EditSubscriptionDialog(this, item.name, item.url) { newName, newUrl ->
                item.name = newName
                val fixed = L1SubUrl.normalize(newUrl)
                if (fixed != item.url) {
                    // ★ 地址变了：多仓身份与"当前选中线路"必须一起作废。
                    // 不重置的话 getEffectiveUrl() 对多仓返回的仍是 activeLineUrl，
                    // 界面上显示的是新地址、实际请求的还是**上一个源的线路** ——
                    // 这正是"切换源了还在用上一个源的线路数据"。重置后按新地址重新识别形态。
                    item.url = fixed
                    item.isMultiRepo = false
                    item.lines = ArrayList<Source>()
                    item.activeLineUrl = ""
                    reloadSubscriptionLines(item, position)
                }
                // 若编辑的是当前启用项,清掉对应的 ApiConfig 缓存避免读到旧仓
                if (item.isChecked) {
                    mSelectedUrl = item.effectiveUrl
                }
                mSubscriptionAdapter.notifyItemChanged(position)
            })
            .show()
    }

    /**
     * 地址变更后重新识别订阅形态（多仓 / 单仓）。
     * 与"添加订阅"共用同一套判定 [parseLines]，两条路径不一致就会出现
     * "改完地址后多仓信息就地残留"或"改完地址后多仓被当单仓、站点池为空"。
     * 识别不出来/网络失败一律按单仓处理 —— 宁可当单仓，也不能沿用旧仓的线路。
     */
    private fun reloadSubscriptionLines(item: Subscription, position: Int) {
        if (item.url.startsWith("clan://")) return
        OkGo.get<String>(item.url)
            .headers(OkGoHelper.subHeaders(item.url))
            .tag("get_subscription")
            .execute(object : AbsCallback<String?>() {
                override fun onSuccess(response: Response<String?>) {
                    val lines = parseLines(response.body())
                    applyLines(item, lines)
                    mSubscriptionAdapter.notifyItemChanged(position)
                }

                @Throws(Throwable::class)
                override fun convertResponse(response: okhttp3.Response): String {
                    return L1SubContent.decodeText(response.body()!!.bytes())
                }

                override fun onError(response: Response<String?>) {
                    super.onError(response)
                    applyLines(item, ArrayList())
                    mSubscriptionAdapter.notifyItemChanged(position)
                }
            })
    }

    private fun applyLines(item: Subscription, lines: List<Source>) {
        if (lines.isEmpty()) {
            item.isMultiRepo = false
            item.lines = ArrayList<Source>()
            item.activeLineUrl = ""
        } else {
            item.isMultiRepo = true
            item.lines = ArrayList(lines)
            val cur = item.activeLineUrl
            if (cur.isNullOrBlank() || lines.none { it.sourceUrl == cur }) {
                item.activeLineUrl = lines[0].sourceUrl
            }
        }
        if (item.isChecked) mSelectedUrl = item.effectiveUrl
    }

    /**
     * 从订阅内容里识别多仓线路：兼容 urls[] 与 storeHouse[] 两种形态。
     * 逐条宽松收集（只要求线路地址非空），不再要求"第 0 条字段齐全"——原实现一旦
     * 第 0 条缺字段就整条订阅静默降级成单仓，站点池因此为 0 且没有任何提示。
     */
    private fun parseLines(body: String?): List<Source> {
        val lines = ArrayList<Source>()
        val json = L1SubContent.toJson(body) ?: return lines
        try {
            val obj = JsonParser.parseString(json).asJsonObject
            obj.getAsJsonArray("urls")?.forEach { e ->
                if (!e.isJsonObject) return@forEach
                val o = e.asJsonObject
                val u = optString(o, "url")
                if (u.isNotEmpty()) lines.add(Source(cleanLineName(optString(o, "name"), lines.size), u))
            }
            if (lines.isEmpty()) {
                obj.getAsJsonArray("storeHouse")?.forEach { e ->
                    if (!e.isJsonObject) return@forEach
                    val o = e.asJsonObject
                    val u = optString(o, "sourceUrl")
                    if (u.isNotEmpty()) lines.add(Source(cleanLineName(optString(o, "sourceName"), lines.size), u))
                }
            }
        } catch (th: Throwable) {
            // 不是多仓 / 内容不合法：按单仓处理
        }
        return lines
    }

    private fun cleanLineName(raw: String, index: Int): String {
        return raw.trim().replace("<|>|《|》|-".toRegex(), "").ifBlank { "线路${index + 1}" }
    }

    private fun openShareDialog(item: Subscription) {
        // 分享源链接 = 订阅的 url;多仓源也是同一个源链接
        XPopup.Builder(this)
            .asCustom(ShareSubscriptionDialog(this, item.name, item.url))
            .show()
    }

    /** 多仓行的"切换影视源"按钮:选线路 */
    private fun openLinePickerForMultiRepo(item: Subscription, position: Int) {
        if (!item.isMultiRepo || item.lines.isEmpty()) return
        XPopup.Builder(this)
            .asCustom(
                ChooseSourceDialog(this, item.lines) { _: Int, pickedUrl: String? ->
                    // 空/无效线路：绝不写入，否则 onPause 会把空串写进 HawkConfig.API_URL，
                    // 下次 loadConfig 取到空串直接 NPE 闪退
                    if (pickedUrl.isNullOrBlank()) return@ChooseSourceDialog
                    item.activeLineUrl = pickedUrl
                    if (item.isChecked) {
                        mSelectedUrl = item.activeLineUrl
                    }
                    mSubscriptionAdapter.notifyItemChanged(position)
                    ToastUtils.showShort("已切换到: " + (item.lines.firstOrNull { it.sourceUrl == pickedUrl }?.sourceName ?: ""))
                }.setTitle("切换影视源")
            )
            .show()
    }

    /** SAF 文件选择器（系统文件界面），全程不需要任何存储权限 */
    private val jsonPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importLocalJson(uri, mPendingCheckedForPick)
    }
    private var mPendingCheckedForPick = false

    /** 把用户选中的 JSON 复制进应用私有目录，再按 clan://local/ 协议加入订阅 */
    private fun importLocalJson(uri: Uri, checked: Boolean) {
        try {
            val name = queryDisplayName(uri)
            val content = contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(charset("UTF-8"))
            } ?: ""
            if (content.isBlank()) {
                ToastUtils.showShort("文件为空或无法读取")
                return
            }
            val subsDir = File(filesDir, "local_subs")
            if (!subsDir.exists()) subsDir.mkdirs()
            val target = File(subsDir, "sub_" + System.currentTimeMillis() + ".json")
            target.writeText(content, charset("UTF-8"))
            addSubscription(name, "clan://local/" + target.name, checked)
            commitList()
        } catch (th: Throwable) {
            ToastUtils.showShort("导入失败：" + (th.message ?: "无法读取文件"))
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (!n.isNullOrBlank()) return n.substringBeforeLast('.')
                }
            }
        } catch (_: Throwable) {
        }
        return "本地订阅"
    }

    /** 记录选择文件时要同时启用 */
    private fun pickLocalJson(checked: Boolean) {
        mPendingCheckedForPick = checked
        runCatching { jsonPicker.launch(arrayOf("*/*")) }
            .onFailure { ToastUtils.showShort("无法打开文件选择器") }
    }

    /**
     * 解析订阅 JSON:
     *  - 多线路 urls[]:收拢成一条"多仓父源"(不再散开)
     *  - 多仓 storeHouse[]:收拢成一条"多仓父"Subscription(lines 为子线路,activeLineUrl 选第一条)
     *  - 其他:单条
     */
    private fun addSubscription(name: String, url: String, checked: Boolean) {
        // 入口先规范化：BOM / 零宽字符 / 首尾引号 / 漏写 http:// 都在这里收敛，后面统一用 fixed
        val fixed = L1SubUrl.normalize(url)
        if (fixed.startsWith("clan://")) {
            addSub2List(name, fixed, checked)
            commitList()
        } else if (L1SubUrl.isRequestable(fixed)) {
            showLoadingDialog()
            OkGo.get<String>(fixed)
                // 与启用阶段(ApiConfig.doLoadConfigRequest)用同一套请求头：原实现这里不带 UA，
                // 同一地址"添加时失败、启用时反而成功"正是这么来的
                .headers(OkGoHelper.subHeaders(fixed))
                .tag("get_subscription")
                .execute(object : AbsCallback<String?>() {
                    override fun onSuccess(response: Response<String?>) {
                        dismissLoadingDialog()
                        // 多仓识别与"编辑地址后重新识别"共用 parseLines：两条路径必须给出同一个结论。
                        // parseLines 内部自带 BOM / 裸 base64 / HTML 外壳净化，裸 base64 的源不再必然失败。
                        val lines = parseLines(response.body())
                        if (lines.isNotEmpty()) {
                            val parent = Subscription(name.ifBlank { fixed }, fixed).apply {
                                isMultiRepo = true
                                this.lines = lines
                            }
                            if (checked) {
                                // 单选启用,并自动选第一条线路
                                for (s in mSubscriptions) s.setChecked(false)
                                parent.setChecked(true)
                                parent.activeLineUrl = lines[0].sourceUrl
                                mSelectedUrl = parent.activeLineUrl
                            }
                            mSubscriptions.add(parent)
                        } else {
                            addSub2List(name, fixed, checked)
                        }
                        commitList()
                    }

                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        // 取原始字节自己解：GBK 源站的名称为乱码时 JSON 结构照常能解析，问题更隐蔽
                        return L1SubContent.decodeText(response.body()!!.bytes())
                    }

                    override fun onError(response: Response<String?>) {
                        super.onError(response)
                        dismissLoadingDialog()
                        // 拿到过响应内容时给出**具体**结论（返回的是网页 / 内容不是订阅配置，
                        // 用户据此就知道该改地址还是该换源）；连内容都没有（网络层就失败了）
                        // 才退回笼统的地址与网络提示 —— 不能把"请求失败"说成"地址没有返回内容"。
                        val body = response.body()
                        val tip = if (body.isNullOrEmpty()) "" else L1SubContent.diagTip(body)
                        ToastUtils.showLong(if (tip.isEmpty()) "订阅失败,请检查地址或网络状态" else "订阅失败：$tip")
                    }
                })
        } else {
            ToastUtils.showShort("订阅格式不正确")
        }
    }

    /**
     * 单线路直接加入(可同时单选启用)
     */
    private fun addSub2List(name: String, url: String, checkNewest: Boolean) {
        if (checkNewest) {
            for (subscription in mSubscriptions) {
                if (subscription.isChecked) {
                    subscription.setChecked(false)
                }
            }
            mSelectedUrl = url
            mSubscriptions.add(Subscription(name, url).setChecked(true))
        } else {
            mSubscriptions.add(Subscription(name, url).setChecked(false))
        }
    }

    /** 订阅列表快照：用于离开页面时判断配置是否真的变化过 */
    private fun currentSignature(): String {
        val effective = mSubscriptions.firstOrNull { it.isChecked }?.effectiveUrl ?: ""
        return effective + "#" + mSubscriptions.joinToString("|") { it.url + ">" + it.effectiveUrl }
    }

    override fun onPause() {
        super.onPause()
        val before = mInitialSignature
        // 把当前选中项的有效 url 写盘(多仓取 activeLineUrl)
        val checked = mSubscriptions.firstOrNull { it.isChecked }
        val effective = checked?.effectiveUrl ?: ""
        ApiConfig.saveApiUrl(effective)
        Hawk.put<List<Subscription>?>(HawkConfig.SUBSCRIPTIONS, mSubscriptions)
        mSelectedUrl = effective
        // 配置确实变了才通知首页刷新，避免什么都没改也重拉一遍订阅
        val after = currentSignature()
        if (isFinishing && before != after) {
            mInitialSignature = after
            org.greenrobot.eventbus.EventBus.getDefault()
                .post(RefreshEvent(RefreshEvent.TYPE_HOME_REFRESH))
        }
    }

    /** 手动添加(原 + 按钮逻辑) */
    private fun openManualAdd() {
        showManualAdd("")
    }

    /** 手动添加订阅:支持把扫描/外部得到的链接预填到地址栏,供用户确认 */
    private fun showManualAdd(defaultUrl: String) {
        XPopup.Builder(this)
            .autoFocusEditText(false)
            .asCustom(
                SubsciptionDialog(
                    this,
                    "订阅: " + (mSubscriptions.size + 1),
                    defaultUrl,
                    object : OnSubsciptionListener {
                        override fun onConfirm(name: String, url: String, checked: Boolean) {
                            for (item in mSubscriptions) {
                                if (item.url == url) {
                                    ToastUtils.showLong("订阅地址与" + item.name + "相同")
                                    return
                                }
                            }
                            addSubscription(name, url, checked)
                        }

                        override fun chooseLocal(checked: Boolean) {
                            // 系统文件选择器（SAF）：无需任何存储权限，用户自主选择 JSON 文件
                            pickLocalJson(checked)
                        }
                    })
            ).show()
    }

    /** 扫一扫添加源 */
    private fun openScan() {
        if (!XXPermissions.isGranted(this, Permission.CAMERA)) {
            XXPermissions.with(this)
                .permission(Permission.CAMERA)
                .request(object : OnPermissionCallback {
                    override fun onGranted(permissions: List<String>, all: Boolean) {
                        if (all) startScan() else ToastUtils.showLong("请授予相机权限后重试")
                    }

                    override fun onDenied(permissions: List<String>, never: Boolean) {
                        if (never) {
                            ToastUtils.showLong("相机权限被永久拒绝,请到设置中开启")
                            XXPermissions.startPermissionActivity(this@SubscriptionActivity, permissions)
                        } else {
                            ToastUtils.showShort("获取相机权限失败")
                        }
                    }
                })
            return
        }
        startScan()
    }

    private fun startScan() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("将二维码放入框内")
            .setBeepEnabled(true)
            .setCaptureActivity(PortraitCaptureActivity::class.java)
            .setOrientationLocked(false)
            .initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null && result.contents != null) {
            handleScannedOrImported(result.contents)
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun handleScannedOrImported(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (t.startsWith("{") || t.startsWith("[")) {
            val n = importSubscriptionsFromText(t)
            ToastUtils.showLong(if (n > 0) "已导入 $n 个订阅源" else "未识别到有效订阅")
        } else {
            // 扫描到链接:自动填充到手动添加订阅的地址栏,由用户确认后添加。
            // 先规范化：二维码里带 BOM/引号/漏协议头很常见，填进输入框前收敛，用户看到的就是可用地址
            showManualAdd(L1SubUrl.normalize(t))
        }
    }

    private fun optString(json: JsonObject, key: String): String {
        val e = json[key] ?: return ""
        if (!e.isJsonPrimitive) return ""
        return e.asString?.trim() ?: ""
    }

    /**
     * 解析文本批量导入,兼容三种格式:
     *  1) TVBox 订阅 JSON 对象({storeHouse:[...]} 多仓父源 / {urls:[...]} 多线路 / 单 url)
     *  2) JSON 数组([{"name","url"}] / ["url1","url2"])
     *  3) 纯文本: 每行一个链接, 支持 "名称,链接"
     * 返回成功导入的条数
     */
    private fun importSubscriptionsFromText(text: String): Int {
        val t = text.trim()
        if (t.isEmpty()) return 0
        var added = 0
        // 1) TVBox JSON 对象
        if (t.startsWith("{")) {
            try {
                val json = JsonParser.parseString(t).asJsonObject
                val storeHouse = json["storeHouse"]
                val urls = json["urls"]
                if (storeHouse != null && storeHouse.isJsonArray) {
                    val list = storeHouse.asJsonArray
                    if (list.size() > 0 && list[0].isJsonObject
                        && list[0].asJsonObject.has("sourceName") && list[0].asJsonObject.has("sourceUrl")
                    ) {
                        val lines: MutableList<Source> = ArrayList()
                        for (i in 0 until list.size()) {
                            val o = list[i].asJsonObject
                            lines.add(Source(optString(o, "sourceName"), optString(o, "sourceUrl")))
                        }
                        val parentUrl = if (optString(json, "url").isNotEmpty()) optString(json, "url") else (lines.firstOrNull()?.sourceUrl ?: "")
                        val parentName = if (optString(json, "name").isNotEmpty()) optString(json, "name") else (lines.firstOrNull()?.sourceName ?: "多仓源")
                        val parent = Subscription(parentName, parentUrl).apply {
                            isMultiRepo = true
                            this.lines = ArrayList(lines)
                        }
                        mSubscriptions.add(parent)
                        added++
                    }
                } else if (urls != null && urls.isJsonArray) {
                    val list = urls.asJsonArray
                    if (list.size() > 0 && list[0].isJsonObject && list[0].asJsonObject.has("url")) {
                        // 多线路 -> 收拢成一条"多仓父源"(不再散开成多条)
                        val lines: MutableList<Source> = ArrayList()
                        for (i in 0 until list.size()) {
                            val o = list[i].asJsonObject
                            val u = optString(o, "url")
                            if (u.isNotEmpty()) {
                                val n = optString(o, "name").ifBlank { "线路${i + 1}" }
                                lines.add(Source(n, u))
                            }
                        }
                        if (lines.isNotEmpty()) {
                            val parentUrl = optString(json, "url").ifEmpty { lines[0].sourceUrl }
                            val parentName = optString(json, "name").ifEmpty { lines[0].sourceName }
                                .ifEmpty { "多线路源" }
                            val parent = Subscription(parentName, parentUrl).apply {
                                isMultiRepo = true
                                this.lines = ArrayList(lines)
                            }
                            mSubscriptions.add(parent)
                            added++
                        }
                    }
                } else {
                    val u = optString(json, "url").ifEmpty { optString(json, "api") }.ifEmpty { optString(json, "drpy") }
                    if (u.isNotEmpty()) {
                        mSubscriptions.add(Subscription(optString(json, "name").ifEmpty { "订阅" }, u))
                        added++
                    }
                }
            } catch (e: Throwable) {
                // 解析失败, 落到行解析
            }
        }
        // 2) JSON 数组
        if (added == 0 && t.startsWith("[")) {
            try {
                val arr = JsonParser.parseString(t).asJsonArray
                for (i in 0 until arr.size()) {
                    val el = arr[i]
                    if (el.isJsonObject) {
                        val o = el.asJsonObject
                        val u = optString(o, "url").ifEmpty { optString(o, "api") }.ifEmpty { optString(o, "sourceUrl") }
                        val n = optString(o, "name").ifEmpty { optString(o, "sourceName") }
                        if (u.isNotEmpty()) {
                            mSubscriptions.add(Subscription(n, u))
                            added++
                        }
                    } else if (el.isJsonPrimitive) {
                        val s = el.asString.trim()
                        if (s.startsWith("http") || s.startsWith("clan")) {
                            mSubscriptions.add(Subscription(s, s))
                            added++
                        }
                    }
                }
            } catch (e: Throwable) {
                // ignore
            }
        }
        // 3) 每行一个链接: "名称,链接" 或 直接链接
        if (added == 0) {
            val lines = t.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            for (line in lines) {
                val parts = line.split(",", "，", limit = 2)
                val (name, url) = if (parts.size == 2 && (parts[1].startsWith("http") || parts[1].startsWith("clan"))) {
                    parts[0].trim() to parts[1].trim()
                } else if (line.startsWith("http") || line.startsWith("clan")) {
                    "" to line
                } else {
                    continue
                }
                mSubscriptions.add(Subscription(name, url))
                added++
            }
        }
        if (added > 0) commitList()
        return added
    }

    override fun onDestroy() {
        super.onDestroy()
        OkGo.getInstance().cancelTag("get_subscription")
    }
}