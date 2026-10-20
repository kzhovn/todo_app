#!/usr/bin/env bash
# The Friday hygiene run's hotspot map: source files ranked by churn (commits touching them in the last ~2 months)
# times size (lines). Large files that keep changing are where a refactor pays back most.
# Usage: tools/hotspots.sh [count]      (default 25)
set -euo pipefail
cd "$(dirname "$0")/.."
git log --since="2 months ago" --name-only --format= -- '*.kt' '*.js' '*.css' '*.py' '*.sh' \
    | grep -v '^$' | grep -v -i '/test/' | sort | uniq -c \
    | while read -r commits file; do
        [ -f "$file" ] && printf '%d\t%d\t%d\t%s\n' "$((commits * $(wc -l <"$file")))" "$commits" "$(wc -l <"$file")" "$file"
    done \
    | sort -rn | head -n "${1:-25}" \
    | awk -F'\t' 'BEGIN { print "score\tcommits\tlines\tfile" } { print }'
