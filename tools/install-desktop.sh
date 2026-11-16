#!/usr/bin/env bash
# macOS counterpart of tools/install-desktop.ps1: builds "Sokoul PC.app" (Java runtime and libVLC inside) and installs
# it in /Applications (or ~/Applications when /Applications is not writable), then opens it.
# Profile data (~/.sokoul) is neither copied nor cleared. Usage: tools/install-desktop.sh [--no-build] [--no-launch]
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/macos-env.sh

build=1 launch=1
for arg in "$@"; do
    case "$arg" in
        --no-build) build=0 ;;
        --no-launch) launch=0 ;;
        *) echo "Unknown option: $arg" >&2; exit 1 ;;
    esac
done

app_name="Sokoul PC.app"
source_app="${SOKOUL_BUILD_ROOT:-$PWD/build-output}/app/compose/binaries/main/app/$app_name"

if [[ $build -eq 1 ]]; then
    [[ -f app/app-resources/macos/vlc/lib/libvlc.dylib || -n "${SOKOUL_VLC_DIR:-}" ]] || tools/fetch-vlc.sh
    # Compose refuses to package with Homebrew's JDK (JetBrains/compose-multiplatform#3107): prefer another vendor's JDK.
    packaging_jdk=""
    while IFS= read -r home; do
        [[ "$home" == *"/Cellar/"* || "$home" == *"homebrew"* ]] && continue
        packaging_jdk="$home"; break
    done < <(/usr/libexec/java_home -V 2>&1 | awk '/^ +[0-9]/ { split($1, v, "."); if (v[1] + 0 >= 17) { n = split($0, p, "\" "); print p[n] } }' | sed 's/^ *//')
    if [[ -z "$packaging_jdk" ]]; then
        echo "Packaging needs a JDK 17+ that is not Homebrew's, for example: brew install --cask corretto@17" >&2
        exit 1
    fi
    echo "Building $app_name with $packaging_jdk"
    JAVA_HOME="$packaging_jdk" ./gradlew :app:createDistributable --console=plain -q
fi

if [[ ! -f "$source_app/Contents/app/resources/vlc/lib/libvlc.dylib" ]]; then
    echo "$source_app is not a complete Sokoul build with libVLC." >&2
    exit 1
fi
codesign --verify --deep --strict "$source_app"

target_dir=/Applications
[[ -w "$target_dir" ]] || { target_dir="$HOME/Applications"; mkdir -p "$target_dir"; }
target_app="$target_dir/$app_name"

# Only close the installed app; never touch another copy.
if pgrep -f "$target_app/Contents/MacOS/" >/dev/null 2>&1; then
    echo "Closing the installed Sokoul..."
    osascript -e 'tell application id "com.sokoul.pc" to quit' >/dev/null 2>&1 || true
    for _ in $(seq 1 30); do pgrep -f "$target_app/Contents/MacOS/" >/dev/null 2>&1 || break; sleep 0.5; done
    if pgrep -f "$target_app/Contents/MacOS/" >/dev/null 2>&1; then
        echo "The installed app did not close. Quit it (Cmd+Q) and retry." >&2
        exit 1
    fi
fi

if [[ -d "$target_app" ]]; then
    backup="$(mktemp -d -t sokoul-install-before)"
    ditto "$target_app" "$backup/$app_name"
    rm -rf "$target_app"
    echo "Previous installation saved: $backup/$app_name"
fi
ditto "$source_app" "$target_app"
codesign --verify --deep --strict "$target_app"
echo "Installed: $target_app"
[[ $launch -eq 1 ]] && open "$target_app"
exit 0
