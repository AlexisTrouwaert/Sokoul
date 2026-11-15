#!/usr/bin/env bash
# Builds the Google TV app. See tools/GRADLE.md for output paths.
set -e
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
./gradlew.bat :tv:assembleRelease --console=plain -q "$@"
echo built
