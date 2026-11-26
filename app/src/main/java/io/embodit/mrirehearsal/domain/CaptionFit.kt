package io.embodit.mrirehearsal.domain

import kotlin.math.max
import kotlin.math.sqrt

/**
 * QA #7 q7-1 (profile D35 / D16): the in-bore readout strip has to fit inside the bore's round cross-section with all
 * four corners, not only its centre.
 *
 * v0.1.2 put the lying strip's centre at radius − 0.015 m on the roof. The strip is flat (0.21 m wide at scale 0.5) and
 * turned towards the eyes (about 24.5 degrees), so its top corners stood about 0.296 m above the axis at 0.105 m to
 * the side, where the 60 cm bore's wall is only 0.281 m high: the corners were 1.5 cm inside the wall and [Sound on]
 * lost its right half (70 cm too). The strip's height above the axis is now solved from its corners: the highest
 * one that keeps every corner at least [CLEARANCE_M] inside the wall, never higher than the design's place
 * (radius − 0.015 lying, 0.19 m sitting).
 *
 * Frame axes as [ScanFrame.at]: the bore's axis is the line through H0 along b (the eyes are on it at the isocentre,
 * [BoreFit.inBore]); "up" is the frame's up. The strip faces the eyes ([eye], H0 for the placement: the strip is fixed
 * with the user, not with the head) with its top towards [panelUp]. The panel's metric size is taken as [M_PER_DP] = 1 mm per dp at scale 1, the
 * upper end of what was used so far (QA #7's 0.21 m wide strip; the scene's own guard uses 0.8 mm): if the panel is really
 * smaller, the corners only keep more room.
 */
object CaptionFit {
    /** Gap kept between every corner of the strip and the bore wall, metres. */
    const val CLEARANCE_M = 0.015f

    /** Metres per dp of a SpatialView panel at scale 1, on the safe (larger) side. */
    const val M_PER_DP = 0.001f

    /** Head movement the clearance has to absorb: the strip turns to the actual head, which can be this far from H0. */
    const val HEAD_SLACK_M = 0.03f

    /** The four corners of a [widthM] × [heightM] panel at [center] facing [eye] with its top towards [panelUp]. */
    fun corners(center: V3, eye: V3, panelUp: V3, widthM: Float, heightM: Float): List<V3> {
        val rot = Q4.facing(eye - center, panelUp)
        val x = rot.rotate(V3(1f, 0f, 0f)) * (widthM / 2f)
        val y = rot.rotate(V3(0f, 1f, 0f)) * (heightM / 2f)
        return listOf(center + x + y, center - x + y, center + x - y, center - x - y)
    }

    /** Distance of [p] from the bore's axis (the line through [f].h0 along [f].b). */
    fun radial(f: ScanFrame, p: V3): Float {
        val d = p - f.h0
        val along = d.dot(f.b)
        val perp = d - f.b * along
        return perp.length
    }

    /** The largest corner distance from the axis for the strip at frame point ([axial], 0, [up]). */
    fun maxCornerRadial(f: ScanFrame, axial: Float, up: Float, eye: V3, panelUp: V3, widthM: Float, heightM: Float): Float {
        var m = 0f
        for (c in corners(f.at(axial, 0f, up), eye, panelUp, widthM, heightM)) m = max(m, radial(f, c))
        return m
    }

    /** H0 and H0 moved by [HEAD_SLACK_M] along each frame axis: every head the strip may turn to. */
    fun slackEyes(f: ScanFrame): List<V3> {
        val s = HEAD_SLACK_M
        return listOf(f.h0) + listOf(f.b, f.right, f.up).flatMap { listOf(f.h0 + it * s, f.h0 - it * s) }
    }

    /** The largest corner distance from the axis over every head in [slackEyes]. */
    fun worstCornerRadial(f: ScanFrame, axial: Float, up: Float, panelUp: V3, widthM: Float, heightM: Float): Float {
        var m = 0f
        for (eye in slackEyes(f)) m = max(m, maxCornerRadial(f, axial, up, eye, panelUp, widthM, heightM))
        return m
    }

    /**
     * Height above the axis for the strip at [axial]: [preferredUp] if every corner already keeps [CLEARANCE_M] from
     * a bore of [boreRadius] for every head in [slackEyes] (the strip turns to the actual head), else the highest
     * height that does (bisection; the corners' radius grows with the height in the range used here).
     */
    fun fitUp(f: ScanFrame, axial: Float, preferredUp: Float, boreRadius: Float, panelUp: V3, widthM: Float, heightM: Float): Float {
        val limit = boreRadius - CLEARANCE_M
        if (worstCornerRadial(f, axial, preferredUp, panelUp, widthM, heightM) <= limit) return preferredUp
        var lo = 0f
        var hi = preferredUp
        repeat(32) {
            val mid = (lo + hi) / 2f
            if (worstCornerRadial(f, axial, mid, panelUp, widthM, heightM) <= limit) lo = mid else hi = mid
        }
        return lo
    }

    /** Wall height left at lateral offset [lateral] in a bore of [boreRadius] (what QA measured: 0.281 m at 0.105 m). */
    fun wallUpAt(boreRadius: Float, lateral: Float): Float = sqrt(max(0f, boreRadius * boreRadius - lateral * lateral))
}
