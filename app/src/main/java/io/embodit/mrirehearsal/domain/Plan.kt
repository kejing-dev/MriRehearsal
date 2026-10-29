package io.embodit.mrirehearsal.domain

/** The noise sequences (design `model.sequences.table`); loudness 0..4 decides the gap caption. */
enum class SeqType(val loudness: Int, val isGap: Boolean = false) {
    LOC(2),
    T1(3),
    T2(3),
    EPI(4),
    GAP(0, isGap = true),
}

/** One stretch of a level's timeline. */
data class Segment(val type: SeqType, val seconds: Int)

/** The four levels (design `model.sequences.levels`). L0 is the tour: no timeline. */
enum class Level(val segments: List<Segment>) {
    L0(emptyList()),
    L1(listOf(Segment(SeqType.LOC, 30))),
    L2(listOf(Segment(SeqType.LOC, 20), Segment(SeqType.GAP, 8), Segment(SeqType.T1, 60), Segment(SeqType.GAP, 8), Segment(SeqType.EPI, 24))),
    L3(
        listOf(
            Segment(SeqType.LOC, 20),
            Segment(SeqType.GAP, 8),
            Segment(SeqType.T1, 60),
            Segment(SeqType.GAP, 8),
            Segment(SeqType.T2, 90),
            Segment(SeqType.GAP, 8),
            Segment(SeqType.EPI, 60),
            Segment(SeqType.GAP, 8),
            Segment(SeqType.T1, 38),
        )
    );

    /** Total seconds, gaps included (30 / 120 / 300). */
    val totalSec: Int
        get() = segments.sumOf { it.seconds }

    /** Seconds inside sequences, the score's denominator (30 / 104 / 268). */
    val sequenceSec: Int
        get() = segments.filter { !it.type.isGap }.sumOf { it.seconds }

    /** Level-clock second at which segment [index] starts. */
    fun startOf(index: Int): Int = segments.take(index).sumOf { it.seconds }

    val isScan: Boolean
        get() = this != L0

    fun next(): Level? = entries.getOrNull(ordinal + 1)

    companion object {
        fun fromTag(tag: String?): Level? = entries.firstOrNull { it.name.equals(tag, ignoreCase = true) }
    }
}

/** How the next sequence compares with the one just finished (gap caption, R1-14 / R2-6). */
enum class NextLoudness { LOUDER, QUIETER, SAME }

fun nextLoudness(level: Level, gapIndex: Int): NextLoudness? {
    val before = level.segments.getOrNull(gapIndex - 1) ?: return null
    val after = level.segments.getOrNull(gapIndex + 1) ?: return null
    return when {
        after.type.loudness > before.type.loudness -> NextLoudness.LOUDER
        after.type.loudness < before.type.loudness -> NextLoudness.QUIETER
        else -> NextLoudness.SAME
    }
}

/** True when the segment after gap [gapIndex] is the last one ("this is the last one"). */
fun lastAfterGap(level: Level, gapIndex: Int): Boolean = gapIndex + 1 == level.segments.lastIndex

/**
 * Where a run is on its level's timeline. Pure value: the ViewModel's ticker advances it, the debug receiver can
 * jump it. [segment] is the index into the level's segments, [elapsed] the seconds inside that segment.
 */
data class RunClock(val level: Level, val segment: Int = 0, val elapsed: Float = 0f) {
    val current: Segment?
        get() = level.segments.getOrNull(segment)

    /** Seconds on the level clock. */
    val levelSec: Float
        get() = level.startOf(segment) + elapsed

    val remaining: Float
        get() = ((current?.seconds ?: 0) - elapsed).coerceAtLeast(0f)

    /**
     * Advances by [dt] seconds without crossing into the next segment; the caller handles the boundary (it has to
     * switch states). Returns the clock and whether the current segment just ended.
     */
    fun advance(dt: Float): Pair<RunClock, Boolean> {
        val seg = current ?: return this to true
        val e = elapsed + dt
        return if (e >= seg.seconds) copy(elapsed = seg.seconds.toFloat()) to true else copy(elapsed = e) to false
    }

    fun nextSegment(): RunClock? = if (segment + 1 < level.segments.size) RunClock(level, segment + 1, 0f) else null

    /** Index of the first segment of [type] at or after [from]. */
    fun find(type: SeqType, from: Int = 0): Int? = level.segments.indices.firstOrNull { it >= from && level.segments[it].type == type }
}
