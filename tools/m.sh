#!/usr/bin/env bash
# QA helper for MRI Rehearsal on one emulator.
#   tools/m.sh cmd "goto S06 seq=T1 t=42"   debug broadcast (see AGENTS.md)
#   tools/m.sh shot NAME                     console screenshot -> build/shots/NAME.png (full Stage frame)
#   tools/m.sh log [N]                       last N lines of the app's log tag
#   tools/m.sh launch | stop | install
set -u
DEV="${DEV:-emulator-5560}"
PKG=io.embodit.mrirehearsal
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SHOTS="${SHOTS:-/c/Users/Admin/Downloads/spatial-app-factory/apps/mri-rehearsal/build/shots}"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
mkdir -p "$SHOTS"
case "${1:-}" in
  cmd) "$ADB" -s "$DEV" shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '${2:?}'" | tail -1 | tr -d '\r' ;;
  shot)
    # the console writes Screenshot_<time>.png into a directory: use a private one, then move it
    tmp="$SHOTS/.tmp"; rm -rf "$tmp"; mkdir -p "$tmp"
    "$ADB" -s "$DEV" emu screenrecord screenshot "$(cygpath -w "$tmp")" >/dev/null 2>&1
    f="$(ls -t "$tmp"/*.png 2>/dev/null | head -1)"
    [ -n "$f" ] && mv -f "$f" "$SHOTS/${2:?}.png" && echo "$SHOTS/$2.png" ;;
  log) "$ADB" -s "$DEV" logcat -d -v time -s MriRehearsal:V | tail -n "${2:-40}" | tr -d '\r' ;;
  launch) "$ADB" -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; echo launched ;;
  stop) "$ADB" -s "$DEV" shell am force-stop $PKG ;;
  install) "$ADB" -s "$DEV" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" | tail -1 ;;
  *) sed -n 2,7p "$0" ;;
esac
