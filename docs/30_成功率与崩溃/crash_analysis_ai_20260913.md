# L1Box ai 版真机崩溃分析（2026-09-13 16:51~16:52）

版本：`L1Box_v1.1.1_release_20260913ai.apk`（并发 16 + 门禁 800ms + 进程级串行锁）
日志：`logcat_ai_20260913.log`（631,378 行，覆盖 16:43~16:56）
场景：**退出软件再进入 → 第一次搜索**（冷启动首轮）

---

## 一、结论（一句话）

**不是我们这几版代码写坏了，而是加固 jar 的 SDK 把"Go 代理"当成进程内独占资源：它用固定路径的同一个二进制，且每次启动都先 `killall` 掉在跑的实例。我们的源池里有多个加固 jar，各带一份 SDK 副本、各启动一次 → 后启动的把先启动的杀掉 → 某个 jar 的健康检查循环盯着一个已经被杀的端口永远失败 → 它的代理对象保持 null → 任何 `/proxy` 请求打到它 → native 直接 abort 整个进程。**

---

## 二、实测数据

| 指标 | 数值 |
|---|---|
| 进程级崩溃 | **3 次**（`Fatal signal 6` × 3） |
| 崩溃时间 | 16:51:46.328 / 16:52:12.309 / 16:52:30.917 |
| 崩溃线程 | 均为 `NanoHttpd Reque` |
| 崩溃签名 | `JNI DETECTED ERROR: obj == null in call to CallObjectMethod from DexNative.proxyInvoke` |
| 代理启动次数（`prepareResources`） | 17 |
| 代理进程被 kill（`exitCode=137`） | **10** |
| `killall begin` | **17**（每次启动前都杀一遍） |
| 代理二进制路径 | `files/AiWex/new_go_proxy_wex`，**全场唯一一条路径** |
| 健康检查 成功/失败 | 13 / 19 |
| 加固 jar 被跳过（内部 loader 未就绪） | 9 |
| 新 DexClassLoader 创建 | 首轮搜索 16:41 = **26 个**；第 2、3 轮 = 3 个 |

共用的 SDK 目录（同一份）：`files/AiWex/` 下的 `new_go_proxy_wex`(124 次引用)、`libdecjni.so`(33)、`siteconfig`(6)、`logs`(14)。

---

## 三、决定性证据链

### 1. 一个进程里多次启动同一个共享代理

`GoProxyManager.startBinary killall begin` → 紧接着 `execNoHup exitCode=137`（128+9=SIGKILL）。
全场 17 次 killall、10 次进程被杀，**路径全是同一个 `new_go_proxy_wex`**。

### 2. 死端口循环（pid 22375，崩溃 #1）

```
16:51:41.207  config loaded                      ← 启动 #1
16:51:41.230  startBinary killall begin          ← 先杀在跑的代理
16:51:41.246  waitHealth try=1  ok=false host=29618   ← 循环 A 盯 29618
16:51:41.249  execNoHup exitCode=137             ← 某个代理进程被杀
16:51:41.254  startBinary selectedPort=43521     ← 启动 #2 换端口
16:51:41.315  listener ready port=43521
16:51:43.997  startBinary selectedPort=38044     ← 启动 #3
16:51:44.067  listener ready port=38044
16:51:46.170  waitHealth try=10 ok=false host=29618   ← 循环 A 仍在查从未 ready 的 29618
16:51:46.690  waitHealth try=11 ok=false host=29618
16:51:46.328  Fatal signal 6（进程崩）
```

**29618 从头到尾没有 `listener ready`** —— 它启动的子进程在 41.249 被 137 杀掉了，而它的健康检查循环还活着，一直被吊着。

### 3. 端口记录被覆盖（pid 25204，崩溃 #3）

```
16:52:25.174  waitHealth try=0 ok=false host=26930
16:52:28.079  waitHealth try=1 ok=true  host=23300   ← 同一个循环，端口被换成 23300
```

同一个循环内目标端口自己变了 → 共享的端口字段被另一次启动改写。

### 4. 刚启动成功就被杀（pid 23726，崩溃 #2）

```
16:52:01.324  try=0 host=31944 → 01.831 try=1 ok=true → health started=true   ← 这一个成功了
16:52:10.959  config loaded（第 3 次启动）
16:52:11.006  execNoHup exitCode=137                                        ← 刚成功那个被杀
16:52:12.309  崩溃
```

---

## 四、为什么"第一次测试不崩、退出重进才崩"

| 时段 | 进程 | 代理启动 | 被杀 | 崩溃 |
|---|---|---|---|---|
| 16:37~16:45（第一轮冷启动） | 13210 / 14172 | 9 次 | 5 次 | **0** |
| 16:51~16:52（退出重进） | 22375 / 23726 / 25204 | 8 次 | 5 次 | **3** |

同样都是"多实例互杀"，只是**第一轮没撞上时序**：崩溃需要「某 jar 的代理被杀」和「有请求打到那个 jar」两件事对上。退出重进时所有加固 jar 要在几秒内全部首次初始化，启动最密集、被杀最频繁，同时首个搜索请求刚好进来 → 概率显著抬高。

**所以这版不是"修好了"，是"上次没抽中"。** 我上一轮把 0 崩溃当成结论，这个判断是错的，收回。

---

## 五、我们三道防线为什么没挡住

| 防线 | 为什么无效 |
|---|---|
| `NATIVE_INIT_LOCK` 进程级串行锁 | 它只保证**我们的调用**排队，不能阻止第二个 jar **杀掉已在运行的代理进程**（那是 jar 内部行为） |
| `GO_PROXY_STARTED` 单飞 | 键是 **jar 自己的 Class**，每个 jar 一份 → 每个 jar 都会启动一次，等于没有跨 jar 去重 |
| `proxyQuietUntil` 800ms 门禁 | 只覆盖 init 后 800ms；本次崩溃发生在 init 之后 2~6 秒，门禁早已过期 |
| 路由 | `ApiConfig.proxyLocal → jarLoader.proxyInvoke`，**不做 jar 识别**，只按 `recentJarKey`（最近用过的那只）转发 → 被杀的 jar 恰好就是"最近"的那只时，必崩 |

---

## 六、顺带查出的第二个缺陷（独立问题）

```
JarKillNeutralizer: 已中和 jar 自杀调用 ae48218142817cd9759e0bd80b97d7de.jar
Failed to open dex files from .../ae48218142817cd9759e0bd80b97d7de.jar
because: Failure to verify dex file: Invalid method name: 'Landroid/os/Process;'
→ ClassNotFoundException: com.github.catvod.spider.Init
```

- 我们的中和器把自杀方法名替换成一个**类描述符字符串**，Dex 校验器在**加载时**就判非法 → **整个 dex 被拒**。
- 全场 `ClassNotFoundException(Init)` 13 次。
- 后果：这些加固 jar 的官方初始化路径彻底走不通（防守过度），且被改坏的 jar 就在磁盘上，每个新进程都再失败一次。
- 修法很小：替身改用**合法方法名**（如 `toString`），运行时才抛 `NoSuchMethodError`。

此缺陷与崩溃是**两条独立的线**，但都指向同一批加固 jar。

---

## 七、修法（与具体源无关，是机制级）

你提醒的"这个软件不止这一个源链接"是对的——但这两个缺陷都出在**加固 SDK 的共享机制**上，跟是哪个源、哪个订阅无关：**任何订阅里只要同时存在 ≥2 个加固 jar，就会命中。**

| 方案 | 做什么 | 代价 / 风险 |
|---|---|---|
| **A（推荐，治本）** | 进程级单飞：`GO_PROXY_STARTED` 改为**进程级**（不再按 jar Class）→ 第一个加固 jar 完整 init（含启动代理），后续加固 jar **只绑定 Context + 内部 loader，跳过启动代理**；并让 `/proxy` 只路由到"代理持有者"那一只 | 后续加固 jar 若依赖自己的端口记录，可能降级（**不崩**）。改动集中在一个文件 |
| **B（最稳，代价明确）** | 一个进程只保留一个加固 jar 的站点，其余加固 jar 拒载（复用现有"干净跳过"通路） | 彻底消除多实例，但会少掉其他加固 jar 的源 |
| **C（最小改动）** | 只做 A 的前半（进程级单飞），不动路由 | 改动最小；若 SDK 还有"惰性重复启动"路径，可能仍有残留概率 |
| **D（对照）** | 退回无防护 + 并发 10 | 崩溃概率回到 8 月初水平（偶发），不推荐 |

另外建议无论选哪个，都顺手把中和器的非法方法名修掉（第六节）。

---

## 八、待办与验证口径

1. 等你拍板 A / B / C。
2. 出包后在真机上按「**退出软件 → 重新进入 → 立刻搜索**」这个路径跑——这才是能复现的路径，之前连续搜索的路径测不出来。
3. 判据：`Fatal signal 6` = 0；同时确认加固 jar 的网盘源仍可用（不能只换到"不崩但少源"）。
