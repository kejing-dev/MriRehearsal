package io.embodit.mrirehearsal.ui

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.mrirehearsal.domain.AppLanguage
import io.embodit.mrirehearsal.domain.Caption
import io.embodit.mrirehearsal.domain.Level
import io.embodit.mrirehearsal.domain.Motion
import io.embodit.mrirehearsal.domain.NextLoudness
import io.embodit.mrirehearsal.domain.Posture
import io.embodit.mrirehearsal.domain.PostureJudge
import io.embodit.mrirehearsal.domain.Q4
import io.embodit.mrirehearsal.domain.RunClock
import io.embodit.mrirehearsal.domain.RunEngine
import io.embodit.mrirehearsal.domain.RunResult
import io.embodit.mrirehearsal.domain.Score
import io.embodit.mrirehearsal.domain.Screen
import io.embodit.mrirehearsal.domain.SeqType
import io.embodit.mrirehearsal.domain.Settings
import io.embodit.mrirehearsal.domain.Slide
import io.embodit.mrirehearsal.domain.StillReading
import io.embodit.mrirehearsal.domain.TourStop
import io.embodit.mrirehearsal.domain.UiState
import io.embodit.mrirehearsal.domain.V3
import io.embodit.mrirehearsal.domain.lastAfterGap
import io.embodit.mrirehearsal.domain.nextLoudness
import io.embodit.mrirehearsal.platform.MriGraph
import io.embodit.mrirehearsal.platform.MriLog
import io.embodit.mrirehearsal.platform.ScanAudio
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the scene should do with the world offset s. [seq] changes with every new command. */
data class SlideCommand(val seq: Int = 0, val mode: Mode = Mode.TO, val target: Float = 0f, val speed: Float = Slide.SLOW, val snap: Boolean = false) {
    enum class Mode {
        /** Go to [target]. */
        TO,

        /** Go to max(0, s − 1.0) from wherever s is (R2-1). */
        CALL,
    }
}

/**
 * The state machine of the design package (`states[]`, `interactions[]`). It runs on the main thread: the scene's
 * frame callbacks are posted here, the 50 ms ticker drives the sequence timeline (not the render frame, design
 * `ecs.SequencePlayerSystem`), audio is started and stopped from here. The world offset itself moves in the scene
 * ([SlideCommand]); the scene reports back when a slide arrives.
 */
class MriViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("mri_rehearsal", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val audio = ScanAudio(app)
    private val postureJudge = PostureJudge()
    private val head = MriGraph.head

    val engine = RunEngine()

    private val _state = MutableStateFlow(UiState(settings = loadSettings(), best = loadBest()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile var slideCommand = SlideCommand()
        private set

    /** Debug time factor (`speed 10`): timeline and slides. */
    @Volatile var speed = 1f

    /** Lying caption strip: axial offset towards the feet, metres (design 0.13, debug 0.10–0.25). */
    @Volatile var captionOffset = 0.13f

    /** The current world offset, reported by the scene. */
    @Volatile var worldOffset = 0f

    private var settleLeft = 0f
    private var resumeClock: RunClock? = null
    private var calledFrom: Screen? = null
    private var lostFrom: Screen? = null
    private var lostSinceMs = 0L
    private var firstSequence = true
    private var backgroundInRun = false
    private var backgrounded = false
    private var built = false

    init {
        applyVolume()
        viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val dt = ((now - last).coerceIn(0L, 250L)) / 1000f
                last = now
                runCatching { tick(dt, now) }.onFailure { MriLog.e("TICK failed: $it", it) }
            }
        }
        // S00 never waits forever for the room (the meshes keep arriving after S01)
        main.postDelayed({ if (_state.value.screen == Screen.S00) onBuilt() }, LOADING_TIMEOUT_MS)
    }

    override fun onCleared() {
        main.removeCallbacksAndMessages(null)
        audio.release()
    }

    // ------------------------------------------------------------------------------------- the ticker

    private fun tick(dtWall: Float, now: Long) {
        val s = _state.value
        if (s.tourDemoUntilMs != 0L && now > s.tourDemoUntilMs) _state.update { it.copy(tourDemoUntilMs = 0L) }
        if (s.lost) {
            if (s.screen != Screen.S09 && now - lostSinceMs > LOST_TO_CALL_MS) {
                MriLog.i("LOST 10 s without tracking: calling")
                call()
            }
            return
        }
        if (s.frozen || backgrounded) return
        val dt = dtWall * speed
        when (s.screen) {
            Screen.S05 -> {
                settleLeft -= dt
                if (settleLeft <= 0f) beginSequence() else setCaption(Caption.Settle(ceil(settleLeft).toInt().coerceIn(1, 3)))
            }
            Screen.S06 -> {
                val (clock, ended) = s.clock.advance(dt)
                _state.update { it.copy(clock = clock, caption = sequenceCaption(clock)) }
                if (ended) segmentEnded()
            }
            Screen.S07 -> {
                val (clock, ended) = s.clock.advance(dt)
                _state.update { it.copy(clock = clock) }
                if (ended) clock.nextSegment()?.let { next -> _state.update { it.copy(clock = next) }; beginSequence() } ?: finishRun()
            }
            else -> Unit
        }
    }

    private fun sequenceCaption(clock: RunClock): Caption {
        val seg = clock.current ?: return Caption.HoldStill
        return when {
            clock.elapsed < START_CAPTION_SEC -> Caption.SeqStart(seg.seconds)
            firstSequence && clock.elapsed < START_CAPTION_SEC * 2 -> Caption.Earplugs
            else -> Caption.HoldStill
        }
    }

    // ------------------------------------------------------------------------------------- run flow

    /** T3 / T11: a new run of [level] from the park position. L0 is the tour. */
    fun selectLevel(level: Level) {
        if (level == Level.L0) {
            _state.update { it.copy(screen = Screen.S03, level = level, tourIndex = 0, cardEpoch = it.cardEpoch + 1, result = null) }
            MriLog.i("STATE -> S03 tour")
            return
        }
        engine.reset()
        firstSequence = true
        resumeClock = null
        calledFrom = null
        _state.update {
            it.copy(screen = Screen.S04, level = level, clock = RunClock(level), result = null, abandoning = false, pauseCardReady = false, caption = Caption.TableMoves, motion = Motion.STILL)
        }
        audio.motor(true)
        command(SlideCommand.Mode.TO, Slide.RESUME_TARGET, Slide.SLOW, snap = _state.value.settings.skipSlide)
        MriLog.i("STATE -> S04 level=$level")
    }

    private fun beginSequence() {
        val clock = _state.value.clock
        val seg = clock.current ?: return finishRun()
        if (seg.type.isGap) {
            enterGap(clock)
            return
        }
        val pose = head.last
        engine.startSequence(pose?.position ?: V3.ZERO, pose?.rotation ?: Q4.IDENTITY)
        audio.playBed(seg.type)
        _state.update { it.copy(screen = Screen.S06, caption = sequenceCaption(clock), motion = Motion.STILL) }
        MriLog.i("STATE -> S06 seq=${seg.type} ${seg.seconds}s segment=${clock.segment}")
    }

    private fun enterGap(clock: RunClock) {
        audio.playBed(SeqType.GAP)
        val level = clock.level
        val caption = Caption.Gap(nextLoudness(level, clock.segment) ?: NextLoudness.SAME, lastAfterGap(level, clock.segment))
        _state.update { it.copy(screen = Screen.S07, clock = clock, caption = caption, motion = Motion.STILL) }
        MriLog.i("STATE -> S07 gap segment=${clock.segment} caption=$caption")
    }

    private fun segmentEnded() {
        val clock = _state.value.clock
        if (clock.current?.type?.isGap == false) {
            engine.endSequence(clock.levelSec)
            firstSequence = false
        }
        val next = clock.nextSegment() ?: return finishRun()
        if (next.current?.type?.isGap == true) {
            enterGap(next)
        } else {
            _state.update { it.copy(clock = next) }
            beginSequence()
        }
    }

    private fun finishRun() {
        audio.stopBed()
        audio.motor(true)
        _state.update { it.copy(screen = Screen.S10, abandoning = false, caption = Caption.Done, motion = Motion.STILL) }
        command(SlideCommand.Mode.TO, Slide.ABANDON_TARGET, Slide.SLOW, snap = _state.value.settings.skipSlide)
        MriLog.i("STATE -> S10 run complete")
    }

    /** T5: the call ball (trigger held / pinch held 0.4 s). */
    fun onCallHold() {
        val s = _state.value
        when {
            s.screen == Screen.S03 && s.tourStop == TourStop.CALL_BALL -> {
                audio.call()
                _state.update { it.copy(tourDemoUntilMs = SystemClock.elapsedRealtime() + DEMO_MS) }
                MriLog.i("CALL demo on the call-ball tour stop")
            }
            s.screen == Screen.S03 || s.screen.inRun -> call()
            s.lost && s.screen == Screen.S10 -> call()
            else -> MriLog.i("CALL ignored in ${s.screen}")
        }
    }

    private fun call() {
        val s = _state.value
        if (s.screen == Screen.S09) return
        calledFrom = if (s.lost) lostFrom ?: s.screen else s.screen
        resumeClock =
            when (calledFrom) {
                Screen.S06 -> s.clock.copy(elapsed = 0f)
                Screen.S07 -> s.clock.nextSegment() ?: s.clock
                else -> s.clock
            }
        if (calledFrom == Screen.S06) engine.dropSequence()
        if (s.lost) lostFrom = Screen.S09
        audio.stopAll()
        audio.call()
        _state.update { it.copy(screen = Screen.S09, pauseCardReady = false, caption = Caption.Called, motion = Motion.STILL) }
        command(SlideCommand.Mode.CALL, 0f, Slide.FAST)
        MriLog.i("STATE -> S09 called from=$calledFrom resume=${resumeClock?.segment}")
    }

    /** T6 "Resume scan": the current sequence starts again (U8). From the tour it goes back to the tour. */
    fun resume() {
        if (calledFrom == Screen.S03) {
            _state.update { it.copy(screen = Screen.S03, pauseCardReady = false, caption = Caption.None, cardEpoch = it.cardEpoch + 1) }
            return
        }
        if (calledFrom == Screen.S10) {
            // called while sliding out: carry on sliding out (the run, if complete, still gets its result)
            audio.motor(true)
            _state.update { it.copy(screen = Screen.S10, pauseCardReady = false, caption = if (it.abandoning) Caption.None else Caption.Done) }
            command(SlideCommand.Mode.TO, Slide.ABANDON_TARGET, Slide.FAST, snap = _state.value.settings.skipSlide)
            return
        }
        val clock = resumeClock ?: _state.value.clock
        audio.motor(true)
        _state.update { it.copy(screen = Screen.S04, clock = clock, pauseCardReady = false, caption = Caption.TableMoves) }
        command(SlideCommand.Mode.TO, Slide.RESUME_TARGET, Slide.FAST, snap = _state.value.settings.skipSlide)
        MriLog.i("STATE -> S04 resume segment=${clock.segment}")
    }

    /** T6 "Slide out & finish": back to park, no result. */
    fun finishFromPause() {
        engine.reset()
        audio.motor(true)
        _state.update { it.copy(screen = Screen.S10, abandoning = true, pauseCardReady = false, caption = Caption.None) }
        command(SlideCommand.Mode.TO, Slide.ABANDON_TARGET, Slide.FAST, snap = _state.value.settings.skipSlide)
        MriLog.i("STATE -> S10 abandon")
    }

    /** The scene: the slide of command [seq] has arrived at s. */
    fun onArrived(seq: Int, s: Float) =
        main.post {
            if (seq != slideCommand.seq) return@post
            val st = _state.value
            MriLog.i("SLIDE arrived s=$s in ${st.screen}")
            when (st.screen) {
                Screen.S04 -> {
                    // only the slide to the isocentre ends S04 (a debug snap half-way does not)
                    if (s < Slide.MAX - 0.001f) return@post
                    audio.motor(false)
                    settleLeft = SETTLE_SEC
                    _state.update { it.copy(screen = Screen.S05, caption = Caption.Settle(3)) }
                    MriLog.i("STATE -> S05 settle")
                }
                Screen.S09 -> _state.update { it.copy(pauseCardReady = true, caption = Caption.None, cardEpoch = it.cardEpoch + 1) }
                Screen.S10 -> {
                    if (s > 0.001f) return@post
                    audio.motor(false)
                    if (st.abandoning) {
                        _state.update { it.copy(screen = Screen.S02, abandoning = false, caption = Caption.None, cardEpoch = it.cardEpoch + 1) }
                        MriLog.i("STATE -> S02 (abandoned)")
                    } else {
                        showResult()
                    }
                }
                else -> Unit
            }
        }

    private fun showResult() {
        val st = _state.value
        val t = engine.totals()
        val score = Score.of(t.events, t.minorSec, st.level.sequenceSec.toFloat())
        val result = RunResult(st.level, score, t.events, t.minorSec, t.minorSpans, t.eventMarks)
        val best = st.best.toMutableMap()
        if (score > (best[st.level] ?: -1)) {
            best[st.level] = score
            saveBest(best)
        }
        _state.update { it.copy(screen = Screen.S11, result = result, best = best, caption = Caption.None, cardEpoch = it.cardEpoch + 1) }
        MriLog.i("STATE -> S11 level=${st.level} score=$score events=${t.events} minorSec=${"%.1f".format(t.minorSec)}")
    }

    /** S11 buttons. */
    fun nextLevel() = selectLevel(_state.value.level.next() ?: _state.value.level)

    fun retry() = selectLevel(_state.value.level)

    fun backToRoom() {
        audio.stopAll()
        _state.update { it.copy(screen = Screen.S02, caption = Caption.None, cardEpoch = it.cardEpoch + 1) }
        command(SlideCommand.Mode.TO, 0f, Slide.FAST, snap = true)
        MriLog.i("STATE -> S02")
    }

    // ------------------------------------------------------------------------------------- tracking

    /** The scene, every frame in S04–S10: is the head pose lost (500 ms without a sample)? */
    fun onLost(lost: Boolean) =
        main.post {
            val s = _state.value
            if (lost == s.lost) return@post
            if (lost) {
                if (!s.screen.watchesTracking) return@post
                lostFrom = s.screen
                lostSinceMs = SystemClock.elapsedRealtime()
                audio.stopAll()
                _state.update { it.copy(lost = true, caption = if (s.screen == Screen.S09 && it.pauseCardReady) it.caption else Caption.Lost, motion = Motion.STILL) }
                MriLog.i("LOST in ${s.screen} (S14)")
            } else {
                val from = lostFrom
                lostFrom = null
                _state.update { it.copy(lost = false) }
                MriLog.i("LOST recovered, was in $from, now ${s.screen}")
                when (from) {
                    Screen.S06, Screen.S07, Screen.S05 -> {
                        if (from == Screen.S06) {
                            engine.dropSequence()
                            _state.update { it.copy(clock = it.clock.copy(elapsed = 0f)) }
                        } else if (from == Screen.S07) {
                            _state.value.clock.nextSegment()?.let { next -> _state.update { it.copy(clock = next) } }
                        }
                        settleLeft = SETTLE_SEC
                        _state.update { it.copy(screen = Screen.S05, caption = Caption.Settle(3)) }
                    }
                    Screen.S04 -> {
                        audio.motor(true)
                        setCaption(Caption.TableMoves)
                    }
                    Screen.S10 -> {
                        audio.motor(true)
                        setCaption(if (s.abandoning) Caption.None else Caption.Done)
                    }
                    else -> if (s.screen == Screen.S09 && !s.pauseCardReady) setCaption(Caption.Called)
                }
            }
        }

    /** The scene: the first head sample has arrived. */
    fun onHeadFound() =
        main.post {
            if (!_state.value.headFound) {
                _state.update { it.copy(headFound = true, frameEpoch = it.frameEpoch + 1) }
                MriLog.i("HEAD found")
            }
        }

    /** The scene, every 0.5 s in S01: F·worldUp of the head (T2). */
    fun onPostureReading(forwardDotUp: Float) =
        main.post {
            val s = _state.value
            if (s.screen != Screen.S01 || s.postureManual) return@post
            val p = postureJudge.feed(forwardDotUp)
            if (p != s.posture) {
                _state.update { it.copy(posture = p, frameEpoch = it.frameEpoch + 1) }
                MriLog.i("POSTURE auto=$p f.up=$forwardDotUp")
            }
        }

    /** The scene, ten times a second while judging (S06). */
    fun onStill(reading: StillReading) =
        main.post {
            val s = _state.value
            if (s.screen != Screen.S06) return@post
            val motion = if (s.settings.motionHint) reading.motion else Motion.STILL
            if (motion == s.motion) return@post
            if (motion == Motion.MAJOR) audio.tick()
            _state.update { it.copy(motion = motion) }
        }

    // ------------------------------------------------------------------------------------- S00–S02, S12, S13

    /** The scene: the room is built (or the loading timeout passed). */
    fun onBuilt() =
        main.post {
            if (built) return@post
            built = true
            if (_state.value.screen == Screen.S00) {
                _state.update { it.copy(screen = Screen.S01, cardEpoch = it.cardEpoch + 1) }
                MriLog.i("STATE -> S01")
            }
        }

    /** T1 "Enter the scan room": lock the posture, take H0 and b from the current head pose (D9). */
    fun enterRoom() {
        _state.update { it.copy(screen = Screen.S02, frameEpoch = it.frameEpoch + 1, cardEpoch = it.cardEpoch + 1) }
        MriLog.i("STATE -> S02 posture=${_state.value.posture} manual=${_state.value.postureManual}")
    }

    /** T2 manual links: "I want to sit" / "I'm lying down". */
    fun setPostureManual(p: Posture) {
        postureJudge.set(p)
        _state.update { it.copy(posture = p, postureManual = true, frameEpoch = it.frameEpoch + 1) }
        MriLog.i("POSTURE manual=$p")
    }

    /** T10 "Re-centre": posture and frame again from the current head pose. */
    fun recenter() {
        val s = _state.value
        if (!s.postureManual) {
            head.last?.let { pose -> postureJudge.feed(pose.forward.y).also { p -> if (p != s.posture) _state.update { it.copy(posture = p) } } }
        }
        _state.update { it.copy(frameEpoch = it.frameEpoch + 1, cardEpoch = it.cardEpoch + 1) }
        MriLog.i("RECENTER posture=${_state.value.posture}")
    }

    fun openSettings() {
        val from = _state.value.screen
        if (from != Screen.S01 && from != Screen.S02) return
        _state.update { it.copy(screen = Screen.S12, settingsFrom = from) }
    }

    fun closeSettings() {
        _state.update { it.copy(screen = it.settingsFrom) }
    }

    fun openAbout() = _state.update { it.copy(screen = Screen.S13) }

    fun closeAbout() = _state.update { it.copy(screen = Screen.S12) }

    // ------------------------------------------------------------------------------------- tour

    fun tourStep(delta: Int) {
        val s = _state.value
        val i = (s.tourIndex + delta).coerceIn(0, s.tourStops.lastIndex)
        _state.update { it.copy(tourIndex = i, tourDemoUntilMs = 0L) }
    }

    fun tourJump(stop: TourStop) {
        val s = _state.value
        if (s.screen != Screen.S03) return
        val i = s.tourStops.indexOf(stop)
        if (i >= 0) _state.update { it.copy(tourIndex = i, tourDemoUntilMs = 0L) }
        MriLog.i("TOUR tapped $stop -> ${i + 1}")
    }

    /** The scene: the current tour stop is behind the user (sitting: the window). */
    fun onTourBehind(behind: Boolean) =
        main.post { if (_state.value.tourBehind != behind) _state.update { it.copy(tourBehind = behind) } }

    fun tourBack() {
        _state.update { it.copy(screen = Screen.S02, cardEpoch = it.cardEpoch + 1, tourDemoUntilMs = 0L) }
    }

    // ------------------------------------------------------------------------------------- settings

    fun updateSettings(change: (Settings) -> Settings) {
        val next = change(_state.value.settings)
        _state.update { it.copy(settings = next) }
        saveSettings(next)
        applyVolume()
    }

    fun setLanguage(l: AppLanguage) = updateSettings { it.copy(language = l) }

    fun toggleMute() = updateSettings { it.copy(muted = !it.muted) }

    fun clearScores() {
        saveBest(emptyMap())
        _state.update { it.copy(best = emptyMap()) }
    }

    private fun applyVolume() {
        val s = _state.value.settings
        audio.setVolume(if (s.muted) 0f else s.volume)
    }

    // ------------------------------------------------------------------------------------- background (T13)

    fun onBackground() {
        val s = _state.value
        backgroundInRun = s.screen.inRun && !s.lost
        backgrounded = true
        audio.stopAll()
        MriLog.i("BACKGROUND in ${s.screen} inRun=$backgroundInRun")
    }

    fun onForeground() {
        MriLog.i("FOREGROUND inRun=$backgroundInRun")
        backgrounded = false
        if (backgroundInRun) {
            backgroundInRun = false
            call()
            // the world is already out when the user comes back (design T13)
            command(SlideCommand.Mode.CALL, 0f, Slide.FAST, snap = true)
        }
    }

    // ------------------------------------------------------------------------------------- helpers

    private fun command(mode: SlideCommand.Mode, target: Float, speed: Float, snap: Boolean = false) {
        slideCommand = SlideCommand(slideCommand.seq + 1, mode, target, speed, snap)
    }

    private fun setCaption(c: Caption) {
        if (_state.value.caption != c) _state.update { it.copy(caption = c) }
    }

    private fun loadSettings(): Settings {
        val systemLang = if (Locale.getDefault().language == "zh") AppLanguage.ZH else AppLanguage.EN
        return Settings(
            language = AppLanguage.fromTag(prefs.getString("lang", null)) ?: systemLang,
            boreCm = prefs.getInt("bore", 60).let { if (it == 70) 70 else 60 },
            motionHint = prefs.getBoolean("hint", true),
            headCoil = prefs.getBoolean("coil", true),
            skipSlide = prefs.getBoolean("skip", false),
            volume = prefs.getFloat("volume", 0.6f),
            muted = prefs.getBoolean("muted", false),
        )
    }

    private fun saveSettings(s: Settings) {
        prefs
            .edit()
            .putString("lang", s.language.name.lowercase())
            .putInt("bore", s.boreCm)
            .putBoolean("hint", s.motionHint)
            .putBoolean("coil", s.headCoil)
            .putBoolean("skip", s.skipSlide)
            .putFloat("volume", s.volume)
            .putBoolean("muted", s.muted)
            .apply()
    }

    private fun loadBest(): Map<Level, Int> =
        listOf(Level.L1, Level.L2, Level.L3).mapNotNull { l -> prefs.getInt("best_${l.name}", -1).takeIf { it >= 0 }?.let { l to it } }.toMap()

    private fun saveBest(best: Map<Level, Int>) {
        val e = prefs.edit()
        for (l in listOf(Level.L1, Level.L2, Level.L3)) {
            val v = best[l]
            if (v == null) e.remove("best_${l.name}") else e.putInt("best_${l.name}", v)
        }
        e.apply()
    }

    // ------------------------------------------------------------------------------------- debug (QA broadcast)

    /** `goto <state> [params]`: jumps for screenshots. Timed states are frozen unless `run=1`. */
    fun debugGoto(screen: Screen, args: Map<String, String>) {
        val run = args["run"] == "1"
        val level = Level.fromTag(args["level"])
        audio.stopAll()
        lostFrom = null
        _state.update { it.copy(lost = false, frozen = false, tourDemoUntilMs = 0L) }
        when (screen) {
            Screen.S00, Screen.S01 -> {
                _state.update { it.copy(screen = screen, postureManual = false, caption = Caption.None, cardEpoch = it.cardEpoch + 1, frameEpoch = it.frameEpoch + 1) }
                command(SlideCommand.Mode.TO, 0f, Slide.FAST, snap = true)
            }
            Screen.S02 -> backToRoom()
            Screen.S03 -> {
                selectLevel(Level.L0)
                val n = (args["tour"] ?: args["n"])?.toIntOrNull()
                if (n != null) _state.update { it.copy(tourIndex = (n - 1).coerceIn(0, it.tourStops.lastIndex)) }
            }
            Screen.S04 -> {
                selectLevel(level ?: Level.L1)
                val t = args["t"]?.toFloatOrNull() ?: 6f
                command(SlideCommand.Mode.TO, Slide.slowSlideAt(t), Slide.SLOW, snap = true)
                if (!run) main.postDelayed({ _state.update { it.copy(frozen = true) } }, 300)
                if (run) main.postDelayed({ command(SlideCommand.Mode.TO, Slide.RESUME_TARGET, Slide.SLOW) }, 300)
            }
            Screen.S05 -> {
                debugInBore(level ?: Level.L1, 0)
                settleLeft = (args["count"]?.toFloatOrNull() ?: 2f)
                _state.update { it.copy(screen = Screen.S05, caption = Caption.Settle(settleLeft.toInt().coerceIn(1, 3)), frozen = !run) }
            }
            Screen.S06 -> {
                val type = SeqType.entries.firstOrNull { it.name.equals(args["seq"], ignoreCase = true) && !it.isGap } ?: SeqType.T1
                val lv = level ?: if (type == SeqType.T2) Level.L3 else if (type == SeqType.LOC) Level.L1 else Level.L2
                val index = RunClock(lv).find(type) ?: 0
                debugInBore(lv, index)
                _state.update { it.copy(clock = it.clock.copy(elapsed = args["t"]?.toFloatOrNull() ?: 0f)) }
                firstSequence = index == 0
                beginSequence()
                _state.update { it.copy(clock = it.clock, frozen = !run) }
            }
            Screen.S07 -> {
                val index =
                    when (args["next"]?.lowercase()) {
                        "quieter" -> 7
                        "same" -> 3
                        else -> 1
                    }
                debugInBore(Level.L3, index)
                enterGap(_state.value.clock.copy(elapsed = args["t"]?.toFloatOrNull() ?: 2f))
                _state.update { it.copy(frozen = !run) }
            }
            Screen.S09 -> {
                if (args["from"].equals("S04", ignoreCase = true)) {
                    selectLevel(level ?: Level.L1)
                    command(SlideCommand.Mode.TO, Slide.slowSlideAt(args["t"]?.toFloatOrNull() ?: 3f), Slide.SLOW, snap = true)
                    main.postDelayed({ call() }, 400)
                } else {
                    debugInBore(level ?: Level.L2, RunClock(level ?: Level.L2).find(SeqType.T1) ?: 0)
                    _state.update { it.copy(clock = it.clock.copy(elapsed = 10f)) }
                    beginSequence()
                    main.postDelayed({ call() }, 400)
                }
            }
            Screen.S10 -> {
                selectLevel(level ?: Level.L1)
                _state.update { it.copy(screen = Screen.S10, caption = Caption.Done, abandoning = false) }
                val t = args["t"]?.toFloatOrNull() ?: 6f
                command(SlideCommand.Mode.TO, (Slide.MAX - Slide.slowSlideAt(t)).coerceAtLeast(0f), Slide.SLOW, snap = true)
                if (!run) main.postDelayed({ _state.update { it.copy(frozen = true) } }, 300)
                if (run) main.postDelayed({ command(SlideCommand.Mode.TO, 0f, Slide.SLOW) }, 300)
            }
            Screen.S11 -> debugResult(args)
            Screen.S12 -> {
                if (_state.value.screen != Screen.S01 && _state.value.screen != Screen.S02) backToRoom()
                openSettings()
            }
            Screen.S13 -> {
                if (_state.value.screen != Screen.S01 && _state.value.screen != Screen.S02) backToRoom()
                openSettings()
                openAbout()
            }
        }
        MriLog.i("DEBUG goto $screen -> ${_state.value.screen} frozen=${_state.value.frozen}")
    }

    /** A run of [level] with the head already at the isocentre and the clock at segment [index]. */
    private fun debugInBore(level: Level, index: Int) {
        engine.reset()
        audio.motor(false)
        firstSequence = index == 0
        _state.update {
            it.copy(level = level, clock = RunClock(level, index, 0f), result = null, abandoning = false, pauseCardReady = false, motion = Motion.STILL, screen = Screen.S05)
        }
        command(SlideCommand.Mode.TO, Slide.MAX, Slide.SLOW, snap = true)
    }

    /** `result score=98 events=0 [level=L2]`. */
    fun debugResult(args: Map<String, String>) {
        val level = Level.fromTag(args["level"]) ?: Level.L1
        val score = args["score"]?.toIntOrNull() ?: 98
        val events = args["events"]?.toIntOrNull() ?: if (score >= 95) 0 else if (score >= 80) 1 else 2
        val sec = level.sequenceSec.toFloat()
        // minor seconds that give this score with these events, drawn as one amber span in the middle
        val minor = (((100 - 15 * events - score).coerceAtLeast(0)) / 40f * sec).coerceIn(0f, sec)
        val mid = level.totalSec / 2f
        val spans = if (minor > 0f) listOf(io.embodit.mrirehearsal.domain.Span(mid - minor / 2f, mid + minor / 2f)) else emptyList()
        // events in the first third, clear of the amber span in the middle
        val marks = List(events) { i -> level.totalSec * (0.12f + 0.08f * i) }
        audio.stopAll()
        _state.update {
            it.copy(screen = Screen.S11, level = level, result = RunResult(level, score, events, minor, spans, marks), caption = Caption.None, cardEpoch = it.cardEpoch + 1)
        }
        command(SlideCommand.Mode.TO, 0f, Slide.FAST, snap = true)
    }

    fun debugBest(values: Map<Level, Int>) {
        val best = _state.value.best.toMutableMap().apply { putAll(values) }
        saveBest(best)
        _state.update { it.copy(best = best) }
    }

    fun debugSlide(s: Float) = command(SlideCommand.Mode.TO, s, Slide.SLOW, snap = true)

    fun debugCall() = call()

    fun debugFreeze(on: Boolean) = _state.update { it.copy(frozen = on) }

    fun debugPosture(p: Posture) = setPostureManual(p)

    fun debugTestTexture(on: Boolean) = _state.update { it.copy(testTexture = on) }

    /** `state`: one log line with the judge's numbers. */
    fun debugStateLine(): String {
        val s = _state.value
        val r = engine.reading()
        val score = Score.of(r.events, r.minorSec, s.level.sequenceSec.toFloat())
        return "STATE screen=${s.screen} level=${s.level} seg=${s.clock.segment}/${s.clock.current?.type} t=${"%.1f".format(s.clock.elapsed)} " +
            "d=${"%.1f".format(r.displacementMm)}mm theta=${"%.2f".format(r.angleDeg)}deg motion=${r.motion} events=${r.events} minorSec=${"%.2f".format(r.minorSec)} " +
            "score=$score s=${"%.2f".format(worldOffset)} posture=${s.posture} lost=${s.lost} frozen=${s.frozen} caption=${s.caption}"
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MriViewModel(app) as T
    }

    private companion object {
        const val TICK_MS = 50L
        const val SETTLE_SEC = 3f
        const val START_CAPTION_SEC = 6f
        const val LOST_TO_CALL_MS = 10_000L
        const val DEMO_MS = 3000L
        const val LOADING_TIMEOUT_MS = 10_000L
    }
}
