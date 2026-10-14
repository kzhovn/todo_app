#!/usr/bin/env bash
# Times the web pages, top bar API, sync and :core list logic over generated task lists (default 3000 tasks).
# Usage: tools/bench.sh [phone] [sizes...], e.g. tools/bench.sh 3000 10000; `phone` times the app's list
# instead (Robolectric). Harnesses: server/src/test/.../bench/Bench.kt, app/src/test/.../bench/PhoneBench.kt,
# over core/src/testFixtures/.../BenchData.kt. BENCH_PROFILE=/tmp/b.jfr records a profile of the server run.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "${1:-}" == phone ]]; then
    shift
    # Gradle's -i log carries the test's own output, indented; keep just the tables.
    BENCH="${*:-3000}" ./gradlew -q :app:testDebugUnitTest --tests '*PhoneBench*' --rerun -i 2>&1 | grep -E '^ +(==|median|[a-z].* [0-9]+\.[0-9]+ +[0-9]+\.[0-9]+$)' | sed 's/^    //'
    exit 0
fi
./gradlew -q :server:bench --args="${*:-3000}" 2>&1 | grep -v "^SLF4J\|INFO\|DEBUG"
