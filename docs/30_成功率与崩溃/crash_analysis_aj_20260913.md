# L1Box aj 版崩溃深度分析（2026-09-13 18:47 / 18:48，2 次）

版本：`L1Box_v1.1.1_release_20260913aj.apk`（进程级单飞 + 持有者路由 + 中和器替身名合法化）
日志：`logcat_aj_20260913.log`（850,734 行，18:42~18:51）
场景：退出重进 → 立刻搜索（老口径）

---

## 一、一句话结论

**单飞闸门只管住了"被签名识别为加固"的 jar（Init + killProcess 特征）；但订阅里还有另一类同族变体——含 `DexNative` + `GoProxyManager` 但没有 killProcess 签名——`ProtectedInitJar.check()` 判它"普通 jar"，我们直接 `Init.init()`，jar 内部照样跑 `startBlockingForInit`（killall + 启动共享代理）。它们互相杀、还杀掉 holder 的代理；同时它们不在路由账本里，`/proxy` 照样能打到它们——对象为 null，进程 abort。防线的门开了一条缝，互杀链从缝里原样走了进来。**

---

## 二、实测数据（本会话 9 个进程，2 次崩溃）

| 指标 | 数值 | 说明 |
|---|---|---|
| `killall begin` | **27 次** | 单飞设计下每进程最多 1~2 次（holder + 重载），**绝大部分来自漏网 jar 的 Init.init** |
| `exitCode=137`（SIGKILL） | 20 次 | 互杀仍在发生 |
| `prepareResources` | 54 次 | |
| 漏网 jar 的 `Init.init→DexNative.getLoader` 栈 | **11,282 次** | 普通路径在大量执行 |
| 漏网 jar 内部代理端口探测失败 | 9051~9056 等几十个端口 × 72 次 | `ProxyOrigin.init` 连不上自己的代理 |
| `Guard jar 内部 loader 未就绪` | 11 次 | |

### 崩溃 #1（pid 19999，18:47:32）
```
18:47:19.771 health started=true        ← 起来了
18:47:23.752 killall begin              ← 被漏网 jar 杀了
18:47:28.905 health started=true        ← 又起来
18:47:29.862 health started=false       ← 再被杀 / 未就绪
18:47:32.048 Fatal signal 6             ← /proxy 打到 null
```
tombstone 回溯（决定性）：
```
#17 JarLoader.proxyInvoke → Method.invoke → jar Proxy → DexNative.proxyInvoke → obj==null
#19 ApiConfig.proxyLocal → #21 RemoteServer.serve（NanoHttpd Reque 线程）
```
**走到了 `proxyFun.invoke`，说明两道门禁（isNativeWindow / 账本 503）都没命中** —— 因为被打的 key 根本不在 `protectedJarKeys` 里（漏网 jar 从没进过受控路径）。

### 崩溃 #2（pid 24911，18:48:27）
```
18:48:22.106 health started=true
18:48:24.169 killall begin              ← 刚成功就被杀
18:48:26.305 killall begin              ← 最后一次 killall 后无 ready
18:48:27.955 Fatal signal 6             ← 空窗期内 /proxy 打进来
```
同进程 18:48:21 还有铁证：漏网 jar 的 `Init.init`（由 `getSpider ← searchResult FastSearchActivity.kt:557` 触发）内部 `ProxyOrigin.init` 连 `127.0.0.1:9977` 被拒 —— **普通路径确实在跑 jar 内部的代理初始化**。

---

## 三、为什么会漏（代码层根因）

`ProtectedInitJar.check()` 的加固判定 = **Init 类 + killProcess 调用签名**。这一判定把同族 jar 分成了两类：

| | A 类：加固 jar（有 kill 签名） | B 类：原生变体（无 kill 签名） |
|---|---|---|
| 结构 | Init + DexNative + GoProxyManager | Init(Origin) + DexNative + ProxyOrigin + GoProxyManager |
| check() | true → 手动绑定 + 单飞 + 账本 ✅ | **false → 普通 `Init.init()`** ❌ |
| jar 内代理启动 | 我们控制（每进程 1 次） | **jar 自己跑 startBlockingForInit（killall）** |
| /proxy 路由 | 账本 503 兜住 | **不在账本，直接放行** ❌ |

B 类 jar 的 `Init.init` 内部链条（真机栈实证）：
`Init.init → DexNative.getLoader → InitOrigin.init → ProxyOrigin.init → 连本地代理端口(8964/9977/905x)`，
以及 `GoProxyManager.startBinary killall begin → exec new_go_proxy_wex`。

**killall 是共享二进制层面的互杀，A 类 B 类的代理互相都是猎物** —— B 类启动时杀掉 A 类 holder 刚启动的代理，holder 的对象变 null；B 类之间也互相杀。`/proxy` 只要打到任何一个对象为 null 的 jar（A 类被杀的 holder、或 B 类没就绪的）就是 SIGABRT。

## 四、为什么"ab 之前没有"、现在这么频繁

- 机制（killall 互杀）自 09-08 起就在，**A 类 B 类两族都带**。
- ab（并发 10 + quick=false 全量搜索）把初始化**摊开**，"代理刚被杀"+"请求恰好打到那只"两个事件撞不上的概率大；ai/aj 的 16 并发 + quick 快速返回把密度顶上去，撞上的概率大增。
- 另一个变量：**当前订阅里 B 类 jar 的数量与位置**（搜索前排有多个 B 类 = 初始化挤在数秒内 + 封面图 /proxy 请求多）。用户感知"越改越频繁"主要是提速把潜伏概率兑现了，与本次改动本身无关（aj 已让 A 类零互杀，剩下全是 B 类贡献的 27 次 killall）。

---

## 五、修法（机制级，把"签名识别"改为"能力识别"）

**判据从"有没有 killProcess"改成"有没有 DexNative"** —— 只要 jar 里存在 `Lcom/github/catvod/spider/DexNative;`，就是原生引导型 jar，全部纳入受控路径：

1. **ProtectedInitJar**：`check()` 升级为三态分类 `classify(jar)`：
   - `0` 普通 jar（无 DexNative）→ 现有普通路径，零变化
   - `1` 加固 jar（DexNative + kill 签名）→ 现有 protected 路径
   - `2` 原生变体（仅 DexNative）→ **改走 protected 路径**（手动 bindContext + bindDexLoader + 单飞 startGoProxyOnce + 账本 + 门禁），**不再调用 Init.init**
2. **效果**：
   - B 类不再跑 jar 内部 `startBlockingForInit` → 27 次 killall 归零（只剩 holder 每进程 1 次）；
   - B 类的 DexNative.getLoader 由我们在 `bindDexLoader` 里调用（与 A 类同款，5 天验证过），内部 loader 照样就绪，spider 正常工作；
   - B 类的 Proxy 类进账本，非 holder 一律 503 —— **abort 面彻底封死**；
   - `isGuardJarBroken` 已有的 loader 就绪检查与手动绑定兼容，不会误跳。
3. **风险与边界**：B 类 `Init.init` 里若有 getLoader/ProxyOrigin 之外的其他初始化会缺失（catvod 系结构实测就是这两样，ProxyOrigin 连不上自己会 catch）；jar 内部若还有惰性再启动路径无法静态排除——但触发密度会大幅下降，且路由 503 兜底。
4. **闸门同步**：回归断言 + dex 符号（classify / hasDexNative 等）。

---

## 六、验证口径（不变）

退出软件 → 重新进入 → 立刻搜索，多轮；判据 `Fatal signal 6` = 0，且 A/B 两类 jar 的网盘源都能出内容。
