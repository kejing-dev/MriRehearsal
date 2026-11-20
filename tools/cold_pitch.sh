#!/usr/bin/env bash
# QA #5 q5-1 check: cold start with the head tilted. Tilts the emulator camera by DY framebuffer px with a right-button
# vertical drag (QA #5: 940 px ~ 30 deg; + = look down), relaunches, logs when the window opens and the pitch it opened
# at, takes a console screenshot 3 s after the open, then tilts back.
#   DEV=emulator-5554 tools/cold_pitch.sh NAME DY
set -u
DEV="${DEV:-emulator-5554}"; export DEV AVD="${AVD:-Pico_Emulator_6_1_fresh}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=io.embodit.mrirehearsal
name="${1:?name}"; dy="${2:?dy}"
OUT="${OUT:-/c/Users/Admin/Downloads/spatial-app-factory/apps/mri-rehearsal/build/r5}"
mkdir -p "$OUT"
adb -s "$DEV" shell am force-stop $PKG; sleep 2
powershell -NoProfile -File "$HERE/harness/uia_cam.ps1" set camera_rotation_button on >/dev/null
if [ "$dy" != 0 ]; then
  bash "$HERE/harness/emu.sh" fast on >/dev/null 2>&1
  bash "$HERE/harness/emu.sh" drag 1080 $((1080 - dy / 2)) 1080 $((1080 + dy / 2)) -Button right -NoFast -Steps 20 -StepMs 25 >/dev/null 2>&1
  sleep 1
fi
adb -s "$DEV" logcat -c
t0=$(date +%s%3N)
adb -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
for i in $(seq 1 30); do
  adb -s "$DEV" logcat -d -s MriRehearsal:V | grep -q "WINDOW ON_RESUME" && break
  sleep 0.5
done
t1=$(date +%s%3N)
bash "$HERE/harness/emu.sh" fast on >/dev/null 2>&1
sleep 3
SHOTS="$OUT" bash "$HERE/m.sh" shot "$name" >/dev/null
sleep 3
{
  echo "# $name  drag=${dy}px  launch->WINDOW ON_RESUME seen by the poll: $((t1 - t0)) ms"
  adb -s "$DEV" logcat -d -v time -s MriRehearsal:V | grep -E "STAGE enter|WINDOW|SCENE frame|HEAD found|LEVEL" | tr -d '\r'
  echo "FATAL: $(adb -s "$DEV" logcat -d | grep -c 'FATAL EXCEPTION')  am_anr: $(adb -s "$DEV" logcat -d -b events | grep -c "am_anr.*$PKG")"
} > "$OUT/$name.txt"
cat "$OUT/$name.txt"
if [ "$dy" != 0 ]; then
  bash "$HERE/harness/emu.sh" fast on >/dev/null 2>&1
  bash "$HERE/harness/emu.sh" drag 1080 $((1080 + dy / 2)) 1080 $((1080 - dy / 2)) -Button right -NoFast -Steps 20 -StepMs 25 >/dev/null 2>&1
fi
