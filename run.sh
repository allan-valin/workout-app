#!/usr/bin/env bash
# One button: boot the emulator (if needed), build + install the debug APK, and
# COLD-launch it. The force-stop is the step that was missing from the old copy-paste
# block: `monkey` only resumes the existing task, so after a quick-boot snapshot the
# newly-installed APK sat behind the old Activity and you saw the previous version.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
EMULATOR="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"
AVD="${AVD:-testphone}"
PKG="dev.allan.workoutapp"

# 1. Start the emulator only if no device is already connected.
if ! "$ADB" devices | grep -qE 'emulator-[0-9]+\s+device'; then
  echo "==> starting emulator $AVD"
  "$EMULATOR" -avd "$AVD" -gpu swiftshader_indirect >/dev/null 2>&1 &
else
  echo "==> emulator already running"
fi

# 2. Wait for a full boot.
echo "==> waiting for boot"
"$ADB" wait-for-device shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 1; done'

# 3. Build + install the debug APK (compiles only what changed).
echo "==> installDebug"
./gradlew installDebug

# 4. Force-stop the old process, then cold-launch — this is what guarantees you see
#    the version you just installed, not the snapshot's stale Activity.
echo "==> force-stop + launch"
"$ADB" shell am force-stop "$PKG"
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null

# 5. Grab a screenshot so a headless run still gives you something to eyeball.
SHOT="${SHOT:-/tmp/${PKG}-run.png}"
sleep 2
"$ADB" exec-out screencap -p > "$SHOT" 2>/dev/null && echo "==> screenshot: $SHOT" || true
echo "==> done"
