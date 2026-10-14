#!/usr/bin/env bash
set -u
./gradlew --no-daemon :app:connectedDebugAndroidTest
capture_status=$?
mkdir -p app/build/reports/androidTests/connected/ui
adb pull /sdcard/Download/luma-ui/. app/build/reports/androidTests/connected/ui/ || true
exit "$capture_status"
