#!/usr/bin/env bash
set -euo pipefail

VERSION_PROPS="app/version.properties"
if [ ! -f "$VERSION_PROPS" ]; then
  echo "Missing $VERSION_PROPS" >&2
  exit 1
fi

# Read versionName from the properties file. grep -m1 stops at the first match;
# `cut -d= -f2-` keeps any trailing '=' chars in the value (paranoia — versionName
# shouldn't contain them, but it costs nothing). tr strips CR in case the file
# was ever touched on a Windows host.
VERSION="$(grep -m1 '^versionName=' "$VERSION_PROPS" | cut -d= -f2- | tr -d '\r')"
if [ -z "$VERSION" ]; then
  echo "versionName not set in $VERSION_PROPS" >&2
  exit 1
fi

APK_OUT="app/release/elpaso-release.apk"
UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
KEYSTORE="$HOME/dev/android_build.jks"
APKSIGNER="$HOME/Library/Android/sdk/build-tools/36.1.0/apksigner"

gradle :app:assembleRelease
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:password --out "$APK_OUT" "$UNSIGNED"

if gh release view "$VERSION" >/dev/null 2>&1; then
  echo "Release $VERSION exists. Replacing APK."
  gh release upload "$VERSION" "$APK_OUT" --clobber
else
  echo "Creating release $VERSION."
  gh release create "$VERSION" "$APK_OUT" --title "$VERSION" --notes ""
fi
