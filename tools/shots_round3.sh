#!/usr/bin/env bash
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; M="$HERE/m.sh"; E="$HERE/harness/emu.sh"
SH=/c/Users/Admin/Downloads/spatial-app-factory/apps/mri-rehearsal/build/shots
step() { local name="$1"; shift; for c in "$@"; do "$M" cmd "$c" >/dev/null; sleep 0.4; done
  "$E" fast on >/dev/null 2>&1; sleep "${WAIT:-3}"; "$M" shot "_a" >/dev/null; sleep 1.5; "$M" shot "$name" >/dev/null; rm -f "$SH/_a.png"; echo "$name"; }
"$M" cmd "lang zh" >/dev/null
step S04-t6 "goto S04 t=6"
step S10 "goto S10 t=6"
step S14-S06 "goto S06 seq=T1 t=20" "lost 1"; "$M" cmd "lost 0" >/dev/null
step S14-S04 "goto S04 t=6 run=1" "lost 1"; "$M" cmd "lost 0" >/dev/null
step S14-S09-sliding "goto S09" "lost 1"; "$M" cmd "lost 0" >/dev/null
WAIT=8 step S14-S09-card "goto S09"; "$M" cmd "lost 1" >/dev/null; step S14-S09-card-lost; "$M" cmd "lost 0" >/dev/null
step S12 "goto S12"
step S11-84 "goto S11 score=84 events=1 level=L2"
step S06-earplug "goto S06 seq=LOC t=7 level=L2"
for n in louder quieter same; do step S07-$n-a "goto S07 next=$n"; sleep 1.2; "$M" shot S07-$n-b >/dev/null; echo S07-$n-b; done
"$M" cmd "lang en" >/dev/null
for n in 1 2 3 4; do step en-S03-$n "goto S03 tour=$n"; done
step en-S12 "goto S12"
"$M" cmd "lang zh" >/dev/null
step S03-3 "goto S03 tour=3"
step S03-4 "goto S03 tour=4"
