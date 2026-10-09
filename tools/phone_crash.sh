#!/usr/bin/env bash
# Prints the app's most recent crash on the phone: the exception and the lines from our own code.
# Usage: tools/phone_crash.sh [--all]  (--all: the whole crash buffer, every app)
set -uo pipefail
ADB=~/Android/Sdk/platform-tools/adb
S=$(timeout 8 avahi-browse -rpt _adb-tls-connect._tcp 2>/dev/null | awk -F';' '/^=/ && $3 == "IPv4" { print $8 ":" $9; exit }')
[[ -z "$S" ]] && { echo "The phone isn't advertising wireless debugging; turn it on (see tools/install_phone.sh)."; exit 2; }
"$ADB" connect "$S" >/dev/null
log=$("$ADB" -s "$S" logcat -d -b crash)
[[ "${1:-}" == --all ]] && { echo "$log"; exit 0; }
# The newest crash block ("FATAL EXCEPTION" onwards) whose process is ours; other apps crash too.
crash=$(echo "$log" | awk '
    /FATAL EXCEPTION/ { if (ours) last = buf; buf = ""; ours = 0 }
    /Process: com\.kzhovn\.todoapp/ { ours = 1 }
    { buf = buf $0 "\n" }
    END { if (ours) last = buf; printf "%s", last }')
if [[ -z "$crash" ]]; then echo "No crash from the app in the phone's crash log (it keeps only recent ones)."; exit 0; fi
echo "$crash" | sed -E 's/^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ E AndroidRuntime: //' |
    grep -E "FATAL|Process:|Exception|Error|Caused by|com\.kzhovn" | head -25
