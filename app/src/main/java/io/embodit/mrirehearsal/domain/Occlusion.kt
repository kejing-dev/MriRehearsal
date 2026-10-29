package io.embodit.mrirehearsal.domain

import kotlin.math.abs

/**
 * QA q2-1: a pinch on a panel button sometimes went through the panel to the room's wall collider behind it (the
 * system pointer does not simply take the nearest hit; Spatial Ruler measured that it ranks a panel by its centre and
 * a collider by its hit point, and found "with nothing behind the glass the panel takes every press"). Rule that
 * follows: **no collider may lie on a line of sight through a visible panel.** This decides, per collider box, whether
 * any of nine lines from the eyes through the panel (centre, corners, edge midpoints, rectangle grown by [MARGIN])
 * continues into the box.
 */
object Occlusion {
    /** Panel rectangle grown by this share on each side (the pointer may start beside the eye). */
    const val MARGIN = 0.15f

    /** A panel in the world: centre, rotation (face = +Z, top = +Y), width and height in metres. */
    data class PanelRect(val center: V3, val rotation: Q4, val width: Float, val height: Float)

    /** An oriented box: centre, rotation, full size in metres. */
    data class Box(val center: V3, val rotation: Q4, val size: V3)

    /** True when a line from [eye] through some point of [panel] runs on, behind the panel, into [box]. */
    fun behind(eye: V3, panel: PanelRect, box: Box): Boolean {
        val right = panel.rotation.rotate(V3.RIGHT)
        val up = panel.rotation.up
        val hw = panel.width * (0.5f + MARGIN)
        val hh = panel.height * (0.5f + MARGIN)
        for (sx in -1..1) for (sy in -1..1) {
            val p = panel.center + right * (hw * sx) + up * (hh * sy)
            val d = p - eye
            if (d.length < 1e-4f) continue
            if (segmentHitsBox(p, d.normalized(), MAX_RAY_M, box)) return true
        }
        return false
    }

    /** Ray from [origin] along unit [dir], up to [maxT] metres, against an oriented box (slab test). */
    fun segmentHitsBox(origin: V3, dir: V3, maxT: Float, box: Box): Boolean {
        val inv = Q4(-box.rotation.x, -box.rotation.y, -box.rotation.z, box.rotation.w)
        val o = inv.rotate(origin - box.center)
        val d = inv.rotate(dir)
        val h = box.size * 0.5f
        var t0 = 0f
        var t1 = maxT
        val os = floatArrayOf(o.x, o.y, o.z)
        val ds = floatArrayOf(d.x, d.y, d.z)
        val hs = floatArrayOf(h.x, h.y, h.z)
        for (i in 0..2) {
            if (abs(ds[i]) < 1e-6f) {
                if (abs(os[i]) > hs[i]) return false
            } else {
                var a = (-hs[i] - os[i]) / ds[i]
                var b = (hs[i] - os[i]) / ds[i]
                if (a > b) a = b.also { b = a }
                if (a > t0) t0 = a
                if (b < t1) t1 = b
                if (t0 > t1) return false
            }
        }
        return true
    }

    /** Extra room around a hole in a wall collider, metres. */
    const val HOLE_PAD_M = 0.08f

    /**
     * The part of a thin [box] (a wall: its smallest side is the normal) that lies behind any of [panels] seen from
     * [eye], as a rectangle in the box's two in-plane axes: [minA, maxA, minB, maxB] in box-local metres, padded by
     * [HOLE_PAD_M] and clipped to the box; null when no line of sight through a panel reaches the box.
     */
    fun hole(eye: V3, panels: List<PanelRect>, box: Box): FloatArray? {
        val n = normalAxis(box.size)
        val (ia, ib) = inPlane(n)
        val inv = Q4(-box.rotation.x, -box.rotation.y, -box.rotation.z, box.rotation.w)
        val half = floatArrayOf(box.size.x / 2, box.size.y / 2, box.size.z / 2)
        var minA = Float.MAX_VALUE
        var maxA = -Float.MAX_VALUE
        var minB = Float.MAX_VALUE
        var maxB = -Float.MAX_VALUE
        var any = false
        for (panel in panels) {
            val right = panel.rotation.rotate(V3.RIGHT)
            val up = panel.rotation.up
            val hw = panel.width * (0.5f + MARGIN)
            val hh = panel.height * (0.5f + MARGIN)
            // all nine lines, so the footprint is the whole panel's shadow once any one of them reaches the wall
            val hits = ArrayList<FloatArray>()
            var reaches = false
            for (sx in -1..1) for (sy in -1..1) {
                val p = panel.center + right * (hw * sx) + up * (hh * sy)
                val d = (p - eye).normalized()
                if (segmentHitsBox(p, d, MAX_RAY_M, box)) reaches = true
                val o = comps(inv.rotate(p - box.center))
                val dl = comps(inv.rotate(d))
                if (abs(dl[n]) < 1e-5f) continue
                val t = -o[n] / dl[n]
                if (t <= 0f) continue
                hits += floatArrayOf(o[ia] + dl[ia] * t, o[ib] + dl[ib] * t)
            }
            if (!reaches) continue
            for (h in hits) {
                any = true
                minA = minOf(minA, h[0])
                maxA = maxOf(maxA, h[0])
                minB = minOf(minB, h[1])
                maxB = maxOf(maxB, h[1])
            }
        }
        if (!any) return null
        return floatArrayOf(
            (minA - HOLE_PAD_M).coerceIn(-half[ia], half[ia]),
            (maxA + HOLE_PAD_M).coerceIn(-half[ia], half[ia]),
            (minB - HOLE_PAD_M).coerceIn(-half[ib], half[ib]),
            (maxB + HOLE_PAD_M).coerceIn(-half[ib], half[ib]),
        )
    }

    /**
     * A box of [size] with the rectangle [hole] (from [hole]) cut out of it, as up to four boxes: (local centre, size).
     * No hole: the whole box.
     */
    fun cut(size: V3, hole: FloatArray?): List<Pair<V3, V3>> {
        if (hole == null) return listOf(V3.ZERO to size)
        val n = normalAxis(size)
        val (ia, ib) = inPlane(n)
        val s = comps(size)
        val ha = s[ia] / 2
        val hb = s[ib] / 2
        val out = ArrayList<Pair<V3, V3>>()
        fun piece(a0: Float, a1: Float, b0: Float, b1: Float) {
            if (a1 - a0 < 0.01f || b1 - b0 < 0.01f) return
            val c = FloatArray(3)
            val z = FloatArray(3)
            c[ia] = (a0 + a1) / 2
            c[ib] = (b0 + b1) / 2
            z[ia] = a1 - a0
            z[ib] = b1 - b0
            z[n] = s[n]
            out += V3(c[0], c[1], c[2]) to V3(z[0], z[1], z[2])
        }
        piece(-ha, hole[0], -hb, hb)
        piece(hole[1], ha, -hb, hb)
        piece(hole[0], hole[1], -hb, hole[2])
        piece(hole[0], hole[1], hole[3], hb)
        return out
    }

    private fun comps(v: V3) = floatArrayOf(v.x, v.y, v.z)

    private fun normalAxis(size: V3): Int = if (size.x <= size.y && size.x <= size.z) 0 else if (size.y <= size.z) 1 else 2

    private fun inPlane(n: Int): Pair<Int, Int> =
        when (n) {
            0 -> 1 to 2
            1 -> 0 to 2
            else -> 0 to 1
        }

    private const val MAX_RAY_M = 12f
}
