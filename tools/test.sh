#!/usr/bin/env bash
# Runs the core, server and app tests and prints only what failed: each failing test with its message.
# Gradle's incremental compile sometimes leaves stale classes (EOFException, NoClassDefFoundError); then it
# cleans and runs once more. Usage: tools/test.sh [core|server|app ...]  (default: all three)
set -uo pipefail
cd "$(dirname "$0")/.."
declare -A TASK=([core]=":core:test" [server]=":server:test" [app]=":app:testDebugUnitTest")
modules=("$@")
[[ $# -eq 0 ]] && modules=(core server app)

run() {
    local tasks=()
    for m in "${modules[@]}"; do tasks+=("${TASK[$m]}"); done
    ./gradlew -q "${tasks[@]}" --continue >build/test.log 2>&1
}

mkdir -p build
if run; then echo "All tests pass (${modules[*]})."; exit 0; fi
if grep -qE "EOFException|NoClassDefFoundError|Incremental compilation failed" build/test.log; then
    echo "Stale build; cleaning and running again."
    for m in "${modules[@]}"; do ./gradlew -q ":$m:clean" >/dev/null 2>&1; done
    if run; then echo "All tests pass (${modules[*]})."; exit 0; fi
fi

# Compile errors come out of Gradle itself; test failures from the JUnit reports.
grep -E "^e: " build/test.log | head -20
python3 - "${modules[@]}" <<'PY'
import glob, html, re, sys
dirs = {"core": "core/build/test-results/test", "server": "server/build/test-results/test", "app": "app/build/test-results/testDebugUnitTest"}
for module in sys.argv[1:]:
    for f in glob.glob(dirs[module] + "/*.xml"):
        text = open(f).read()
        for m in re.finditer(r'<testcase name="([^"]+)" classname="([^"]+)"[^>]*>\s*<failure message="([^"]*)"[^>]*>([^<]*)', text):
            name, cls, message, trace = m.groups()
            ours = [l.strip() for l in html.unescape(trace).splitlines() if "com.kzhovn" in l][:3]
            print(f"FAIL {cls.split('.')[-1]} > {html.unescape(name)}\n  {html.unescape(message)[:400]}")
            for line in ours: print("    " + line)
PY
echo "(full Gradle output: build/test.log)"
exit 1
