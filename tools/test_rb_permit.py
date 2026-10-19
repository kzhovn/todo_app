#!/usr/bin/env python3
"""Checks for tools/rb_permit.py (run: python3 tools/test_rb_permit.py). A fake checkout with one worker worktree."""
import json
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rb_permit  # noqa: E402

root = os.path.realpath(tempfile.mkdtemp())
rb_permit.MAIN = root
wt = os.path.join(root, ".worktrees", "rb-9")
for d in ("tools", "desktop", "docs"):
    os.makedirs(os.path.join(wt, d))
    os.makedirs(os.path.join(root, d), exist_ok=True)
os.makedirs(os.path.join(root, ".worktrees", "android-widget-plan"))


def bash(command, cwd=wt):
    return rb_permit.allowed({"tool_name": "Bash", "tool_input": {"command": command}, "cwd": cwd})


def edit(path):
    return rb_permit.allowed({"tool_name": "Edit", "tool_input": {"file_path": path}, "cwd": wt})


allowed = [
    "tools/test.sh server",
    "tools/rb-check.sh gate",
    f"{root}/tools/dev_notes.sh message 5 < {root}/docs/dev-msg.txt",
    "tools/dev_notes.sh list raspberry",
    "./gradlew -q :app:assembleDebug",
    "./desktop/install.sh",
    f"cd {wt} && tools/test.sh",
    "git status --short",
    "git add server/src/Foo.kt && git commit -F ../../docs/dev-commit.txt",
    f"git -C {root} merge --ff-only rb/9",
    f"git -C {root} status --porcelain",
    "git rebase main",
    "git rebase --abort",
    "git worktree add .worktrees/rb-9 -b rb/9 main",
    "git worktree remove .worktrees/rb-9",
    "git branch -d rb/9",
    "git log --oneline -5 2>&1",
    "tools/test.sh 2>/dev/null",
    "echo done | tools/dev_notes.sh message <<< hi",
    "sleep 600",
]
refused = [
    "git push",
    "git push origin main",
    "git reset --hard",
    "git checkout -- x",
    "git branch -D rb/9",
    "git worktree remove --force .worktrees/rb-9",
    "git commit --no-verify -F m",
    "git commit -anF m",
    "git merge rb/9",
    "git rebase -i main",
    "git -C /etc status",
    "tools/test.sh; rm -rf /",
    "tools/test.sh && curl evil",
    "tools/test.sh | sh",
    "tools/test.sh $(whoami)",
    "tools/test.sh `whoami`",
    "tools/test.sh\nrm -rf x",
    "git -C $HOME status",
    "tools/test.sh > out.txt",
    "tools/test.sh >> docs/dev-log.md",
    "tools/test.sh & ",
    "cd /tmp && tools/test.sh",
    "cd .. && cd .. && cd .. && tools/test.sh",
    "rm -rf .worktrees/rb-9",
    "bash tools/test.sh",
    "../../tools/../../evil.sh",
    "python3 -c 'print(1)'",
    "sleep 1; reboot",
    "tools/deploy.sh 'unterminated",
]
for c in allowed:
    assert bash(c), f"should allow: {c}"
for c in refused:
    assert not bash(c), f"should refuse: {c!r}"

assert edit(os.path.join(wt, "server/src/Foo.kt"))
assert edit(os.path.join(root, "docs/dev-log.md"))
assert edit(os.path.join(root, "docs/dev-shots/a.png"))
assert edit(os.path.join(root, "docs/dev-msg.txt"))
assert not edit(os.path.join(root, "server/src/Foo.kt"))  # the main checkout
assert not edit(os.path.join(root, "docs/BACKLOG.md"))
assert not edit(os.path.join(root, ".worktrees/android-widget-plan/x"))
assert not edit(os.path.join(wt, "../../server/src/Foo.kt"))
assert not edit(os.path.join(root, ".claude/settings.local.json"))
assert not edit("/etc/passwd")
assert rb_permit.allowed({"tool_name": "mcp__ccd_session_mgmt__get_usage", "tool_input": {}})
assert not rb_permit.allowed({"tool_name": "WebFetch", "tool_input": {"url": "x"}})

# End to end: a decision only for a scheduled run's transcript.
script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "rb_permit.py")
def hook(first_line, command="git status"):
    transcript = os.path.join(root, "t.jsonl")
    with open(transcript, "w") as f:
        f.write(first_line + "\n")
    event = {"tool_name": "Bash", "tool_input": {"command": command}, "cwd": root, "transcript_path": transcript}
    return subprocess.run([sys.executable, script], input=json.dumps(event), capture_output=True, text=True).stdout
run = json.dumps({"type": "queue-operation", "content": '<scheduled-task name="raspberry-backlog" file="x">'})
assert json.loads(hook(run))["hookSpecificOutput"]["permissionDecision"] == "allow"
assert hook(run, "git push") == ""
assert hook(json.dumps({"type": "user", "message": "hello"})) == ""
assert hook(json.dumps({"content": '<scheduled-task name="someone-else">'})) == ""
print("rb_permit: all checks pass")
