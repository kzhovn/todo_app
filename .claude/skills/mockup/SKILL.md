---
name: mockup
description: Make an HTML mockup with options for a Raspberry feature or UI change, phone and web side by side, before building it. Use when Kira asks to mock something up, or for a UI change big enough to want sign-off first.
---

# Mockups

Start from `template.html` here (the app's own palette, light and dark, plus phone and web frames, task
rows, chips, the editor's title box and pick-one bar). Save as
`docs/superpowers/specs/<yyyy-mm-dd>-<feature>-mockups.html` and show it with SendUserFile
(`display: render`).

How Kira likes them:
- **Options, not one design.** Two to four, lettered (A, B, C...), each phone and web, each with a line on
  how it works and what it costs. Mark the one you'd choose with the `pick` tag.
- **Real-looking content** (her kind of tasks: "Renew passport", "Pay rent", a roommate decision), not lorem.
- **A Questions section at the end**: each open decision with a proposed answer.
- **Expect rounds.** She refines: "B but with C's tags", "drop the parent row", "more options for 4".
  Keep the file; put the revised set at the top and earlier rounds below ("Earlier rounds"). Use her
  words for what changed.
- **Wording she has rejected** (see `docs/BACKLOG.md`, Relationship wording): don't reuse it.

Once she picks, build it in both the phone and the web (see CLAUDE.md), then note the decision in
CLAUDE.md or the backlog if it's still open.
