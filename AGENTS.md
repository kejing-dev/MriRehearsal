# MRI Rehearsal · 磁共振预习

A PICO OS 6 Spatial SDK app (Kotlin + ECS, BOM 6.1.9). Lying on your own bed (or sitting), a life-size MRI scanner
slides you into its 60 cm bore, the scan noise plays, and the headset measures how far your head moves: a room tour,
then 30 s, 2 min and 5 min scans scored for stillness. Fully offline, no permissions, Chinese / English in the app.

- Package / applicationId: `io.embodit.mrirehearsal`, version 0.1.1 (versionCode 2)
- Containers: the default Stage, **Progressive**, immersion 100 / 40 / 100 (`AndroidManifest.xml`) for the scan room,
  plus **one Planar WindowContainer** `MriWindow` (640 × 620 dp) for every 2D page (v0.1.1, factory
  `prompts/snippets/native-window-move.md`): native SpatialUI TabBar (Scan / How to / About / Settings), the system's
  own move / close / minimise controls under the window. No self-made drag bar, no panel drag code.
- Design package: `spatial-app-factory/apps/mri-rehearsal/design-package.json` (rev 3, archetype C, 17 states, 14 interactions)
- Reference repo: `moon-phases` (Stage manifest, HeadTracker, debug receiver, fonts, in-app language); controller
  trigger from `dance-steps` / `bianzhong`; window template, LevelGate and recall from `too-much` / `pruning-class`

## Build

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest assembleDebug --no-daemon
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. `gradle.properties` keeps the build JVM at 2 GB and the daemon
off (several projects build on this machine at once). Release: `assembleRelease` gives an unsigned APK (no
signingConfig, no keystore in the repo; the store signs it). Offline release builds: add
`-x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease`.

Unit tests (`app/src/test/...`): `DesignNumbersTest` (design U1–U11, level totals, slide paths incl. the v0.1.1 call
back to park, panel orientation), `OcclusionTest` (q2-1 line-of-sight guard), `WindowLayoutTest` (one placement
function for every window opening path, the window ≥ 10° from the gantry from either eye sitting / standing / lying,
D9 upright window, the lying readout clear of the window, the level gate incl. the supine branch, volume steps).

Assets: `python tools/make_icon.py …` (adaptive icon), `python -I tools/cut_cc0.py FFMPEG MRI_MP3 BED_MP3 WORK` (scan-room
loops from the CC0 freesound recordings), `python tools/make_audio.py` (UI cues tick / call only),
`python -I tools/loudness.py OUT.wav` (loudness evidence at the default volume 0.8, rendered).

## Layout of the code (`app/src/main/java/io/embodit/mrirehearsal`)

```
Main.kt                DefaultStage { MriScreen() } + WindowContainer("MriWindow", Planar) { MriWindow() }
platform/              SpatialApplication, LaunchActivity, MriLog, MriGraph; HeadTracker (HMDTrackingProvider + debug offset),
                       ControllerInput (trigger held = call ball), ScanAudio (SoundPool beds + cues, MediaPlayer localiser)
domain/                MathKit, Stillness (judge + score), Plan (sequences, levels, run clock), Slide (world offset s),
                       Frame (posture judge, ScanFrame), RunEngine, UiState (screens, WindowPage, VolumeSteps),
                       LevelGate (open the window only level / lying looking up), WindowLayout (THE placement function:
                       where the window opens, the room laid out around it, the ≥ 10° check), Occlusion (q2-1)
ui/MriShared.kt        process-level: the ViewModel, the window's lifecycle, the gate, recall = close → ON_DESTROY → open
ui/MriViewModel.kt     the state machine; 50 ms ticker drives the timeline; slide commands for the scene
ui/MriScreen.kt        the Stage: SpatialView (room + the in-bore readout + the "open panel" chip), tap / hold gesture
ui/window/             MainWindow (template: TabBar, exitApp), MriWindow (the four pages, settings, confirmations)
ui/card/               CaptionStrip (the in-bore readout fixed on the bore), Chip, Common (Txt with OVERFLOW log, buttons)
scene/MriScene.kt      ECS: room, gantry (+ bore, strips, band), table, coil, call ball, window, tour highlight,
                       the two Stage panels; onFrame(): gate step, slide, stillness sampling, panel placement, hit guard
scene/SceneKit.kt      SDK helpers, MeshBuilder, programmatic meshes (gantry with rounded outline, bore, strips, band,
                       birdcage coil, sphere, rounded slabs for table / pillow / plinth, cylinders for hose / speaker)
debug/                 DebugCommandReceiver + DebugHost (the QA broadcast)
```

Conventions: Stage metres; the scan frame's local axes are +Z = b (towards the feet), +Y = up, +X = up × b. World
offset s ∈ [0, 1.8]: 0 = gantry parked (front face 1.0 m beyond the top of the head), 1.8 = head at the isocentre.
All 2D UI is SpatialUI inside PicoTheme; no Material / Material3 imports (factory gate). Nothing aims with the head's
forward: selection is the system pointer (controller ray / gaze + pinch); the head pose is the stillness input, the
posture judge, the level gate and the placement origin (`apps/mri-rehearsal/build/headpose-audit.md`).

### Window and room (v0.1.1)

- The window opens on the line of sight about 0.82 m away (system; the app cannot place it). Every open goes through
  `MriShared.askOpen` → `LevelGate`: |pitch| ≤ 10° for 300 ms, or lying looking up (`WindowLayout.supineOk`); meanwhile
  the Stage chip says "Look straight ahead — the panel opens" on the line of sight.
- Sitting, the room is laid out around the window: the gantry stands 80° to the right of the direction the window
  opened in (`WindowLayout.frameFor`), so the window's outline (+ TabBar above, system controls below) stays ≥ 10° clear
  of it from both eyes. Lying, the window opens above the face and the gantry is beyond the top of the head.
- Every window open outside a run (S00–S03, S11) re-lays the room around the new window (`MriViewModel.onWindowOpened`).
  Inside a run the room stays; a call (S09) slides the world back to park, where the gantry clears the window again.
- [Bring back in front] (Settings first screen, scan page S02): close → wait for ON_DESTROY → open (an open right
  after a close is swallowed, native-window-move §3.5). The Stage chip "Open panel" shows while the window is closed
  (its ×) or minimised; a minimised window is restored where it was.
- The in-bore readout (`CaptionStrip`, 420 × 108 dp) stays a reading fixed on the bore (template §3.6 / §6): in Full
  Space the window is where the user looked when it opened, 80° from the bore (sitting) or above the face (lying), so
  in the bore the user looks at the readout, not the window (on the emulator the window draws over the bore wall, it is
  not depth-hidden; spatial-ui-skills #64). The window's scan page shows the same lines.
- Quit (Settings → Quit app → Quit): release the scene and the sound, close the window container, close the Stage,
  `finishAndRemoveTask`, `exitProcess` 400 ms later (`ui/window/MainWindow.exitApp`).

## Debug broadcast (QA screenshots)

Debug builds only. One command per broadcast; log tag `MriRehearsal` answers every command (`adb logcat -s MriRehearsal`).

```bash
adb -s <serial> shell am broadcast -a io.embodit.mrirehearsal.DEBUG -p io.embodit.mrirehearsal --es cmd "goto S06 seq=T1 t=42"
```

`goto` freezes the timeline and the slide at the requested moment; add `run=1` to let it continue, or send `release`
later. Start every shot series with `reset` (clears timers, offsets, frozen / lost flags, the page; back to S01 / S02).

| Command | Precondition | What it does |
| --- | --- | --- |
| `reset` | any | sound off, head offsets 0, not lost, unfrozen, scan page, world parked, back to S01 (from S00 / S01) or S02 |
| `goto S00` / `goto S01` | any | loading / ready (posture judged again) |
| `goto S02` | any | the scan room (world parked) |
| `goto S03 tour=n` | any | tour stop n (1..5; 1..4 sitting, the table is skipped) |
| `goto S04 t=6 [level=L1]` | any | sliding in, world offset as at second t of the 0.15 m/s slide (t=6 → s≈0.9, t=8 → s≈1.2) |
| `goto S05 [count=2]` | any | settling countdown, head at the isocentre |
| `goto S06 seq=T1 t=42 [level=L2]` | any | scanning that sequence at second t (seq LOC / T1 / T2 / EPI; T2 picks L3) |
| `goto S07 next=louder\|quieter\|same` | any | the gap before a louder / quieter / same sequence |
| `goto S09` | any | called from S06 (L2, T1 at 10 s): the world slides back to park (≈ 4.5 s), pause page on the window. `goto S09 from=S04 t=3`: called half-way in |
| `goto S10 t=6` | any | sliding out, as at second 6 |
| `goto S11 score=98 events=0 [level=L2]` (= `result …`) | any | result page with that score |
| `goto S12` / `goto S13` (= `page settings` / `page about`) | any | the Settings / About page of the window (the scan state underneath is kept) |
| `page scan\|howto\|about\|settings` | window open | switches the TabBar page (a real tab press does the same) |
| `window open\|close\|recall` | Stage running | ask for an open (waits for a level head) / close (×) / [Bring back in front]; no word = log the window state |
| `posture lie\|sit` | S01 (or any; then `recenter`) | posture override (as the manual links; `lie` on the emulator takes b from the camera's forward) |
| `best L1=98 L2=84` | any | stored best scores |
| `tour n` | any (goes to S03) | tour stop n |
| `head dx=0.012 dy= dz= deg=2 pivot=1` / `head 0` | S06 for the judge | offset added to every head sample (metres, degrees about world up; `pivot=1` about the neck) |
| `lost 1\|0` | S04–S10 | force tracking lost (S14) / restore |
| `s 0.45` | in a run | snap the world offset |
| `call` / `resume` / `finish` | S03–S07 / S09 / S09 | the call ball / pause page buttons (finish here skips the page's confirmation) |
| `speed 10` | any | timeline and slides 10× faster (`reset` sets 1) |
| `freeze 1\|0`, `release` | any | freeze / unfreeze timeline and slide |
| `lang zh\|en`, `bore 60\|70`, `hint 0\|1`, `coil 0\|1`, `skip 0\|1`, `mute 0\|1`, `volume 0.8` | any | settings (volume snaps to 0.4 / 0.8 / 1.0) |
| `captionoffset 0.13` | lying, S05–S07 | the in-bore readout's axial offset on the bore's roof (0.10–0.25) |
| `recenter` | outside a run | posture and room frame again from the current head pose (no window change) |
| `testtex 1\|0` | any | "F↑" test card beside the chip and on the ceiling light box (texture orientation, taste D10) |
| `hits 0\|1`, `guard 0\|1` | any | room colliders off / on; QA control for the line-of-sight guard |
| `state` | any | one log line: screen, segment, d (mm), θ (°), motion, events, minor seconds, score, s, posture |
| `dump` | Stage running | head pose, frame, s, the Stage panels' positions |

Log lines worth grepping: `WINDOW ask|open|opened|ON_*`, `LEVEL hint|ok`, `RECALL`, `TAB`, `PAGE`, `QUIT`, `EXIT`,
`OVERFLOW <panel>/<text>` (a fixed-line text that does not fit; run every state in English), `COLORS` (theme roles,
for the contrast table), `STATE ->`.

### Screenshot list (design package `qaCapture`, v0.1.1)

The emulator is the seated mode (S15) by default; `posture lie` forges lying (window ahead, gantry behind). Shots of
the window: console screenshot after the window has opened (3–6 s after launch / recall); the system TabBar sits above
the window, the system controls (dot / × / handle / −) under it. Start each series with `reset`.

| State | Commands (in order) |
| --- | --- |
| default view | cold start, camera untouched, wait for `WINDOW ON_RESUME` + `SCENE build done`: window ahead, gantry's flank at the right |
| S00 Loading | launch, capture within 2 s (may be skipped) |
| S01 Ready | `reset`; then `posture lie` / `posture sit` |
| S02 Scan room | `goto S02`; `best L1=98` then again |
| S03 Tour | `goto S03 tour=1` … `tour=4` (sitting) / `posture lie` first for all five |
| S04 Sliding in | `goto S04 t=6`; `goto S04 t=8` (look right at the bore: the readout) |
| S05 Settling | `goto S05` |
| S06 Scanning | `goto S06 seq=T1 t=42`; S08: `head dx=0.006` (amber), `head dx=0.012` (red), `head 0` |
| S07 Gap | `goto S07 next=louder` / `quieter` / `same` |
| S09 Called | `goto S09` (wait 5 s: pause page, readout says "choose on the panel"); then a real tap on [Restart this level] → the confirmation (= `build/newgame-confirm.png`) |
| S10 Sliding out | `goto S10 t=6` |
| S11 Result | `goto S11 score=98 events=0`; `score=84 events=1`; `score=60` |
| S12 Settings | `goto S12` (first screen: [Bring back in front] [Quit app]); `lang en` then again |
| S13 About | `goto S13` |
| How to | `page howto` (zh and en) |
| S14 Tracking lost | `goto S06 seq=T1 t=20` then `lost 1`; `lost 0` |
| S16 English | `lang en`, cold start: the four TabBar tabs read Scan / How to / About / Settings; S02 / S06 / S11 |
| Chip | `window close` (wait 2 s): "Open panel" in front; tap it → the window comes back |
| Quit | Settings → Quit app → Quit (real taps), then `adb shell dumpsys activity recents` must not list the package |

## Deviations (also in state.json `deviations[]`)

Within tolerance; see the design package for the original wording. v0.1.1 changes first.

1. v0.1.1: every 2D page (main card S00–S02 / S11, settings S12, about S13, the tour tag S03, the pause card S09) is a
   page of one system window with a native TabBar; the tour's leader line is gone (the scene does not know where the
   user dragged the window). The in-bore readout stays fixed on the bore (in the bore the user faces the bore, not the window).
2. v0.1.1: sitting, the gantry stands 80° to the right of the window's direction (not straight ahead): the window opens
   where the user looks and only this keeps its outline ≥ 10° from the gantry. Turn right to face the bore.
3. v0.1.1: a call slides the world all the way back to park (s = 0) at 0.45 m/s (≈ 4.5 s) instead of s − 1.0 (R2-1):
   only at park does the gantry clear the window that carries the pause page.
4. v0.1.1: lying, the window may open above the face (LevelGate supine branch, design R1-1) — an explicit exception to
   D9's "pitch 0", recorded in headpose-audit.md for the coordinator.
5. v0.1.1: volume has three steps 40 / 80 / 100 % (default 80 %), the table motor at the beds' level: sounding RMS
   −17.4 dBFS, peak −3.4 dBFS (rendered from the shipped assets, `qa/loudness-v0.1.1.wav`).
6. Scan-room sounds (versionCode 3) are CC0 freesound recordings: a GE Signa MRI by parabolix (320532) and an electric
   bed motor by kedarda (613865), cut into seamless loops by `tools/cut_cc0.py` (denoise, HP 120 Hz, LP 6 kHz,
   crossfaded seam); only tick / call stay synthesised UI cues (`build/sound-sources.md`).
7. Call ball (N7): controller trigger held 0.4 s anywhere (ControllerActionListener); the pinch path is a pinch held
   0.4 s on a room collider (in a run) or a tour object (S03).
8. Tour hover highlight (N6): the system's hover on the tour colliders; the highlight shell is a 2 % larger translucent
   box (an abstract marker); the head coil is tinted instead (the user's head is inside it).
9. Room surfaces and the bore's inner wall are pre-shaded Unlit (one directional light, no IBL in Full immersion);
   gantry, table, pillow, coil and ball are PBR with a small emissive term.
10. Sitting: the call ball sits at the right hand at lap height; the gantry gets a 5 cm plinth.
11. Called from the tour (S03): "Resume" goes back to the tour, "Slide out & finish" to the scan room.
12. S00 → S01 when the room is built, or after 10 s at the latest (the idle emulator builds one resource a second).
13. Contrast (D13): status colours (still / amber / red, steady / good / retry) only fill dots and the ring, each with a
    labelPrimary rim; text stays labelPrimary / labelSecondary.
14. Sitting readout on the far rim at 0.19 m up instead of 0.24 m (its corners reached into the bore wall).
15. QA q2-1: no collider may lie on a line of sight through a shown panel or the window where it opened; room hit boxes
    only in a run, tour boxes only in S03 (`domain/Occlusion`, `MriScene.updateHits`).

## Emulator notes

v0.1.1 round (2026-11-17, emulator-5554, Pico_Emulator_6_1_fresh, HD): every qaCapture state in zh / en
(`tools/shots_v011.sh all en-|zh-`), real-pointer walk of the main loop, q2-1 / q1-1 / q1-2, quit + recents, cold
start 3 x 60 s (0 ANR), OVERFLOW sweep (`tools/overflow_sweep.sh`), whitespace (`tools/whitespace.py --box
819,822,1800,1741`), contrast (`tools/contrast.py`); results in `spatial-app-factory/apps/mri-rehearsal/build/ui-audit.md`
section 10.12. Real taps: `tools/harness/emu.sh click X Y` (2160-wide coordinates; `tools/buttons.py SHOT` finds the
window's buttons); the camera turns with `tools/turn.sh YAW` (reads the head back with `dump`). Wait 5 s after a tap
before a console screenshot: at the idle 2-4 fps the window's frame lags one step. Pressing HOME closes the Stage but
leaves the window in the shared space (known, ui-audit 10.14).

Emulator quirks seen here: the first HMD sample is (0,0,0) + identity (dropped); textured quads sample v from the
bottom; the controller provider reports DEVICE_NOT_SUPPORTED (the trigger hold can only be tested on the headset); the
idle emulator renders at 2–4 fps (`tools/harness/emu.sh fast on` for real pointer input). Tools: `tools/m.sh`
(cmd / shot / log / install / launch), `tools/harness/emu.sh` (fast on, click, drag), `tools/harness/uia_cam.ps1`
(camera reset), `tools/cold_start.sh` (3 × 60 s cold start, ANR scan).

Still device-only: lying posture for real (the supine window), trigger hold, audio by ear, crown immersion 40,
tracking jitter, dragging the window with a hand / the controller ray.
