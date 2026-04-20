#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
npx react-native bundle \
  --platform android \
  --dev false \
  --entry-file index.js \
  --bundle-output android/app/src/main/assets/index.android.bundle \
  --assets-dest android/app/src/main/res/
cd android && ./gradlew assembleRelease
cd ..
adb install -r android/app/build/outputs/apk/release/app-release.apk
