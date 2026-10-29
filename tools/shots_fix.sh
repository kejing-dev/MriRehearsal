#!/usr/bin/env bash
# Re-shoot after build 9 + English (S16) set. Uses shots_all.sh's step().
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; M="$HERE/m.sh"; E="$HERE/harness/emu.sh"; P="${1:-}"
step() { local name="$1"; shift; for c in "$@"; do "$M" cmd "$c" >/dev/null; sleep 0.4; done
  "$E" fast on >/dev/null 2>&1; sleep "${WAIT:-3}"; "$M" shot "_a" >/dev/null; sleep 1.5; "$M" shot "$P$name" >/dev/null; rm -f "$(dirname "$0")/../.none"; echo "$P$name"; }
"$M" cmd "lang zh" >/dev/null
step S01 "goto S01"
step S02-best "best L1=98 L2=84" "goto S02"
for n in 1 2 3 4; do step S03-$n "goto S03 tour=$n"; done
step S04-t8 "goto S04 t=8"
step S06 "goto S06 seq=T1 t=42"
step testtex "goto S02" "testtex 1"
"$M" cmd "testtex 0" >/dev/null
"$M" cmd "lang en" >/dev/null
step en-S01 "goto S01"
step en-S02 "goto S02"
step en-S03-4 "goto S03 tour=4"
step en-S06 "goto S06 seq=T1 t=2"
step en-S07 "goto S07 next=quieter"
WAIT=8 step en-S09 "goto S09"
step en-S11 "goto S11 score=84 events=1 level=L2"
step en-S12 "goto S12"
step en-S13 "goto S13"
"$M" cmd "lang zh" >/dev/null
