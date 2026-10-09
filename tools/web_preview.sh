#!/usr/bin/env bash
# Runs the server locally for looking at the web app: http://localhost:8099, web pages on (HOST=127.0.0.1),
# a throwaway token, and its own database (never the real one). Run by .claude/launch.json's "web-preview";
# tools/web_preview.sh seed adds sample tasks to a running one. Build first: ./gradlew :server:installDist
set -euo pipefail
cd "$(dirname "$0")/.."
DB=${PREVIEW_DB:-build/preview/preview.db}
URL=http://127.0.0.1:8099

if [[ "${1:-}" == seed ]]; then
    # A folder, a project with steps, a prerequisite, a checklist and a waiting item: enough to look at most screens.
    curl -s -o /dev/null -X POST "$URL/tasks/new" -d "title=Personal&type=FOLDER"
    for t in "Renew passport" "Pay rent !" "groceries [milk, eggs, bread]" "wait landlord replies" "Call the dentist due tomorrow" "Water the plants every 3 days after done"; do
        curl -s -o /dev/null -X POST "$URL/quickadd" --data-urlencode "text=$t"
    done
    echo "seeded; open $URL/all"
    exit 0
fi

mkdir -p "$(dirname "$DB")"
DB_PATH=$DB API_TOKEN=$(head -c 16 /dev/urandom | xxd -p) PORT=8099 HOST=127.0.0.1 exec server/build/install/server/bin/server
