@echo off
chcp 65001 >nul
title L1Box APK push to GitHub
echo ============================================================
echo  正在把"仓库内置 APK"提交推送到 GitHub
echo  （自动重试 6 次；看到 PUSH_OK 就是成功，可以关窗口）
echo ============================================================
echo.
"D:\Workbuddy\resources\vendor\PortableGit\usr\bin\bash.exe" -lc "cd <本机>/WorkBuddy/2026-08-19-14-43-05 && bash _gh_push.sh"
echo.
echo ============================================================
echo  结束：上面出现 PUSH_OK = 成功；PUSH_FAILED = 网络还不行，
echo  过一会儿再双击本文件重试即可（不需要改任何东西）。
echo ============================================================
pause
