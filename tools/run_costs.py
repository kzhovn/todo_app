#!/usr/bin/env python3
"""Token use of the worker's scheduled runs, from their transcripts: one line per run, so empty (stopped) runs can be
told apart from working ones. Usage: tools/run_costs.py [days]   (default 7)"""
import glob
import json
import os
import sys
import time

TASKS = ("raspberry-backlog", "raspberry-backlog-now", "raspberry-hygiene", "raspberry-hygiene-now")
days = float(sys.argv[1]) if len(sys.argv) > 1 else 7
since = time.time() - days * 86400
rows = []
for path in glob.glob(os.path.expanduser("~/.claude/projects/*/*.jsonl")):
    if os.path.getmtime(path) < since:
        continue
    with open(path, encoding="utf-8") as f:
        try:
            first = json.loads(f.readline())
        except ValueError:
            continue
        content = str(first.get("content", ""))
        task = next((t for t in TASKS if content.startswith(f'<scheduled-task name="{t}"')), None)
        if first.get("type") != "queue-operation" or not task:
            continue
        totals = {"input": 0, "cache_write": 0, "cache_read": 0, "output": 0}
        calls, start = 0, first.get("timestamp", "")
        for line in f:
            usage = (json.loads(line).get("message") or {}).get("usage")
            if not usage:
                continue
            calls += 1
            totals["input"] += usage.get("input_tokens", 0)
            totals["cache_write"] += usage.get("cache_creation_input_tokens", 0)
            totals["cache_read"] += usage.get("cache_read_input_tokens", 0)
            totals["output"] += usage.get("output_tokens", 0)
    rows.append((start[:16].replace("T", " "), task, calls, totals))

print(f"{'started (UTC)':16}  {'task':22} {'turns':>5} {'input':>8} {'cache wr':>9} {'cache rd':>9} {'output':>7}")
for start, task, calls, t in sorted(rows):
    print(f"{start:16}  {task:22} {calls:5} {t['input']:8} {t['cache_write']:9} {t['cache_read']:9} {t['output']:7}")
