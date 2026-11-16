#!/usr/bin/env bash
# macOS: double-click in the Finder to start Sokoul PC from sources (libVLC is downloaded on first use).
cd "$(dirname "$0")"
tools/run-desktop.sh "$@"
status=$?
if [[ $status -ne 0 ]]; then
    echo
    echo "Sokoul stopped with an error (code $status). Press Enter to close this window."
    read -r
fi
exit $status
