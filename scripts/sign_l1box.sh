#!/usr/bin/env bash
# sign_l1box.sh — zipalign + apksigner v1(SHA-1)/v2/v3 re-sign for L1Box release APK
# Usage: bash sign_l1box.sh <unsigned_apk>
set -e
SDK="$USERPROFILE/AppData/Local/Android/Sdk/build-tools/33.0.2"
JKS="$USERPROFILE/WorkBuddy/2026-08-19-14-43-05/FreeBox-src/TVBoxOSC.jks"
KS_PASS="TVBoxOSC"
ALIAS="tvboxosc"
IN="$1"
OUT="${IN%.apk}_signed.apk"

echo ">>> zipalign: $IN -> $OUT"
"$SDK/zipalign.exe" -p 4 "$IN" "$OUT"

echo ">>> apksigner: v1(SHA-1)+v2+v3, min-sdk 17"
"$SDK/apksigner.bat" sign \
  --min-sdk-version 17 \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --ks "$JKS" \
  --ks-key-alias "$ALIAS" \
  --ks-pass pass:"$KS_PASS" \
  --key-pass pass:"$KS_PASS" \
  "$OUT"

echo ">>> verify (min-sdk 17)"
"$SDK/apksigner.bat" verify --verbose --min-sdk-version 17 "$OUT" | grep -E "Verified|v1|v2|v3|Signer" || true
echo ">>> DONE: $OUT"
