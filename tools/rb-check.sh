#!/usr/bin/env bash
# The backlog worker's cheap checks and its lock (docs/superpowers/specs/2026-10-18-dev-workflow-design.md).
# Usage: tools/rb-check.sh gate      prints why a scheduled run shouldn't start; nothing means go
#        tools/rb-check.sh notes     prints "none" if no raspberry note is open
#        tools/rb-check.sh lock      takes the global lock (fails if another run holds it)
#        tools/rb-check.sh touch     marks the lock as still held (call between steps)
#        tools/rb-check.sh unlock    releases it
set -euo pipefail
LOCK=~/.cache/claude-dev.lock
# ponytail: a lock file with a heartbeat, not flock: a Claude session holds no process across its tool calls.
# A run that crashes leaves it; after STALE_MIN without a touch it counts as free.
STALE_MIN=120
held() { [ -f "$LOCK" ] && [ -n "$(find "$LOCK" -mmin -"$STALE_MIN")" ]; }

case "${1:-}" in
gate)
    [ -e ~/.config/raspberry/paused ] && { echo "paused (~/.config/raspberry/paused)"; exit 0; }
    held && { echo "another run holds the lock: $(cat "$LOCK")"; exit 0; }
    # Kira (or any other Claude session) active in the last 30 minutes, in any project. Our own scheduled runs
    # are skipped (their first line names the task; the lock covers overlap). Subagent transcripts sit a level
    # deeper and move with their session's own file, so only top-level ones are looked at.
    while IFS= read -r f; do
        head -n 1 "$f" | grep -q 'scheduled-task name=\\"raspberry-' && continue
        echo "Claude was used in the last 30 minutes ($(basename "$(dirname "$f")"))"
        exit 0
    done < <(find ~/.claude/projects -mindepth 2 -maxdepth 2 -name '*.jsonl' -mmin -30)
    ;;
notes)
    "$(dirname "$0")/dev_notes.sh" list raspberry \
        | python3 -c 'import json,sys; sys.exit(0 if any(n["state"] == "open" for n in json.load(sys.stdin)) else 1)' \
        || echo none
    ;;
lock)
    held && { echo "held by: $(cat "$LOCK")" >&2; exit 1; }
    mkdir -p "$(dirname "$LOCK")"
    echo "${2:-run} since $(date '+%F %T')" >"$LOCK"
    ;;
touch) touch "$LOCK" ;;
unlock) rm -f "$LOCK" ;;
*) sed -n 3,7p "$0"; exit 1 ;;
esac
