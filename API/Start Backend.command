#!/usr/bin/env bash
# macOS: double-click in the Finder to start the local Sokoul backend in Docker (see start-backend.sh for options).
cd "$(dirname "$0")"
./start-backend.sh "$@"
status=$?
echo
if [[ $status -eq 0 ]]; then echo "Backend started. Press Enter to close this window."; else echo "Backend failed (code $status). Press Enter to close this window."; fi
read -r
exit $status
