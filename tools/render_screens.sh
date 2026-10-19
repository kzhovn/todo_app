#!/usr/bin/env bash
# Renders the phone's screens to PNGs with Robolectric (ScreenshotRender), for when the phone isn't reachable.
# Usage: tools/render_screens.sh <output dir>
set -euo pipefail
[ $# -eq 1 ] || { echo "usage: $0 <output dir>" >&2; exit 1; }
out=$(realpath -m "$1")
cd "$(dirname "$0")/.."
mkdir -p "$out"
RENDER_DIR="$out" ./gradlew -q :app:testDebugUnitTest --tests '*ScreenshotRender*'
ls "$out"
