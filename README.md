I've been annoyed for years that todo apps do not cater to me specifically, and then we built robots that could solve this problem without me having to become a mobile dev. So this is a todo app (Android, web app, Linux) built around two core concepts:

1) The most important thing to see on a todo app is only and all of the tasks you can actually do right then.
2) If it is even in the slightest bit incovnenient to add or look at tasks, you simply Will Not.

It is built to make sense to me. You probably don't want to use it, if only because this is what I am pretty sure if a giant rickety pile of slop, except I have no idea because I haven't even looked at the code. 100% Claude-generated horrors in here, or possibly not horrors.

If you do want to use it, here's what Claude has to say about what it does and how to run it:

## What it does

One task list, shared by an Android app, a web app, a GNOME top-bar extension, a small desktop app
and a Discord bot, all synced through a server you run yourself. It's single-user: one person's
list, however many devices.

**Three lists.** *Doing* is what to work on now: tasks you've starred, and ones due soon.
*Active* is everything you could do right now, and nothing that's waiting on something. *All* is the
whole tree. A task stays out of Active until its start date comes, its prerequisites are done, its
context holds (a wifi network for place contexts, a window of the day for time contexts) and, in a
step-by-step project, the steps before it are done.

**Quick add.** One line does most of it, the same on every device:
`call bank -d fri 3pm @phone // ask about the fee` is a task due Friday at 3pm, in the context
"phone", with a note. Folders (`work: …`), checklists (`groceries: milk, eggs`,
`packing [passport, charger]`), repeats (`every mon, thu`, `every 4 days after done`), reminders,
timed tasks (`~30m`), stars (`*`) and maybes (`?`) all have a shorthand. The full cheat sheet is in
the app's side menu and the web app's sidebar.

**Also:**
- Tasks, projects (done step by step), checklists and folders, nested as deep as you like.
- Plain-text notes on any task.
- A pinned "current task" and a countdown timer, both shared by every device.
- A focus mode that takes over every device (and pins the phone's screen).
- Reminders, and "today only" tasks that disappear at the end of the day.
- Review: what got done each day.
- Search with filters.
- Undo for completing and deleting.
- An Android home-screen widget.
- A Discord bot: `-- task` adds one, `.list` / `.doing` / `.active` list them, reactions complete
  them, and a morning digest nudges tasks that have sat in Doing for days.
- Hands-free adds by voice: "Hey Google, add call mom due Friday to my tasks" (in the car too) comes
  in through Google Tasks.

## How to run it

You'll need JDK 17. The phone app also needs the Android SDK (API 34, runs on Android 8 and up).

The repo has four parts: `core` (the shared rules: lists, quick add, recurrence, sync), `app` (Android),
`server` (sync, web app and Discord bot, one process) and `desktop` (GNOME).

### Phone app

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

It works on its own, offline. To sync, open Settings in the side menu and enter the server's URL and
API token.

### Server

```bash
./gradlew :server:distTar     # server/build/distributions/server.tar
tar xf server.tar && API_TOKEN=… server/bin/server
```

Settings are environment variables:

| Variable | |
|---|---|
| `API_TOKEN` | Required. The phone and the top bar send it; make it long and random (`openssl rand -hex 32`). |
| `DB_PATH` | The SQLite database (default `todo.db`). |
| `PORT` | Default 8443. |
| `HOST` | Set to `127.0.0.1` to serve the web app (see below). |
| `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` | To serve HTTPS directly, without a reverse proxy. |
| `BOT_TOKEN`, `ALLOWED_USER_IDS` | Turn on the Discord bot; only the listed Discord user ids are answered. |
| `DIGEST_CHANNEL_ID`, `DIGEST_TIME` | The bot's morning digest, e.g. `08:00`. |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REFRESH_TOKEN` | Hands-free adds: "Hey Google, add … to my tasks" lands in Raspberry. Setup in `server/DEPLOY.md`. |

The web app has no login of its own, so it's only served when the server listens on localhost, meant
to sit behind a reverse proxy that adds one. With Caddy, for example: `reverse_proxy` to the server,
`basic_auth` on everything except `/sync` and `/api/*` (those carry the API token). Listening on any
other address, only the token-protected sync and top-bar API exist. `server/DEPLOY.md` walks through
the whole setup on a cloud VM, Caddy included.

### Desktop (GNOME)

Tested on Ubuntu 24.04:

```bash
./desktop/install.sh
```

This installs the top-bar extension and the desktop app (a window on the web app). Put your
server's URL and API token in `~/.config/raspberry-tray/config.json`, then log out and back in.

### Tests

```bash
./gradlew :core:test :server:test :app:testDebugUnitTest
```
