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
        if [[ $(adb shell getprop ro.build.version.sdk | tr -d '\r') == 35 ]]; then
            mkdir -p "$diagnostics/previews"
            for format in docx xlsx pptx odt ods odp md csv html; do
                if adb shell run-as "$package" ls "cache/preview-$format.png" >/dev/null 2>&1; then
                    adb exec-out run-as "$package" cat "cache/preview-$format.png" > "$diagnostics/previews/$format.png" || true
                fi
            done
        fi
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

adb shell am force-stop "$package"
adb shell cmd uimode night yes
timeout 25s adb shell am start -W -n "$package/.MainActivity"
adb shell uiautomator dump /sdcard/docs-viewer-ui.xml
adb exec-out cat /sdcard/docs-viewer-ui.xml > "$diagnostics/dark-home.xml"
grep -q 'text="Browse files"' "$diagnostics/dark-home.xml"
echo "Dark theme home screen rendered successfully."

adb shell am force-stop "$package"
adb shell cmd uimode night no
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
timeout 180s adb shell am instrument -w "$package.test/$package.AndroidSmoke" | tee "$diagnostics/reader.txt"
grep -q 'Reader smoke tests passed' "$diagnostics/reader.txt"
if [[ $(adb shell getprop ro.build.version.sdk | tr -d '\r') == 35 ]]; then
    mkdir -p "$diagnostics/previews"
    for format in docx xlsx pptx odt ods odp md csv html; do
        adb exec-out run-as "$package" cat "cache/preview-$format.png" > "$diagnostics/previews/$format.png"
    done
fi
adb logcat -d -b crash > "$diagnostics/crash.txt"
if grep -q "Process: $package," "$diagnostics/crash.txt"; then
    echo "Docs Viewer crashed during verification."
    exit 1
fi
