package io.embodit.mrirehearsal.domain

import kotlin.math.roundToInt

/** How far the head is from its reference right now (design `model.stillness.thresholds`). */
enum class Motion { STILL, MINOR, MAJOR }

/** One reading of the judge, a few times a second to the panels. */
data class StillReading(
    val displacementMm: Float = 0f,
    val angleDeg: Float = 0f,
    val motion: Motion = Motion.STILL,
    val events: Int = 0,
    val minorSec: Float = 0f,
)

/** A span of the run's timeline (seconds on the level's own clock), for the result card's line. */
data class Span(val fromSec: Float, val toSec: Float)

/**
 * The stillness judge (design `model.stillness`). Fed one head sample per frame while a sequence runs:
 *
 * - the pivot correction P' = P − 0.09·F turns the HMD origin (in front of the eyes) into the neck pivot, so a pure
 *   rotation adds no displacement (R1-13);
 * - displacement d = |P' − P0'| and angle θ = 2·acos(|q·q0|) against the pose taken when the sequence started;
 * - both through a 200 ms median filter (the headset jitters about 1 mm);
 * - minor: d > 4 mm or θ > 1.5°; major: d > 10 mm or θ > 4°;
 * - a major event = at least 300 ms above major; going above major again within 1 s of coming back inside minor
 *   is the same event;
 * - minor seconds = time above minor (major included), counted only inside sequences.
 *
 * The sequence's own counts are kept apart from the finished sequences' so that "resume" after a call can drop
 * the current sequence and start it again (U8). Not thread-safe: one caller (the frame system).
 */
class StillnessJudge(
    private val minorMm: Float = MINOR_MM,
    private val minorDeg: Float = MINOR_DEG,
    private val majorMm: Float = MAJOR_MM,
    private val majorDeg: Float = MAJOR_DEG,
) {
    private var refPivot: V3? = null
    private var refRot: Q4? = null
    private val window = ArrayDeque<Triple<Long, Float, Float>>()

    // current sequence
    private var seqEvents = 0
    private var seqMinorSec = 0f
    private var seqMinorSpans = ArrayList<Span>()
    private var seqEventMarks = ArrayList<Float>()

    // finished sequences
    private var doneEvents = 0
    private var doneMinorSec = 0f
    private val doneMinorSpans = ArrayList<Span>()
    private val doneEventMarks = ArrayList<Float>()

    // event detection
    private var aboveMajorSinceMs = -1L
    private var inEvent = false
    private var calmSinceMs = -1L
    private var minorFromSec = -1f
    private var lastMs = -1L

    var last = StillReading()
        private set

    /** True between [startSequence] and [endSequence]. */
    var active = false
        private set

    /** Takes the reference pose of a new sequence (or the restart of the current one). */
    fun startSequence(position: V3, rotation: Q4) {
        refPivot = pivotOf(position, rotation)
        refRot = rotation.normalized()
        window.clear()
        seqEvents = 0
        seqMinorSec = 0f
        seqMinorSpans = ArrayList()
        seqEventMarks = ArrayList()
        aboveMajorSinceMs = -1L
        inEvent = false
        calmSinceMs = -1L
        minorFromSec = -1f
        lastMs = -1L
        active = true
        last = StillReading(events = doneEvents, minorSec = doneMinorSec)
    }

    /** Ends the current sequence and keeps its counts. */
    fun endSequence(atSec: Float) {
        if (!active) return
        closeMinorSpan(atSec)
        doneEvents += seqEvents
        doneMinorSec += seqMinorSec
        doneMinorSpans += seqMinorSpans
        doneEventMarks += seqEventMarks
        active = false
        last = last.copy(motion = Motion.STILL, events = doneEvents, minorSec = doneMinorSec)
    }

    /** Drops the current sequence's counts (S09 resume / S14 recovery start it again). */
    fun dropSequence() {
        active = false
        seqEvents = 0
        seqMinorSec = 0f
        seqMinorSpans = ArrayList()
        seqEventMarks = ArrayList()
        last = StillReading(events = doneEvents, minorSec = doneMinorSec)
    }

    /** Clears everything (a new run). */
    fun reset() {
        dropSequence()
        doneEvents = 0
        doneMinorSec = 0f
        doneMinorSpans.clear()
        doneEventMarks.clear()
        last = StillReading()
    }

    /**
     * One head sample at [nowMs] (monotonic), [levelSec] on the level's clock (for the result timeline). Returns the
     * reading after this sample.
     */
    fun sample(nowMs: Long, levelSec: Float, position: V3, rotation: Q4): StillReading {
        val p0 = refPivot ?: return last
        val q0 = refRot ?: return last
        if (!active) return last
        val d = (pivotOf(position, rotation) - p0).length * 1000f
        val a = angleBetweenDeg(rotation, q0)
        window.addLast(Triple(nowMs, d, a))
        while (window.size > 1 && nowMs - window.first().first > WINDOW_MS) window.removeFirst()
        val dm = median(window.map { it.second })
        val am = median(window.map { it.third })
        val motion =
            when {
                dm > majorMm || am > majorDeg -> Motion.MAJOR
                dm > minorMm || am > minorDeg -> Motion.MINOR
                else -> Motion.STILL
            }
        val dtSec = if (lastMs < 0) 0f else ((nowMs - lastMs).coerceIn(0L, 250L)) / 1000f
        lastMs = nowMs
        if (motion != Motion.STILL) {
            seqMinorSec += dtSec
            if (minorFromSec < 0f) minorFromSec = (levelSec - dtSec).coerceAtLeast(0f)
        } else {
            closeMinorSpan(levelSec)
        }
        // major events: >= 300 ms above major; within 1 s of calming down it is still the same event
        if (motion == Motion.MAJOR) {
            if (aboveMajorSinceMs < 0) aboveMajorSinceMs = nowMs
            calmSinceMs = -1L
            if (!inEvent && nowMs - aboveMajorSinceMs >= EVENT_MIN_MS) {
                inEvent = true
                seqEvents++
                seqEventMarks += levelSec
            }
        } else {
            aboveMajorSinceMs = -1L
            if (inEvent) {
                if (motion == Motion.STILL) {
                    if (calmSinceMs < 0) calmSinceMs = nowMs
                    if (nowMs - calmSinceMs > MERGE_MS) inEvent = false
                } else {
                    // between minor and major: not yet calm
                    calmSinceMs = -1L
                }
            }
        }
        last = StillReading(dm, am, motion, doneEvents + seqEvents, doneMinorSec + seqMinorSec)
        return last
    }

    /** The finished-run numbers (only meaningful after the last [endSequence]). */
    fun totals(): Totals = Totals(doneEvents, doneMinorSec, doneMinorSpans.toList(), doneEventMarks.toList())

    data class Totals(val events: Int, val minorSec: Float, val minorSpans: List<Span>, val eventMarks: List<Float>)

    private fun closeMinorSpan(atSec: Float) {
        if (minorFromSec >= 0f) {
            seqMinorSpans += Span(minorFromSec, atSec)
            minorFromSec = -1f
        }
    }

    companion object {
        const val MINOR_MM = 4f
        const val MINOR_DEG = 1.5f
        const val MAJOR_MM = 10f
        const val MAJOR_DEG = 4f
        const val WINDOW_MS = 200L
        const val EVENT_MIN_MS = 300L
        const val MERGE_MS = 1000L

        /** Neck pivot behind the HMD origin (R1-13). */
        const val PIVOT_M = 0.09f

        fun pivotOf(position: V3, rotation: Q4): V3 = position - rotation.forward * PIVOT_M

        private fun median(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            val s = values.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
        }
    }
}

/** Grade of a stillness score (design: ≥ 95 steady / ≥ 80 good / below: try again). */
enum class Grade { STEADY, GOOD, RETRY }

object Score {
    /**
     * Stillness score = 100 − 15 × major events − 40 × (minor seconds / sequence seconds), rounded, floor 0. The
     * denominator is the level's sequence seconds without the gaps (L1 30 / L2 104 / L3 268, R2-5).
     */
    fun of(events: Int, minorSec: Float, sequenceSec: Float): Int {
        val ratio = if (sequenceSec > 0f) (minorSec / sequenceSec).coerceIn(0f, 1f) else 0f
        val raw = 100f - 15f * events - 40f * ratio
        return raw.roundToInt().coerceIn(0, 100)
    }

    fun grade(score: Int): Grade =
        when {
            score >= 95 -> Grade.STEADY
            score >= 80 -> Grade.GOOD
            else -> Grade.RETRY
        }
}
