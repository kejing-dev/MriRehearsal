#!/usr/bin/env bash
# QA #5 q5-2 self-test (profile D60): BACK / B and the other pad keys after real taps on the TabBar and window buttons.
#   DEV=emulator-5554 tools/back_keys.sh press LABEL      BACK x3, BUTTON_B x3, then A / X / Y / ENTER / DPAD_CENTER x2 each
#   DEV=emulator-5554 tools/back_keys.sh tap LABEL X Y    a real pinch-tap (2160-wide coordinates), 5 s settle
# Every step appends to $OUT: the app's log lines since the step (BACK / KEY / WINDOW / TAB / PAGE / QUIT / EXIT),
# the top resumed activity and whether the app process is still alive.
set -u
DEV="${DEV:-emulator-5554}"; export DEV AVD="${AVD:-Pico_Emulator_6_1_fresh}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=io.embodit.mrirehearsal
OUT="${OUT:-/c/Users/Admin/Downloads/spatial-app-factory/apps/mri-rehearsal/build/back-key.txt}"
A="adb -s $DEV"
state() {
  local top pid
  top=$($A shell dumpsys activity activities | grep -m1 "topResumedActivity" | tr -d '\r' | sed 's/^ *//')
  pid=$($A shell pidof $PKG | tr -d '\r')
  echo "  -> $top | pid=${pid:-NONE} | window=$($A logcat -d -s MriRehearsal:V | grep -oE 'WINDOW ON_[A-Z]+' | tail -1)"
}
since() { $A logcat -d -v time -s MriRehearsal:V -T "$1" | grep -E "BACK |KEY eaten|WINDOW|TAB |PAGE |QUIT|EXIT|MUTE|mute" | grep -v "BACK keys" | tr -d '\r' | sed 's/^/  /'; }
mark() { $A shell date +%s.000 | tr -d ""; }
case "${1:-}" in
  press)
    label="${2:?label}"; m=$(mark)
    echo "## $label: BACK x3, BUTTON_B x3, A/X/Y/ENTER/DPAD_CENTER x2  (device time $m)" >> "$OUT"
    for k in 4 4 4 97 97 97 96 96 99 99 100 100 66 66 23 23; do $A shell input keyevent $k; sleep 0.7; done
    sleep 3
    since "$m" >> "$OUT"; state >> "$OUT"; tail -n 40 "$OUT" | sed -n "/## $label/,\$p" ;;
  tap)
    label="${2:?label}"; m=$(mark)
    echo "## tap $label at (${3:?x}, ${4:?y})  (device time $m)" >> "$OUT"
    bash "$HERE/harness/emu.sh" fast on >/dev/null 2>&1
    bash "$HERE/harness/emu.sh" click "$3" "$4" >/dev/null 2>&1 || bash "$HERE/harness/emu.sh" click "$3" "$4" -NoFast >/dev/null 2>&1
    sleep 5
    since "$m" >> "$OUT"; state >> "$OUT"; tail -n 20 "$OUT" | sed -n "/## tap $label/,\$p" ;;
  *) sed -n 2,6p "$0" ;;
esac
