package io.embodit.mrirehearsal.domain

import kotlin.math.abs

/**
 * The system pointer's drag as measured in Spatial Ruler / dose-distance (SDK 6.1.9 emulator): the system reports how
 * far the hand travelled in the view's plane; the pointing line turns by that travel over an "equivalent depth" of the
 * press point. The main card follows that line at the distance it had when it was grabbed (D21).
 */
object DragLine {
    const val HAND_OFFSET_M = 0.67f
    const val HAND_PER_M = 1.186f
    const val HAND_PER_PITCH_DEG = 0.0015f

    /** Metres of system drag travel per unit of view-plane displacement for a press [distance] metres away. */
    fun equivalentDepth(distance: Float, pitchDeg: Float): Float {
        if (distance <= 1e-3f) return 0f
        return (HAND_OFFSET_M + HAND_PER_M * distance) * (1f + HAND_PER_PITCH_DEG * abs(pitchDeg))
    }

    /** The pointing line after one hand step ([step], Stage metres) in the view's axes. */
    fun turn(direction: V3, step: V3, equivalentDepth: Float, right: V3, up: V3, forward: V3): V3 {
        val depth = direction.dot(forward)
        if (equivalentDepth <= 1e-3f || depth <= 1e-3f) return direction
        val x = direction.dot(right) / depth + step.dot(right) / equivalentDepth
        val y = direction.dot(up) / depth + step.dot(up) / equivalentDepth
        val to = right * x + up * y + forward
        return if (to.length < 1e-6f) direction else to.normalized()
    }
}
