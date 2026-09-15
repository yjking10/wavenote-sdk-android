#!/bin/bash
# 导入 Release AAR；无参数时校验已导入的本地 SDK。
set -euo pipefail
demo_root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
[ "$#" -le 1 ] || { echo "用法：$0 [AAR路径]" >&2; exit 1; }
mkdir -p "$demo_root/build" "$demo_root/app/libs"
staging="$(mktemp -d "$demo_root/build/import.XXXXXX")"
if [ "$#" = 0 ]; then
    source_path="$demo_root/app/libs/sdk-release.aar"
    [ -f "$source_path" ] || { echo '缺少 SDK。请先单独取得 SDK，再运行 bash scripts/prepare-sdk.sh /absolute/path/sdk-release.aar' >&2; exit 1; }
else
    source_path="$1"
fi
unzip -tq "$source_path" >/dev/null
unzip -p "$source_path" classes.jar > "$staging/classes.jar"
[ -s "$staging/classes.jar" ] || { echo 'AAR 缺少 classes.jar' >&2; exit 1; }
cp "$source_path" "$staging/sdk-release.aar"
mv "$staging/sdk-release.aar" "$demo_root/app/libs/sdk-release.aar"
echo "已准备：$demo_root/app/libs/sdk-release.aar"
