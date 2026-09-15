#!/bin/bash
set -euo pipefail
demo_root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$demo_root"
mkdir -p build
logs="$(mktemp -d "$demo_root/build/verify.XXXXXX")"
run_check() {
    local name="$1"; shift
    if "$@" > "$logs/$name.log" 2>&1; then echo "PASS $name"; else tail -60 "$logs/$name.log"; echo "FAIL $name: $logs/$name.log" >&2; exit 1; fi
}
if [ "${1:-}" = '--maven' ]; then
    shift
    args=(-PlocalAar=false "$@")
else
    run_check binary-prepare bash scripts/prepare-sdk.sh "$@"
    args=(-PlocalAar=true)
fi
run_check demo-build-tests-lint ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug "${args[@]}" --console=plain
echo "Demo verification: $logs"
