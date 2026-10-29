# MRI Rehearsal · 磁共振预习

A PICO OS 6 Spatial SDK app (Kotlin + ECS, BOM 6.1.9). Lying on your own bed (or sitting), a life-size MRI scanner
slides you into its 60 cm bore, the scan noise plays, and the headset measures how far your head moves: a room tour,
then 30 s, 2 min and 5 min scans scored for stillness. Fully offline, no permissions, Chinese / English in the app.

- Package / applicationId: `io.embodit.mrirehearsal`, version 0.1.0
- Container: the default Stage, **Progressive**, immersion 100 / 40 / 100 (`AndroidManifest.xml`); no WindowContainer
- Design package: `spatial-app-factory/apps/mri-rehearsal/design-package.json` (rev 3, archetype C, 17 states, 14 interactions)
- Reference repo: `moon-phases` (Stage manifest, HeadTracker, panel pool, debug receiver, fonts, in-app language);
  controller trigger from `dance-steps` / `bianzhong`; colliders + `InteractableComponent` from `dose-distance`

## Build

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat assembleDebug testDebugUnitTest --no-daemon
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. `gradle.properties` keeps the build JVM at 2 GB and the daemon
off (several projects build on this machine at once). Unit tests: `app/src/test/.../DesignNumbersTest.kt` (design
U1–U11, level totals 30 / 120 / 300 and sequence seconds 30 / 104 / 268, slide paths, panel orientation).

Regenerate assets (both scripts are standard-library Python + Pillow, no numpy):

```bash
python tools/make_icon.py ../spatial-app-factory/apps/mri-rehearsal/icon.png   # adaptive-icon vector + legacy PNG + 512 export
python tools/make_audio.py                                                       # app/src/main/assets/audio/*.wav
```

## Layout of the code (`app/src/main/java/io/embodit/mrirehearsal`)

```
Main.kt                DefaultStage { MriTheme { MriScreen() } }
platform/              SpatialApplication, LaunchActivity, MriLog, MriGraph; HeadTracker (HMDTrackingProvider + debug offset),
                       ControllerInput (trigger held = call ball), ScanAudio (SoundPool beds + cues, MediaPlayer localiser)
domain/                MathKit (V3, Q4, facing), Stillness (judge + score), Plan (sequences, levels, run clock),
                       Slide (world offset s), Frame (posture judge, ScanFrame H0 / b), RunEngine (judge behind a lock), UiState
ui/MriViewModel.kt     the state machine; 50 ms ticker drives the timeline; slide commands for the scene
ui/MriScreen.kt        SpatialView + the four pre-built panels + the pointer gesture (tap a tour point / hold = call)
ui/card/               MainCard (S00 S01 S02 S09 S11 S13), SettingsCard (S12), CaptionStrip, TourTag, Common
scene/MriScene.kt      ECS: room, gantry (+ bore, strips, band), table, coil, call ball, window, tour shell + leader,
                       panel holders; onFrame() every frame: slide, stillness sampling, panel placement
scene/SceneKit.kt      SDK helpers, MeshBuilder, programmatic meshes (gantry body with rounded outline, bore with chamfers,
                       light strips, front band, birdcage coil, sphere) and textures (ceiling sky, "F↑" test card)
debug/                 DebugCommandReceiver + DebugHost (the QA broadcast)
```

Conventions: Stage metres; the scan frame's local axes are +Z = b (towards the feet), +Y = up, +X = up × b. World
offset s ∈ [0, 1.8]: 0 = gantry parked (front face 1.0 m beyond the top of the head), 1.8 = head at the isocentre.
All 2D UI is SpatialUI inside PicoTheme; no Material / Material3 imports (factory gate). Nothing aims with the head's
forward: selection is the system pointer (controller ray / gaze + pinch); the head pose is only the stillness input,
the posture judge and the placement origin.

## Debug broadcast (QA screenshots)

Debug builds only. One command per broadcast; log tag `MriRehearsal` answers every command (`adb logcat -s MriRehearsal`).

```bash
adb -s <serial> shell am broadcast -a io.embodit.mrirehearsal.DEBUG -p io.embodit.mrirehearsal --es cmd "goto S06 seq=T1 t=42"
adb -s <serial> shell am broadcast -a io.embodit.mrirehearsal.DEBUG -p io.embodit.mrirehearsal --es cmd "head dx=0.012"
adb -s <serial> shell am broadcast -a io.embodit.mrirehearsal.DEBUG -p io.embodit.mrirehearsal --es cmd "state"
```

`goto` freezes the timeline and the slide at the requested moment (lesson 12: QA can shoot it); add `run=1` to let it
continue, or send `release` later. The stillness judge keeps sampling while frozen, so `head …` injections still
change the ring.

| Command | What it does |
| --- | --- |
| `goto S00` … `goto S13` | jump to a base state (parameters below) |
| `goto S03 tour=n` | tour stop n (1..5; 1..4 sitting, the table is skipped) |
| `goto S04 t=6 [level=L1]` | sliding in, world offset as at second t of the 0.15 m/s slide (t=6 → s≈0.9, t=8 → s≈1.2, caption on the roof) |
| `goto S05 [count=2]` | settling countdown, head at the isocentre |
| `goto S06 seq=T1 t=42 [level=L2]` | scanning that sequence at second t (seq LOC / T1 / T2 / EPI; T2 picks L3) |
| `goto S07 next=louder\|quieter\|same` | the gap before a louder / quieter / equally loud sequence (L3 gaps 1 / 7 / 3) |
| `goto S09` | called from S06 (L2, T1 at 10 s): the world slides out 1.0 m, then the pause card. `goto S09 from=S04 t=3`: called half-way in, the world goes back to park |
| `goto S10 t=6` | sliding out, as at second 6 |
| `goto S11 score=98 events=0 [level=L2]` (= `result …`) | result card with that score (one amber span for the minor seconds the score implies, red dots for the events) |
| `goto S12` / `goto S13` | settings / about (from S02) |
| `posture lie\|sit` | posture override (as the manual links; `lie` on the emulator takes b from the camera's forward) |
| `best L1=98 L2=84` | stored best scores |
| `tour n` | tour stop n |
| `head dx=0.012 dy= dz= deg=2 pivot=1` | offset added to every head sample (metres; deg turns about world up, `pivot=1` about the neck pivot 9 cm behind the HMD). `head 0` clears |
| `lost 1\|0` | force tracking lost (S14) / restore |
| `s 0.45` | snap the world offset (then `call` for U11) |
| `call` / `resume` / `finish` | the call ball / pause card buttons |
| `speed 10` | timeline and slides 10× faster |
| `freeze 1\|0`, `release` | freeze / unfreeze timeline and slide |
| `lang zh\|en`, `bore 60\|70`, `hint 0\|1`, `coil 0\|1`, `skip 0\|1`, `mute 0\|1`, `volume 0.6` | settings |
| `captionoffset 0.13` | lying caption strip: axial offset on the bore's roof (0.10–0.25) |
| `recenter` | T10 |
| `testtex 1\|0` | "F↑" test card to the right of the main card and on the ceiling light box (texture orientation, taste D10) |
| `dragcard 0.2 0.05` | move the main card as a drag would (metres along the view's right / up; D21) |
| `hits 0\|1` | room colliders off / on (click tests) |
| `guard 0\|1` | QA control: `0` puts the build-13 behaviour back (room colliders always in, no line-of-sight holes) |
| `state` | one log line: screen, segment, d (mm), θ (°), motion, events, minor seconds, score, s, posture |
| `dump` | head pose, frame, s, every panel holder's position |

### Screenshot list (design package `qaCapture`)

The emulator is the seated mode (S15) by default; `posture lie` forges lying.

| State | Commands |
| --- | --- |
| S00 Loading | launch, capture within 0.3 s (may be skipped) |
| S01 Ready | `goto S01`; then `posture sit` / `posture lie` (posture row and the room change) |
| S02 Scan room | `goto S02`; `best L1=98` then again (row shows "稳 98") |
| S03 Tour | `goto S03 tour=1` … `tour=5` (`posture lie` first for all five) |
| S04 Sliding in | `goto S04 t=6` (front face nearly at the head); `goto S04 t=8` (caption already on the roof) |
| S05 Settling | `goto S05` (countdown 2) |
| S06 Scanning | `goto S06 seq=T1 t=42` (ring white); camera left 30° / right 30° / towards the feet 30° (D8) |
| S08 Motion hint | in S06: `head dx=0.006` (amber), `head dx=0.012` (red); `hint 0` then the same (no change); `head 0` |
| S07 Gap | `goto S07 next=louder`; `next=quieter`; `next=same` |
| S09 Called | `goto S09` (pause card, strip hidden, head out of the bore); `goto S09 from=S04 t=3` (world back at park) |
| S10 Sliding out | `goto S10 t=6` |
| S11 Result | `goto S11 score=98 events=0`; `score=84 events=1`; `score=60` |
| S12 Settings | `goto S12`; `lang en` then again (S16); `bore 70` and compare the bore with `goto S06` |
| S13 About | `goto S13` |
| S14 Tracking lost | `goto S06 seq=T1 t=20` then `lost 1`; `goto S04 t=6 run=1` then `lost 1` (world stops half-way); `goto S09` then `lost 1`; `lost 0` recovers (S06 → S05); `lost 1` held 10 s → S09 |
| S15 Seated | S02 / S05 / S06 in the default emulator pose |
| S16 English | `lang en`: S02 / S06 / S11 |

Numbers (U1–U11) against the judge: `goto S06 seq=T1 t=0`, then `head dx=0.004` / `0.006` / `0.010` / `0.012`,
`head deg=1.5` / `2` / `2.5 pivot=1` / `4`, and `state` after each (d, θ, events, minor seconds, score).

## Deviations (also in state.json `deviations[]`)

All within tolerance; see the design package for the original wording.

1. Scan noise is the synthesised fallback track (tools/make_audio.py); no freesound CC0 recording was fetched (N8:
   downloads need the user's go-ahead, and the user was asleep). Listen-check still to do on the device.
2. Call ball (N7): controller trigger held 0.4 s anywhere (ControllerActionListener); the pinch path is a pinch held
   0.4 s with the system pointer on any room / gantry / tour collider (a pinch at nothing has no target). No
   "double press" fallback needed unless the emulator shows the hold does not arrive.
3. Tour hover highlight (N6): the system's `HoverEffectComponent` on the tour colliders; the app does not get hover
   events of its own. The highlight shell is a 2 % larger translucent box (not a copy of the mesh); the head coil is
   tinted instead of shelled (the user's head is inside it). The leader line is a thin box, not a cylinder.
4. Room surfaces (walls, floor, ceiling) and the bore's inner wall are pre-shaded Unlit, not PBR: with one directional
   light and no IBL in Full immersion, the bore's roof (what a lying user stares at) would be black. Gantry, table,
   coil and ball stay PBR with a small emissive term.
5. Caption strip: two rows inside its 96 dp (sequence / progress / ring / mute on top, the 24 dp caption below), so the
   16-character Chinese line fits the 420 dp width; English captions longer than one line are two parts, 3 s each.
   Before the front face passes the head (s < 1.0) it hangs 0.6 m up in view at scale 0.8 (design gives no scale).
   Sitting, it stays on the far rim the whole time (always visible through the bore), no pre-position.
6. Sitting: the call ball sits at the right hand at lap height (the design places it only for lying); the gantry gets
   a 5 cm plinth so it stands on the floor 1.2 m below the eyes.
7. Called from the tour (S03): "Resume" goes back to the tour, "Slide out & finish" to the scan room (the design's
   resume path is for scans).
8. The main card has two pre-built sizes (480 × 400 — inside the design range 300–440 — and settings 480 × 440); only one shows at a time, at the same place.
9. S00 → S01 when the room is built, or after 10 s at the latest (the idle emulator builds one resource a second).
10. Contrast (D13): the scan room is light, so the Stage glass is light too (about #969693 measured in other factory
    apps). Caption text, grade, "tracking lost" and the tour's call demo are labelPrimary text; the status colours
    (still / amber / red, steady / good / retry) only fill dots and the ring, each with a labelPrimary rim.

11. Sitting caption strip on the far rim at 0.19 m up instead of 0.24 m: at scale 1.0 its top corners reached into
    the 0.30 m bore wall (emulator shot).
12. The tour leader line is not drawn when it would pass within 0.3 m of the eyes (the coil; sitting, the window
    behind the user): it turned into a wedge across the view. Instead, a stop behind the head adds "(behind you —
    turn round)" to the tag's text.
13. Call-ball tour text mentions pinch-and-hold too (the design's copy has no tour texts; these are the app's own,
    process only).

14. D21 (QA q1-2): the main card has a grab bar 2.5 cm under it; the system pointer drags it (pinch, or controller
    ray + trigger), the card follows at its grab distance, faces the user, and stays where it is released; "Re-centre"
    brings it back. The tour tag is 440 dp wide so the English button row fits (QA q1-1).

15. QA q2-1: no collider may lie on a line of sight through a shown panel. Room hit boxes exist only where the
    pinch-held call works (S03–S07, lost S10) and get a hole cut where a panel is in front of them (`domain/Occlusion`,
    `MriScene.updateHits`); tour hit boxes only in S03, stowed when the tag is in front. Tests: `tools/tap_test.sh N X Y`
    (real pinch taps, reads `muted` from shared_prefs after each).

## Emulator round (2026-10-29, emulator-5560, HD)

All `qaCapture` states shot (`tools/shots_all.sh`, `tools/shots_fix.sh`, D8 head turns), 30 panel cycles each way,
cold start 3 × 60 s clean (`tools/cold_start.sh`), real pinch tap / pinch hold / pause-card tap, HOME → background →
relaunch → S09. Results and the fixes they led to: `spatial-app-factory/apps/mri-rehearsal/build/ui-audit.md` §7.
Emulator quirks seen here: the first HMD sample is (0,0,0) + identity (dropped); textured quads sample v from the
bottom; the controller provider reports DEVICE_NOT_SUPPORTED (the trigger hold can only be tested on the headset).
Tools: `tools/m.sh` (cmd / shot / log / install / launch), `tools/harness/emu.sh` (from juggle-coach: fast on, click,
drag), `tools/harness/uia_cam.ps1` (camera reset). `hits 0|1` toggles the room colliders for click tests.

Still device-only: lying posture for real, trigger hold, audio by ear, crown immersion 40, tracking jitter.
