---
name: ship
description: Wrap up a Raspberry change - test, commit, build, and tell Kira what she has to do to get it everywhere (deploy the server, log out for the top bar, install on the phone). Use at the end of any change, or when she says "install and push" or asks what's left.
---

# Ship a change

1. **Test**: `tools/test.sh` (all three modules, failures only).
2. **Commit** only your own files: another session may be editing the repo at the same time, so check
   `git status` and stage by path, never `git add -A` blindly. Push only when Kira asks.
3. **Build** what changed, yourself (never leave a build or install step to Kira):
   `./gradlew -q :app:assembleDebug :server:distTar`.
4. **Server**, if `server/` or `core/` changed: `tools/deploy.sh` (it backs up the database, swaps the build
   in, and rolls back if the server doesn't come up; `tools/deploy.sh status` shows recent warnings). A new
   task type or field: deploy before installing on the phone, or the phone's sync fails.
5. **Phone**, if `app/` or `core/` changed: `tools/install_phone.sh` (see the install-phone skill).
6. **Top bar / desktop app**, if `desktop/` changed: run `./desktop/install.sh` (it copies into this user's
   `~/.local/share` and leaves the config alone), then check `diff -rq desktop/raspberry@kzhovn
   ~/.local/share/gnome-shell/extensions/raspberry@kzhovn` is empty.
7. **Tell Kira** what's left for her (only what needs her: a log-out, a phone that isn't reachable), from what the change touched (`git diff --stat <last shipped>..HEAD`):
   - `desktop/raspberry@kzhovn/` changed: it's installed; she logs out and back in (GNOME Shell only reloads
     an extension then).
   - Pushed or not, and anything she still has to decide.
8. **Stop Gradle** to free its memory (the Gradle and Kotlin daemons hold several GB):
   `./gradlew --stop`. It stops every Gradle daemon, including another session's build, so skip it if
   `pgrep -f GradleWrapperMain` shows a build still running.
