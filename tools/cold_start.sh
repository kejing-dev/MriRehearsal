#!/usr/bin/env bash
# Cold start gate (learning-2026-10-26 #4): N launches, each left idle for S seconds; counts ANRs and crashes.
set -u
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"; DEV="${DEV:-emulator-5560}"; PKG=io.embodit.mrirehearsal
N="${1:-3}"; S="${2:-60}"
for i in $(seq "$N"); do
  "$ADB" -s "$DEV" shell am force-stop $PKG; sleep 3; "$ADB" -s "$DEV" logcat -c
  "$ADB" -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep "$S"
  anr=$("$ADB" -s "$DEV" logcat -d -b events | grep -c "am_anr.*$PKG")
  fatal=$("$ADB" -s "$DEV" logcat -d | grep -c "FATAL EXCEPTION")
  win=$("$ADB" -s "$DEV" shell dumpsys window windows | grep -c "Application Not Responding")
  built=$("$ADB" -s "$DEV" logcat -d -s MriRehearsal:V | grep -oE "build done total_ms=[0-9]+" | tail -1)
  s01=$("$ADB" -s "$DEV" logcat -d -s MriRehearsal:V | grep -c "STATE -> S01")
  echo "run $i: am_anr=$anr fatal=$fatal anr_windows=$win $built S01=$s01"
done
