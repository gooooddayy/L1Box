#!/bin/bash
# git 凭据助手：从本机文件读 token 交给 git。
# 目的：token 绝不进命令行参数、绝不出现在任何命令输出/错误信息里。
TOKEN_FILE="<本机>/WorkBuddy/2026-08-19-14-43-05/.gh_token.txt"
case "$1" in
  get)
    TOKEN="$(head -1 "$TOKEN_FILE" 2>/dev/null | tr -d '\r\n\t ')"
    [ -n "$TOKEN" ] || exit 1
    printf 'username=x-access-token\npassword=%s\n' "$TOKEN"
    exit 0
    ;;
  store|erase)
    exit 0
    ;;
esac
exit 1
