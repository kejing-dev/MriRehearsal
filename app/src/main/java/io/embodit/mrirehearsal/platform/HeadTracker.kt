package io.embodit.mrirehearsal.platform

import android.os.SystemClock
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.mrirehearsal.domain.Q4
import io.embodit.mrirehearsal.domain.StillnessJudge
import io.embodit.mrirehearsal.domain.V3

/** Head pose in Stage space. */
data class HeadPose(val position: V3, val rotation: Q4) {
    val forward: V3
        get() = rotation.forward
}

/**
 * HMD pose source (`HMDTrackingProvider`, Full Space only), read once per frame from the ECS system; the last valid
 * pose is kept so that a missing sample never makes content jump (moon-phases HeadTracker).
 *
 * Lost tracking (S14): no valid pose for [LOST_AFTER_MS]. The emulator never loses tracking; `lost=1` forces it.
 *
 * Debug offset (`head dx= dy= dz= deg= pivot=`): added to every sample so QA can put exact numbers into the
 * stillness judge. `deg` turns the head about world up; with `pivot=1` it turns about the neck pivot 0.09 m behind
 * the HMD origin (a real head turn), otherwise about the HMD origin.
 */
class HeadTracker {
    private var provider: HMDTrackingProvider? = null
    private var lastValidMs = 0L

    @Volatile var forceLost = false

    @Volatile var offset = V3.ZERO

    @Volatile var offsetDeg = 0f

    @Volatile var offsetPivot = false

    /** The last pose handed out, for callers off the frame thread (the ViewModel taking a sequence reference). */
    @Volatile var last: HeadPose? = null
        private set

    fun start() {
        if (provider != null) return
        val p = HMDTrackingProvider()
        val result = runCatching { p.start() }
        MriLog.i("TRACK hmd start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
        lastValidMs = SystemClock.elapsedRealtime()
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
    }

    val running: Boolean
        get() = provider != null

    fun lost(nowMs: Long): Boolean = forceLost || (provider != null && last != null && nowMs - lastValidMs > LOST_AFTER_MS)

    /** Current head pose (with the debug offset), or the last valid one, or null before the first sample. */
    fun pose(): HeadPose? {
        val p = provider ?: return last
        if (forceLost) return last
        val pose = runCatching { p.latestData.hmdPose }.getOrNull() ?: return last
        val position = pose.position
        val rotation = pose.rotation
        var pos = V3(position.x, position.y, position.z)
        var rot = Q4(rotation.x, rotation.y, rotation.z, rotation.w).normalized()
        if (!pos.isFinite() || !rot.isFinite()) return last
        // the first samples after start() are an untracked zero pose (emulator: (0,0,0) + identity, the head is at 1.34 m)
        if (pos.length < 1e-4f && kotlin.math.abs(rot.w) > 0.99999f) return last
        lastValidMs = SystemClock.elapsedRealtime()
        if (offsetDeg != 0f) {
            val turn = Q4.axisAngle(V3.UP, offsetDeg)
            if (offsetPivot) {
                val pivot = StillnessJudge.pivotOf(pos, rot)
                rot = (turn * rot).normalized()
                pos = pivot + rot.forward * StillnessJudge.PIVOT_M
            } else {
                rot = (turn * rot).normalized()
            }
        }
        pos += offset
        val sample = HeadPose(pos, rot)
        last = sample
        return sample
    }

    private companion object {
        const val LOST_AFTER_MS = 500L
    }
}
