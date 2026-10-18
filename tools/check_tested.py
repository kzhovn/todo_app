#!/usr/bin/env python3
"""PreToolUse hook on `git commit` (.claude/settings.json): refuses a commit whose core, server or app files
changed after that module's last passing tools/test.sh run. A core change needs all three, as both
clients build on it. Looks at what's staged plus paths named in the command (`git add a b && git commit`)."""
import json
import os
import re
import shlex
import subprocess
import sys

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
NEEDS = {"core": ["core", "server", "app"], "server": ["server"], "app": ["app"]}


def git_lines(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True).stdout.split("\n")


def adds(command):
    """What the command's `git add`s and `git commit -a` will stage: paths, or "." for everything."""
    named = []
    for segment in re.split(r"&&|\|\||;|\n", command):
        try:
            words = shlex.split(segment)
        except ValueError:
            continue
        if "git" not in words or len(words) <= words.index("git") + 1:
            continue
        verb, rest = words[words.index("git") + 1], words[words.index("git") + 2:]
        paths = [w for w in rest if not w.startswith("-")]
        if verb == "add":
            named += paths or ["."]
        elif verb == "commit" and any(w in ("-a", "--all") or re.fullmatch(r"-a\w+", w) for w in rest):
            named.append(".")
    return named


command = json.load(sys.stdin).get("tool_input", {}).get("command", "")
changed = set(git_lines("diff", "--cached", "--name-only"))
named = adds(command)
if named:  # a named directory brings in its changed files, as `git add` would
    changed |= set(git_lines("diff", "--name-only", "HEAD", "--", *named))
    changed |= set(git_lines("ls-files", "--others", "--exclude-standard", "--", *named))

stale = {}
for f in changed:
    module = f.split("/")[0]
    if module not in NEEDS or not os.path.isfile(f):
        continue
    for m in NEEDS[module]:
        stamp = f"build/test-pass-{m}"
        if not os.path.exists(stamp) or os.path.getmtime(f) > os.path.getmtime(stamp):
            stale.setdefault(m, f)

if stale:
    print(f"Not committing: tests haven't passed since these changed. Run tools/test.sh {' '.join(sorted(stale))} "
          f"first (e.g. {', '.join(sorted(set(stale.values()))[:3])}). If Kira wants it committed untested, "
          "she commits it herself.", file=sys.stderr)
    sys.exit(2)
