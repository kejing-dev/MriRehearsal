#!/usr/bin/env bash
# v0.1.1 QA shots on one emulator (see AGENTS.md "Screenshot list"): console screenshots of the Stage + main window.
#   DEV=emulator-5554 SHOTS=<dir> tools/shots_v011.sh cold            force-stop, launch, wait for the window and the room
#   DEV=… SHOTS=… tools/shots_v011.sh all [prefix]                    every state, current language
#   DEV=… SHOTS=… tools/shots_v011.sh one NAME "cmd1" "cmd2" …        commands, settle, one shot
set -u
DEV="${DEV:-emulator-5554}"
PKG=io.embodit.mrirehearsal
HERE="$(cd "$(dirname "$0")" && pwd)"
export DEV
M="$HERE/m.sh"
ADB="adb -s $DEV"
SETTLE="${SETTLE:-5}"
PY="${PY:-$HOME/.factory-venv/Scripts/python.exe}"

waitlog() { # REGEX TIMEOUT
  local t=0
  while [ $t -lt "${2:-40}" ]; do
    $ADB logcat -d -v time -s MriRehearsal:V | grep -qE "$1" && return 0
    sleep 1; t=$((t + 1))
  done
  echo "timeout waiting for $1" >&2; return 1
}

cold() {
  $ADB shell am force-stop $PKG; sleep 2
  $ADB logcat -c
  $ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  waitlog "WINDOW ON_RESUME" 60 && waitlog "SCENE build done" 60 && waitlog "STATE -> S01" 30
  sleep 8   # the window's glass draws black for a few seconds after a cold start (emulator)
}

one() { # NAME cmd...   (60 fps for the settle, two shots 1.5 s apart, the second kept: no half-drawn frames)
  local name="$1"; shift
  for c in "$@"; do bash "$M" cmd "$c" >/dev/null; sleep 1.5; done
  AVD="${AVD:-Pico_Emulator_6_1_fresh}" bash "$HERE/harness/emu.sh" fast on >/dev/null 2>&1
  sleep "$SETTLE"
  bash "$M" shot "$name" >/dev/null; sleep 1.5
  bash "$M" shot "$name" >/dev/null
  # a half-drawn (black) window glass: settle again and retake, up to 3 times
  for k in 1 2 3; do
    "$PY" -c "import sys;from PIL import Image;p=Image.open(sys.argv[1]).convert('L').getpixel((832,1000));sys.exit(0 if p>120 else 1)" "$SHOTS/$name.png" && break
    echo "retake $name (black glass)" >&2; sleep 6; bash "$M" shot "$name" >/dev/null
  done
  echo "$name"
}

all() {
  local p="${1:-}"
  one "${p}S01" "reset" "goto S01"
  one "${p}S01-lie" "posture lie"
  one "${p}S01-sit" "posture sit"
  one "${p}S02" "goto S02" "best L1=98"
  for n in 1 2 3 4; do one "${p}S03-$n" "goto S03 tour=$n"; done
  one "${p}S04-t6" "goto S04 t=6"
  one "${p}S05" "goto S05"
  one "${p}S06" "goto S06 seq=T1 t=42"
  one "${p}S08-amber" "head dx=0.006"
  one "${p}S08-red" "head dx=0.012"
  one "${p}S07-louder" "head 0" "goto S07 next=louder"
  one "${p}S07-quieter" "goto S07 next=quieter"
  SETTLE=8 one "${p}S09" "goto S09"
  one "${p}S10" "goto S10 t=6"
  one "${p}S11-98" "goto S11 score=98 events=0"
  one "${p}S11-84" "goto S11 score=84 events=1 level=L2"
  one "${p}S11-60" "goto S11 score=60 level=L3"
  one "${p}S12" "reset" "goto S12"
  one "${p}S13" "goto S13"
  one "${p}howto" "page howto"
  # S14 turns into a call after 10 s without tracking: shoot it 3 s after `lost 1`, no 60 fps settle
  one "${p}S14-pre" "page scan" "goto S06 seq=T1 t=20"
  bash "$M" cmd "lost 1" >/dev/null; sleep 3; bash "$M" shot "${p}S14" >/dev/null && echo "${p}S14"
  bash "$M" cmd "lost 0" >/dev/null
  one "${p}S14-recover"
  bash "$M" cmd "reset" >/dev/null
}

case "${1:-}" in
  cold) cold ;;
  all) shift; all "${1:-}" ;;
  one) shift; one "$@" ;;
  *) sed -n 2,6p "$0" ;;
esac
