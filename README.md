# L1Box

基于 [TVBoxOSC](https://github.com/q215613905/TVBoxOSC) 的 **竖屏手机版** fork。

在保留原有影视聚合能力的基础上，针对**手机竖屏**使用场景做了大量体验与稳定性改造：
竖屏布局与手势、播放器作用域与内核选择、搜索链路可靠性、订阅源解析容错、播放起播与缓冲控制等。

- **包名**：`com.github.tvbox.osc`
- **版本**：`1.1.1`（versionCode 31）
- **编译**：`compileSdk 33` / `minSdk 24` / `targetSdk 28`

## 为什么 targetSdk 停在 28

本工程的 spider（影视源）以**加固 jar** 形式分发，其解密后的内层 dex 会写入可写目录再加载。
Android 10+ 对 `targetSdk >= 29` 的应用强制 W^X 限制，会导致该内层 dex 偶发加载被拒，
进而触发加固层自检失败并主动退出。28 是当前加固方案的兼容性最优解。

## 构建

| 组件 | 版本 |
|---|---|
| JDK | **11**（AGP 7.2.2 要求） |
| Gradle | 7.3.3（含 wrapper） |
| Android Gradle Plugin | 7.2.2 |
| Android SDK | platform 33 + build-tools 33.0.2 |

```bash
# 1. 准备 JDK 11（或在 gradle.properties 里指定 org.gradle.java.home）
# 2. 指向本机 Android SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
# 3. 构建
./gradlew assembleRelease
```

产物输出到 `app/build/outputs/apk/release/`。

> 注：`gradle.properties` 中的 `kotlin.compiler.execution.strategy=in-process` 是为低内存机器
> （Kotlin 守护进程原生内存分配失败）保留的稳妥设置，内存充足时可改回默认。

## 目录结构

```
app/                 主模块（竖屏 UI、搜索、订阅、播放页逻辑）
player/              dkplayer 播放器模块（缓冲接管、内核选择）
quickjs/             JS 引擎
crash/               崩溃捕获
TabLayout/           分类 Tab 组件
ViewPager1Delegate/  分类栏滑动委托
tools/fakeboot/      桌面 JVM 桩类（用于免真机跑订阅解析用例）
scripts/             构建、质量闸门与真机日志分析脚本
docs/                开发过程文档（方案、验证记录、踩坑）
```

## 关于 `scripts/` 与 `docs/`

`scripts/` 里是本项目开发期使用的构建、质量闸门与真机日志分析脚本，作为**参考**提供。
其中出现的 `<工作区>` / `<本机>` 是**路径占位符**（原为本机绝对路径），使用前请替换为你自己的路径；
`build_l1box.sh` 假定工作区下存在 `FreeBox-src/` 目录结构，与当前仓库布局不同，直接跑需要微调。

`docs/` 是开发过程的完整记录：方案设计、真机验证结论、踩坑与已否决方案。
其中设备序列号、局域网地址、私有接口域名与本机路径已做**脱敏处理**。

## 免责声明

本项目仅供学习与技术交流使用，不提供、不内置任何影视资源。
所有内容来源均由使用者自行配置的订阅源提供，与本项目无关。
请于下载后 24 小时内删除。

## 致谢

- [TVBoxOSC](https://github.com/q215613905/TVBoxOSC)
- [dkplayer](https://github.com/Doikki/DKVideoPlayer)
