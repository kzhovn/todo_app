---
name: ship
description: Wrap up a Raspberry change - test, commit, build, and tell Kira what she has to do to get it everywhere (deploy the server, log out for the top bar, install on the phone). Use at the end of any change, or when she says "install and push" or asks what's left.
---

# Ship a change

1. **Test**: `tools/test.sh` (all three modules, failures only).
2. **Commit** only your own files: another session may be editing the repo at the same time, so check
   `git status` and stage by path, never `git add -A` blindly. Push only when Kira asks.
3. **Build** what changed: `./gradlew -q :app:assembleDebug :server:distTar`.
4. **Phone**, if `app/` or `core/` changed: `tools/install_phone.sh` (see the install-phone skill).
5. **Tell Kira** what's left for her, from what the change touched (`git diff --stat <last shipped>..HEAD`):
   - `server/` or `core/` changed: deploy the server. She runs, on the VM:
     `sudo systemctl stop todo && tar xf ~/server.tar -C ~ && sudo systemctl start todo`
     (`server/build/distributions/server.tar`, copied over). A new task type or field: before the phone
     syncs, or the phone's sync fails.
   - `desktop/raspberry@kzhovn/` changed: log out and back in (GNOME Shell only reloads an extension then).
   - Pushed or not, and anything she still has to decide.
