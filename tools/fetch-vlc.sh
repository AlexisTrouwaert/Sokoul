#!/usr/bin/env bash
# macOS counterpart of tools/fetch-vlc.ps1.
# Downloads the official VLC for macOS from VideoLAN (arm64 or intel64, matching this Mac), checks its published SHA-256
# and keeps what the PC app embeds: lib/ (libvlc.dylib, libvlccore.dylib) and plugins/, in the same relative layout as
# VLC.app/Contents/MacOS so the plugins still find libvlccore. libVLC is not stored in Git (see docs/building.md).
# Usage: tools/fetch-vlc.sh [version] [target]
set -euo pipefail

version="${1:-3.0.21}"
project_root="$(cd "$(dirname "$0")/.." && pwd)"
target="${2:-$project_root/app/app-resources/macos/vlc}"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "This script is for macOS. On Windows use tools/fetch-vlc.ps1." >&2
    exit 1
fi
if [[ -f "$target/lib/libvlc.dylib" ]]; then
    echo "libVLC is already present in $target. Delete that folder first to download it again."
    exit 0
fi

case "$(uname -m)" in
    arm64) arch=arm64 ;;
    x86_64) arch=intel64 ;;
    *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

name="vlc-$version-$arch.dmg"
url="https://download.videolan.org/pub/videolan/vlc/$version/macosx/$name"
work="$(mktemp -d -t sokoul-vlc)"
mount="$work/mount"
mounted=0
cleanup() {
    if [[ $mounted -eq 1 ]]; then hdiutil detach -quiet "$mount" || hdiutil detach -quiet -force "$mount" || true; fi
    rm -rf "$work"
}
trap cleanup EXIT

echo "Downloading $url"
curl -fL --progress-bar -o "$work/$name" "$url"

# VideoLAN publishes a SHA-256 file next to each archive: the download must match it.
expected="$(curl -fsSL "$url.sha256" | awk '{print tolower($1)}')"
actual="$(shasum -a 256 "$work/$name" | awk '{print tolower($1)}')"
if [[ -z "$expected" || "$expected" != "$actual" ]]; then
    echo "Checksum mismatch for $name." >&2
    exit 1
fi

mkdir -p "$mount"
hdiutil attach -quiet -nobrowse -readonly -noautoopen -mountpoint "$mount" "$work/$name"
mounted=1
source_dir="$mount/VLC.app/Contents/MacOS"
if [[ ! -f "$source_dir/lib/libvlc.dylib" ]]; then
    echo "Unexpected DMG layout: $source_dir/lib/libvlc.dylib not found." >&2
    exit 1
fi

mkdir -p "$target"
# ditto keeps symlinks and code signatures intact.
ditto "$source_dir/lib" "$target/lib"
ditto "$source_dir/plugins" "$target/plugins"
echo "libVLC $version ($arch) installed in $target"
