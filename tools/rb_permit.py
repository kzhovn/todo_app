#!/usr/bin/env python3
"""PreToolUse hook: approves the backlog worker's own commands in its scheduled runs only, so they don't stall at a
prompt nobody sees (scheduled runs start in default permission mode). A session whose transcript doesn't start with
one of our scheduled tasks gets no decision from here: Kira's sessions prompt as before. Anything not listed falls
through to the normal prompt too, which in a scheduled run means it stalls rather than runs.

It guards against the wrong command, not a hostile agent: the worker runs tests, which are code it writes."""
import json
import os
import re
import shlex
import sys

MAIN = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
MARKER = 'scheduled-task name=\\"raspberry-'  # as it appears, JSON-escaped, on the transcript's first line
SCRIPTS = {"tools/rb-check.sh", "tools/dev_notes.sh", "tools/deploy.sh", "tools/test.sh", "tools/install_phone.sh",
           "desktop/install.sh", "gradlew"}
READ_ONLY_TOOLS = {"mcp__ccd_session_mgmt__get_usage", "mcp__ccd_session_mgmt__get_session"}
PLAIN = {"echo", "printf", "true"}
OPERATORS = {"|", "||", "&&", ";"}


def inside(path, *roots):
    path = os.path.realpath(path)
    return any(path == r or path.startswith(r + os.sep) for r in roots)


def worktrees():
    return [os.path.join(MAIN, ".worktrees", d) for d in os.listdir(os.path.join(MAIN, ".worktrees"))
            if d.startswith("rb-")] if os.path.isdir(os.path.join(MAIN, ".worktrees")) else []


def editable(path):
    """The worker's worktrees, and its own files in docs/ (dev-log.md, dev-shots/, message and commit texts)."""
    real = os.path.realpath(path)
    if inside(real, *[os.path.realpath(w) for w in worktrees()]) and "/.git/" not in real:
        return True
    return real.startswith(os.path.join(MAIN, "docs", "dev-"))


def git_ok(args, cwd):
    if args[:1] == ["-C"]:
        if len(args) < 2 or not inside(os.path.join(cwd, args[1]), MAIN):
            return False
        args = args[2:]
    if not args:
        return False
    verb, rest = args[0], args[1:]
    if verb in ("status", "log", "diff", "show", "rev-parse", "add"):
        return True
    if verb == "commit":
        return not any(a in ("--no-verify", "-n") or re.fullmatch(r"-[a-zA-Z]*n[a-zA-Z]*", a) for a in rest)
    if verb == "rebase":
        return set(rest) <= {"main", "--abort", "--continue"}
    if verb == "merge":
        return "--ff-only" in rest
    if verb == "worktree":
        return rest[:1] in (["add"], ["list"]) or (rest[:1] == ["remove"] and not {"-f", "--force"} & set(rest))
    if verb == "branch":
        return not {"-D", "-f", "--force", "-M", "-m", "-c", "-C"} & set(rest) and \
            (not rest or rest[0] in ("-d", "--list", "--show-current"))
    return False


def segment_ok(words, cwd):
    """One command between operators; returns the new cwd (for `cd`) or None if it isn't allowed."""
    prog, args = words[0], words[1:]
    if prog == "cd":
        target = os.path.join(cwd, args[0]) if args else ""
        return os.path.realpath(target) if args and inside(target, MAIN) else None
    if prog in PLAIN or (prog == "sleep" and len(args) == 1 and args[0].isdigit()):
        return cwd
    if prog == "git":
        return cwd if git_ok(args, cwd) else None
    real = os.path.realpath(os.path.join(cwd, prog))
    for root in [MAIN, *worktrees()]:
        if any(real == os.path.join(os.path.realpath(root), s) for s in SCRIPTS):
            return cwd
    return None


def bash_ok(command, cwd):
    # Substitutions could run anything and variables hide what a path really is, so neither passes: the worker uses
    # literal paths, and free text goes through files (`< docs/dev-msg.txt`, `git commit -F`). One line only.
    if any(c in command for c in "`$\n\r"):
        return False
    lexer = shlex.shlex(command, posix=True, punctuation_chars=";&|<>")
    lexer.whitespace_split = True
    try:
        tokens = list(lexer)
    except ValueError:
        return False
    words, i = [], 0
    while i < len(tokens) + 1:
        t = tokens[i] if i < len(tokens) else ";"
        if t in OPERATORS:
            if words:
                cwd = segment_ok(words, cwd)
                if cwd is None:
                    return False
            words = []
        elif t in (">", ">&", "<", "<<<") and i + 1 < len(tokens):
            target = tokens[i + 1]
            # Output only to /dev/null or another stream; input from a file or a here-string.
            if t in (">", ">&") and target not in ("/dev/null", "1", "2"):
                return False
            if words and t != "<<<" and words[-1] in ("1", "2"):  # the 2 of 2>&1
                words.pop()
            i += 1
        elif set(t) <= set(";&|<>"):
            return False  # >>, <<, &, and the like
        else:
            words.append(t)
        i += 1
    return True


def allowed(event):
    tool, data, cwd = event.get("tool_name"), event.get("tool_input", {}), event.get("cwd") or MAIN
    if tool in READ_ONLY_TOOLS:
        return True
    if tool in ("Edit", "Write", "NotebookEdit"):
        return editable(data.get("file_path") or data.get("notebook_path") or "")
    if tool == "Bash":
        return bash_ok(data.get("command", ""), cwd)
    return False


def scheduled_run(event):
    try:
        with open(event.get("transcript_path") or "", encoding="utf-8") as f:
            return MARKER in f.readline()
    except OSError:
        return False


if __name__ == "__main__":
    event = json.load(sys.stdin)
    if scheduled_run(event) and allowed(event):
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "allow",
                                                 "permissionDecisionReason": "backlog worker (tools/rb_permit.py)"}}))
