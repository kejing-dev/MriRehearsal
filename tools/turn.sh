#!/usr/bin/env bash
# Turn the emulator camera to an absolute yaw (degrees, + = right of the start view -Z) with right-button drags,
# reading the head back with the app's `dump` (rate ~0.032 deg per 2160-frame px; corrected up to 6 times).
#   DEV=emulator-5554 AVD=Pico_Emulator_6_1_fresh tools/turn.sh 80
set -u
DEV="${DEV:-emulator-5554}"; export DEV AVD="${AVD:-Pico_Emulator_6_1_fresh}"
HERE="$(cd "$(dirname "$0")" && pwd)"
target="${1:?yaw}"
yaw() {
  bash "$HERE/m.sh" cmd dump >/dev/null; sleep 1
  adb -s "$DEV" logcat -d -s MriRehearsal:V | grep DUMP | tail -1 | grep -o "fwd=([^)]*)" | tr -d 'fwd=()' |
    awk -F', ' '{ printf "%.1f\n", atan2($1, -$3) * 57.29578 }'
}
powershell -NoProfile -File "$HERE/harness/uia_cam.ps1" set camera_rotation_button on >/dev/null
for i in 1 2 3 4 5 6 7 8 9 10; do
  y=$(yaw); err=$(awk -v t="$target" -v y="$y" 'BEGIN { print t - y }')
  echo "yaw=$y err=$err"
  awk -v e="$err" 'BEGIN { exit !(e < 1.0 && e > -1.0) }' && exit 0
  px=$(awk -v e="$err" 'BEGIN { p = e / 0.032; if (p > 600) p = 600; if (p < -600) p = -600; printf "%d", p }')
  bash "$HERE/harness/emu.sh" fast on >/dev/null 2>&1
  bash "$HERE/harness/emu.sh" drag 1080 1900 $((1080 + px)) 1900 -Button right -NoFast -Steps 10 -StepMs 25 >/dev/null 2>&1
  sleep 1
done
exit 1
