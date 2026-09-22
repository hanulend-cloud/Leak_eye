#!/usr/bin/env bash
# 커밋마다 debug APK를 빌드해 GitHub Release로 올린다. PostToolUse(Bash, git commit) 훅에서 호출된다.
# 같은 커밋(SHA)에 대한 릴리스가 이미 있으면 건너뛴다 — git commit이 실패해 HEAD가 그대로여도 안전하다.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

REPO="hanulend-cloud/Leak_eye"
JAVA_HOME="${JAVA_HOME:-E:/Program install/Android studio/jbr}"
SHA=$(git rev-parse --short=7 HEAD)
TAG="build-${SHA}"

exists_status=$(curl -s -o /dev/null -w '%{http_code}' \
  "https://api.github.com/repos/${REPO}/releases/tags/${TAG}")
if [ "$exists_status" = "200" ]; then
  echo "release-apk: ${TAG} already released, skipping"
  exit 0
fi

JAVA_HOME="$JAVA_HOME" ./gradlew.bat assembleDebug -q

VERSION_NAME=$(grep -m1 "versionName" app/build.gradle | sed -E "s/.*versionName '([^']*)'.*/\1/")
APK="app/build/outputs/apk/debug/app-debug.apk"
ASSET_NAME="Leak_eye-v${VERSION_NAME}-${SHA}-debug.apk"
COMMIT_SUBJECT=$(git log -1 --pretty=%s)

TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill | sed -n 's/^password=//p')
if [ -z "$TOKEN" ]; then
  echo "release-apk: no cached GitHub credential, skipping" >&2
  exit 0
fi

BODY=$(jq -n --arg tag "$TAG" --arg name "Leak Eye v${VERSION_NAME} (${SHA})" \
  --arg body "Debug build from commit ${SHA}: ${COMMIT_SUBJECT}" \
  '{tag_name:$tag, target_commitish:"main", name:$name, body:$body, draft:false, prerelease:true}')

RESP=$(curl -s -X POST -H "Authorization: token ${TOKEN}" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/${REPO}/releases" -d "$BODY")
UPLOAD_URL=$(echo "$RESP" | jq -r '.upload_url // empty' | sed -E 's/\{.*//')
if [ -z "$UPLOAD_URL" ]; then
  echo "release-apk: release create failed: $RESP" >&2
  exit 1
fi

curl -s -X POST -H "Authorization: token ${TOKEN}" -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @"$APK" "${UPLOAD_URL}?name=${ASSET_NAME}" -o /dev/null
echo "release-apk: released ${TAG} (${ASSET_NAME})"
