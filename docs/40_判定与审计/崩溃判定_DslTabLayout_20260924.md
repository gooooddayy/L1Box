# 崩溃判定：搜索页滑动触发 DslTabLayout 空指针（2026-09-24 21:32:29）

> 只做**原因判定**，未动任何代码（按项目纪律：测试期间不改码，方案待全部测完再出）。

---

## 一、崩溃事实（唯一一次）

| 项 | 值 |
|---|---|
| 时间 | `09-24 21:32:29.192` |
| 进程 | `com.github.tvbox.osc`（PID **29690**） |
| 线程 | **main**（主线程 → 必崩） |
| 异常 | `java.lang.NullPointerException: Parameter specified as non-null is null: method kotlin.jvm.internal.Intrinsics.checkNotNullParameter, parameter e1` |
| 崩溃点 | `com.angcyo.tablayout.DslTabLayout$_gestureDetector$2$1.onScroll` |
| 后续 | `CustomActivityOnCrash` 接管 → 跳 `DefaultErrorActivity`（项目内置崩溃兜底页） |

**调用链**（自下而上读）：

```
Choreographer.doFrame
 └ ViewRootImpl$ConsumeBatchedInputRunnable.run
   └ InputEventReceiver.consumeBatchedInputEvents      ← 批处理输入（关键）
     └ DslTabLayout.onInterceptTouchEvent(DslTabLayout.kt:1542)
       └ GestureDetectorCompat.onTouchEvent
         └ GestureDetector.onTouchEvent(GestureDetector.java:724)
           └ onScroll(e1=null, e2=…, …)                ← 传进来就是 null
             └ Intrinsics.checkNotNullParameter → NPE
```

---

## 二、崩溃时在做什么（日志还原）

| 时间 | 事件 |
|---|---|
| `21:31:00.950` | 在 `DetailActivity` 按返回键（`dispatchKeyEvent … will call onBackPressed`） |
| `21:31:00.962` | `FastSearchActivity` 可见性切回 true（回到搜索页） |
| `21:31:00` ~ `21:32:29` | 停留在**搜索页结果列表**（本项＝"图片"测试所在页面） |
| `21:32:29.192` | **一次批处理输入事件派发时崩** |
| `21:32:29.356` | 系统焦点切到 `DefaultErrorActivity`（崩溃提示页） |

---

## 三、根因（定位到具体代码行）

### 3.1 出问题的源码

`FreeBox-src/TabLayout/src/main/java/com/angcyo/tablayout/DslTabLayout.kt:1511`

```kotlin
override fun onScroll(
    e1: MotionEvent,          // ← 声明成「非空」
    e2: MotionEvent,          // ← 同上
    distanceX: Float,
    distanceY: Float
): Boolean {
```

`GestureDetector.SimpleOnGestureListener` 是 **Java** 类，参数是**平台类型**（Java 没有非空概念）。
Kotlin 重写时把它写成了**非空** ⇒ 编译器自动插入
`Intrinsics.checkNotNullParameter(e1, "e1")` ⇒ 一旦实参为 null 立刻抛 NPE。

同一个 `_gestureDetector` 里的 `onFling(e1: MotionEvent, e2: MotionEvent, …)`（`DslTabLayout.kt:1490`）**有完全一样的隐患**，只是这次没走到。

### 3.2 框架为什么传 null

Android 框架 `GestureDetector.onTouchEvent()`：

- 收到 `ACTION_CANCEL` ⇒ 走内部的 `cancel()`，其中把 `mCurrentDownEvent` **recycle 并置 null**；
- 但 `mLastFocusX / mLastFocusY` **不重置**；
- 之后若再来 `ACTION_MOVE`，位移量 `abs(scrollX) >= 1` 判定照样成立 ⇒ 执行
  `mListener.onScroll(mCurrentDownEvent /* = null */, ev, scrollX, scrollY)`（即 `GestureDetector.java:724`）。

**堆栈里的 `consumeBatchedInputEvents` 是关键证据** —— 它说明 CANCEL 与其后的 MOVE 是**在同一批（batched）输入里一起送到**的，所以 CANCEL 刚把 `mCurrentDownEvent` 置 null，紧接着的 MOVE 就踩着它进来了。

### 3.3 为什么是"偶发、只崩一次"

必须三个条件同时凑齐：

1. `needScroll == true`（Tab 内容超出可视区，才会喂手势给 `_gestureDetector`）；
2. 同一次手势里 `_gestureDetector` 先收到 **ACTION_CANCEL**；
3. 紧随其后**同一批**里还有 MOVE，且位移 ≥ 1px。

手指快速挥动、食指/拇指跨过控件边界、父容器中途抢事件（`requestDisallowInterceptTouchEvent`）时才容易凑齐 —— 所以是概率性触发，不是每次滑动都崩。

**这次为什么凑齐**：搜索页左侧那个 `DslTabLayout` 是**竖排站点列表**（`tab_orientation="VERTICAL"`，宽 `120dp`、高 `match_parent`，站点多 ⇒ `needScroll` 恒 true）。在结果列表上滑动时，手指很容易按到/划过左侧这条竖栏。

---

## 四、与本轮（bv）改动的关系：**无关**

三条独立证据：

1. **堆栈里零命中本轮改动**：`FastSearchAdapter` / `OkGoHelper` / `Picasso` / `RemoteServer` / `SourceViewModel` / `FastSearchActivity` 的代码**一个都没出现在栈里**——崩溃全程只在第三方库和框架代码里跑。
2. **这段库代码不是我们写的**：`git log -- TabLayout/src/main/java/com/angcyo/tablayout/DslTabLayout.kt` 只有一条上游初始提交 `f834e74`，且 `git status` 对该目录**干净** ⇒ `onScroll` 的非空签名是**上游原版**。
3. **bu → bv 的差异里没有任何触摸/Tab 相关改动**（本轮是搜索收敛 + 转发端点 + 图片口径 + 字色）。

⇒ 判定为**存量缺陷**，与版本无关。

---

## 五、影响面：两个页面都有

| 页面 | 布局 | 方向 | 风险 |
|---|---|---|---|
| 搜索页 | `activity_fast_search.xml:130` | `VERTICAL`（左侧站点竖栏） | **本次崩在这** |
| 首页 | `fragment_home.xml:96` | 默认横向（分类栏） | 同一段库代码，同样有隐患 |

两处共用同一个 `DslTabLayout` 类 ⇒ **修一处即覆盖两页**。

---

## 六、历史对照：首次触发，不是"一直在崩"

| 证据源 | 覆盖时段 | 结论 |
|---|---|---|
| `_a2_v1.log`（556MB 全量） | 20:4x ~ 21:26 | `FATAL EXCEPTION` **0 条**；`DslTabLayout` 仅命中 18 行，全是无害的 `index out of list.` |
| 系统 **crash buffer** | 长期 | 唯一的 app 崩溃就是 `21:32:29`；另一条 `15:21:53` 是系统进程（pid 1922）的 native tombstone，与本 App 无关 |
| 本轮扫描（21:31–21:33 上下文） | — | 无 ANR、无 OOM、无 lmkd 杀进程 |

⇒ **本次会话首次命中**；也说明它不是"每次用都会崩"，而是需要上述三条件凑齐。

---

## 七、可修性（只陈述，不执行）

- 该库**不是外部 aar，而是本仓库内的源码模块**（`app/build.gradle:168` `implementation project(":TabLayout")`）⇒ 可以直接改。
- 修法方向明确：让 `onScroll` / `onFling` 的 `e1`/`e2` 容忍 null（Java 平台类型允许 Kotlin 重写为可空），或对 null 直接早退。
- **本轮不动** —— 按纪律，等剩余测试项跑完、统一出方案后再说。

---

## 八、待你确认 / 待办

1. 【**必答**】"图片"这一项本身的结果：**搜索页海报有没有加载出来**？（这是本轮主修之一，崩溃是附带发现的，别被它盖过去）
2. 【可选】崩溃前你是不是**手指划过搜索页左侧那条竖着的站点名列表**？（能进一步坐实触发路径）
3. 【可选】崩溃前你在结果列表里滚动得多不多？滚动手势越猛越容易凑齐条件。

---

## 九、崩溃后的状态（21:32:40 ~ 21:42:30 全段核对）

| 时间 | 事件 |
|---|---|
| `21:32:40.304` | `error_activity` 进程退出（你关掉了崩溃提示页） |
| `21:32:40.558` | **App 自动重建**（`从本地缓存加载 /data/user/0/…/files/d5e026a8…`），pid `29690` → **`758`** |
| `21:32:45` ~ `21:33:01` | 重新发起搜索（`SourceViewModel.getSearch` 正常跑） |
| `21:32:59` / `21:33:04` / `21:35:38` / `21:42:28` | **4 次进入 `PlayFragment`**（＝E3 续播浮层的操作路径，与你说"提示外观也测过了"吻合） |
| `21:42:23` ~ `21:42:25` | 又在搜索 |
| 全段 | **无第二次 FATAL**、无 OOM、无 lmkd 杀进程 |

结论：**崩溃后 App 自恢复**，你接着正常用（不需要重装/清数据）；本次会话 app 总共只崩这一次。

两个"看起来像问题、其实不是"的旁支，一并排除：

- `21:33:37` 有一条 `ANR_LOG` ⇒ **属于别的进程（pid 2540）**，与本 App 无关。
- `21:32:45` 起大量 `System.err` 栈（`ApiConfig.getCSP` → `SourceViewModel.getSearch`）⇒ **是老现象**：老日志（18:17~21:26）里 `getCSP` 出现 **9291 次**、`System.err` **58628 次**，首条是 `org.json.JSONException: End of input at character 0 of`（站点返回空内容）。属"某些站点本机跑不通"的常态化输出，**与本次崩溃无关**。

---

## 附：本次为判定生成的物料（可删）

| 文件 | 说明 |
|---|---|
| `_bv_crash_hits.txt` | 47MB，崩溃现场流式扫描结果 |
| `_bv_ctx.txt` | 1.3MB，21:31–21:33 上下文 |
| `_bv_crashbuf.txt` | 8KB，系统 crash buffer 原文 |
| `_bv_crash_scan.py` / `_bv_ctx_scan.py` | 扫描脚本（**留用**，以后判崩溃可直接复用） |
