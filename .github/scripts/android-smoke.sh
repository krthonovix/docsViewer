#!/usr/bin/env bash
set -euo pipefail

package=com.docsviewer
diagnostics=android-diagnostics
mkdir -p "$diagnostics"

collect_failure() {
    local code=$?
    if [[ $code -ne 0 ]]; then
        adb logcat -d -b crash | tee "$diagnostics/crash.txt" || true
        adb exec-out screencap -p > "$diagnostics/screen.png" || true
    fi
}
trap collect_failure EXIT

adb install app/build/outputs/apk/debug/app-debug.apk
adb logcat -c -b crash
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
timeout 25s adb shell am start -W -n "$package/.MainActivity"
adb shell uiautomator dump /sdcard/docs-viewer-ui.xml
adb exec-out cat /sdcard/docs-viewer-ui.xml > "$diagnostics/home.xml"
grep -q 'text="Browse files"' "$diagnostics/home.xml"
echo "Home screen rendered successfully."
