# av 全场景测试 · 用户动作级会话表

日志：`logcat_ax_test_20260917.log`　埋点区间：14:10:53.218 ~ 14:21:20.754

- 用户动作（点播）次数：**11**
- 起播就绪：**10**　失败：**1**　结果未留痕（用户提前离开）：**0**
- 起播成功率：**90.9%**（口径 ≥95%）
- 可算观看时长 9 段：≥30s **8** 段，中位 65.9s，最短 6.6s，最长 114.0s
- 不足 30s 的片段：14:21:09.095 7s
- 埋点跨度：10 分钟

| # | 时间 | 站点 | 集 | 结果 | 说明 |
|---|------|------|---|------|------|
| 1 | 14:10:53.218 | 热播影视 | 0 | ✅就绪 | 688ms<br>净化无需处理 158ms<br>url=http://111.170.9.52:9090/nby/m3u8/getM3u8?… |
| 2 | 14:12:07.056 | WexAiYueYue | 0 | ❌失败 | 出链 reason=自动兜底试尽，转为明确提示 ms=13<br>内含自动重试×2<br>净化预取失败(本地代理地址/HttpException)<br>内置降级→IJK播放器<br>14:12:14.708 自动重取<br>净化预取失败(本地代理地址/HttpException)<br>14:12:17.450 自动重取<br>净化预取失败(本地代理地址/HttpException)<br>url=http://127.0.0.1:9978/proxy?… |
| 3 | 14:12:30.831 | 剧圈99 | 0 | ✅就绪 | 3010ms<br>净化无需处理 3001ms<br>url=https://vip.ffzy-play6.com/20221203/9519_d07be |
| 4 | 14:14:30.861 | 苹果 | 0 | ✅就绪 | 576ms<br>净化无需处理 207ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/XYwRS-- |
| 5 | 14:15:52.422 | 天堂 | 0 | ✅就绪 | 176ms<br>净化无需处理 52ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/XYwRS-- |
| 6 | 14:16:42.595 | 华谊 | 0 | ✅就绪 | 208ms<br>净化无需处理 79ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/XYwRS-- |
| 7 | 14:17:48.888 | WexAiReBo | 0 | ✅就绪 | 472ms<br>净化无需处理 474ms<br>url=http://43.248.96.62:9090/nby/m3u8/getM3u8?… |
| 8 | 14:18:39.415 | WexAiGuaZi | 0 | ✅就绪 | 393ms<br>净化无需处理 332ms<br>url=https://vd.wmvbo.com/ff3d79b6012…/index.m3u8 |
| 9 | 14:19:45.421 | WexAiDuBoKu | 0 | ✅就绪 | 1898ms<br>净化命中→本地HLS 3412ms<br>url=https://vid.dbokutv.com/20260730…/chunklist.m3 |
| 10 | 14:21:08.173 | Rebo | 0 | ✅就绪 | 454ms<br>净化无需处理 193ms<br>url=http://43.248.96.62:9090/nby/m3u8/getM3u8?… |
| 11 | 14:21:15.699 | AppV7 | 0 | ✅就绪 | 2524ms<br>净化无需处理 1970ms<br>url=http://<订阅接口地址已隐去> |
