#!/usr/bin/env bash
# app/build.gradle의 versionName 기준으로 debug APK를 빌드해 GitHub Release "Leak Eye v<버전>"으로 올린다.
# 새 릴리스를 만들려면 먼저 versionName(과 versionCode)을 올리고 커밋해야 한다.
# 같은 버전(v<버전> 태그)의 릴리스가 이미 있으면 건너뛴다 — 커밋 훅에서 매 커밋 호출해도 안전하다.
# 새 릴리스는 정식 릴리스(Latest)로 올라가 GitHub에서 최신 버전이 바로 보인다.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

REPO="hanulend-cloud/Leak_eye"
if [ -z "${JAVA_HOME:-}" ] && [ -d "E:/Program install/Android studio/jbr" ]; then
  export JAVA_HOME="E:/Program install/Android studio/jbr"
fi
# Windows의 python3는 스토어 안내용 별칭일 수 있으므로 실제로 실행되는 것을 고른다.
PY=""
for candidate in python3 python; do
  if "$candidate" -c "" >/dev/null 2>&1; then PY="$candidate"; break; fi
done
if [ -z "$PY" ]; then
  echo "release-apk: python not found, skipping" >&2
  exit 0
fi

VERSION_NAME=$(grep -m1 "versionName" app/build.gradle | sed -E "s/.*versionName '([^']*)'.*/\1/")
VERSION_CODE=$(grep -m1 "versionCode" app/build.gradle | sed -E "s/.*versionCode ([0-9]+).*/\1/")
TAG="v${VERSION_NAME}"
FULL_SHA=$(git rev-parse HEAD)
SHA=$(git rev-parse --short=7 HEAD)

exists_status=$(curl -s -o /dev/null -w '%{http_code}' \
  "https://api.github.com/repos/${REPO}/releases/tags/${TAG}")
if [ "$exists_status" = "200" ]; then
  echo "release-apk: ${TAG} already released — bump versionName/versionCode in app/build.gradle to release again"
  exit 0
fi

# 릴리스는 원격에 존재하는 커밋만 가리킬 수 있으므로 태그를 만들기 전에 현재 브랜치를 push한다.
if ! git push origin HEAD; then
  echo "release-apk: push failed, skipping release" >&2
  exit 0
fi

./gradlew.bat assembleDebug -q

APK="app/build/outputs/apk/debug/app-debug.apk"
ASSET_NAME="Leak_eye-v${VERSION_NAME}.apk"
# 직전 버전 태그(v*) 이후 커밋 제목들을 변경 내역으로 쓴다.
PREV_TAG=$(git tag -l 'v[0-9]*' --sort=-v:refname | grep -v "^${TAG}$" | head -1 || true)
if [ -n "$PREV_TAG" ] && git rev-parse -q --verify "$PREV_TAG" >/dev/null; then
  CHANGES=$(git log --pretty='- %s' "${PREV_TAG}..HEAD")
else
  CHANGES=$(git log -1 --pretty='- %s')
fi
NOTES="versionName ${VERSION_NAME} / versionCode ${VERSION_CODE} / commit ${SHA}

${CHANGES}"

TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill | sed -n 's/^password=//p')
if [ -z "$TOKEN" ]; then
  echo "release-apk: no cached GitHub credential, skipping" >&2
  exit 0
fi

BODY=$(TAG="$TAG" SHA="$FULL_SHA" NAME="Leak Eye v${VERSION_NAME}" NOTES="$NOTES" "$PY" -c \
  'import json, os; print(json.dumps({"tag_name": os.environ["TAG"], "target_commitish": os.environ["SHA"],
   "name": os.environ["NAME"], "body": os.environ["NOTES"], "draft": False, "prerelease": False,
   "make_latest": "true"}))')

RESP=$(curl -s -X POST -H "Authorization: token ${TOKEN}" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/${REPO}/releases" -d "$BODY")
UPLOAD_URL=$(echo "$RESP" | "$PY" -c 'import json, sys; print(json.load(sys.stdin).get("upload_url", "").split("{")[0])')
if [ -z "$UPLOAD_URL" ]; then
  echo "release-apk: release create failed: $RESP" >&2
  exit 1
fi

curl -s -X POST -H "Authorization: token ${TOKEN}" -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @"$APK" "${UPLOAD_URL}?name=${ASSET_NAME}" -o /dev/null
echo "release-apk: released ${TAG} (${ASSET_NAME}) https://github.com/${REPO}/releases/tag/${TAG}"
