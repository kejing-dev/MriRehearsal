package io.embodit.mrirehearsal.domain

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Device rework r5 (gate C, 2026-11-24, user on the headset: "the thing above my head is in the way, not sure it is in
 * the right place" -> the gantry / bore).
 *
 * The room's frame used to be taken at T1 "Enter the scan room" and then only again when the window opened. A user who
 * pressed T1 sitting up on the bed (or with the head raised to look at the window) and then lay back kept a frame from
 * that other head pose: the bore's axis was no longer through the eyes and, if T1 was judged sitting, the gantry stood
 * as a horizontal tunnel at the old sitting eye height, over the lying user. The frame now follows the head the user
 * actually scans with:
 *
 * - every run start (T3, the level buttons) takes H0 and b again from the head pose of that moment, posture judged again
 *   from it (unless the posture was set by hand), so at s = 1.8 the eyes are on the bore's axis (1:1, see [inBore]);
 * - outside a run, in the parked room (S02) and on the result page (S11), a posture change held for [PostureFollow.HOLD_MS]
 *   lays the room out again (the tour S03 is left alone: looking at the ceiling light box sitting is 49 degrees up).
 */
object BoreFit {
    /** Posture for a run that starts now: the manual choice, else the judge (with its hysteresis) fed the head now. */
    fun postureAtRunStart(current: Posture, manual: Boolean, forwardDotUp: Float?): Posture {
        if (manual || forwardDotUp == null) return current
        return PostureJudge(current).feed(forwardDotUp)
    }

    /** True when a new frame differs enough from the old one that the room should fade instead of jumping. */
    fun jumps(old: ScanFrame?, new: ScanFrame): Boolean {
        if (old == null) return false
        if (old.posture != new.posture) return true
        if ((old.h0 - new.h0).length > JUMP_M) return true
        val c = old.b.dot(new.b).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c).toDouble()) > JUMP_DEG
    }

    /**
     * Where the [eye] is in the bore at world offset [s] (frame axes): [axial] from the isocentre (+ = towards the feet),
     * [lateral], [up] above the axis, [roofM] = eye to the bore's roof straight above (lying) / along the frame's up.
     */
    data class InBore(val axial: Float, val lateral: Float, val up: Float, val offAxisM: Float, val roofM: Float)

    fun inBore(f: ScanFrame, s: Float, eye: V3, boreRadius: Float): InBore {
        // the isocentre sits at H0 + b (s - 1.8): at s = 1.8 it is H0
        val iso = f.h0 + f.b * (s - Slide.MAX)
        val d = eye - iso
        val axial = d.dot(f.b)
        val lateral = d.dot(f.right)
        val up = d.dot(f.up)
        val off = sqrt(lateral * lateral + up * up)
        return InBore(axial, lateral, up, off, boreRadius - up)
    }

    const val JUMP_M = 0.03f
    const val JUMP_DEG = 3f
}

/**
 * Posture change outside a run (S02, S11): the judge's answer has to stay different from the frame's posture for
 * [HOLD_MS] before the room is laid out again (a glance up while sitting does not turn the room over).
 */
class PostureFollow(private val holdMs: Long = HOLD_MS) {
    private var since = -1L
    private var pending: Posture? = null

    /** Feed the judged posture at [nowMs]; returns the new posture once it has held, else null. */
    fun feed(judged: Posture, framePosture: Posture, nowMs: Long): Posture? {
        if (judged == framePosture) {
            reset()
            return null
        }
        if (pending != judged) {
            pending = judged
            since = nowMs
            return null
        }
        if (nowMs - since >= holdMs) {
            reset()
            return judged
        }
        return null
    }

    fun reset() {
        since = -1L
        pending = null
    }

    companion object {
        const val HOLD_MS = 3000L
    }
}
