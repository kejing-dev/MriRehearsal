#!/usr/bin/env bash
# QA q2-1 check: N real pinch taps at fb X Y (a toggle button); reads the saved "muted" after each tap.
# tools/tap_test.sh N X Y LABEL
set -u
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"; DEV="${DEV:-emulator-5560}"; PKG=io.embodit.mrirehearsal
HERE="$(cd "$(dirname "$0")" && pwd)"; E="$HERE/harness/emu.sh"
N="$1"; X="$2"; Y="$3"; L="${4:-tap}"
muted() { "$ADB" -s "$DEV" shell run-as $PKG cat shared_prefs/mri_rehearsal.xml 2>/dev/null | grep -oE 'name="muted" value="[a-z]+"' | grep -oE 'true|false' || echo none; }
prev="$(muted)"; ok=0
"$ADB" -s "$DEV" logcat -c
for i in $(seq "$N"); do
  "$E" fast on >/dev/null 2>&1; sleep 2.5; "$E" click "$X" "$Y" >/dev/null 2>&1; sleep 2
  now="$(muted)"
  if [ "$now" != "$prev" ]; then ok=$((ok+1)); r=hit; else r=MISS; fi
  echo "$L tap $i: muted=$now $r"; prev="$now"
done
pass3d=$("$ADB" -s "$DEV" logcat -d -s MriRehearsal:V | grep -c "TAP 3D")
echo "$L result: $ok/$N toggled, TAP 3D (pass-through) lines=$pass3d"
