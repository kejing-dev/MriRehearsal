package io.embodit.mrirehearsal.domain

import kotlin.math.abs

/**
 * Opens the main window only while the head is in an accepted pose (factory native-window-move §7 item 9, D9): a Planar
 * window opens on the line of sight and perpendicular to it, and the SDK cannot turn it afterwards. Every open is
 * asked for; the Stage feeds the head pitch each frame; the gate answers "open now" after the pose has held for
 * [dwellMs], or after [noHeadMs] without a valid head pose (counted from the first such frame, not from the ask).
 *
 * Accepted poses: level (|pitch| ≤ [maxPitchDeg]) — the template's rule — or, for this app only, lying on the back and
 * looking at the ceiling ([supine], decided by [WindowLayout.supineOk] while the posture is LIE). The design (rev 3,
 * R1-1) puts every 2D surface of a lying user above the face, its top towards the top of the head; a level-only gate
 * would never open the window for the app's main posture. Pure Kotlin, unit-tested (LevelGateTest).
 */
class LevelGate(val maxPitchDeg: Float = 10f, val dwellMs: Long = 300L, val noHeadMs: Long = 5000L) {
    var pending: String? = null
        private set
    private var askedMs = 0L
    private var okSinceMs = -1L
    private var noHeadSinceMs = -1L

    val waiting: Boolean
        get() = pending != null

    fun ask(why: String, now: Long) {
        if (pending == null) askedMs = now
        pending = why
        okSinceMs = -1L
        noHeadSinceMs = -1L
    }

    fun cancel() {
        pending = null
        okSinceMs = -1L
        noHeadSinceMs = -1L
    }

    fun waitedMs(now: Long): Long = if (pending == null) 0L else now - askedMs

    /** The reason to open now, or null to keep waiting. [pitchDeg]: + = looking up; null = no valid head pose. */
    fun step(now: Long, pitchDeg: Float?, supine: Boolean = false): String? {
        val why = pending ?: return null
        if (pitchDeg == null || !pitchDeg.isFinite()) {
            okSinceMs = -1L
            if (noHeadSinceMs < 0L) noHeadSinceMs = now
            if (now - noHeadSinceMs < noHeadMs) return null
            cancel()
            return "$why/nohead"
        }
        noHeadSinceMs = -1L
        if (!accepted(pitchDeg, supine)) {
            okSinceMs = -1L
            return null
        }
        if (okSinceMs < 0L) okSinceMs = now
        if (now - okSinceMs < dwellMs) return null
        cancel()
        return if (abs(pitchDeg) <= maxPitchDeg) why else "$why/supine"
    }

    private fun accepted(pitchDeg: Float, supine: Boolean): Boolean = abs(pitchDeg) <= maxPitchDeg || supine

    /** Show "look straight ahead": an open is waiting and the head is neither level nor lying looking up. */
    fun hintWanted(pitchDeg: Float?, supine: Boolean = false): Boolean = pending != null && pitchDeg != null && !accepted(pitchDeg, supine)
}
