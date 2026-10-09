---
name: web-preview
description: Run the Raspberry web app locally with sample data and look at it in the browser pane. Use to check a web change (the editor, lists, Doing's sections, popovers) before calling it done.
---

# Preview the web app

1. Build: `./gradlew -q :server:installDist`.
2. Start it with the browser tools' `preview_start` and the name `web-preview` (`.claude/launch.json` runs
   `tools/web_preview.sh`: port 8099, its own database under `build/preview/`, a throwaway token, web pages
   on). Rebuild and restart it after each server change; the old process keeps the old code.
3. Fill it: `tools/web_preview.sh seed` adds a folder, a project, a high-priority task, a checklist, a
   waiting item, a due task and a repeat. For more, POST to `/quickadd` (`text=...`, any quick add syntax)
   or `/tasks/new` (`title=...&type=FOLDER&parent=<id>`). Ids are in `/all`'s `data-id` attributes.
4. Look: navigate to `/all`, `/doing` or `/tasks/<id>?mode=ALL` (the editor in the side panel). Open a
   popover with JS (`document.querySelector('.pp[data-kind=tree]').open = true`) before a screenshot.
5. `preview_stop` when done. Delete `build/preview/preview.db` for a fresh start.

Interaction quirks: a screenshot right after a click can come before htmx swaps the page; check with
`get_page_text` or `read_network_requests` before deciding something didn't work.
