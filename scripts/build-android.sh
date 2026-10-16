#!/bin/zsh
set -euo pipefail
cd "${0:A:h}/../android"
./gradlew :app:assembleDebug --console=plain --max-workers=2
mkdir -p ../dist
cp app/build/outputs/apk/debug/app-debug.apk ../dist/Panel-Android.apk
cp ../dist/Panel-Android.apk ../dist/Pnale-Android.apk
printf 'Built: %s/dist/Panel-Android.apk\n' "${PWD:h}"
