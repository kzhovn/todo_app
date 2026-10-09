---
name: dupcheck
description: Check Raspberry for drift between its clients and leftover code - wording written in both the app and the server, functions nothing calls, unused CSS. Use after a feature touching both clients, before pushing, or when asked to review for duplication.
---

# Duplication check

Run `tools/dupcheck.py`. It lists candidates in three groups; each needs a look, not a blind fix:

- **Wording in both the app and the server**: move it to `Labels` (core) and use it from both. Wording
  that differs only by the platform's conventions can stay.
- **Functions nothing calls**: usually left over from a refactor; delete them (and their imports). If one
  is called by a framework (an annotation, a callback), add it to the script's `CALLBACKS` instead.
- **CSS classes nothing uses** (web `app.css`, the top bar's `stylesheet.css`): delete the rule, unless the
  platform styles it by name (add it to `PLATFORM_CLASSES`).

The script only finds text-level duplicates. Logic written twice is the bigger risk: business rules belong
in `:core` (see CLAUDE.md's Conventions), carried out by the app's `TaskRepository` and the server's
`TaskService`. When a change adds the same `if`/`filter` on both sides, move it to core.

Shared code goes in `:core`, never the server: the phone must keep working offline.
