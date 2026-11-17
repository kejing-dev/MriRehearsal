#!/usr/bin/env bash
# OVERFLOW sweep (D13): every state and every caption in one language, widest dynamic values, both mute labels;
# the log is streamed to a file (build.md: long scans stream, never read the ring buffer at the end).
#   DEV=emulator-5554 tools/overflow_sweep.sh en|zh OUT.txt
set -u
DEV="${DEV:-emulator-5554}"; export DEV
HERE="$(cd "$(dirname "$0")" && pwd)"
LANG_="${1:-en}"; OUT="${2:?out}"
c() { bash "$HERE/m.sh" cmd "$1" >/dev/null; sleep "${2:-6}"; }
adb -s "$DEV" logcat -v time -s MriRehearsal:V > "$OUT.raw" 2>&1 &
LP=$!
c "lang $LANG_"; c "reset"; c "best L1=100 L2=100 L3=100"
for m in 0 1; do
  c "mute $m"
  c "goto S01"; c "posture lie"; c "posture sit"; c "goto S02"
  for n in 1 2 3 4; do c "goto S03 tour=$n"; done; c "posture lie"; for n in 1 2 3 4 5; do c "goto S03 tour=$n"; done; c "posture sit"
  c "goto S04 t=2"; c "goto S04 t=8"
  c "goto S05 count=3"; c "goto S05 count=2"; c "goto S05 count=1"
  c "goto S06 seq=LOC t=1" 4; c "goto S06 seq=LOC t=8" 4; c "goto S06 seq=EPI t=59 level=L3" 4; c "goto S06 seq=T2 t=89"
  c "head dx=0.006"; c "head dx=0.012"; c "head 0"
  for nx in louder quieter same; do c "goto S07 next=$nx" 7; done
  c "goto S09" 8; c "lost 1" 3; c "lost 0"
  c "goto S09 from=S04 t=3" 8
  c "goto S10 t=6"
  c "goto S11 score=100 events=0 level=L3"; c "goto S11 score=10 events=6 level=L3"; c "goto S11 score=84 events=1 level=L2"
  c "goto S06 seq=T1 t=20"; c "lost 1" 4; c "lost 0"
  c "goto S12"; c "goto S13"; c "page howto"; c "page scan"
done
c "window close" 6; c "window open" 12
kill $LP 2>/dev/null
grep "OVERFLOW" "$OUT.raw" | sort -u > "$OUT"
echo "$LANG_: $(wc -l < "$OUT") OVERFLOW lines"; cat "$OUT"
