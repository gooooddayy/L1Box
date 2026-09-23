# al 版 5 次崩溃深度分析（2026-09-14 am 修复依据）

## 一、现象
用户按「退出→重进→立刻搜索」口径多轮测试，**崩溃 5 次**，全部同签名：
`SIGABRT in NanoHttpd Reque 线程，JNI DETECTED ERROR obj == null`（DexNative.proxyInvoke）。

崩溃时间轴（logcat_al_20260914.log，138MB）：
- 12:57:16 pid 1531 / 12:57:31 pid 3108 / 12:57:58 pid 5655 / 12:59:04 pid 11553 / 12:59:19 pid 12830

## 二、决定性证据

### 1. 崩溃栈（tombstone，pid 1531）
```
Init.proxyInvoke → DexNative.proxyInvoke   ← jar 内部，obj == null
Proxy.proxy
JarLoader.proxyInvoke+102                   ← proxyFun.invoke 那一行（门禁被穿透）
ApiConfig.proxyLocal → RemoteServer.serve
```
**两道 503 门禁都没拦住** → 说明被打的 jar 通过了 `protectedJarKeys.contains(key)` 检查路径——它根本不在账本里。

### 2. killall 互杀实锤（exitCode=137）
```
pid 5655: 35.703 killall → 36.240 ready      （第1代 holder 启动）
          41.175 killall → 41.187 exitCode=137（上一代代理被杀！）
          43.437 killall → 43.454 exitCode=137
          46.834 killall → 46.850 exitCode=137
pid 12830: 13.572 killall → 13.587 exitCode=137 → 19.056 崩溃
```
单进程多轮代理重启 + 旧代理被 SIGKILL——**新 holder 启动会杀掉旧 holder 的代理**。

### 3. 进程存活模式
pid 5655 在 23 秒内 5 轮 killall——「退出」并不杀进程，「重进」复用同一进程重新 loadConfig。每次 loadConfig 都触发 JarLoader.load()。

## 三、根因（机制闭环）

`JarLoader.load()` 里的全局重置（`resetProxyFlight()` + 清 `protectedJarKeys`/`proxyHolderKey`）**没有区分实例**：

1. **homeJarLoader.load()（ApiConfig:554，home 配置加载时调用）也执行全局重置**——它清掉了静态账本，但清不掉**主实例**缓存的 `classLoaders` 和 `proxyMethods`。
2. 于是家族 jar A（原 holder）处于**"账本上没有、方法还能调"**的空洞状态：`protectedJarKeys` 不含 A → 门禁放行；`proxyMethods.get(A)` 还在 → 照常 invoke。
3. 账本清空后 GO_PROXY_STARTED=false，下一个家族 jar B 初始化时重新 startGoProxy → **killall 杀掉 A 的代理**（exitCode=137）→ A 的原生代理对象变 null。
4. /proxy 请求带着 recentJarKey=A 进来 → 门禁全过 → invoke A → **obj==null → SIGABRT**。

ak 为什么没崩：没有 warmUp 主动批量重载家族 jar，且时序未碰撞；al 的 warmUp 让"重进后马上有家族 jar 重新 init"成为必然，空洞窗口被稳定命中。

## 四、am 修复（三层）

| # | 修复 | 内容 |
|---|---|---|
| 1 | **身份终审（治本）** | `ProtectedInitJar` 新增 `FAMILY_LOADERS`（init 成功即登记 jar 的 ClassLoader，**永不随配置重载清空**）。`proxyInvoke` 在 invoke 前按 `Method` 的 declaringClass ClassLoader 终审：是家族 loader 且不是当前 holder → 一律 503。**ClassLoader 身份不会因 load() 清账而消失**——从数学上封死"打到非 holder 家族 jar" |
| 2 | **重置归位** | `JarLoader` 加 `mainLoader` 标记（构造器注入，主实例 `new JarLoader(true)`）；`load()` 的全局重置只准主实例做，homeJarLoader.load() 不再碰静态账本——空洞不再产生 |
| 3 | **预热让路** | warmUp 带代际 token（load() 推进 LOAD_GEN，过期任务瞬间退出）+ HomeFragment 两处预热延迟 4 秒挂载——修复"切线路后首页自动加载慢"（预热任务顶住单线程池） |

## 五、附带结论

### "本接口免费分享"弹窗
清扫器**仍在正常工作**：本会话 47 次全部移除（"本接口免费，请勿上当购买！"×28、"本接口免费分享！切勿上当！"×18、"软件接口免费 请勿受骗购买"×1），无一漏网。用户看到的"闪现"是主线程拥塞（崩溃重启+预热+16 并发回调）导致移除晚几帧，非拦截失效；崩溃修复+预热延后会显著缓解。

### 切线路后首页加载慢
warmUpSiteJars 在 loadConfig success 回调里即时排队，排在新线路首页 jar 装载**前面**，单线程池被预热任务占满。am 用"延迟 4 秒 + 代际失效"双保险解决。
