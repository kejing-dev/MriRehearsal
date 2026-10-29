package io.embodit.mrirehearsal.domain

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The world offset s ∈ [0, 1.8] (design `model.scannerPlacement`, R2-1): s = 0 is the parked scanner (its front
 * face 1.0 m beyond the top of the head), s = 1.8 puts the head at the isocentre, the front face passes the top
 * of the head at s = 1.0. Room, gantry and window sit at park + s·b; the table, coil and call ball stay with the user.
 *
 * The motion is a trapezoid: 0.5 s ease in, cruise at [speed], 0.5 s ease out (0.15 m/s in / out, 0.25 m/s for the
 * call, resume and abandon paths).
 */
data class Slide(val s: Float = 0f, val target: Float = 0f, val speed: Float = SLOW, val velocity: Float = 0f) {
    val moving: Boolean
        get() = abs(target - s) > EPS || abs(velocity) > EPS

    val arrived: Boolean
        get() = !moving

    fun toward(target: Float, speed: Float): Slide = copy(target = target.coerceIn(0f, MAX), speed = speed)

    /** One step of [dt] seconds (scaled by the debug speed factor at the caller). */
    fun step(dt: Float): Slide {
        if (!moving) return copy(s = target, velocity = 0f)
        val dir = if (target > s) 1f else -1f
        val dist = abs(target - s)
        val accel = speed / EASE_SEC
        // speed allowed so that we can still stop at the target with the same deceleration
        val vStop = sqrt(2f * accel * dist)
        val vWanted = dir * min(speed, vStop)
        val dv = accel * dt
        var v = velocity
        v = if (abs(vWanted - v) <= dv) vWanted else v + if (vWanted > v) dv else -dv
        var ns = s + v * dt
        if ((dir > 0 && ns >= target) || (dir < 0 && ns <= target) || (dist < 1e-3f && abs(v) < accel * 0.05f)) {
            ns = target
            v = 0f
        }
        return copy(s = ns.coerceIn(0f, MAX), velocity = v)
    }

    /** Jumps straight to the target (skip-animation setting, debug jumps). */
    fun snap(): Slide = copy(s = target, velocity = 0f)

    companion object {
        const val MAX = 1.8f
        const val FACE_AT_HEAD = 1.0f
        const val SLOW = 0.15f
        const val FAST = 0.25f
        const val EASE_SEC = 0.5f
        const val EPS = 1e-4f

        /** Target of a call: one metre back out, never past the park position (R2-1). */
        fun callTarget(s: Float): Float = (s - 1.0f).coerceAtLeast(0f)

        const val RESUME_TARGET = MAX
        const val ABANDON_TARGET = 0f

        /** Offset at [seconds] of a slow slide-in that started from park (QA "S04 at 6 s"), ignoring the ease. */
        fun slowSlideAt(seconds: Float): Float = (seconds * SLOW).coerceIn(0f, MAX)
    }
}
