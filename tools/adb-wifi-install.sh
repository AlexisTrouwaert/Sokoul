#!/usr/bin/env bash
# macOS counterpart of tools/adb-wifi-install.ps1: installs the TV APK over the network and starts it.
# Never uninstalls and never clears the app's data (install -r keeps them).
#
#   Android 11+ (Settings > Developer options > Wireless debugging):
#     tools/adb-wifi-install.sh --pair 10.0.10.50:37123 --connect 10.0.10.50:41234
#     (--pair: address of "Pair device with pairing code"; you type the code at the prompt.
#      --connect: address of the main Wireless debugging screen, a different port.)
#   Older Android TV (Developer options > USB/network debugging): tools/adb-wifi-install.sh --connect 10.0.10.50
#     (port 5555; accept the "Allow debugging?" prompt on the TV).
#   --apk <file> installs another APK.
set -euo pipefail
cd "$(dirname "$0")/.."

pair="" connect="" apk="build-output/tv/outputs/apk/release/tv-release.apk"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --pair) pair="${2:-}"; shift ;;
        --connect) connect="${2:-}"; shift ;;
        --apk) apk="${2:-}"; shift ;;
        -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
    shift
done

[[ -n "$connect" ]] || { echo "Missing --connect <TV address>. See --help." >&2; exit 1; }
[[ -f "$apk" ]] || { echo "APK not found: $apk (build it with tools/build-tv.sh)." >&2; exit 1; }
[[ "$connect" == *:* ]] || connect="$connect:5555"
host_re='^[A-Za-z0-9.-]+:[0-9]{1,5}$'
[[ "$connect" =~ $host_re ]] || { echo "--connect must be host[:port]." >&2; exit 1; }

adb="$(command -v adb || true)"
[[ -n "$adb" ]] || adb="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[[ -x "$adb" ]] || { echo "adb not found. Install Android Studio or the Android platform-tools." >&2; exit 1; }

if [[ -n "$pair" ]]; then
    [[ "$pair" =~ $host_re ]] || { echo "--pair must be host:port." >&2; exit 1; }
    [[ "${pair%%:*}" == "${connect%%:*}" ]] || { echo "--pair and --connect must use the same TV address." >&2; exit 1; }
    echo "Keep the pairing-code screen open on the TV and type its current code below."
    "$adb" pair "$pair"
fi

echo "Connecting to $connect (accept the debugging prompt on the TV if it appears)..."
"$adb" connect "$connect"
state=""
for _ in $(seq 1 20); do
    state="$("$adb" -s "$connect" get-state 2>/dev/null || true)"
    [[ "$state" == "device" ]] && break
    sleep 1
done
if [[ "$state" != "device" ]]; then
    echo "The TV is not authorized or not reachable. Accept the prompt on the TV (tick 'Always allow') and retry." >&2
    exit 1
fi
echo "Connected to: $("$adb" -s "$connect" shell getprop ro.product.model | tr -d '\r')"

# Keep data; fail on an incompatible signature instead of uninstalling.
"$adb" -s "$connect" install -r "$apk" || { echo "Installation failed; nothing was uninstalled or cleared." >&2; exit 1; }
"$adb" -s "$connect" shell am start -n com.sokoul.tv/.MainActivity >/dev/null
echo "Sokoul installed and started on the TV."
