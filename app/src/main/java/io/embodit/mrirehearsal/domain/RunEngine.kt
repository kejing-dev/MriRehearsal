package io.embodit.mrirehearsal.domain

/**
 * The stillness judge behind a lock: the frame system feeds head samples, the ViewModel starts / ends / drops
 * sequences and reads the totals. Nothing here touches the SDK, so the unit tests drive it directly.
 */
class RunEngine {
    private val judge = StillnessJudge()
    private val lock = Any()

    val active: Boolean
        get() = synchronized(lock) { judge.active }

    fun startSequence(position: V3, rotation: Q4) = synchronized(lock) { judge.startSequence(position, rotation) }

    fun endSequence(levelSec: Float) = synchronized(lock) { judge.endSequence(levelSec) }

    fun dropSequence() = synchronized(lock) { judge.dropSequence() }

    fun reset() = synchronized(lock) { judge.reset() }

    fun sample(nowMs: Long, levelSec: Float, position: V3, rotation: Q4): StillReading = synchronized(lock) { judge.sample(nowMs, levelSec, position, rotation) }

    fun reading(): StillReading = synchronized(lock) { judge.last }

    fun totals(): StillnessJudge.Totals = synchronized(lock) { judge.totals() }
}
