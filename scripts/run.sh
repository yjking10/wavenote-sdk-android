#!/bin/bash
set -euo pipefail
root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$root"
if [ "${1:-}" = '--maven' ]; then
    shift; ./gradlew :app:assembleDebug -PlocalAar=false "$@" --console=plain
else
    bash scripts/prepare-sdk.sh "$@"
    ./gradlew :app:assembleDebug -PlocalAar=true --console=plain
fi
echo "APK: $root/app/build/outputs/apk/debug/app-debug.apk"
