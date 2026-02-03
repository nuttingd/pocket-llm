#!/usr/bin/env bash
set -euo pipefail

VERSION="$1"
IFS='.' read -r MAJOR MINOR PATCH <<< "$VERSION"
VERSION_CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))

sed -i.bak -e "s/versionCode = .*/versionCode = $VERSION_CODE/" app/build.gradle.kts
sed -i.bak -e "s/versionName = .*/versionName = \"$VERSION\"/" app/build.gradle.kts
rm -f app/build.gradle.kts.bak

echo "Set versionName=$VERSION versionCode=$VERSION_CODE"
