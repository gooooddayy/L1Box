@echo off
setlocal
set BASE=<工作区>
set DST=<本机>\.workbuddy\projects\c-Users-<用户名>-WorkBuddy-2026-08-19-14-43-05\541a152a-4c04-49ea-8612-0fcc730b2300.jsonl

echo ============================================================
echo   L1Box - replace session file (pruned)
echo ============================================================
echo.
echo   Part 1 (pruned) : %BASE%\_session_pruned.jsonl
echo   Part 2 (delta)  : %BASE%\_session_delta.jsonl
echo   Target          : %DST%
echo.
echo   Result: 242.8 MB  -^>  98.4 MB   (same conversation, -59%%)
echo.
echo   *** WorkBuddy must be FULLY CLOSED before continuing ***
echo       (otherwise the file is locked and this will fail)
echo.
pause

copy /b "%BASE%\_session_pruned.jsonl"+"%BASE%\_session_delta.jsonl" "%DST%"
if errorlevel 1 goto FAIL

echo.
echo   [DONE] Session file replaced successfully.
echo          You can start WorkBuddy now.
echo.
pause
exit /b 0

:FAIL
echo.
echo   [FAILED] Could not write the file.
echo            - Make sure WorkBuddy is completely closed (check the tray).
echo            - Then run this script again.
echo.
pause
exit /b 1
