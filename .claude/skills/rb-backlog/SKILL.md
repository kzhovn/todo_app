---
name: rb-backlog
description: Work through Kira's dev notes (her DMs to the Raspberry bot) - take the oldest open note plus related ones, fix them in a worktree, ask questions through the bot, ship. Run by the raspberry-backlog scheduled task, or when Kira says "/rb-backlog" or "work the backlog".
---

# Work the backlog

Kira DMs the Raspberry bot ideas and bugs; each DM is a dev note on the server. You work through them in a fresh
session, unattended. Design: `docs/superpowers/specs/2026-10-18-dev-workflow-design.md` (read it if something here
is unclear). Follow CLAUDE.md as for any change. Never push.

**Permissions.** `tools/rb_permit.py` approves only exact command shapes, in a scheduled run; anything else
stops at a prompt nobody sees, so use the commands below **exactly as written**:
- **one command per Bash call**: no `&&`, `;`, `|`, `$`, quotes, backticks, globs or `~`; only `2>&1` or
  `>/dev/null` as redirects. Write literal paths (run `cd <path>` as its own call when you need to move).
- `tools/rb-check.sh`, `tools/dev_notes.sh`, `tools/deploy.sh`, `tools/install_phone.sh`, `./desktop/install.sh`
  run from MAIN only; `tools/test.sh`, `tools/render_screens.sh` and `./gradlew` from the worktree.
- free text (messages, commit messages) goes in `MAIN/docs/dev-msg.txt` / `MAIN/docs/dev-commit.txt` (Write tool)
  first. Edits are allowed in `.worktrees/rb-*/` and those `docs/dev-*` files only.

`MAIN` below means the main checkout's literal path: the first one in `git worktree list`. Logs live in
`MAIN/docs/` (gitignored, so they're not in a worktree).

## 1. Should this run go ahead?

Scheduled runs only (the prompt says "scheduled"); a run Kira started skips to the lock.
1. `tools/rb-check.sh gate`: any output is the reason to stop. Say it in one line and end.
2. `mcp__ccd_session_mgmt__get_usage`: the 5-hour window over 50% used → stop ("leaving the window to Kira").
3. `tools/rb-check.sh notes`: `none` → stop.

Then `tools/rb-check.sh lock backlog` (fails if another run holds it: stop). From here on, **always** finish
with `tools/rb-check.sh unlock`, whatever happens, and run `tools/rb-check.sh touch` after each note.

## 2. Pick the batch

`tools/dev_notes.sh list raspberry` gives every note as JSON. Open notes are `open`, plus any `working` ones: you
hold the lock, so a `working` note is a crashed run's; its branch `rb/<id>` may hold progress, and
`MAIN/docs/dev-log.md` its handoff.

Take the **oldest open note**, plus up to 3 more that touch the same feature or files (judge from the text and
replies). Fewer is fine. Read each note's `replies` (Kira's answers to earlier questions) and fetch attachments
with `tools/dev_notes.sh attachment <id> <name> MAIN/docs/dev-shots/in-<id>-<name>`, then Read them.

**Ask before coding.** If a note is unclear, or is a behaviour change or needs a mockup (CLAUDE.md: ask first),
ask now (section 5) before reading much code, and leave it out of the batch.

Mark each note in the batch: `tools/dev_notes.sh state <id> working`.

## 3. Set up

From MAIN, one call each:
```
git worktree add .worktrees/rb-<first id> -b rb/<first id> main      (if rb/<first id> exists: without -b rb/…)
cd MAIN/.worktrees/rb-<first id>
tools/test.sh
```
Work, test and commit **inside the worktree** (the commit hook checks the checkout you're in). If `tools/test.sh`
fails on untouched `main`: write "main is red: <failing tests>" to `MAIN/docs/dev-msg.txt`, `cd MAIN`, run
`tools/dev_notes.sh message < MAIN/docs/dev-msg.txt`, remove the worktree, unlock, stop.

## 4. Each note

1. Make the change the normal way (CLAUDE.md conventions; both clients unless it's clearly one-sided; `Labels`).
2. `tools/test.sh <modules>` green, then one commit for the note: the message in `MAIN/docs/dev-commit.txt`,
   then `git add <files>` and `git commit -F MAIN/docs/dev-commit.txt` as two calls (with the attribution line CLAUDE.md's commits
   carry). The repo is public: describe the change, never quote the note or link Discord. Two failed attempts at green → treat it as a question (section 5).
3. For a visible phone change: `tools/render_screens.sh MAIN/docs/dev-shots/<note id>` (renders every screen;
   name the relevant PNG in the log). No web screenshots in an unattended run: the browser tools aren't allowed.
4. Append to `MAIN/docs/dev-log.md`: `- <date> note <id>: <what changed> (<commit>) [<screenshot>]`.
5. `tools/dev_notes.sh state <id> done`, then `tools/rb-check.sh touch` (both from MAIN: `cd` there and back).

## 5. A question for Kira

1. Commit whatever is done on the branch.
2. Append a handoff to `MAIN/docs/dev-log.md`: note id, branch, what's done, what's left, the question.
3. Get this session's link (`mcp__ccd_session_mgmt__get_session` with `self`: `link`). Write the question, then
   "Or answer in the session: <link>", to `MAIN/docs/dev-msg.txt`, and run
   `tools/dev_notes.sh message <id> < MAIN/docs/dev-msg.txt` (it arrives as a reply to her DM) and
   `tools/dev_notes.sh state <id> question`.
4. Finish the rest of the batch first. Then ship what's done (section 6, leaving this note's branch alone if it
   has unfinished work), `tools/rb-check.sh unlock`, and wait for the answer: run
   `tools/dev_notes.sh wait <id>` **in the background** and end your turn. It costs nothing while it waits.
5. When it exits (she replied in Discord: read the note's new reply with `list`), or Kira types in this session:
   `tools/rb-check.sh lock backlog` (held: run `sleep 600` in the background and try again), set the note
   `working`, carry on, and ship as usual. If the wait timed out, end: the next run picks the note up from the
   branch and the handoff once she replies.

## 6. Ship

From the worktree:
1. `git rebase main`. A conflict: `git rebase --abort`, ask about it (section 5), stop shipping. If `main` had
   moved, `tools/test.sh` again.
2. Only if `git -C MAIN status --porcelain` prints nothing: `git -C MAIN merge --ff-only rb/<first id>`.
   Otherwise leave the branch and say so in the digest (Kira is mid-change in the main checkout).
3. From MAIN (`cd` there), once merged, what changed since the batch started:
   - `server/` or `core/`: `tools/deploy.sh`. **Hold and ask instead** (section 5) if the diff touches `Task.kt`,
     `TaskType`, `taskFields` or a database migration: the phone mustn't sync a field the old server can't decode.
   - `app/` or `core/` (and no hold): `tools/install_phone.sh`. It often fails unattended (wireless debugging
     drops); a failure goes in the digest, nothing more.
   - `desktop/`: `./desktop/install.sh`.
4. `git worktree remove .worktrees/rb-<first id>` and `git branch -d rb/<first id>` (both refuse if anything is
   unmerged or uncommitted: then leave them and mention it in the digest).

## 7. Digest and end

The 23:00 scheduled run (or a run Kira asks for one in) sends the day's digest:
write it to `MAIN/docs/dev-msg.txt` and run `tools/dev_notes.sh message < MAIN/docs/dev-msg.txt`. It covers today's
`MAIN/docs/dev-log.md` lines: done (commits), waiting on
Kira, branches waiting on a clean checkout, leftover worktrees, failed installs, what to try on the phone, and
the usage from `get_usage`. No runs today, no digest.

Always end with `tools/rb-check.sh unlock` and a short summary in the session.
