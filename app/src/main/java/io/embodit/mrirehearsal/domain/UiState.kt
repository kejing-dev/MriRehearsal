package io.embodit.mrirehearsal.domain

/** The base states of the design package (`states[]`); S08, S14, S15, S16 are modifiers carried in [UiState]. */
enum class Screen {
    S00,
    S01,
    S02,
    S03,
    S04,
    S05,
    S06,
    S07,
    S09,
    S10,
    S11,
    S12,
    S13;

    /** Inside the scanner run: the call ball works, tracking loss pauses (S04–S07; S03 has its own call handling). */
    val inRun: Boolean
        get() = this == S04 || this == S05 || this == S06 || this == S07

    /** States in which a lost head pose becomes S14 (design: S04–S10). */
    val watchesTracking: Boolean
        get() = inRun || this == S09 || this == S10

    companion object {
        fun fromTag(tag: String?): Screen? = entries.firstOrNull { it.name.equals(tag, ignoreCase = true) }
    }
}

enum class AppLanguage {
    ZH,
    EN;

    companion object {
        fun fromTag(tag: String?): AppLanguage? =
            when (tag?.lowercase()) {
                "zh", "cn", "zh-cn" -> ZH
                "en" -> EN
                else -> null
            }
    }
}

/** The six settings (design S12) plus mute, all persisted. */
data class Settings(
    val language: AppLanguage = AppLanguage.ZH,
    /** Bore diameter in cm: 60 (default) or 70. */
    val boreCm: Int = 60,
    val motionHint: Boolean = true,
    val headCoil: Boolean = true,
    val skipSlide: Boolean = false,
    val volume: Float = 0.6f,
    val muted: Boolean = false,
) {
    val boreRadius: Float
        get() = boreCm / 200f
}

/** What the caption strip says (resolved to text in the panel, in the current language). */
sealed interface Caption {
    data object TableMoves : Caption

    data class Settle(val count: Int) : Caption

    data class SeqStart(val seconds: Int) : Caption

    data object Earplugs : Caption

    data object HoldStill : Caption

    data class Gap(val next: NextLoudness, val last: Boolean) : Caption

    data object Done : Caption

    data object Called : Caption

    data object Lost : Caption

    data object None : Caption
}

/** The result of a finished level (S11). */
data class RunResult(
    val level: Level,
    val score: Int,
    val events: Int,
    val minorSec: Float,
    val minorSpans: List<Span> = emptyList(),
    val eventMarks: List<Float> = emptyList(),
) {
    val grade: Grade
        get() = Score.grade(score)
}

/** Everything the panels show. The scene reads it once per frame; high-rate values never come back through it. */
data class UiState(
    val screen: Screen = Screen.S00,
    val posture: Posture = Posture.SIT,
    /** The user picked the posture by hand ("I want to sit" / "I'm lying down"): overrides the judge until exit. */
    val postureManual: Boolean = false,
    val headFound: Boolean = false,
    val level: Level = Level.L1,
    val clock: RunClock = RunClock(Level.L1),
    val caption: Caption = Caption.None,
    /** Stillness reading, sampled ten times a second. */
    val motion: Motion = Motion.STILL,
    /** S14: tracking lost while in S04–S10. */
    val lost: Boolean = false,
    /** S09: the world has finished sliding out and the pause card may show. */
    val pauseCardReady: Boolean = false,
    /** S10 path: true after "Slide out & finish" (no result, no caption). */
    val abandoning: Boolean = false,
    val tourIndex: Int = 0,
    /** S03: the current stop is behind the user's head (the tag then says so). */
    val tourBehind: Boolean = false,
    /** S03: the call-ball demo line on the tag (until this uptime, ms). */
    val tourDemoUntilMs: Long = 0L,
    val result: RunResult? = null,
    val best: Map<Level, Int> = emptyMap(),
    val settings: Settings = Settings(),
    /** Where S12 returns to (S01 or S02), also after a detour through S13 (R2-8). */
    val settingsFrom: Screen = Screen.S02,
    /** Bumped whenever the main card must be placed again in front of the head (each appearance, T10). */
    val cardEpoch: Int = 0,
    /** Bumped when the room must be laid out again from the current head pose (T1, T10). */
    val frameEpoch: Int = 0,
    val frozen: Boolean = false,
    val testTexture: Boolean = false,
) {
    val language: AppLanguage
        get() = settings.language

    /** Tour stops in this posture (the table is skipped when sitting). */
    val tourStops: List<TourStop>
        get() = TourStop.entries.filter { posture == Posture.LIE || it != TourStop.TABLE }

    val tourStop: TourStop
        get() = tourStops[tourIndex.coerceIn(0, tourStops.lastIndex)]
}

/** The five tour points (design S03). */
enum class TourStop {
    GANTRY,
    TABLE,
    COIL,
    CALL_BALL,
    WINDOW,
}
