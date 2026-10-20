#!/usr/bin/env python3
"""PreToolUse hook: approves the backlog worker's own commands in its scheduled runs only, so they don't stall at a
prompt nobody sees (scheduled runs start in default permission mode). A session that isn't one of those runs gets no
decision from here: Kira's sessions prompt as before. Anything not listed falls through to the normal prompt too,
which in a scheduled run means it stalls rather than runs.

Exact command shapes, one command per call, not a shell parser: parsing shell right is how hooks like this leak.
It guards against the wrong command and against text the worker reads steering it, not a hostile agent: the worker
runs tests, which are code it writes, so tools/test.sh, render_screens.sh and gradlew come from its worktree. Scripts
that reach outside (Discord, the VM, the phone) only ever run from the main checkout."""
import filecmp
import json
import os
import re
import subprocess
import sys

MAIN = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
TASKS = tuple(f'<scheduled-task name="{t}"' for t in ("raspberry-backlog", "raspberry-backlog-now", "raspberry-hygiene", "raspberry-hygiene-now"))
READ_ONLY_TOOLS = {"mcp__ccd_session_mgmt__get_usage", "mcp__ccd_session_mgmt__get_session"}
# No quotes, braces, globs, ~, #, $, backticks, parentheses or newlines: none are needed (free text goes through
# files), and each is a way for the shell to read a command differently from this script.
SAFE = re.compile(r"[A-Za-z0-9 _./:=@,+%<>&-]*")
REDIRECTS = re.compile(r"(\s+(2>&1|>/dev/null|2>/dev/null))+$")
# What a worker's branch can't change and still merge through this hook: settings and skills, the guard scripts, and
# every script the hook approves from the main checkout. Kira merges those herself.
PROTECTED = (".claude/", "tools/", "desktop/install.sh", "gradlew", "gradle/wrapper/")
# Run from a worktree, these must still be main's own copies: the worktree's build is the accepted code, not a
# script the worker rewrote.
FROM_WORKTREE = ("tools/test.sh", "tools/render_screens.sh", "gradlew")
# Options that make a read-only git verb write a file, run an external program, or read outside the repo.
READ_ONLY_FORBIDDEN = ("--output", "--ext-diff", "--textconv", "--no-index")
# Read-only commands and the flags they may take (none that write, follow commands or run anything).
READERS = {
    "grep": re.compile(r"-[rnilcvwxEFoHhsq]+|-[ABCm]\d+"),  # -r, not -R: no following symlinks out
    "ls": re.compile(r"-[laAhRt1d]+"),
    "wc": re.compile(r"-[lwc]+"),
    "head": re.compile(r"-n\d+|-\d+|-n"),
    "tail": re.compile(r"-n\d+|-\d+|-n"),
}
GRADLE_ARG = re.compile(r"-q|--continue|--offline|:?[A-Za-z0-9_][A-Za-z0-9:_-]*")
WORKTREE = re.compile(r"\.worktrees/rb-\d+")
BRANCH = r"rb/\d+"
DEPENDABOT = re.compile(r"origin/dependabot/(?!.*\.\.)[A-Za-z0-9._/-]+")  # no `..`: a ref range or a climb out
# The Friday run reads Dependabot's PR list: numbers, titles and branch names only, never bodies (text it didn't
# choose to read is how a prompt gets injected).
DEPENDABOT_PRS = "gh pr list --author app/dependabot --state open --json number,title,headRefName".split()


def real(path, cwd):
    return os.path.realpath(os.path.join(cwd, path))


def worktree_of(path):
    """The rb-<n> worktree a real path lies in, or None. A real one: git keeps a .git file in each."""
    m = re.match(re.escape(MAIN) + r"/\.worktrees/rb-\d+(?=/|$)", path)
    return m.group(0) if m and os.path.isfile(os.path.join(m.group(0), ".git")) else None


def in_main(path):
    return path == MAIN or path.startswith(MAIN + "/")


def is_doc(path, *names):
    """A docs/dev-* file in the main checkout: one of `names`, or under docs/dev-shots/."""
    docs = os.path.join(MAIN, "docs")
    return path in [os.path.join(docs, n) for n in names] or path.startswith(os.path.join(docs, "dev-shots") + "/")


def editable(path):
    p = os.path.realpath(path)
    parts = p.split("/")
    if ".git" in parts or ".claude" in parts:
        return False
    wt = worktree_of(p)
    if wt:
        return p != wt
    return is_doc(p, "dev-log.md", "dev-msg.txt", "dev-commit.txt", "dev-hygiene-log.md", "dev-architecture-notes.md")


def script(token, cwd, name, from_worktree=False):
    # No symlinks: a link to main's deploy.sh from a worktree would run it against the worktree's files.
    p = os.path.normpath(os.path.join(cwd, token))
    if p != os.path.realpath(p):
        return False
    if p == os.path.join(MAIN, name):
        return True
    wt = worktree_of(p)
    return from_worktree and wt is not None and p == os.path.join(wt, name) and name in FROM_WORKTREE \
        and filecmp.cmp(p, os.path.join(MAIN, name), shallow=False)


def touches_protected(branch):
    # --no-renames lists a moved file under its old name too; -z keeps unusual names unquoted.
    files = subprocess.run(["git", "-C", MAIN, "diff", "--no-renames", "-z", "--name-only", f"main...{branch}"],
                           capture_output=True, text=True)
    return files.returncode != 0 or any(f.startswith(PROTECTED) for f in files.stdout.split("\0") if f)


def forbidden_option(arg):
    """git takes any unambiguous abbreviation of a long option, so `--no-ind` is `--no-index`."""
    if arg.startswith("-o"):
        return True
    name = arg.split("=", 1)[0]
    return name.startswith("--") and any(f.startswith(name) or name.startswith(f) for f in READ_ONLY_FORBIDDEN)


def git_ok(args, cwd):
    in_wt = worktree_of(os.path.realpath(cwd)) is not None
    if args[:2] == ["-C", MAIN] or (args[:1] == ["-C"] and len(args) > 1 and real(args[1], cwd) == MAIN):
        rest = args[2:]
        return rest == ["status", "--porcelain"] or (
            len(rest) == 3 and rest[:2] == ["merge", "--ff-only"] and re.fullmatch(BRANCH, rest[2]) is not None
            and not touches_protected(rest[2]))
    if not args or args[0].startswith("-"):
        return False
    verb, rest = args[0], args[1:]
    if verb in ("status", "log", "diff", "show", "rev-parse"):
        # Read-only, as long as nothing writes a file, runs an external diff, or reads outside the repo (a diff with
        # a path outside it prints any file). Options go by prefix: git takes abbreviations.
        return in_main(cwd) and not any(forbidden_option(a) or (not a.startswith("-") and not in_main(real(a, cwd)))
                                        for a in rest)
    if verb == "worktree":
        return in_main(cwd) and rest == ["list"] or (rest[:1] == ["remove"] and len(rest) == 2 and WORKTREE.fullmatch(rest[1]) is not None) or (
            rest[:1] == ["add"] and cwd == MAIN and len(rest) in (3, 5) and WORKTREE.fullmatch(rest[1]) is not None
            and rest[-1] == "main" and (len(rest) == 3 or (rest[2] == "-b" and re.fullmatch(BRANCH, rest[3])))) or (
            # Resuming a crashed run's branch.
            rest[:1] == ["add"] and cwd == MAIN and len(rest) == 3 and WORKTREE.fullmatch(rest[1]) is not None
            and rest[2] == "rb/" + rest[1].rsplit("-", 1)[1]) or (
            # A Dependabot PR's branch, to test and take its patch updates.
            rest[:1] == ["add"] and cwd == MAIN and len(rest) == 5 and WORKTREE.fullmatch(rest[1]) is not None
            and rest[2] == "-b" and re.fullmatch(BRANCH, rest[3]) is not None and DEPENDABOT.fullmatch(rest[4]) is not None)
    if verb == "fetch":
        return rest == ["origin"] and in_main(cwd)
    if verb == "tag":
        return rest == ["-f", "last-review", "main"] and cwd == MAIN
    if verb == "branch":
        return in_main(cwd) and len(rest) == 2 and rest[0] == "-d" and re.fullmatch(BRANCH, rest[1]) is not None
    if not in_wt:  # the rest change history: only in a worker's worktree
        return False
    if verb == "add":
        return bool(rest) and all(a == "-A" or (not a.startswith("-") and worktree_of(real(a, cwd))) for a in rest)
    if verb == "commit":
        return len(rest) == 2 and rest[0] == "-F" and real(rest[1], cwd) == os.path.join(MAIN, "docs", "dev-commit.txt")
    if verb == "rebase":
        return rest in (["main"], ["--abort"], ["--continue"])
    return False


def bash_ok(command, cwd):
    if not SAFE.fullmatch(command):
        return False
    command = REDIRECTS.sub("", command.strip())
    words = command.split()
    if not words:
        return False
    # `<` only feeds dev_notes.sh message its text file (checked below); no other redirection survives.
    if "<" in words and not script(words[0], cwd, "tools/dev_notes.sh"):
        return False
    if any(set(w) & set("<>&") and w != "<" for w in words):
        return False
    prog, args = words[0], words[1:]
    if prog == "cd":
        return len(args) == 1 and (real(args[0], cwd) == MAIN or worktree_of(real(args[0], cwd)) is not None)
    if prog == "sleep":
        return len(args) == 1 and args[0].isdigit()
    if prog in READERS:
        # Read-only, plain flags, paths inside the repo. Anything fancier (a quoted pattern) prompts: the skill points
        # the worker at the Grep, Glob and Read tools, which need no approval.
        flags = [a for a in args if a.startswith("-")]
        return all(READERS[prog].fullmatch(f) for f in flags) and all(in_main(real(a, cwd)) for a in args if not a.startswith("-")) \
            and (prog != "grep" or len(args) > len(flags))
    if prog == "mkdir":
        return args[:1] == ["-p"] and len(args) > 1 and all(
            worktree_of(real(a, cwd)) or is_doc(real(a, cwd)) for a in args[1:])
    if prog == "git":
        return git_ok(args, cwd)
    if prog == "gh":
        return words == DEPENDABOT_PRS
    if script(prog, cwd, "tools/rb-check.sh"):
        return args in (["gate"], ["notes"], ["touch"], ["unlock"]) or (len(args) == 2 and args[0] == "lock" and args[1].isalpha())
    if script(prog, cwd, "tools/dev_notes.sh"):
        n = len(args)
        if n == 2 and args[0] in ("list",) and args[1].isalpha():
            return True
        if n == 2 and args[0] == "wait" and args[1].isdigit():
            return True
        if n == 3 and args[0] == "state" and args[1].isdigit() and args[2] in ("working", "done", "question", "open", "skip"):
            return True
        if n == 4 and args[0] == "propose" and args[1] in ("hygiene", "refactor", "deps", "vm") and args[2] == "<":
            return real(args[3], cwd) == os.path.join(MAIN, "docs", "dev-msg.txt")
        if args[:1] == ["message"] and n in (3, 4) and args[-2] == "<" and (n == 3 or args[1].isdigit()):
            return real(args[-1], cwd) == os.path.join(MAIN, "docs", "dev-msg.txt")
        if n == 4 and args[0] == "attachment" and args[1].isdigit() and re.fullmatch(r"[A-Za-z0-9._-]+", args[2]):
            return is_doc(real(args[3], cwd))
        return False
    if script(prog, cwd, "tools/dupcheck.py"):
        return args == []
    if script(prog, cwd, "tools/hotspots.sh"):
        return args == [] or (len(args) == 1 and args[0].isdigit())
    if script(prog, cwd, "tools/deploy.sh"):
        return args in ([], ["status"])
    if script(prog, cwd, "tools/install_phone.sh") or script(prog, cwd, "desktop/install.sh"):
        return args == []
    if script(prog, cwd, "tools/test.sh", from_worktree=True):
        return all(a in ("core", "server", "app") for a in args)
    if script(prog, cwd, "tools/render_screens.sh", from_worktree=True):
        return len(args) == 1 and is_doc(real(args[0], cwd))
    if script(prog, cwd, "gradlew", from_worktree=True):
        # The worktree's build is code the worker writes anyway; but no -p (another project) or -I (an init script).
        return all(GRADLE_ARG.fullmatch(a) for a in args)
    return False


def allowed(event):
    tool, data, cwd = event.get("tool_name"), event.get("tool_input", {}), event.get("cwd") or MAIN
    if tool in READ_ONLY_TOOLS:
        return True
    if tool in ("Edit", "Write", "NotebookEdit"):
        return editable(data.get("file_path") or data.get("notebook_path") or "")
    if tool == "Bash":
        return bash_ok(data.get("command", ""), os.path.realpath(cwd))
    return False


def scheduled_run(event):
    try:
        with open(event.get("transcript_path") or "", encoding="utf-8") as f:
            first = json.loads(f.readline())
    except (OSError, ValueError):
        return False
    return first.get("type") == "queue-operation" and str(first.get("content", "")).startswith(TASKS)


if __name__ == "__main__":
    event = json.load(sys.stdin)
    if scheduled_run(event) and allowed(event):
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "allow",
                                                 "permissionDecisionReason": "backlog worker (tools/rb_permit.py)"}}))
