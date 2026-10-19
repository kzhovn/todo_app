#!/usr/bin/env bash
# Client for the server's dev notes (/api/dev): Kira's DMs to the bot, for the backlog worker. The URL and token
# come from the top bar's config, so the token never appears in a command line or transcript.
# Usage: tools/dev_notes.sh list [project]              notes as JSON (all states)
#        tools/dev_notes.sh state <id> <state>          open|working|done|question|skip|proposed|deleted
#        tools/dev_notes.sh message [<id>] < text       a question on a note (a Discord reply), or a plain DM
#        tools/dev_notes.sh propose <tag> [project] < text   a Friday-run item for Kira's 👍
#        tools/dev_notes.sh attachment <id> <name> <file>    save one of a note's attachments
set -euo pipefail
config=${DEV_NOTES_CONFIG:-~/.config/raspberry-tray/config.json} # overridable for a local test server
url=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["url"])' "$config")
api() {
    # The header comes from a file descriptor, not the argument list.
    curl -fsS -H @<(python3 -c 'import json,sys; print("Authorization: Bearer " + json.load(open(sys.argv[1]))["token"])' "$config") "$@"
}
case "${1:-}" in
list) api "$url/api/dev${2:+?project=$2}" ;;
state) api -o /dev/null --data-urlencode "state=$3" "$url/api/dev/$2/state" && echo "note $2: $3" ;;
message) api -o /dev/null ${2:+--data-urlencode "id=$2"} --data-urlencode "text@-" "$url/api/dev/message" && echo sent ;;
propose) api --data-urlencode "tag=$2" --data-urlencode "project=${3:-raspberry}" --data-urlencode "text@-" "$url/api/dev/propose" ;;
attachment) api -o "$4" "$url/api/dev/$2/attachments/$3" ;;
*) sed -n 4,8p "$0"; exit 1 ;;
esac
