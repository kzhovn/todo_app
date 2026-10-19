#!/usr/bin/env bash
# Installs the debug APK on the phone over wireless debugging: finds its port, refuses during a focus
# session (it would break the screen pin), then pushes and installs (plain `adb install` often fails
# silently over wifi). Usage: tools/install_phone.sh [--build]
set -uo pipefail
cd "$(dirname "$0")/.."
ADB=~/Android/Sdk/platform-tools/adb # the system adb is too old to pair with this phone
APK=app/build/outputs/apk/debug/app-debug.apk

if [[ "${1:-}" == --build ]]; then ./gradlew -q :app:assembleDebug || exit 1; fi

find_phone() { timeout 8 avahi-browse -rpt _adb-tls-connect._tcp 2>/dev/null | awk -F';' '/^=/ && $3 == "IPv4" { print $8 ":" $9; exit }'; }
# The phone can take a minute to show up after wireless debugging is turned on (or after it changes networks),
# so keep looking for up to 90s before giving up.
connect() {
    local until=$((SECONDS + 90))
    while true; do
        S=$(find_phone)
        # `adb connect` exits 0 even when it fails; only its message tells.
        [[ -n "$S" ]] && "$ADB" connect "$S" | grep -q "connected to" && return 0
        ((SECONDS >= until)) && break
        [[ -z "${looking:-}" ]] && looking=1 && echo "Looking for the phone (up to 90s)..." >&2
        sleep 5
    done
    if [[ -n "$S" ]]; then echo "The phone is at $S but won't connect: the pairing was lost. Pair again with"
    else echo "The phone isn't advertising wireless debugging. Turn it on; if it says it's not paired, pair with"; fi
    echo "  $ADB pair <ip>:<pairing port> <code>   (Wireless debugging > Pair device with pairing code)"
    exit 2
}
# The phone drops off wifi now and then (battery saving); the pushed APK survives, so retry.
retry() { for _ in 1 2 3; do "$@" && return 0; sleep 5; connect; done; return 1; }

connect
state=$("$ADB" -s "$S" shell dumpsys activity activities | grep -o 'mLockTaskModeState=[A-Z]*' | head -1)
if [[ -z "$state" ]]; then echo "Couldn't read the phone's state; not installing."; exit 1; fi
if [[ "$state" != "mLockTaskModeState=NONE" ]]; then
    echo "A focus session is running ($state); not installing. Render screens with ScreenshotRender instead."
    exit 3
fi
# Functions, so a retry after reconnecting uses the phone's new port.
push() { "$ADB" -s "$S" push "$APK" /data/local/tmp/r.apk >/dev/null; }
install() { "$ADB" -s "$S" shell pm install -r --user 0 /data/local/tmp/r.apk; }
retry push || { echo "push failed"; exit 1; }
retry install || { echo "install failed"; exit 1; }
"$ADB" -s "$S" shell dumpsys package com.kzhovn.todoapp | grep -m1 lastUpdateTime
"$ADB" -s "$S" shell rm -f /data/local/tmp/r.apk
