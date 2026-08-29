#!/bin/bash
set -e

KEY=~/.ssh/deploy_key
HOST=root@64.90.31.124
APK_LOCAL=android/app/build/outputs/apk/release/app-release.apk
APK_REMOTE=/var/www/dl-hegouzi/StruGGle.apk
VERSION_FILE=android/version_counter.txt
URL="https://dl.hegoulaogouzi.icu/StruGGle.apk"

VERSION=$(cat "$VERSION_FILE" 2>/dev/null || echo "0.01")
echo "部署版本: $VERSION"

# 上传 APK
echo "上传 APK..."
scp -i "$KEY" "$APK_LOCAL" "$HOST:$APK_REMOTE"

# 更新 version.json
echo "更新 version.json..."
ssh -i "$KEY" "$HOST" "echo '{\"version\":\"$VERSION\",\"url\":\"$URL\"}' > /var/www/dl-hegouzi/version.json"

echo "部署完成: $VERSION"
