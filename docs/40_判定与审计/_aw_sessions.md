# av 全场景测试 · 用户动作级会话表

日志：`logcat_aw_test_20260917.log`　埋点区间：12:35:51.440 ~ 13:00:56.298

- 用户动作（点播）次数：**36**
- 起播就绪：**24**　失败：**12**　结果未留痕（用户提前离开）：**0**
- 起播成功率：**66.7%**（口径 ≥95%）
- 可算观看时长 24 段：≥30s **20** 段，中位 53.2s，最短 2.0s，最长 115.1s
- 不足 30s 的片段：12:37:15.778 2s、12:47:26.997 3s、12:45:17.931 4s、12:56:03.547 8s
- 埋点跨度：25 分钟

| # | 时间 | 站点 | 集 | 结果 | 说明 |
|---|------|------|---|------|------|
| 1 | 12:35:51.440 | 剧圈99 | 0 | ✅就绪 | 1825ms<br>净化无需处理 1636ms<br>url=https://yzzy.play-cdn22.com/20240507/1076_45ae |
| 2 | 12:36:26.855 | 剧圈99 | 1 | ✅就绪 | 1526ms<br>净化无需处理 924ms<br>url=https://yzzy.play-cdn22.com/20240507/1075_6a56 |
| 3 | 12:37:14.969 | 天堂 | 0 | ✅就绪 | 462ms<br>净化无需处理 184ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/e-2qDJA |
| 4 | 12:37:17.742 | 天堂 | 1 | ✅就绪 | 383ms<br>净化无需处理 202ms<br>url=https://onvideo-safety.ssscdn.com/ksc2/8atp6UX |
| 5 | 12:38:29.091 | 天堂 | 2 | ✅就绪 | 306ms<br>净化无需处理 240ms<br>url=https://onvideo-safety.ssscdn.com/ksc2/tANRCpX |
| 6 | 12:39:59.808 | 天堂 | 3 | ✅就绪 | 688ms<br>净化无需处理 163ms<br>url=https://onvideo-safety.ssscdn.com/ksc2/-Lz4ij4 |
| 7 | 12:41:03.791 | 天堂 | 4 | ✅就绪 | 899ms<br>净化无需处理 294ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/MG8lyIp |
| 8 | 12:42:28.513 | 天堂 | 5 | ✅就绪 | 367ms<br>净化无需处理 244ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/4bHHXDM |
| 9 | 12:43:29.949 | 天堂 | 6 | ✅就绪 | 676ms<br>净化无需处理 286ms<br>url=https://onvideo-safety.ssscdn.com/ksc2/KT-GMnk |
| 10 | 12:44:19.022 | 天堂 | 7 | ✅就绪 | 341ms<br>净化无需处理 287ms<br>url=https://onvideo-safety.ssscdn.com/ksc2/XI0_ipz |
| 11 | 12:45:17.093 | 苹果 | 0 | ✅就绪 | 428ms<br>净化无需处理 308ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/e-2qDJA |
| 12 | 12:45:21.783 | 苹果 | 1 | ✅就绪 | 416ms<br>净化无需处理 110ms<br>url=https://onvideo-safety.ssscdn.com/ksc2/8atp6UX |
| 13 | 12:46:22.187 | 热播影视 | 0 | ❌失败 | 取链 reason=站点未返回播放信息 ms=-1<br>内含自动重试×3<br>12:46:22.381 自动重取<br>用户手动重试<br>12:46:26.355 自动重取<br>12:46:26.398 自动重取 |
| 14 | 12:46:29.490 | 热播影视 | 1 | ❌失败 | 取链 reason=站点未返回播放信息 ms=-1<br>内含自动重试×1<br>12:46:29.563 自动重取 |
| 15 | 12:46:31.891 | 热播影视 | 1 | ✅就绪 | 2432ms<br>净化命中→本地HLS 497ms<br>url=https://v.lzcdn25.com/20250727/3348_a5bcb7c3/i |
| 16 | 12:47:25.646 | 苹果 | 0 | ✅就绪 | 231ms<br>净化无需处理 119ms<br>url=https://onvideo-safety.ssscdn.com/ksc2/bPKwRhQ |
| 17 | 12:47:30.064 | 苹果 | 1 | ✅就绪 | 244ms<br>净化无需处理 45ms<br>url=https://onvideo-safety.ssscdn.com/ksc2/4ql9kUD |
| 18 | 12:48:24.191 | 剧圈99 | 0 | ✅就绪 | 2576ms<br>净化无需处理 1785ms<br>url=https://vid.dbokutv.com/20230408/qqPoibnu/inde |
| 19 | 12:49:09.355 | 天堂 | 0 | ✅就绪 | 206ms<br>净化无需处理 28ms<br>url=https://onvideo-safety.ssscdn.com/ksc2/bPKwRhQ |
| 20 | 12:50:39.003 | 天堂 | 1 | ✅就绪 | 302ms<br>净化无需处理 46ms<br>url=https://onvideo-safety.ssscdn.com/ksc2/4ql9kUD |
| 21 | 12:52:34.637 | 天堂 | 2 | ✅就绪 | 293ms<br>净化无需处理 128ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/LmYuUxA |
| 22 | 12:53:57.282 | 天堂 | 3 | ✅就绪 | 239ms<br>净化无需处理 74ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/FyIpHnY |
| 23 | 12:54:33.404 | 天堂 | 4 | ✅就绪 | 230ms<br>净化无需处理 60ms<br>url=https://onvideo-safety.ssrcdn.com/ksc2/6DEYI_1 |
| 24 | 12:56:02.484 | 天堂 | 5 | ✅就绪 | 434ms<br>净化无需处理 129ms<br>url=https://onvideo-safety.ssscdn.com/ksc2/9GR0TuX |
| 25 | 12:56:11.723 | WexAiReBo | 0 | ❌失败 | 取链 reason=站点未返回播放信息 ms=-1<br>内含自动重试×1<br>12:56:11.913 自动重取 |
| 26 | 12:56:17.792 | WexAiReBo | 1 | ❌失败 | 取链 reason=站点未返回播放信息 ms=-1<br>内含自动重试×1<br>12:56:17.885 自动重取 |
| 27 | 12:56:20.090 | WexAiReBo | 2 | ❌失败 | 取链 reason=站点未返回播放信息 ms=-1<br>内含自动重试×1<br>12:56:20.177 自动重取 |
| 28 | 12:56:21.223 | WexAiReBo | 3 | ❌失败 | 取链 reason=站点未返回播放信息 ms=-1<br>内含自动重试×1<br>12:56:21.314 自动重取 |
| 29 | 12:56:22.071 | WexAiReBo | 4 | ❌失败 | 取链 reason=站点未返回播放信息 ms=-1<br>内含自动重试×1<br>12:56:22.165 自动重取 |
| 30 | 12:56:27.253 | WexAiReBo | 4 | ✅就绪 | 2036ms<br>净化无需处理 1524ms<br>url=https://vip.ffzy-plays.com/20250728/43631_a53e |
| 31 | 12:57:08.417 | Rebo | 0 | ❌失败 | 取链 reason=站点未返回播放信息 ms=-1<br>内含自动重试×1<br>12:57:08.641 自动重取 |
| 32 | 12:57:12.064 | Rebo | 1 | ❌失败 | 取链 reason=站点未返回播放信息 ms=-1<br>内含自动重试×1<br>12:57:12.148 自动重取 |
| 33 | 12:57:21.821 | AppV7 | 0 | ✅就绪 | 2774ms<br>url=https://v16m-default.akamaized.n…/?… |
| 34 | 12:58:11.929 | WexAiYueYue | 0 | ❌失败 | 出链 reason=自动兜底试尽，转为明确提示 ms=16<br>内含自动重试×5<br>净化预取失败(本地代理地址/HttpException)<br>内置降级→IJK播放器<br>12:58:18.369 自动重取<br>净化预取失败(本地代理地址/HttpException)<br>用户手动重试<br>12:58:22.870 自动重取<br>净化预取失败(本地代理地址/HttpException)<br>内置降级→IJK播放器<br>12:58:29.280 自动重取<br>净化预取失败(本地代理地址/HttpException)<br>用户手动重试<br>12:58:30.836 自动重取<br>净化预取失败(本地代理地址/HttpException)<br>内置降级→IJK播放器<br>12:58:37.613 自动重取<br>净化预取失败(本地代理地址/HttpException)<br>url=http://127.0.0.1:9978/proxy?… |
| 35 | 12:59:16.611 | WexAiYueYue | 0 | ❌失败 | 出链 reason=自动兜底试尽，转为明确提示 ms=6184<br>内含自动重试×7<br>净化预取失败(本地代理地址/HttpException)<br>内置降级→IJK播放器<br>12:59:23.019 自动重取<br>净化预取失败(本地代理地址/HttpException)<br>12:59:28.605 自动重取<br>净化预取失败(本地代理地址/HttpException)<br>12:59:28.814 自动重取<br>净化预取失败(本地代理地址/HttpException)<br>12:59:30.867 自动重取<br>净化预取失败(本地代理地址/HttpException)<br>12:59:31.092 自动重取<br>净化预取失败(本地代理地址/HttpException)<br>用户手动重试<br>12:59:33.723 自动重取<br>净化预取失败(本地代理地址/HttpException)<br>内置降级→Exo播放器<br>12:59:40.135 自动重取<br>净化预取失败(本地代理地址/HttpException)<br>url=http://127.0.0.1:9978/proxy?… |
| 36 | 13:00:06.810 | 热播影视 | 0 | ❌失败 | 出链 reason=自动兜底试尽，转为明确提示 ms=728<br>内含自动重试×3<br>净化无需处理 126ms<br>变形重试 https://111.170.141.203:9090/nby/m3u8/getM3u8?…<br>内置降级→IJK播放器<br>13:00:22.575 自动重取<br>净化无需处理 158ms<br>用户手动重试<br>13:00:38.948 自动重取<br>净化无需处理 172ms<br>变形重试 https://43.248.96.62:9090/nby/m3u8/getM3u8?…<br>内置降级→IJK播放器<br>13:00:54.899 自动重取<br>净化无需处理 116ms<br>url=http://111.170.141.203:9090/nby/m3u8/getM3u8?… |
