#!/bin/bash
# L1Box → GitHub 推送脚本（带重试，token 从本机文件读，不进命令行）
# 用法: bash _gh_push.sh          # 正常推送
#       bash _gh_push.sh --dry    # 只校验 token，不推送

export PATH="<本机>/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:$PATH"
ROOT="<本机>/WorkBuddy/2026-08-19-14-43-05"
TOKEN_FILE="$ROOT/.gh_token.txt"
REPO="$ROOT/_gh_upload_L1Box"
CRED="$ROOT/_gh_cred.sh"
LOG="$ROOT/_gh_push.log"

cd "$REPO" || exit 1

# ---- 1. 校验 token ----
if [ ! -f "$TOKEN_FILE" ]; then echo "TOKEN_FILE_MISSING"; exit 2; fi
TOKEN="$(head -1 "$TOKEN_FILE" | tr -d '\r\n\t ')"
case "$TOKEN" in
  PASTE_TOKEN_HERE*|"") echo "TOKEN_NOT_FILLED（文件里还是占位符）"; exit 2 ;;
esac
case "$TOKEN" in
  github_pat_*|ghp_*) : ;;
  *) echo "TOKEN_PREFIX_UNEXPECTED（不是 github_pat_ / ghp_ 开头，长度 ${#TOKEN}）"; exit 2 ;;
esac
echo "TOKEN_OK  长度=${#TOKEN}  前缀=$(echo "$TOKEN" | cut -c1-11)…"
[ "$1" = "--dry" ] && exit 0

# ---- 2. 抗抖动配置 ----
git config http.version HTTP/1.1
git config http.postBuffer 524288000
git config http.lowSpeedLimit 1000
git config http.lowSpeedTime 60
export GIT_TERMINAL_PROMPT=0

# ---- 3. 推送（最多 6 次；凭据走助手脚本，URL 里没有 token）----
for i in $(seq 1 6); do
  echo "--- 第 $i 次尝试 ---"
  if timeout 900 git -c credential.helper="!bash $CRED" push origin main > "$LOG" 2>&1; then
    echo "PUSH_OK"
    tail -6 "$LOG"
    exit 0
  fi
  echo "失败："
  tail -4 "$LOG" | sed 's/^/    /'
  sleep 5
done
echo "PUSH_FAILED（6 次均失败，详见 $LOG）"
exit 1
