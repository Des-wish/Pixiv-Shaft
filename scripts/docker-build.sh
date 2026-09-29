#!/usr/bin/env bash
set -euo pipefail

build_type=debug
if [[ ${1:-} == "debug" || ${1:-} == "release" ]]; then
    build_type=$1
    shift
fi

case "$build_type" in
    debug) gradle_task=assembleGithubDebug ;;
    release) gradle_task=assembleGithubRelease ;;
esac

bash ./gradlew "$gradle_task" "$@"

apk_root="app/build/outputs/apk"
if [[ ! -d "$apk_root" ]]; then
    echo "No APK output directory was produced."
    exit 0
fi

while IFS= read -r -d '' apk; do
    relative_path="${apk#${apk_root}/}"
    destination="/container-data/artifacts/apk/${relative_path}"
    mkdir -p "$(dirname "$destination")"
    cp -- "$apk" "$destination"
    echo "Exported APK: ${destination}"
done < <(find "$apk_root" -type f -name '*.apk' -print0)
