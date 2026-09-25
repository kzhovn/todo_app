#!/usr/bin/env python3
"""One-off import of MyLifeOrganized tasks into Raspberry, through the server's /sync endpoint.

Run on the server machine with the server's env loaded, dry run first:
    set -a; . ~/todo.env; set +a
    python3 import_mlo.py My_Tasks.mlobak --dry-run
    python3 import_mlo.py My_Tasks.mlobak

Selection and mapping follow the decisions made for the Sep 2026 import:
- Only the "Personal" and "Hobby" trees; completed items and hide-branch-in-to-do subtrees are
  skipped, along with anything under them.
- "Hide this task in to-do" containers become folders; everything else is a task. The two roots
  map onto existing Raspberry folders of the same name (created if missing).
- Start/due dates (and times), complete-in-order, dependencies, and @Home carry over. Other
  contexts, importance, tags, colors and notes are dropped.
- Recurrence: MLO "N days/weeks after completion" -> after-completion days; anything else must be
  listed in RECURRENCE_OVERRIDES or the script stops.
- A task whose title matches an open Raspberry task is skipped; its subtasks attach to that task.
"""
import argparse
import csv
import io
import json
import os
import re
import sqlite3
import ssl
import sys
import time
import urllib.request
import zipfile
from datetime import datetime
from zoneinfo import ZoneInfo

ROOTS = ("Personal", "Hobby")
KEPT_CONTEXTS = {"@Home": "Home"}  # MLO place -> Raspberry context name
# MLO schedule-based rules with no automatic mapping, decided case by case.
# "Due the 4th monthly, active 5 days earlier": starting on the second-to-last day of each month
# always puts the due date (start + 5 days) on the 4th.
RECURRENCE_OVERRIDES = {"Financial review": ("RRULE", "FREQ=MONTHLY;BYMONTHDAY=-2")}


def read_sections(path):
    if zipfile.is_zipfile(path):
        text = zipfile.ZipFile(path).read("tasks.csv").decode("utf-8-sig")
    else:
        text = open(path, encoding="utf-8-sig").read()
    parts = re.split(r"^\[([\w.]+)\][ \t]*\r?\n", text, flags=re.M)
    return {parts[i]: list(csv.DictReader(io.StringIO(parts[i + 1]))) for i in range(1, len(parts), 2)}


def truthy(value):
    return (value or "").strip().lower() in ("1", "true", "-1")


def timezone_from_env():
    match = re.search(r"-Duser\.timezone=(\S+)", os.environ.get("JAVA_OPTS", ""))
    return ZoneInfo(match.group(1) if match else "America/Los_Angeles")


def to_millis(value, tz):
    if not value.strip():
        return None
    return int(datetime.fromisoformat(value.strip()).replace(tzinfo=tz).timestamp() * 1000)


def recurrence(item):
    if item["RecType"].strip() in ("", "0"):
        return None, None
    caption = item["Caption"].strip()
    if caption in RECURRENCE_OVERRIDES:
        return RECURRENCE_OVERRIDES[caption]
    interval = int(item["RecInterval"] or 1)
    if truthy(item["RecUseCompletionDate"]) and item["RecType"] == "1":
        return "AFTER_COMPLETION", str(interval)
    if truthy(item["RecUseCompletionDate"]) and item["RecType"] == "2":
        return "AFTER_COMPLETION", str(7 * interval)
    sys.exit(f"No mapping for the recurrence on {caption!r} (RecType {item['RecType']}); add it to RECURRENCE_OVERRIDES.")


def load_existing(db_path, now):
    """Open Raspberry state: folders and open tasks by lowercase title, contexts by name, max version."""
    db = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    folders, open_tasks, contexts = {}, {}, {}
    for table, row_id, fields in db.execute("SELECT tbl, id, fields FROM rows"):
        f = json.loads(fields)
        if f.get("deletedAt") is not None:
            continue
        if table == "context":
            contexts[f.get("name")] = row_id
        elif table == "task" and not (f.get("expiresAt") is not None and f["expiresAt"] <= now):
            key = f.get("title", "").strip().lower()
            if f.get("type") == "FOLDER":
                folders[key] = row_id
            elif not f.get("isComplete"):
                open_tasks[key] = row_id
    version = db.execute("SELECT COALESCE(MAX(version), 0) FROM rows").fetchone()[0]
    return folders, open_tasks, contexts, version


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("backup", help="MLO .mlobak (or its tasks.csv)")
    parser.add_argument("--db", default=os.environ.get("DB_PATH", "todo.db"))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    tz = timezone_from_env()
    now = int(time.time() * 1000)
    sections = read_sections(args.backup)
    items = sections["TodoItems"]
    by_uid = {i["UID"]: i for i in items}
    children = {}
    for i in items:
        children.setdefault(i["ParentUID"], []).append(i)
    places = {p["UID"]: p["Caption"] for p in sections["Places"]}
    item_contexts = {}
    for link in sections["TodoItemPlaces"]:
        item_contexts.setdefault(link["TodoItemUID"], []).append(places.get(link["PlaceUID"]))
    depends = {}
    for d in sections.get("TodoItems.Dependency", []):
        depends.setdefault(d["TaskUID"], []).append(d["DependencyUID"])

    folders, open_tasks, contexts, version = load_existing(args.db, now)
    missing = {c for c in KEPT_CONTEXTS.values() if c not in contexts}
    if missing:
        sys.exit(f"Raspberry has no context named {missing}; create it first.")

    # uid -> Raspberry id, for new rows and for items matched to existing ones.
    ids, rows, skipped, report = {}, [], [], []
    next_id = [now << 11]

    def new_id():
        next_id[0] += 1  # strictly increasing, so MLO's sibling order is kept
        return next_id[0]

    def add(item, parent_id, depth):
        caption = item["Caption"].strip()
        is_root = depth == 0
        is_folder = is_root or truthy(item["HideInToDoThisTask"])
        existing = (folders if is_folder else open_tasks).get(caption.lower())
        if existing is not None:
            ids[item["UID"]] = existing
            if not is_root:
                skipped.append(caption)
            report.append("  " * depth + f"= {caption}  (already in Raspberry)")
            return
        ids[item["UID"]] = new_id()
        rec_type, rec_rule = (None, None) if is_folder else recurrence(item)
        fields = {
            "type": "FOLDER" if is_folder else "TASK",
            "title": caption,
            "parentId": parent_id,
            "sequential": truthy(item["CompleteInOrder"]),
            "startDate": None if is_folder else to_millis(item["StartDateTime"], tz),
            "dueDate": None if is_folder else to_millis(item["DueDateTime"], tz),
            "isStarred": truthy(item["Starred"]),
            "isComplete": False,
            "completedAt": None,
            "recurrenceType": rec_type,
            "recurrenceRule": rec_rule,
            "icon": None,
            "reminderOffsetMinutes": None,
            "isMaybe": False,
            "expiresAt": None,
            "contextIds": sorted(contexts[KEPT_CONTEXTS[c]] for c in item_contexts.get(item["UID"], []) if c in KEPT_CONTEXTS),
            "dependsOn": [],  # filled in once every id is known
            "deletedAt": None,
        }
        rows.append((item["UID"], fields))
        extras = [k for k in ("startDate", "dueDate", "recurrenceRule", "contextIds") if fields[k]]
        report.append("  " * depth + f"+ {fields['type'].lower()} {caption}" +
                      (f"  {{{', '.join(f'{k}={fields[k]}' for k in extras)}}}" if extras else "") +
                      ("  [sequential]" if fields["sequential"] else ""))

    def walk(item, parent_id, depth):
        if truthy(item["HideInToDo"]) or item["CompletionDateTime"].strip():
            return
        add(item, parent_id, depth)
        for child in sorted(children.get(item["UID"], []), key=lambda c: float(c["ItemIndex"] or 0)):
            walk(child, ids[item["UID"]], depth + 1)

    roots = {i["Caption"].strip(): i for i in items if i["ParentUID"] not in by_uid}
    for name in ROOTS:
        walk(roots[name], None, 0)

    for uid, fields in rows:
        fields["dependsOn"] = sorted(ids[d] for d in depends.get(uid, []) if d in ids)

    print("\n".join(report))
    print(f"\n{len(rows)} new rows; {len(skipped)} skipped as duplicates: {skipped}")
    if args.dry_run:
        return

    changes = [{"table": "task", "id": ids[uid], "fields": fields, "clocks": {k: now for k in fields}} for uid, fields in rows]
    body = json.dumps({"cursor": version, "changes": changes}).encode()
    scheme = "https" if os.environ.get("KEYSTORE_PATH") else "http"
    request = urllib.request.Request(
        f"{scheme}://127.0.0.1:{os.environ.get('PORT', '8443')}/sync", data=body, method="POST",
        headers={"Authorization": f"Bearer {os.environ['API_TOKEN']}", "Content-Type": "application/json"},
    )
    # Loopback only, to the server's own self-signed certificate (issued for the public IP).
    context = ssl._create_unverified_context() if scheme == "https" else None
    with urllib.request.urlopen(request, context=context) as response:
        print("server accepted", len(json.load(response)["rows"]), "rows")


if __name__ == "__main__":
    main()
