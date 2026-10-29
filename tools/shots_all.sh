#!/usr/bin/env bash
# All design qaCapture states as screenshots: tools/shots_all.sh [prefix]
# Each step: debug command(s), wake the emulator to 60 fps, wait, two console shots 1.5 s apart (keep the second).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
M="$HERE/m.sh"
E="$HERE/harness/emu.sh"
P="${1:-}"
step() { # name, then commands
  local name="$1"; shift
  for c in "$@"; do "$M" cmd "$c" >/dev/null; sleep 0.4; done
  "$E" fast on >/dev/null 2>&1; sleep "${WAIT:-3}"
  local a; a="$("$M" shot "_a")"; sleep 1.5; "$M" shot "$P$name" >/dev/null; rm -f "$a"
  echo "$P$name"
}
step S01 "goto S01"
step S01-lie "posture lie"
step S01-sit "posture sit"
step S02 "goto S02"
step S02-best "best L1=98 L2=84" "goto S02"
for n in 1 2 3 4; do step S03-$n "goto S03 tour=$n"; done
step S04-t6 "goto S04 t=6"
step S04-t8 "goto S04 t=8"
step S05 "goto S05"
step S06 "goto S06 seq=T1 t=42"
step S08-amber "head dx=0.006"
step S08-red "head dx=0.012"
step S08-hintoff "hint 0" "head 0" "goto S06 seq=T1 t=42" "head dx=0.012"
"$M" cmd "head 0" >/dev/null; "$M" cmd "hint 1" >/dev/null
step S07-louder "goto S07 next=louder"
step S07-quieter "goto S07 next=quieter"
step S07-same "goto S07 next=same"
WAIT=8 step S09 "goto S09"
WAIT=4 step S09-fromS04 "goto S09 from=S04 t=3"
step S10 "goto S10 t=6"
step S11-98 "goto S11 score=98 events=0"
step S11-84 "goto S11 score=84 events=1 level=L2"
step S11-60 "goto S11 score=60 level=L3"
step S12 "goto S12"
step S13 "goto S13"
step S14-S06 "goto S06 seq=T1 t=20" "lost 1"
"$M" cmd "lost 0" >/dev/null
step S14-S04 "goto S04 t=6 run=1" "lost 1"
"$M" cmd "lost 0" >/dev/null
WAIT=8 step S14-S09 "goto S09" "lost 1"
"$M" cmd "lost 0" >/dev/null
step S12-70 "bore 70" "goto S06 seq=T1 t=10"
"$M" cmd "bore 60" >/dev/null
step testtex "goto S02" "testtex 1"
"$M" cmd "testtex 0" >/dev/null
