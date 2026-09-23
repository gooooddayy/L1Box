# av 全场景测试 · 用户动作级会话表

日志：`logcat_av_test_20260915.log`　埋点区间：20:19:51.392 ~ 21:20:31.879

- 用户动作（点播）次数：**43**
- 起播就绪：**36**　失败：**5**　结果未留痕（用户提前离开）：**2**
- 起播成功率：**83.7%**（口径 ≥95%）
- 可算观看时长 36 段：≥30s **32** 段，中位 33.9s，最短 0.5s，最长 903.9s
- 不足 30s 的片段：21:13:31.960 0s、21:17:21.513 4s、21:16:42.935 5s、21:13:09.630 22s
- 埋点跨度：61 分钟

| # | 时间 | 站点 | 集 | 结果 | 说明 |
|---|------|------|---|------|------|
| 1 | 20:19:51.393 | Rebo | 0 | ✅就绪 | 560ms<br>净化命中→本地HLS 203ms<br>url=http://43.248.96.62:9090/nby/m3u8/getM3u8?… |
| 2 | 20:20:41.922 | Rebo | 0 | ✅就绪 | 225ms<br>净化命中→本地HLS 197ms<br>url=http://111.170.9.52:9090/nby/m3u8/getM3u8?… |
| 3 | 20:21:16.258 | Rebo | 0 | ✅就绪 | 183ms<br>净化命中→本地HLS 194ms<br>url=http://43.248.96.62:9090/nby/m3u8/getM3u8?… |
| 4 | 20:21:50.753 | Rebo | 0 | ✅就绪 | 251ms<br>净化命中→本地HLS 152ms<br>url=http://111.170.9.52:9090/nby/m3u8/getM3u8?… |
| 5 | 20:22:26.079 | Rebo | 0 | ✅就绪 | 186ms<br>净化命中→本地HLS 133ms<br>url=http://111.170.141.203:9090/nby/m3u8/getM3u8?… |
| 6 | 20:23:00.558 | Rebo | 0 | ✅就绪 | 311ms<br>净化命中→本地HLS 509ms<br>url=http://43.248.96.62:9090/nby/m3u8/getM3u8?… |
| 7 | 20:23:35.126 | Rebo | 0 | ✅就绪 | 247ms<br>净化命中→本地HLS 534ms<br>url=http://43.248.96.62:9090/nby/m3u8/getM3u8?… |
| 8 | 20:24:09.538 | Rebo | 0 | ✅就绪 | 246ms<br>净化命中→本地HLS 731ms<br>url=http://43.248.96.62:9090/nby/m3u8/getM3u8?… |
| 9 | 20:24:44.243 | Rebo | 0 | ✅就绪 | 251ms<br>净化命中→本地HLS 159ms<br>url=http://111.170.141.203:9090/nby/m3u8/getM3u8?… |
| 10 | 20:25:50.913 | Rebo | 0 | ✅就绪 | 208ms<br>净化命中→本地HLS 156ms<br>url=http://43.248.96.62:9090/nby/m3u8/getM3u8?… |
| 11 | 20:26:34.033 | Rebo | 1 | ❌失败 | 取链 reason=站点未返回播放信息 ms=-1<br>内含自动重试×1<br>20:26:34.128 自动重试 |
| 12 | 20:27:57.834 | Rebo | 2 | ✅就绪 | 286ms<br>净化命中→本地HLS 153ms<br>url=http://111.170.9.17:9090/nby/m3u8/getM3u8?… |
| 13 | 20:29:36.906 | Rebo | 3 | ❌失败 | 取链 reason=站点未返回播放信息 ms=-1<br>内含自动重试×1<br>20:29:37.015 自动重试 |
| 14 | 20:49:43.335 | WexAiYueYue | 0 | ⚠️无留痕 | 净化预取失败(本地代理地址/IOException)<br>变形重试 https://127.0.0.1:9978/proxy?…<br>看门狗 起播 20 秒无进展，再复核 10 秒<br>url=http://127.0.0.1:9978/proxy?… |
| 15 | 20:50:12.497 | AppV7 | 0 | ✅就绪 | 2428ms<br>url=https://lf26-csp-sign.bytetos.com/tos-cn-v-5f7 |
| 16 | 20:50:50.419 | AppV7 | 0 | ⚠️无留痕 | 净化无需处理 372ms<br>变形重试 http://<下载地址已隐去><br>url=https://<下载地址已隐去> |
| 17 | 20:51:16.742 | AppV7 | 0 | ✅就绪 | 1164ms<br>url=https://ykj-eos-wx2-01.eos-wuxi-3.cmecloud.cn/ |
| 18 | 20:51:52.029 | AppV7 | 1 | ✅就绪 | 1128ms<br>url=https://ykj-eos-wx2-01.eos-wuxi-3.cmecloud.cn/ |
| 19 | 20:52:28.904 | AppV7 | 2 | ✅就绪 | 1083ms<br>url=https://ykj-eos-wx2-01.eos-wuxi-3.cmecloud.cn/ |
| 20 | 20:53:07.199 | AppV7 | 3 | ✅就绪 | 1097ms<br>url=https://ykj-eos-wx2-01.eos-wuxi-3.cmecloud.cn/ |
| 21 | 20:53:41.515 | AppV7 | 4 | ✅就绪 | 1078ms<br>url=https://ykj-eos-wx2-01.eos-wuxi-3.cmecloud.cn/ |
| 22 | 20:54:17.731 | WexAiReBo | 0 | ✅就绪 | 860ms<br>净化无需处理 257ms<br>url=http://111.170.9.17:9090/nby/m3u8/getQyM3u8?… |
| 23 | 20:54:52.269 | AppV7 | 0 | ✅就绪 | 3132ms<br>净化无需处理 220ms<br>url=http://<订阅接口地址已隐去> |
| 24 | 20:55:28.443 | AppV7 | 1 | ✅就绪 | 2187ms<br>净化无需处理 1414ms<br>url=http://<订阅接口地址已隐去> |
| 25 | 20:56:46.546 | AppV7 | 2 | ✅就绪 | 1583ms<br>净化无需处理 936ms<br>url=http://<订阅接口地址已隐去> |
| 26 | 20:57:22.639 | AppV7 | 3 | ✅就绪 | 2025ms<br>净化无需处理 1407ms<br>url=http://<订阅接口地址已隐去> |
| 27 | 20:57:59.589 | AppV7 | 4 | ✅就绪 | 2583ms<br>净化无需处理 529ms<br>url=http://<订阅接口地址已隐去> |
| 28 | 21:13:08.592 | 华谊 | 0 | ✅就绪 | 677ms<br>净化无需处理 251ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/XYwRS-- |
| 29 | 21:13:31.559 | 华谊 | 0 | ✅就绪 | 280ms<br>净化无需处理 75ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/XYwRS-- |
| 30 | 21:13:32.418 | 华谊 | 1 | ✅就绪 | 495ms<br>净化无需处理 78ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/RkBmTi0 |
| 31 | 21:14:14.234 | 华谊 | 2 | ✅就绪 | 385ms<br>净化无需处理 143ms<br>url=https://onvideo-safety.ssscdn.com/ksc2/XHZmllV |
| 32 | 21:14:47.746 | 华谊 | 3 | ✅就绪 | 368ms<br>净化无需处理 85ms<br>url=https://onvideo-safety.ssscdn.com/ksc2/hvj4HJW |
| 33 | 21:15:21.929 | 华谊 | 4 | ✅就绪 | 403ms<br>净化无需处理 92ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/3FWUyzt |
| 34 | 21:15:57.780 | 天堂 | 0 | ✅就绪 | 292ms<br>净化无需处理 65ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/XYwRS-- |
| 35 | 21:16:42.314 | 天堂 | 1 | ✅就绪 | 342ms<br>净化无需处理 81ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/RkBmTi0 |
| 36 | 21:16:47.636 | 热播影视 | 2 | ✅就绪 | 662ms<br>净化无需处理 164ms<br>url=http://43.248.96.62:9090/nby/m3u8/getM3u8?… |
| 37 | 21:17:21.044 | 苹果 | 0 | ✅就绪 | 284ms<br>净化无需处理 66ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/XYwRS-- |
| 38 | 21:17:25.127 | 苹果 | 1 | ✅就绪 | 409ms<br>净化无需处理 63ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/RkBmTi0 |
| 39 | 21:18:04.680 | 剧圈99 | 0 | ✅就绪 | 6030ms<br>净化无需处理 2212ms<br>url=https://vod.feifei-kan.com/20230205/7342_c99e9 |
| 40 | 21:19:06.383 | 苹果 | 0 | ✅就绪 | 1378ms<br>url=https://hcybf06.eos-huhehaote-5.cmecloud.cn/K4 |
| 41 | 21:19:43.234 | 苹果 | 0 | ❌失败 | 出链 reason=自动兜底试尽，转为明确提示 ms=23<br>内含自动重试×1<br>内置降级→IJK播放器<br>21:19:49.543 自动重试<br>用户手动重试<br>url=Ksvideo-aed463fa76ffb834f1aa33814f230f50bfd49c |
| 42 | 21:19:58.991 | 苹果 | 0 | ❌失败 | 出链 reason=自动兜底试尽，转为明确提示 ms=30<br>内含自动重试×1<br>内置降级→IJK播放器<br>21:20:05.271 自动重试<br>url=Ksvideo-aed463fa76ffb834f1aa33814f230f50bfd49c |
| 43 | 21:20:25.437 | 天堂 | 0 | ❌失败 | 出链 reason=自动兜底试尽，转为明确提示 ms=19<br>内含自动重试×1<br>内置降级→IJK播放器<br>21:20:31.669 自动重试<br>url=Ksvideo-aed463fa76ffb834f1aa33814f230f50bfd49c |
