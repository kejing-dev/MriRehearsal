package io.embodit.mrirehearsal.domain

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/** The scanner's outer size (the gantry body; the 5 cm plinth under it while sitting is inside its footprint). */
object Gantry {
    const val W = 2.10f
    const val H = 2.30f
    const val D = 1.60f
    const val PLINTH_H = 0.05f
}

/**
 * Where the main system window opens and where the scan room goes with it — the one placement function (factory
 * profile 48: launch, reopen, recall and BACK all call [place]).
 *
 * The SDK cannot place a Planar window: it opens on the line of sight, about [DIST_M] away (factory measurement, see
 * pico-cli-feedback, the Planar window size entry), perpendicular to it, and stays where the user drags it. Since
 * v0.1.1 build 4 (QA #5 q5-1, profile D9 / D10: no waiting for a level head) every open goes out straight away, so
 * [expectedWindow] is the window on the actual line of sight, head pitch included. The room is laid out around it
 * (native-window-move §4): sitting, the gantry stands [GANTRY_YAW_SIT_DEG] to the right of the window's direction, or
 * further if the window's outline at that pitch needs it ([gantryYawFor]); lying looking up, the window opens above the
 * face and the gantry stays beyond the top of the head. [separationDeg] is the D41 check: the window's outline with the
 * system TabBar above it and the system controls below it, seen from either eye, keeps >= [MIN_SEP_DEG] from the
 * gantry's box.
 */
object WindowLayout {
    /** Window size, dp: the tallest page (settings) fits without scrolling; ≤ 640 dp (factory D16). */
    const val W_DP = 640
    const val H_DP = 620

    /** dpToLength of a Planar window, and its distance along the line of sight (measured: 0.82 m). */
    const val DP_M = 0.0008f
    const val DIST_M = 0.82f

    /** The system strips outside the window: TabBar capsule above (~88 dp measured), dot / × / handle / − below (~77 dp). */
    const val TABBAR_DP = 96f
    const val CONTROLS_DP = 80f

    const val GANTRY_YAW_SIT_DEG = 80f

    /** Sitting gantry yaws tried, in order, when the window's outline (with its pitch) would come within [MIN_SEP_DEG]. */
    val GANTRY_YAW_TRIES = floatArrayOf(80f, 95f, 110f, 125f, 140f, -80f, -95f, -110f, -125f, -140f, 160f, -160f, 180f)
    const val MIN_SEP_DEG = 10f
    const val IPD_M = 0.064f

    /** Lying looking up: at least this much (forward·up ≥ cos 50°) ... */
    const val SUPINE_MIN_UP = 0.64f

    /** ... and tilted towards the top of the head by at most this much (beyond it the TabBar reaches the gantry). */
    const val SUPINE_MAX_HEADTOP_DEG = 8f

    /** A window in the world: centre, its right and up (unit vectors in its plane); it faces the eye. */
    data class WindowRect(val center: V3, val right: V3, val up: V3) {
        val normal: V3
            get() = right.cross(up).normalized()
    }

    data class Placement(val window: WindowRect, val frame: ScanFrame)

    /** Head pitch in degrees, + = looking up. */
    fun pitchDeg(rotation: Q4): Float = asin(rotation.forward.y.coerceIn(-1f, 1f)).toDeg()

    /** The horizontal direction the face points to (lying looking up: from the head's up, towards the feet). */
    fun yawForward(rotation: Q4): V3 {
        val f = rotation.forward.horizontal()
        if (f.length > 0.2f) return f.normalized()
        val u = rotation.up.horizontal()
        return if (u.length > 1e-3f) (-u).normalized() else V3.AHEAD
    }

    /**
     * Lying on the back and looking at the ceiling, not back over the head: forward·up ≥ [SUPINE_MIN_UP] and the line of
     * sight leans at most [SUPINE_MAX_HEADTOP_DEG] towards the top of the head (the head's up, flattened). Only asked
     * while the posture is LIE.
     */
    fun supineOk(rotation: Q4): Boolean {
        val f = rotation.forward
        if (f.y < SUPINE_MIN_UP) return false
        val top = rotation.up.horizontal()
        if (top.length < 1e-3f) return true
        val lean = atan2(f.dot(top.normalized()), f.y).toDeg()
        return lean <= SUPINE_MAX_HEADTOP_DEG
    }

    /**
     * The window the system opens for this head pose, on the actual line of sight (pitch included; the system window
     * opens perpendicular to it): its up is world up made perpendicular to the line of sight. Lying looking up
     * ([supineOk]): above the face, its top towards the top of the head.
     */
    fun expectedWindow(eye: V3, rotation: Q4, posture: Posture): WindowRect {
        if (posture == Posture.LIE && supineOk(rotation)) {
            val f = rotation.forward.normalized()
            var up = rotation.up - f * rotation.up.dot(f)
            if (up.length < 1e-3f) up = V3.AHEAD
            up = up.normalized()
            val center = eye + f * DIST_M
            val toEye = -f
            return WindowRect(center, up.cross(toEye).normalized(), up)
        }
        val f = rotation.forward.normalized()
        var up = V3.UP - f * f.y
        // looking (almost) straight up or down: the head's up stands in
        if (up.length < 0.2f) up = rotation.up - f * rotation.up.dot(f)
        if (up.length < 1e-3f) up = V3.AHEAD
        up = up.normalized()
        val toEye = -f
        return WindowRect(eye + f * DIST_M, up.cross(toEye).normalized(), up)
    }

    /** The sitting frame turned [yawDeg] to the right of the window's direction [rotation]. */
    private fun sitFrame(eye: V3, rotation: Q4, yawDeg: Float): ScanFrame =
        ScanFrame.from(eye, (Q4.axisAngle(V3.UP, -yawDeg) * rotation).normalized(), Posture.SIT, yawDeg)

    /**
     * Sitting: the first of [GANTRY_YAW_TRIES] (80 degrees right first) at which the gantry at park keeps [MIN_SEP_DEG]
     * from the [window]'s outline from both eyes (q5-1: the window opens at whatever pitch the head has). If none does,
     * the best one.
     */
    fun gantryYawFor(eye: V3, rotation: Q4, window: WindowRect): Float {
        val o = outline(window)
        var best = GANTRY_YAW_SIT_DEG
        var bestSep = -1f
        for (y in GANTRY_YAW_TRIES) {
            val sep = separationBothEyesDeg(eye, rotation, o, gantryBox(sitFrame(eye, rotation, y), 0f))
            if (sep >= MIN_SEP_DEG) return y
            if (sep > bestSep) {
                bestSep = sep
                best = y
            }
        }
        return best
    }

    /**
     * The scan room's frame for this head pose: sitting, the gantry turned [gantryYawFor] to the right of the window's
     * direction ([window]: the one that opened, else the one this pose would open); lying, from the head's up.
     */
    fun frameFor(eye: V3, rotation: Q4, posture: Posture, window: WindowRect? = null): ScanFrame =
        if (posture == Posture.SIT) sitFrame(eye, rotation, gantryYawFor(eye, rotation, window ?: expectedWindow(eye, rotation, Posture.SIT)))
        else ScanFrame.from(eye, rotation, Posture.LIE)

    /** The one placement function: the window the system opens now and the room laid out around it. */
    fun place(eye: V3, rotation: Q4, posture: Posture): Placement {
        val w = expectedWindow(eye, rotation, posture)
        return Placement(w, frameFor(eye, rotation, posture, w))
    }

    /** The window on the actual line of sight (the same as [expectedWindow] since every open goes out at once). */
    fun actualWindow(eye: V3, rotation: Q4, posture: Posture): WindowRect = expectedWindow(eye, rotation, posture)

    /** Four corners of the window grown by the TabBar above and the system controls below (metres, world). */
    fun outline(w: WindowRect): List<V3> {
        val hw = W_DP * DP_M / 2f
        val top = H_DP * DP_M / 2f + TABBAR_DP * DP_M
        val bottom = H_DP * DP_M / 2f + CONTROLS_DP * DP_M
        return listOf(
            w.center - w.right * hw + w.up * top,
            w.center + w.right * hw + w.up * top,
            w.center + w.right * hw - w.up * bottom,
            w.center - w.right * hw - w.up * bottom,
        )
    }

    /** The gantry's box in the world at world offset [s] (centre H0 + b·(s − 1.8), axes of the frame). */
    fun gantryBox(f: ScanFrame, s: Float): Occlusion.Box {
        val center = f.h0 + f.b * (s - Slide.MAX) - V3.UP * (Gantry.PLINTH_H / 2f)
        return Occlusion.Box(center, f.rotation, V3(Gantry.W, Gantry.H + Gantry.PLINTH_H, Gantry.D))
    }

    fun boxCorners(box: Occlusion.Box): List<V3> {
        val r = box.rotation
        val h = box.size * 0.5f
        val out = ArrayList<V3>(8)
        for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) out += box.center + r.rotate(V3(h.x * sx, h.y * sy, h.z * sz))
        return out
    }

    /**
     * Smallest angle (degrees) between the window's [outline] and the [box], seen from [eye]; 0 when they overlap (a line
     * of sight through the outline meets the box, or a box edge shows inside the outline).
     */
    fun separationDeg(eye: V3, outline: List<V3>, box: Occlusion.Box): Float {
        val (a, b, c, d) = outline
        val n = 24
        // overlap: sight lines through a grid over the outline
        for (i in 0..n) for (j in 0..n) {
            val u = i / n.toFloat()
            val v = j / n.toFloat()
            val top = a + (b - a) * u
            val bottom = d + (c - d) * u
            val p = top + (bottom - top) * v
            val dir = (p - eye).normalized()
            if (Occlusion.segmentHitsBox(eye, dir, 50f, box)) return 0f
        }
        val corners = boxCorners(box)
        val edges = ArrayList<Pair<V3, V3>>()
        for (i in corners.indices) for (j in i + 1 until corners.size) {
            // box edges join corners that differ in exactly one sign: distance equals one side
            val diff = box.rotation.let { q -> Q4(-q.x, -q.y, -q.z, q.w) }.rotate(corners[j] - corners[i])
            val axes = listOf(kotlin.math.abs(diff.x), kotlin.math.abs(diff.y), kotlin.math.abs(diff.z)).count { it > 1e-3f }
            if (axes == 1) edges += corners[i] to corners[j]
        }
        val boxDirs = ArrayList<V3>()
        for ((p, q) in edges) for (k in 0..n) boxDirs += ((p + (q - p) * (k / n.toFloat())) - eye).normalized()
        // a box edge showing inside the outline (the box's silhouette pokes into the window)
        val wn = (a - b).cross(c - b).normalized()
        for (dir in boxDirs) if (insideOutline(eye, dir, outline, wn)) return 0f
        val winDirs = ArrayList<V3>()
        val ring = listOf(a, b, c, d, a)
        for (e in 0 until 4) for (k in 0..n) winDirs += ((ring[e] + (ring[e + 1] - ring[e]) * (k / n.toFloat())) - eye).normalized()
        var best = 180f
        for (w in winDirs) for (x in boxDirs) best = min(best, acos(w.dot(x).coerceIn(-1f, 1f)).toDeg())
        return best
    }

    /** Separation from both eyes (binocular parallax), the smaller one. */
    fun separationBothEyesDeg(eye: V3, rotation: Q4, outline: List<V3>, box: Occlusion.Box): Float {
        val right = rotation.rotate(V3.RIGHT).horizontal().let { if (it.length < 1e-3f) V3.RIGHT else it.normalized() }
        return min(separationDeg(eye + right * (IPD_M / 2f), outline, box), separationDeg(eye - right * (IPD_M / 2f), outline, box))
    }

    private fun insideOutline(eye: V3, dir: V3, o: List<V3>, n: V3): Boolean {
        val denom = dir.dot(n)
        if (kotlin.math.abs(denom) < 1e-6f) return false
        val t = (o[0] - eye).dot(n) / denom
        if (t <= 0f) return false
        val p = eye + dir * t
        // inside the convex quad: same side of all four edges
        var sign = 0
        for (i in 0 until 4) {
            val e0 = o[i]
            val e1 = o[(i + 1) % 4]
            val s = (e1 - e0).cross(p - e0).dot(n)
            val sg = if (s > 0f) 1 else -1
            if (sign == 0) sign = sg else if (sg != sign) return false
        }
        return true
    }

    /** Largest angle of the outline's corners from the line of sight (the window stays in the ±39° default view). */
    fun maxCornerOffAxisDeg(eye: V3, rotation: Q4, outline: List<V3>): Float {
        val f = rotation.forward.normalized()
        var worst = 0f
        for (p in outline) worst = max(worst, acos((p - eye).normalized().dot(f).coerceIn(-1f, 1f)).toDeg())
        return worst
    }
}
