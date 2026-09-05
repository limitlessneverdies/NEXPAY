#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
command -v java >/dev/null || { echo 'Install JDK 17 (or use Android Studio JBR 17/21) first.'; exit 1; }
JAVA_VER="$(java -version 2>&1 | head -n1)"
if ! [[ "$JAVA_VER" =~ \"(17|21)\. ]]; then echo "Use JDK 17 or 21; found: $JAVA_VER"; exit 1; fi
CACHE="${PAILA_BUILD_CACHE:-$HOME/.cache/paila-build}"
mkdir -p "$CACHE"
GRADLE_VERSION=8.9
if [[ ! -x "$CACHE/gradle-$GRADLE_VERSION/bin/gradle" ]]; then
  curl --fail --location --proto '=https' --tlsv1.2 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$CACHE/gradle.zip"
  EXPECTED="$(curl --fail --location --proto '=https' --tlsv1.2 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip.sha256")"
  if command -v sha256sum >/dev/null; then ACTUAL="$(sha256sum "$CACHE/gradle.zip" | cut -d' ' -f1)"; else ACTUAL="$(shasum -a 256 "$CACHE/gradle.zip" | cut -d' ' -f1)"; fi
  [[ "$EXPECTED" == "$ACTUAL" ]] || { echo 'Gradle checksum mismatch'; exit 1; }
  unzip -q -o "$CACHE/gradle.zip" -d "$CACHE"
fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  SYSTEM=linux; [[ "$(uname)" == Darwin ]] && SYSTEM=mac
  mkdir -p "$ANDROID_HOME/cmdline-tools" "$CACHE/sdk"
  curl --fail --location --proto '=https' --tlsv1.2 "https://dl.google.com/android/repository/commandlinetools-${SYSTEM}-11076708_latest.zip" -o "$CACHE/sdk-tools.zip"
  # Fetch authoritative repository metadata and verify this exact archive's published checksum.
  curl --fail --location --proto '=https' --tlsv1.2 https://dl.google.com/android/repository/repository2-1.xml -o "$CACHE/repository.xml"
  python3 "$ROOT/scripts/verify-sdk.py" "$CACHE/repository.xml" "commandlinetools-${SYSTEM}-11076708_latest.zip" "$CACHE/sdk-tools.zip"
  unzip -q -o "$CACHE/sdk-tools.zip" -d "$CACHE/sdk"
  mv "$CACHE/sdk/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
SDK="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
echo 'Review and accept the Android SDK licenses when prompted.'
"$SDK" --licenses
"$SDK" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
"$CACHE/gradle-$GRADLE_VERSION/bin/gradle" -p "$ROOT/android" --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug "$@"
echo "APK: $ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
echo 'Debug build only. Test two physical phones before preparing a signed release.'
