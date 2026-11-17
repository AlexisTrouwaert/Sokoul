#!/usr/bin/env bash
# Runs the Sokoul PC app on macOS from sources (the counterpart of `./gradlew.bat :app:run` on Windows).
# Downloads libVLC on first use (tools/fetch-vlc.sh), then starts Gradle. Extra arguments go to Gradle.
# Usage: tools/run-desktop.sh [gradle args...]
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/macos-env.sh

if [[ ! -f app/app-resources/macos/vlc/lib/libvlc.dylib && -z "${SOKOUL_VLC_DIR:-}" ]]; then
    echo "libVLC not found: downloading it once (about 50 MB)..."
    tools/fetch-vlc.sh
fi

exec ./gradlew :app:run --console=plain "$@"
