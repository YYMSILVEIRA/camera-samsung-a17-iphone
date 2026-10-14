#!/usr/bin/env bash
set -u
./gradlew --no-daemon :app:connectedDebugAndroidTest
capture_status=$?
mkdir -p app/build/reports/androidTests/connected/ui
adb pull /sdcard/Android/data/com.ib2s.lumaa17.debug/files/. app/build/reports/androidTests/connected/ui/ || true
exit "$capture_status"
