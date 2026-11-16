#!/usr/bin/env bash
# Builds the Google TV / phone app. See tools/GRADLE.md for output paths.
# macOS: uses ./gradlew and the Android Studio SDK (~/Library/Android/sdk) unless ANDROID_HOME is set.
# Windows (Git Bash): keeps using gradlew.bat and %LOCALAPPDATA%\Android\Sdk.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "$(uname -s)" == "Darwin" ]]; then
    source tools/macos-env.sh
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
    gradle=(./gradlew)
else
    export ANDROID_HOME="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
    gradle=(./gradlew.bat)
fi
if [[ ! -d "$ANDROID_HOME/platforms" ]]; then
    echo "Android SDK not found in $ANDROID_HOME. Install Android Studio or set ANDROID_HOME." >&2
    exit 1
fi

"${gradle[@]}" :tv:assembleRelease --console=plain -q "$@"
echo "built: build-output/tv/outputs/apk/release/tv-release.apk"
