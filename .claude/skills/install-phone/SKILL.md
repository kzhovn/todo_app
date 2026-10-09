---
name: install-phone
description: Install the Raspberry Android app's debug build on Kira's phone over wireless debugging. Use when asked to install, put it on the phone, or "debug is on".
---

# Install on the phone

Run `tools/install_phone.sh --build` (or without `--build` when the APK is already current). It finds the
phone's wireless-debugging port, refuses if a focus session is running, pushes the APK and installs it,
retrying if the phone drops off wifi, then prints `lastUpdateTime` to confirm.

What its exit codes mean, and what to tell Kira:
- **2, not reachable**: the phone isn't advertising wireless debugging. Ask her to turn it on (and keep
  the Wireless debugging screen open). If `adb connect` fails while the port is open, the pairing was lost
  (the phone's "Paired devices" list is empty): ask for a pairing code and IP:port from
  "Pair device with pairing code", then `~/Android/Sdk/platform-tools/adb pair <ip>:<port> <code>`.
  Use the SDK's adb, not `/usr/bin/adb` (too old to pair).
- **3, focus session**: don't install (it would break the screen pin). Check phone UI with the Robolectric
  renders instead (`RENDER_DIR=... ./gradlew :app:testDebugUnitTest --tests '*ScreenshotRender*'`, or a
  render in `RelatedSectionTest`).
- **1**: push or install failed after retries; report the output.

If the change adds a task type or field, remind Kira to deploy the server first: an old server can't
decode it, and the phone's next sync fails.
