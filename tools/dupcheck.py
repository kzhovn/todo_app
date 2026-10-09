#!/usr/bin/env python3
"""Looks for drift between the clients: wording written out in both the app and the server (it belongs
in Labels, in :core), functions nothing calls, and web or top-bar CSS classes nothing uses.
A heuristic: it lists candidates to look at, not certainties. Run from anywhere: tools/dupcheck.py"""
import collections
import glob
import os
import re

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

# Framework callbacks and entry points: called, just not by name from our code.
CALLBACKS = {"onCreate", "onResume", "onPause", "onDestroy", "main", "onReceive", "doWork", "invoke", "onUpdate",
             "provideGlance", "onAction", "toString", "onEntered", "onMoved", "onExited", "onEnded", "onDrop",
             "onAvailable", "onLost", "onMessageReceived", "onMessageUpdate", "onMessageDelete",
             "onMessageReactionAdd", "onMessageReactionRemove"}
# Styled by name by the platform, not by our code (St's placeholder text in an entry).
PLATFORM_CLASSES = {"hint-text"}


def kotlin(pattern):
    return glob.glob(pattern, recursive=True)


def strings(paths):
    found = collections.defaultdict(set)
    for path in paths:
        for line in open(path):
            if line.strip().startswith("//") or line.startswith("import "):
                continue
            for m in re.finditer(r'"((?:[^"\\$]|\\.){4,})"', line):
                text = m.group(1)
                if re.search(r"[a-z]{3}", text) and " " in text.strip() and not re.search(r"[{}<>/=;]|hx-|var\(|px", text):
                    found[text].add(os.path.basename(path))
    return found


def shared_wording():
    app = strings(kotlin("app/src/main/**/*.kt"))
    web = strings(kotlin("server/src/main/**/*.kt") + ["server/src/main/resources/static/app.js"])
    return [f"{t!r}  app: {sorted(app[t])}  server: {sorted(web[t])}" for t in sorted(set(app) & set(web))]


def unused_functions():
    everything = "\n".join(open(f).read() for f in kotlin("*/src/**/*.kt"))
    out = []
    for path in kotlin("core/src/main/**/*.kt") + kotlin("app/src/main/**/*.kt") + kotlin("server/src/main/**/*.kt"):
        # Annotated functions (Room's @TypeConverter, @Query...) are called by the framework.
        decl = re.findall(r"^(?!.*@)\s*(?:internal |private |override )?(?:suspend )?fun (?:[A-Za-z<>?,. ]+\.)?([a-zA-Z_]\w*)\(", re.sub(r"@\w+(\([^)]*\))?\s*\n\s*(?=(?:internal |private |override )?(?:suspend )?fun )", "@", open(path).read()), re.M)
        out += [f"{os.path.basename(path)}: {n}" for n in decl if n not in CALLBACKS and len(re.findall(rf"\b{n}\b", everything)) <= 1]
    return out


def unused_css(css, sources):
    rules = re.sub(r"/\*.*?\*/", "", open(css).read(), flags=re.S)
    text = "\n".join(open(f).read() for f in sources)
    return [f"{os.path.basename(css)}: .{c}" for c in sorted(set(re.findall(r"\.([a-zA-Z][\w-]*)", rules)))
            if c not in PLATFORM_CLASSES and not re.search(rf"(?<![\w-]){re.escape(c)}(?![\w-])", text)]


sections = {
    "Wording in both the app and the server (move to Labels)": shared_wording(),
    "Functions nothing calls": unused_functions(),
    "CSS classes nothing uses": unused_css("server/src/main/resources/static/app.css",
                                           kotlin("server/src/main/kotlin/**/*.kt") + ["server/src/main/resources/static/app.js"])
    + unused_css("desktop/raspberry@kzhovn/stylesheet.css", ["desktop/raspberry@kzhovn/extension.js"]),
}
for title, items in sections.items():
    print(f"## {title}: {len(items)}")
    for item in items:
        print("  " + item)
