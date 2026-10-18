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
# Sample Google Calendar events around now (two calendars), so the calendar, Doing and contexts have some.
CAL=$(dirname "$DB")/calendar.json
python3 - "$CAL" <<'PY'
import datetime, json, sys
day = 86400000; start = int(datetime.datetime.combine(datetime.date.today(), datetime.time()).timestamp() * 1000)  # local midnight
def ev(i, cal, title, d, h, hours, **kw): return dict(id=f"{cal}/{i}", calendarId=cal, title=title, start=start + d * day + int(h * 3600000), end=start + d * day + int((h + hours) * 3600000), **kw)
nowh = datetime.datetime.now().hour  # one event on now, one next, whenever the preview runs
events = [ev(1, "p", "Dentist", 0, nowh + 1, 1, location="12 Elm St"), ev(2, "p", "Meeting", 0, nowh - 1, 2), ev(3, "w", "oncall for Payments", 1, 9, 8),
          ev(4, "e", "Maya's party", 4, 19, 4, location="Lou's place"), ev(5, "p", "Book club", 0, 19.5, 2), ev(6, "e", "Trip", 9, 0, 48, allDay=True, busy=False)]
cals = [dict(id="p", name="Personal", color="#7986CB"), dict(id="w", name="Work", color="#E67C73"), dict(id="e", name="Events", color="#33B679")]
json.dump(dict(calendars=cals, events=events), open(sys.argv[1], "w"))
PY
CALENDAR_FILE=$CAL DB_PATH=$DB API_TOKEN=$(head -c 16 /dev/urandom | xxd -p) PORT=8099 HOST=127.0.0.1 exec server/build/install/server/bin/server
